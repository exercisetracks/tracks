#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
garmin-sync: pull .fit files from a Garmin MTP device using libmtp directly.

Architecture:

  The long-running parent process polls lsusb cheaply (~ms) for the Garmin
  vendor ID. When something with that vendor appears, it spawns this same
  script with `--worker`. The worker initialises libmtp fresh, opens the
  watch, enumerates the configured folder, downloads every new .fit in a
  single MTP session, then exits. Result is communicated back as a single
  JSON line on stdout.

  Talking to libmtp directly (ctypes) is what gives the per-file speedup:
  one MTP session amortises libmtp's session-setup + object-enumeration
  cost across every file in the plug-in. The previous mtp-tools shell-out
  approach paid that cost on every `mtp-getfile`, which is catastrophic on
  Garmin watches (hundreds-to-thousands of activity/monitor/sleep objects).

  Doing the libmtp work in a *fresh subprocess* per plug-in is what gives
  the reliability: libmtp never calls libusb_exit, so a long-running parent
  accumulates a stale libusb default context — new devices stop appearing
  in libusb's internal list even though the kernel sees them. A fresh
  worker process always gets a fresh libusb context (same property
  mtp-detect relies on).

Security:

  This container never writes a downloaded FIT file to shared/persistent
  storage in plaintext, and doesn't mount the backend's shared fit-files
  volume at all. Each file is downloaded to local (container-only,
  ephemeral) disk, immediately sealed with libsodium against the target
  account's public key (fetched once per plug-in via GET /sync/pubkey), and
  handed to the backend at POST /sync/ingest — which stores ciphertext it
  cannot open itself. The plaintext copy is deleted the moment sealing
  finishes, success or failure. See app/services/sync_agent_auth.py and
  app/api/sync_ingest.py on the backend for the other side of this.

  Authenticates with TRACKS_SYNC_TOKEN, a per-agent bearer token paired
  once from the web UI (Settings > Sync Agents) — not a fixed secret shared
  by the whole install. Only *personal* agents (one account) are supported
  here; see the TRACKS_SYNC_TOKEN comment below for what household
  (shared-dock) support would need.

All behaviour is driven by environment variables; see README.md.
"""
from __future__ import annotations

import ctypes
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from ctypes import (
    CDLL, POINTER, Structure,
    c_char_p, c_int, c_long, c_uint8, c_uint16, c_uint32, c_uint64, c_void_p,
)
from dataclasses import dataclass
from pathlib import Path
from typing import Optional


# ---------------------------------------------------------------------------
# Configuration (via env)
# ---------------------------------------------------------------------------

def _env(key: str, default: str) -> str:
    val = os.environ.get(key)
    return val if val is not None and val != "" else default


def _env_int(key: str, default: int) -> int:
    raw = _env(key, str(default))
    try:
        return int(raw)
    except ValueError:
        sys.stderr.write(f"[WARN] env {key}={raw!r} is not an int; using {default}\n")
        return default


DEST = Path(_env("GARMIN_SYNC_DEST", "/output"))
POLL_INTERVAL = _env_int("GARMIN_SYNC_POLL_INTERVAL", 10)
UNPLUG_POLL_INTERVAL = _env_int("GARMIN_SYNC_UNPLUG_POLL", 20)
VENDOR_ID = _env("GARMIN_SYNC_VENDOR_ID", "091e").lower()
STORAGE_NAME = _env("GARMIN_SYNC_STORAGE", "Primary")
FOLDER_PATHS = [p.strip() for p in _env(
    "GARMIN_SYNC_FOLDER_PATH",
    "GARMIN/Activity,GARMIN/Sleep,GARMIN/HRVStatus,GARMIN/Monitor",
).split(",") if p.strip()]
FILE_EXT = _env("GARMIN_SYNC_FILE_EXT", ".fit").lower()
STATE_FILENAME = _env("GARMIN_SYNC_STATE_FILE", ".garmin-sync-state.json")
LOG_LEVEL = _env("GARMIN_SYNC_LOG_LEVEL", "INFO").upper()
RESYNC_COOLDOWN = _env_int("GARMIN_SYNC_RESYNC_COOLDOWN", 3600)
# Hard cap on a single worker subprocess. Should generously cover a full
# plug-in's enumeration + downloads on a maxed-out watch.
WORKER_TIMEOUT = _env_int("GARMIN_SYNC_WORKER_TIMEOUT", 600)
# How often (seconds) to emit an "idle, still waiting for a watch" heartbeat at
# INFO while nothing is on the USB bus. Without this the poll loop is completely
# silent when idle, which looks indistinguishable from a hung/broken container.
# Set to 0 to disable the heartbeat.
IDLE_HEARTBEAT_INTERVAL = _env_int("GARMIN_SYNC_IDLE_HEARTBEAT", 300)
PUID = _env_int("PUID", -1)
PGID = _env_int("PGID", -1)

# Backend URL + this agent's pairing token (see backend/app/services/
# sync_agent_auth.py — paired once from the web UI under Settings > Sync
# Agents, not a fixed secret baked into .env). Sent as a bearer token on
# every call, replacing the old shared X-Garmin-Sync-Secret header.
#
# Which watch is docked: its unit id, read from GARMIN/GarminDevice.xml once
# per plug-in (see _read_unit_id) and sent as X-Garmin-Device-Serial on every
# backend call. The server dock is shared — anyone can plug a watch into the
# machine — so the server files each watch's data under whoever claimed it,
# and needs to know which watch this is to do that (see the backend's
# sync_agent_auth.is_shared_dock). Before this, every docked watch's files
# went to the admin's account.
BACKEND_URL = _env("TRACKS_API_URL", "").rstrip("/")
SYNC_TOKEN = _env("TRACKS_SYNC_TOKEN", "")
# Local cache directory for the downloaded CPE.bin (avoids re-downloading on every plug-in)
AGPS_CACHE_DIR = Path(_env("GARMIN_AGPS_CACHE_DIR", str(DEST)))

# Set umask so files are readable by group/others (rw-r--r--)
os.umask(0o022)


def _chown_if_needed(path: Path) -> None:
    if PUID < 0 and PGID < 0:
        return
    uid = PUID if PUID >= 0 else -1
    gid = PGID if PGID >= 0 else -1
    try:
        os.chown(path, uid, gid)
    except OSError as exc:
        warn(f"could not chown {path} to {uid}:{gid}: {exc}")


# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

_LEVELS = {"DEBUG": 10, "INFO": 20, "WARN": 30, "ERROR": 40}


def _log(level: str, msg: str) -> None:
    if _LEVELS.get(level, 20) < _LEVELS.get(LOG_LEVEL, 20):
        return
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    # Logs go to stderr so the worker's stdout can be used as a clean JSON
    # channel back to the parent. docker logs aggregates both, so this
    # doesn't change what the user sees.
    print(f"{ts} [{level}] {msg}", flush=True, file=sys.stderr)


def debug(msg: str) -> None: _log("DEBUG", msg)
def info(msg: str) -> None:  _log("INFO", msg)
def warn(msg: str) -> None:  _log("WARN", msg)
def error(msg: str) -> None: _log("ERROR", msg)


def _state_key(name: str) -> str:
    """Synced files are recorded per watch: the dock is shared, and two
    watches can each have a file of the same name."""
    return f"{_DEVICE_SERIAL}/{name}" if _DEVICE_SERIAL else name


# ---------------------------------------------------------------------------
# State (which files we've already synced)
# ---------------------------------------------------------------------------

@dataclass
class State:
    path: Path
    synced: set[str]

    @classmethod
    def load(cls, path: Path) -> "State":
        if path.exists():
            try:
                data = json.loads(path.read_text())
                names = data.get("synced", [])
                if not isinstance(names, list):
                    warn(f"state file {path} has non-list 'synced'; resetting")
                    names = []
                return cls(path=path, synced=set(names))
            except (json.JSONDecodeError, OSError) as exc:
                warn(f"could not read state file {path}: {exc}; starting fresh")
        return cls(path=path, synced=set())

    def save(self) -> None:
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        try:
            tmp.write_text(json.dumps({"synced": sorted(self.synced)}, indent=2))
            tmp.replace(self.path)
            _chown_if_needed(self.path)
        except OSError as exc:
            error(f"could not write state file {self.path}: {exc}")

    def has(self, name: str) -> bool:
        # A bare name is from before entries were kept per watch; honour it
        # for any watch rather than re-upload everything once.
        return _state_key(name) in self.synced or name in self.synced

    def mark(self, name: str) -> None:
        self.synced.add(_state_key(name))
        # Save after each file so a crash mid-sync doesn't lose progress.
        self.save()


# ---------------------------------------------------------------------------
# libmtp via ctypes
# ---------------------------------------------------------------------------
# Struct layouts mirror libmtp 1.1.x (libmtp.h in Debian's libmtp-dev). The
# fields used here are part of libmtp's public/documented API and have been
# stable across the 1.1.x line. We only declare fields up through the ones
# we actually read — trailing fields are deliberately omitted so internal
# additions after them don't matter.

LIBMTP_FILES_AND_FOLDERS_ROOT = 0xFFFFFFFF
LIBMTP_FILETYPE_FOLDER = 0           # First value in the LIBMTP_filetype_t enum
LIBMTP_STORAGE_SORTBY_NOTSORTED = 0
LIBMTP_ERROR_NONE = 0


class _DeviceEntry(Structure):
    _fields_ = [
        ("vendor", c_char_p),
        ("vendor_id", c_uint16),
        ("product", c_char_p),
        ("product_id", c_uint16),
        ("device_flags", c_uint32),
    ]


class _RawDevice(Structure):
    _fields_ = [
        ("device_entry", _DeviceEntry),
        ("bus_location", c_uint32),
        ("devnum", c_uint8),
    ]


class _Storage(Structure):
    pass


_Storage._fields_ = [
    ("id", c_uint32),
    ("StorageType", c_uint16),
    ("FilesystemType", c_uint16),
    ("AccessCapability", c_uint16),
    ("MaxCapacity", c_uint64),
    ("FreeSpaceInBytes", c_uint64),
    ("FreeSpaceInObjects", c_uint64),
    ("StorageDescription", c_char_p),
    ("VolumeIdentifier", c_char_p),
    ("next", POINTER(_Storage)),
    ("prev", POINTER(_Storage)),
]


class _MtpDevice(Structure):
    pass


_MtpDevice._fields_ = [
    ("object_bitsize", c_uint8),
    ("params", c_void_p),
    ("usbinfo", c_void_p),
    ("storage", POINTER(_Storage)),
]


class _File(Structure):
    pass


_File._fields_ = [
    ("item_id", c_uint32),
    ("parent_id", c_uint32),
    ("storage_id", c_uint32),
    ("filename", c_char_p),
    ("filesize", c_uint64),
    ("modificationdate", c_long),   # time_t on linux x86_64
    ("filetype", c_int),
    ("next", POINTER(_File)),
]


_libmtp = CDLL("libmtp.so.9")
_libc = CDLL("libc.so.6")
_libc.free.argtypes = [c_void_p]
_libc.free.restype = None

_libmtp.LIBMTP_Init.restype = None
_libmtp.LIBMTP_Init.argtypes = []

_libmtp.LIBMTP_Set_Debug.restype = None
_libmtp.LIBMTP_Set_Debug.argtypes = [c_int]

_libmtp.LIBMTP_Detect_Raw_Devices.restype = c_int
_libmtp.LIBMTP_Detect_Raw_Devices.argtypes = [POINTER(POINTER(_RawDevice)), POINTER(c_int)]

_libmtp.LIBMTP_Open_Raw_Device_Uncached.restype = POINTER(_MtpDevice)
_libmtp.LIBMTP_Open_Raw_Device_Uncached.argtypes = [POINTER(_RawDevice)]

_libmtp.LIBMTP_Open_Raw_Device.restype = POINTER(_MtpDevice)
_libmtp.LIBMTP_Open_Raw_Device.argtypes = [POINTER(_RawDevice)]

_libmtp.LIBMTP_Release_Device.restype = None
_libmtp.LIBMTP_Release_Device.argtypes = [POINTER(_MtpDevice)]

# Returns a strdup'd char* — caller must free().
_libmtp.LIBMTP_Get_Friendlyname.restype = c_void_p
_libmtp.LIBMTP_Get_Friendlyname.argtypes = [POINTER(_MtpDevice)]

_libmtp.LIBMTP_Get_Storage.restype = c_int
_libmtp.LIBMTP_Get_Storage.argtypes = [POINTER(_MtpDevice), c_int]

_libmtp.LIBMTP_Get_Files_And_Folders.restype = POINTER(_File)
_libmtp.LIBMTP_Get_Files_And_Folders.argtypes = [POINTER(_MtpDevice), c_uint32, c_uint32]

_libmtp.LIBMTP_Get_File_To_File.restype = c_int
_libmtp.LIBMTP_Get_File_To_File.argtypes = [POINTER(_MtpDevice), c_uint32, c_char_p, c_void_p, c_void_p]

_libmtp.LIBMTP_destroy_file_t.restype = None
_libmtp.LIBMTP_destroy_file_t.argtypes = [POINTER(_File)]

# Write support (used for AGPS CPE.bin upload to watch)
_libmtp.LIBMTP_new_file_t.restype = POINTER(_File)
_libmtp.LIBMTP_new_file_t.argtypes = []

_libmtp.LIBMTP_Send_File_From_File.restype = c_int
_libmtp.LIBMTP_Send_File_From_File.argtypes = [POINTER(_MtpDevice), c_char_p, POINTER(_File), c_void_p, c_void_p]

_libmtp.LIBMTP_Delete_Object.restype = c_int
_libmtp.LIBMTP_Delete_Object.argtypes = [POINTER(_MtpDevice), c_uint32]

_libmtp.LIBMTP_Create_Folder.restype = c_uint32
_libmtp.LIBMTP_Create_Folder.argtypes = [POINTER(_MtpDevice), c_char_p, c_uint32, c_uint32]

LIBMTP_FILETYPE_UNKNOWN = 44


_libmtp_initialized = False


def _ensure_libmtp_init() -> None:
    global _libmtp_initialized
    if _libmtp_initialized:
        return
    # Gate BOTH libmtp's own debug output AND libusb's debug output on
    # GARMIN_SYNC_LOG_LEVEL=DEBUG. libusb reads LIBUSB_DEBUG via getenv
    # at init time, so we set it before libmtp's first libusb_init call.
    # Without this, Debian Bookworm's libmtp 1.1.20 can leave libusb
    # chatty even when LIBMTP_Set_Debug(0) tells libmtp to be quiet.
    if LOG_LEVEL == "DEBUG":
        os.environ["LIBUSB_DEBUG"] = "4"  # LIBUSB_LOG_LEVEL_DEBUG
    else:
        os.environ.pop("LIBUSB_DEBUG", None)
    _libmtp.LIBMTP_Init()
    # 0x05 = LIBMTP_DEBUG_PTP | LIBMTP_DEBUG_USB. Surfaces any silent
    # failure inside Open_Raw_Device when we're trying to diagnose.
    _libmtp.LIBMTP_Set_Debug(0x05 if LOG_LEVEL == "DEBUG" else 0)
    _libmtp_initialized = True


def _free_file_list(head) -> None:
    cur = head
    while bool(cur):
        nxt = cur.contents.next
        _libmtp.LIBMTP_destroy_file_t(cur)
        cur = nxt


# ---------------------------------------------------------------------------
# Thin libmtp wrappers — kept narrow so tests can monkey-patch them without
# touching ctypes directly. Everything below this line is Python-typed.
# ---------------------------------------------------------------------------

def _mtp_open_garmin() -> Optional[object]:
    """Probe libmtp for a device matching the configured vendor. Returns a
    libmtp device handle on success, or None if no such device is in an
    MTP-ready state right now. This is the real "is the watch ready?"
    check — it doubles as the lsusb-vs-MTP race resolver, since libmtp
    will only see the device once it has settled into its MTP-mode PID."""
    _ensure_libmtp_init()
    raw = POINTER(_RawDevice)()
    num = c_int(0)
    ret = _libmtp.LIBMTP_Detect_Raw_Devices(ctypes.byref(raw), ctypes.byref(num))
    debug(f"LIBMTP_Detect_Raw_Devices: ret={ret} num={num.value}")
    if ret != LIBMTP_ERROR_NONE or num.value <= 0:
        if bool(raw):
            _libc.free(raw)
        return None
    try:
        vid_target = int(VENDOR_ID, 16)
        chosen = -1
        for i in range(num.value):
            e = raw[i].device_entry
            debug(f"  raw[{i}]: vid={e.vendor_id:#06x} pid={e.product_id:#06x} "
                  f"bus={raw[i].bus_location} devnum={raw[i].devnum}")
            if chosen < 0 and e.vendor_id == vid_target:
                chosen = i
        if chosen < 0:
            debug(f"no detected device matched vendor 0x{vid_target:04x}")
            return None
        debug(f"trying LIBMTP_Open_Raw_Device_Uncached on raw[{chosen}]")
        device = _libmtp.LIBMTP_Open_Raw_Device_Uncached(ctypes.byref(raw[chosen]))
        if bool(device):
            debug("uncached open succeeded")
            return device
        # Uncached path failed silently. Fall back to the cached Open, which
        # is the same code path mtp-detect / mtp-getfile use. If it works,
        # we still get the structural speedup (one session reused across all
        # downloads) — we just pay an up-front object enumeration cost too.
        debug("LIBMTP_Open_Raw_Device_Uncached returned NULL; falling back to cached open")
        device = _libmtp.LIBMTP_Open_Raw_Device(ctypes.byref(raw[chosen]))
        if bool(device):
            info("opened device via cached LIBMTP_Open_Raw_Device "
                 "(uncached path failed; this is slower but still single-session)")
            return device
        debug("cached LIBMTP_Open_Raw_Device also returned NULL")
        return None
    finally:
        if bool(raw):
            _libc.free(raw)


def _mtp_release(device) -> None:
    if device is None or not bool(device):
        return
    try:
        _libmtp.LIBMTP_Release_Device(device)
    except Exception as exc:
        debug(f"release_device error (ignored): {exc}")


def _mtp_friendlyname(device) -> str:
    ptr = _libmtp.LIBMTP_Get_Friendlyname(device)
    if not ptr:
        return "Garmin device"
    try:
        return ctypes.string_at(ptr).decode("utf-8", errors="replace")
    finally:
        _libc.free(ptr)


def _mtp_storages(device) -> list[tuple[int, str]]:
    """Return [(storage_id, description), ...]. Tolerant of partial
    Get_Storage results — libmtp may return 1 (partial) but still populate
    the storage linked list with the storages it could see."""
    _libmtp.LIBMTP_Get_Storage(device, LIBMTP_STORAGE_SORTBY_NOTSORTED)
    out: list[tuple[int, str]] = []
    s_ptr = device.contents.storage
    while bool(s_ptr):
        s = s_ptr.contents
        desc = s.StorageDescription.decode("utf-8", errors="replace") if s.StorageDescription else ""
        out.append((s.id, desc))
        s_ptr = s.next
    return out


def _mtp_list(device, storage_id: int, parent_id: int) -> list[tuple[int, str, bool]]:
    """Return [(item_id, name, is_folder), ...] for one level of the tree."""
    head = _libmtp.LIBMTP_Get_Files_And_Folders(device, storage_id, parent_id)
    out: list[tuple[int, str, bool]] = []
    try:
        cur = head
        while bool(cur):
            f = cur.contents
            name = f.filename.decode("utf-8", errors="replace") if f.filename else ""
            out.append((f.item_id, name, f.filetype == LIBMTP_FILETYPE_FOLDER))
            cur = f.next
    finally:
        _free_file_list(head)
    return out


def _mtp_list_sizes(device, storage_id: int, parent_id: int) -> dict[int, tuple[str, int]]:
    """Like _mtp_list but returns {item_id: (name, filesize)} for non-folder items —
    used by the course inventory so the backend can dedupe by filename+size."""
    head = _libmtp.LIBMTP_Get_Files_And_Folders(device, storage_id, parent_id)
    out: dict[int, tuple[str, int]] = {}
    try:
        cur = head
        while bool(cur):
            f = cur.contents
            if f.filetype != LIBMTP_FILETYPE_FOLDER:
                name = f.filename.decode("utf-8", errors="replace") if f.filename else ""
                out[f.item_id] = (name, int(f.filesize))
            cur = f.next
    finally:
        _free_file_list(head)
    return out


def _mtp_download(device, file_id: int, dest_path: bytes) -> int:
    """0 on success, non-zero on failure."""
    return _libmtp.LIBMTP_Get_File_To_File(device, file_id, dest_path, None, None)


def _mtp_find_or_create_folder(device, storage_id: int, parent_id: int, name: str) -> Optional[int]:
    """Return the folder ID for `name` under `parent_id`, creating it if absent.
    Matching is case-insensitive — Garmin watches vary casing across models
    (e.g. RemoteSW vs REMOTESW)."""
    for item_id, item_name, is_folder in _mtp_list(device, storage_id, parent_id):
        if is_folder and item_name.lower() == name.lower():
            return item_id
    new_id = _libmtp.LIBMTP_Create_Folder(
        device, name.encode("utf-8"), c_uint32(parent_id), c_uint32(storage_id)
    )
    if new_id == 0:
        warn(f"LIBMTP_Create_Folder({name!r}) returned 0 — failed")
        return None
    debug(f"created folder {name!r} id={new_id:#x}")
    return new_id


def _mtp_navigate_path(device, storage_id: int, path: str, create_missing: bool = False) -> Optional[int]:
    """Walk a slash-separated path from the storage root, returning the deepest
    folder ID.  Matching is case-insensitive at every segment.  If
    create_missing is True, absent intermediate folders are created.
    Returns None if any segment cannot be found or created."""
    segments = [s for s in path.split("/") if s]
    parent = LIBMTP_FILES_AND_FOLDERS_ROOT
    for seg in segments:
        if create_missing:
            fid = _mtp_find_or_create_folder(device, storage_id, parent, seg)
        else:
            fid = None
            for item_id, name, is_folder in _mtp_list(device, storage_id, parent):
                if is_folder and name.lower() == seg.lower():
                    fid = item_id
                    break
        if fid is None:
            debug(f"path segment {seg!r} not found under parent {parent:#x}")
            return None
        parent = fid
    return parent


def _mtp_write_file(device, storage_id: int, folder_id: int, filename: str, local_path: Path) -> bool:
    """Write local_path to the watch as `filename` in folder_id.
    Deletes an existing file with the same name first."""
    # Check for and delete existing file (case-insensitive match)
    for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id):
        if not is_folder and name.lower() == filename.lower():
            debug(f"deleting existing {filename!r} (id={item_id:#x}) before write")
            ret = _libmtp.LIBMTP_Delete_Object(device, c_uint32(item_id))
            if ret != 0:
                warn(f"LIBMTP_Delete_Object({filename!r}) returned {ret}; continuing anyway")
            break

    file_size = local_path.stat().st_size
    fobj = _libmtp.LIBMTP_new_file_t()
    if not bool(fobj):
        warn("LIBMTP_new_file_t returned NULL")
        return False
    # Keep the encoded filename in a local so it stays alive for the entire
    # Send_File_From_File call (ctypes only holds a raw C pointer, not a ref).
    fname_bytes = filename.encode("utf-8")
    try:
        fobj.contents.filename = fname_bytes
        fobj.contents.parent_id = folder_id
        fobj.contents.storage_id = storage_id
        fobj.contents.filesize = file_size
        fobj.contents.filetype = LIBMTP_FILETYPE_UNKNOWN
        ret = _libmtp.LIBMTP_Send_File_From_File(
            device, str(local_path).encode("utf-8"), fobj, None, None
        )
    finally:
        # NULL out filename before destroy: LIBMTP_destroy_file_t calls
        # free(file->filename), but we pointed it at Python-owned memory
        # (not a malloc'd C string), so freeing it corrupts the heap.
        fobj.contents.filename = None
        _libmtp.LIBMTP_destroy_file_t(fobj)

    if ret != 0:
        warn(f"LIBMTP_Send_File_From_File({filename!r}) returned {ret}")
        return False
    info(f"wrote {filename} ({file_size} bytes) to watch")
    return True


# ---------------------------------------------------------------------------
# AGPS: fetch config, download CPE.bin, push to watch
# ---------------------------------------------------------------------------

# The docked watch's unit id, set by _worker_main once the device is open.
# One worker process handles one plug-in of one watch, so a module global is
# the whole lifetime it needs.
_DEVICE_SERIAL: Optional[str] = None


def _auth_header() -> dict:
    headers = {"Authorization": f"Bearer {SYNC_TOKEN}"}
    if _DEVICE_SERIAL:
        headers["X-Garmin-Device-Serial"] = _DEVICE_SERIAL
    return headers


def _backend_get(path: str) -> Optional[dict]:
    if not BACKEND_URL or not SYNC_TOKEN:
        debug("TRACKS_API_URL or TRACKS_SYNC_TOKEN not set; skipping backend call")
        return None
    url = f"{BACKEND_URL}{path}"
    req = urllib.request.Request(url, headers=_auth_header())
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except Exception as exc:
        warn(f"backend GET {path} failed: {exc}")
        return None


def _backend_get_bytes(path: str, dest: Path) -> bool:
    """Stream a binary response to `dest`. True on success.

    Separate from _backend_get because music is megabytes: holding a track in
    memory to hand it to libmtp, which wants a file anyway, buys nothing. The
    timeout is generous for the same reason — a slow LAN moving a 10 MB track
    is not a stuck request.
    """
    if not BACKEND_URL or not SYNC_TOKEN:
        return False
    req = urllib.request.Request(f"{BACKEND_URL}{path}", headers=_auth_header())
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, dest.open("wb") as out:
            shutil.copyfileobj(resp, out, length=256 * 1024)
        return dest.stat().st_size > 0
    except Exception as exc:
        warn(f"backend GET {path} failed: {exc}")
        return False


def _backend_post(path: str) -> None:
    if not BACKEND_URL or not SYNC_TOKEN:
        return
    url = f"{BACKEND_URL}{path}"
    req = urllib.request.Request(
        url, data=b"", method="POST",
        headers={**_auth_header(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10):
            pass
    except Exception as exc:
        warn(f"backend POST {path} failed: {exc}")


def _backend_post_json(path: str, body: object) -> Optional[dict]:
    if not BACKEND_URL or not SYNC_TOKEN:
        return None
    url = f"{BACKEND_URL}{path}"
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={**_auth_header(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except Exception as exc:
        warn(f"backend POST {path} failed: {exc}")
        return None


# ---------------------------------------------------------------------------
# Encrypted ingest — seal each FIT file against the account's public key
# before it ever leaves this machine. The backend stores ciphertext it
# can't open itself; it's only unsealed once you next log in (see
# backend/app/services/fit_import.py). See app/services/sync_agent_auth.py
# and app/api/sync_ingest.py for the server side of this contract.
# ---------------------------------------------------------------------------

def _fetch_pubkey() -> Optional[bytes]:
    """Fetch this agent's target user's ingestion public key once per worker
    run — cheap, and it doesn't change between files in the same plug-in."""
    import base64
    resp = _backend_get("/sync/pubkey")
    if not resp or not resp.get("public_key"):
        warn("could not fetch ingestion public key from backend — if this "
             f"watch ({_DEVICE_SERIAL or 'unit id unknown'}) is not claimed yet, "
             "claim it under Devices; its files stay on the watch and are "
             "retried on the next sync")
        return None
    try:
        return base64.b64decode(resp["public_key"])
    except Exception as exc:
        warn(f"malformed public_key from backend: {exc}")
        return None


def _seal_and_ingest(pubkey: bytes, path: Path, filename: str) -> bool:
    """Seal `path`'s bytes against `pubkey` and hand them to the backend.
    Always removes the local plaintext copy before returning, success or
    not — this machine never keeps a plaintext copy longer than it takes to
    seal and send it. Returns True if the backend accepted (or already had)
    the file."""
    import base64
    import hashlib
    from nacl.public import PublicKey, SealedBox

    try:
        data = path.read_bytes()
    except OSError as exc:
        warn(f"could not read {path} for sealing: {exc}")
        return False

    try:
        content_hash = hashlib.sha256(data).hexdigest()
        sealed = SealedBox(PublicKey(pubkey)).encrypt(data)
    finally:
        # Plaintext never survives past this point, regardless of outcome.
        try:
            path.unlink()
        except OSError:
            pass

    resp = _backend_post_json("/sync/ingest", {
        "device_serial": _DEVICE_SERIAL,
        "filename": filename,
        "content_hash": content_hash,
        "sealed_b64": base64.b64encode(sealed).decode(),
    })
    if resp is None:
        warn(f"ingest of {filename!r} failed (no response from backend); "
             f"will re-download and retry on the next sync")
        return False

    status = resp.get("status")
    if status not in ("queued", "duplicate"):
        warn(f"ingest of {filename!r} returned unexpected status {status!r}")
        return False
    debug(f"ingest of {filename!r}: {status}")
    return True


def _agps_is_stale(last_synced_at: Optional[str], max_age_hours: int) -> bool:
    if last_synced_at is None:
        return True
    try:
        from datetime import datetime, timezone
        # isoformat from backend includes +00:00
        ts = datetime.fromisoformat(last_synced_at.replace("Z", "+00:00"))
        age_hours = (datetime.now(timezone.utc) - ts).total_seconds() / 3600
        return age_hours >= max_age_hours
    except Exception:
        return True


def _download_agps(url: str, cache_path: Path) -> bool:
    """Download the EPO/CPE file from `url` to `cache_path` atomically."""
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = cache_path.with_suffix(".tmp")
    info(f"AGPS: downloading CPE data from {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "garmin-sync/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp, open(tmp, "wb") as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
    except Exception as exc:
        warn(f"AGPS: download failed: {exc}")
        if tmp.exists():
            tmp.unlink()
        return False
    size = tmp.stat().st_size
    if size == 0:
        warn("AGPS: downloaded file is empty")
        tmp.unlink()
        return False
    tmp.replace(cache_path)
    info(f"AGPS: downloaded {size} bytes -> {cache_path}")
    return True


def agps_sync_cycle(device, storage_id: int) -> bool:
    """Fetch AGPS config from backend, download CPE.bin if stale, write to watch.
    Returns True if the watch was updated, False otherwise (including skip)."""
    cfg = _backend_get("/training-plan/sync/agps-config")
    if cfg is None or not cfg.get("enabled"):
        debug("AGPS: disabled or config unavailable; skipping")
        return False

    url = cfg.get("url")
    if not url:
        warn("AGPS: enabled but no download URL resolved; check agps_source/agps_custom_url")
        return False

    epo_path = cfg.get("epo_path", "GARMIN/REMOTESW/CPE.bin")
    max_age_hours = cfg.get("max_age_hours", 24)
    last_synced_at = cfg.get("last_synced_at")

    if not _agps_is_stale(last_synced_at, max_age_hours):
        info(f"AGPS: data is fresh (< {max_age_hours}h old); skipping")
        return False

    # epo_path is like "GARMIN/REMOTESW/CPE.bin" — split into folder path + filename
    parts = epo_path.replace("\\", "/").split("/")
    filename = parts[-1]
    folder_path = "/".join(parts[:-1])

    cache_path = AGPS_CACHE_DIR / filename
    if not _download_agps(url, cache_path):
        return False

    folder_id = _mtp_navigate_path(device, storage_id, folder_path, create_missing=True)
    if folder_id is None:
        warn(f"AGPS: could not navigate to/create {folder_path!r} on watch")
        return False

    if not _mtp_write_file(device, storage_id, folder_id, filename, cache_path):
        return False

    _backend_post("/training-plan/sync/agps-synced")
    return True


# ---------------------------------------------------------------------------
# Workout upload / delete cycles
# ---------------------------------------------------------------------------

def workout_upload_cycle(device, storage_id: int) -> int:
    """Drop everything the backend still owes the watch into GARMIN/NewFiles/,
    except planned workouts. Returns the number uploaded.

    Planned workouts are deliberately excluded and left to
    schedule_upload_cycle. The watch only puts a workout on the calendar when
    its file arrives in the same NewFiles batch as the schedule naming it, so a
    workout sent here — on the one sync where it happened to be owed — is a
    workout the calendar can never claim afterwards. Race plans and anything
    else on the upload list have no such coupling and still go here.
    """
    import base64
    import tempfile

    items = [it for it in (_backend_get("/training-plan/sync/upload-list") or [])
             if it.get("type") != "workout"]
    if not items:
        debug("workout upload: nothing to upload")
        return 0

    folder_id = _mtp_navigate_path(device, storage_id, "GARMIN/NewFiles", create_missing=True)
    if folder_id is None:
        warn("workout upload: could not navigate to/create GARMIN/NewFiles on watch")
        return 0

    uploaded = []
    for item in items:
        fit_b64 = item.get("fit_b64")
        filename = item.get("filename")
        if not fit_b64 or not filename:
            warn(f"workout upload: skipping item id={item.get('id')} — missing fit_b64 or filename")
            continue
        try:
            fit_bytes = base64.b64decode(fit_b64)
        except Exception as exc:
            warn(f"workout upload: could not decode base64 for {filename!r}: {exc}")
            continue

        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_wkt_")
        try:
            with os.fdopen(tmp_fd, "wb") as f:
                f.write(fit_bytes)
            if _mtp_write_file(device, storage_id, folder_id, filename, Path(tmp_path)):
                uploaded.append({"id": item["id"], "filename": filename,
                                  "type": item.get("type", "workout")})
            else:
                warn(f"workout upload: failed to write {filename!r} to watch")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    if uploaded:
        _backend_post_json("/training-plan/sync/mark-uploaded", uploaded)
        info(f"workout upload: {len(uploaded)} file(s) written to watch")
    return len(uploaded)


def schedule_upload_cycle(device, storage_id: int) -> bool:
    """Write the training calendar to GARMIN/NewFiles/ — the schedule together
    with every workout file it names. Returns True if the schedule was written.

    Sending the workouts again, including the ones the watch demonstrably
    already has, is not waste. Measured on a fenix 6X on 2026-08-27: a schedule
    naming sixteen workouts went into NewFiles beside two workout files, and
    exactly two entries appeared in the calendar — the two from that batch. The
    other fourteen were already in GARMIN/Workouts and resolved to nothing. The
    watch binds a schedule entry to a file that arrives with it, not to one it
    is already holding, so the whole set travels every time.

    Workouts are written before the schedule: if a cable is pulled mid-write,
    a partial set of workouts is recoverable next sync, whereas a schedule
    naming files that never arrived is a calendar with holes in it.
    """
    import base64
    import tempfile

    resp = _backend_get("/training-plan/sync/schedule-bundle")
    if not resp:
        debug("schedule upload: no response from backend")
        return False

    count = resp.get("count", 0)
    fit_b64 = resp.get("fit_b64")
    if not fit_b64:
        debug("schedule upload: no schedule FIT from backend")
        return False
    # count == 0 still writes: an empty SCHEDULE.fit is how a deleted
    # training calendar gets cleared off the watch.

    try:
        fit_bytes = base64.b64decode(fit_b64)
    except Exception as exc:
        warn(f"schedule upload: could not decode base64: {exc}")
        return False

    folder_id = _mtp_navigate_path(device, storage_id, "GARMIN/NewFiles", create_missing=True)
    if folder_id is None:
        warn("schedule upload: could not navigate to/create GARMIN/NewFiles on watch")
        return False

    delivered = []
    for item in resp.get("workouts", []):
        wkt_b64 = item.get("fit_b64")
        filename = item.get("filename")
        if not wkt_b64 or not filename:
            warn(f"schedule upload: skipping workout id={item.get('id')} — missing fit_b64 or filename")
            continue
        try:
            wkt_bytes = base64.b64decode(wkt_b64)
        except Exception as exc:
            warn(f"schedule upload: could not decode base64 for {filename!r}: {exc}")
            continue
        w_fd, w_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_wkt_")
        try:
            with os.fdopen(w_fd, "wb") as f:
                f.write(wkt_bytes)
            if _mtp_write_file(device, storage_id, folder_id, filename, Path(w_path)):
                delivered.append({"id": item["id"], "filename": filename,
                                  "type": item.get("type", "workout")})
            else:
                # Keep going. One missing workout costs one calendar entry;
                # abandoning here costs every later one too.
                warn(f"schedule upload: failed to write {filename!r} to watch")
        finally:
            try:
                os.unlink(w_path)
            except OSError:
                pass

    if delivered:
        _backend_post_json("/training-plan/sync/mark-uploaded", delivered)
        info(f"schedule upload: {len(delivered)} workout file(s) written to NewFiles")

    tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_sched_")
    try:
        with os.fdopen(tmp_fd, "wb") as f:
            f.write(fit_bytes)
        ok = _mtp_write_file(device, storage_id, folder_id, "SCHEDULE.fit", Path(tmp_path))
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass

    if ok:
        info(f"schedule upload: SCHEDULE.fit written to NewFiles ({count} workout(s))")
    else:
        warn("schedule upload: failed to write SCHEDULE.fit to watch")
    return ok


def workout_delete_cycle(device, storage_id: int) -> int:
    """Delete completed workout FIT files from GARMIN/Workouts/ on the watch.
    Returns the number of items marked deleted."""
    items = _backend_get("/training-plan/sync/delete-list")
    if not items:
        debug("workout delete: nothing to delete")
        return 0

    folder_id = _mtp_navigate_path(device, storage_id, "GARMIN/Workouts", create_missing=False)
    # Build name→object_id map only if the folder exists
    on_watch: dict[str, int] = {}
    if folder_id is not None:
        on_watch = {name.lower(): item_id
                    for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id)
                    if not is_folder}

    deleted_ids: list[int] = []
    deleted_pending_ids: list[int] = []
    for item in items:
        filename = item.get("filename")
        if not filename:
            continue
        obj_id = on_watch.get(filename.lower())
        if obj_id is not None:
            ret = _libmtp.LIBMTP_Delete_Object(device, c_uint32(obj_id))
            if ret != 0:
                warn(f"workout delete: LIBMTP_Delete_Object({filename!r}) returned {ret}; skipping")
                continue
            info(f"workout delete: removed {filename!r} from watch")
        else:
            debug(f"workout delete: {filename!r} not on watch; marking deleted")

        if item.get("type") == "pending":
            deleted_pending_ids.append(item["id"])
        else:
            deleted_ids.append(item["id"])

    if deleted_ids or deleted_pending_ids:
        _backend_post_json("/training-plan/sync/mark-deleted",
                           {"ids": deleted_ids, "pending_ids": deleted_pending_ids})
        info(f"workout delete: {len(deleted_ids) + len(deleted_pending_ids)} item(s) marked deleted")
    return len(deleted_ids) + len(deleted_pending_ids)


def course_upload_cycle(device, storage_id: int) -> int:
    """Fetch pending course FIT files from the backend and drop them into
    GARMIN/Courses/ so the watch can navigate them.  Returns the number
    uploaded."""
    import base64
    import tempfile

    items = _backend_get("/maps/sync/course-upload-list")
    if not items:
        debug("course upload: nothing to upload")
        return 0

    courses_folder_id = _mtp_navigate_path(
        device, storage_id, "GARMIN/Courses", create_missing=True,
    )
    if courses_folder_id is None:
        warn("course upload: could not navigate to/create GARMIN/Courses on watch")
        return 0

    uploaded = []
    for item in items:
        fit_b64 = item.get("fit_b64")
        filename = item.get("filename")
        if not fit_b64 or not filename:
            warn(f"course upload: skipping item id={item.get('id')} — missing fit_b64 or filename")
            continue
        try:
            fit_bytes = base64.b64decode(fit_b64)
        except Exception as exc:
            warn(f"course upload: could not decode base64 for {filename!r}: {exc}")
            continue

        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_course_")
        try:
            with os.fdopen(tmp_fd, "wb") as f:
                f.write(fit_bytes)
            if _mtp_write_file(device, storage_id, courses_folder_id, filename, Path(tmp_path)):
                uploaded.append({"id": item["id"], "filename": filename,
                                  "type": "course"})
            else:
                warn(f"course upload: failed to write {filename!r} to watch")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    if uploaded:
        _backend_post_json("/maps/sync/mark-course-uploaded", uploaded)
        info(f"course upload: {len(uploaded)} file(s) written to watch")
    return len(uploaded)


def course_delete_cycle(device, storage_id: int) -> int:
    """Remove course FIT files from GARMIN/Courses/ that the backend has queued
    (a custom track the user unloaded, or an external course they removed).
    Returns the number marked deleted. Mirrors workout_delete_cycle."""
    items = _backend_get("/maps/sync/course-delete-list")
    if not items:
        debug("course delete: nothing to delete")
        return 0

    folder_id = _mtp_navigate_path(device, storage_id, "GARMIN/Courses", create_missing=False)
    on_watch: dict[str, int] = {}
    if folder_id is not None:
        on_watch = {name.lower(): item_id
                    for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id)
                    if not is_folder}

    deleted_ids: list[int] = []
    for item in items:
        filename = item.get("filename")
        if not filename:
            continue
        obj_id = on_watch.get(filename.lower())
        if obj_id is not None:
            ret = _libmtp.LIBMTP_Delete_Object(device, c_uint32(obj_id))
            if ret != 0:
                warn(f"course delete: LIBMTP_Delete_Object({filename!r}) returned {ret}; skipping")
                continue
            info(f"course delete: removed {filename!r} from watch")
        else:
            debug(f"course delete: {filename!r} not on watch; marking deleted")
        deleted_ids.append(item["id"])

    if deleted_ids:
        _backend_post_json("/maps/sync/mark-course-deleted", {"ids": deleted_ids})
        info(f"course delete: {len(deleted_ids)} item(s) marked deleted")
    return len(deleted_ids)


LOCATIONS_FOLDER = "GARMIN/Locations"


def waypoint_upload_cycle(device, storage_id: int) -> int:
    """Write the backend's rebuilt Locations.fit into GARMIN/Locations/.

    Unlike courses there is never more than one item, and it is not one waypoint
    — a watch keeps every saved place in a single file, so the backend hands over
    the whole set or nothing. The `ids` it came with are echoed back so the
    server records exactly what the file contained; see waypoints_sync.py.
    """
    items = _backend_get("/maps/sync/waypoint-upload-list")
    if not items:
        debug("waypoint upload: nothing to upload")
        return 0

    folder_id = _mtp_navigate_path(
        device, storage_id, LOCATIONS_FOLDER, create_missing=True,
    )
    if folder_id is None:
        warn(f"waypoint upload: could not navigate to/create {LOCATIONS_FOLDER} on watch")
        return 0

    uploaded = []
    for item in items:
        fit_b64 = item.get("fit_b64")
        filename = item.get("filename")
        if not fit_b64 or not filename:
            warn("waypoint upload: skipping item — missing fit_b64 or filename")
            continue
        try:
            fit_bytes = base64.b64decode(fit_b64)
        except Exception as exc:
            warn(f"waypoint upload: could not decode base64 for {filename!r}: {exc}")
            continue

        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_locations_")
        try:
            with os.fdopen(tmp_fd, "wb") as f:
                f.write(fit_bytes)
            if _mtp_write_file(device, storage_id, folder_id, filename, Path(tmp_path)):
                uploaded.append({"id": item.get("id", 0), "type": "waypoint",
                                 "filename": filename, "ids": item.get("ids") or []})
            else:
                warn(f"waypoint upload: failed to write {filename!r} to watch")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    if uploaded:
        _backend_post_json("/maps/sync/mark-waypoint-uploaded", uploaded)
        count = sum(len(u["ids"]) for u in uploaded)
        info(f"waypoint upload: {count} saved place(s) written to watch")
    return len(uploaded)


def waypoint_delete_cycle(device, storage_id: int) -> int:
    """Remove Locations.fit once the user has unloaded every place in it.

    Only ever fires for the empty case — any other change is an overwrite the
    upload cycle handles, and deleting a file we are about to rewrite would lose
    every saved place if the sync is interrupted in between.
    """
    items = _backend_get("/maps/sync/waypoint-delete-list")
    if not items:
        debug("waypoint delete: nothing to delete")
        return 0

    folder_id = _mtp_navigate_path(device, storage_id, LOCATIONS_FOLDER, create_missing=False)
    on_watch: dict[str, int] = {}
    if folder_id is not None:
        on_watch = {name.lower(): item_id
                    for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id)
                    if not is_folder}

    removed = 0
    for item in items:
        filename = item.get("filename")
        if not filename:
            continue
        obj_id = on_watch.get(filename.lower())
        if obj_id is not None:
            ret = _libmtp.LIBMTP_Delete_Object(device, c_uint32(obj_id))
            if ret != 0:
                warn(f"waypoint delete: LIBMTP_Delete_Object({filename!r}) returned {ret}; skipping")
                continue
            info(f"waypoint delete: removed {filename!r} from watch")
        else:
            debug(f"waypoint delete: {filename!r} not on watch; marking deleted")
        removed += 1

    if removed:
        _backend_post_json("/maps/sync/mark-waypoint-deleted", {})
        info("waypoint delete: saved places marked off the watch")
    return removed


def waypoint_inventory_cycle(device, storage_id: int) -> int:
    """Hand the watch's own Locations.fit to the backend and let it reconcile.

    The whole file rather than a listing, unlike courses: there is only ever
    one, so asking which files are there would be a round trip to learn a name
    we already know. An absent file is reported as an absent file — that is how
    a wipe on the device itself reaches Tracks, and skipping the call would
    leave every place listed as on the watch forever.
    """
    folder_id = _mtp_navigate_path(device, storage_id, LOCATIONS_FOLDER, create_missing=False)
    payload = {}

    if folder_id is not None:
        on_watch = {name.lower(): item_id
                    for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id)
                    if not is_folder}
        item_id = on_watch.get("locations.fit")
        if item_id is not None:
            tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_devloc_")
            os.close(tmp_fd)
            try:
                if _mtp_download(device, item_id, tmp_path.encode("utf-8")) != 0:
                    warn("waypoint inventory: could not read Locations.fit off the watch")
                    return 0
                payload["fit_b64"] = base64.b64encode(
                    Path(tmp_path).read_bytes()).decode()
            finally:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass

    resp = _backend_post_json("/maps/sync/waypoint-ingest", payload)
    if not isinstance(resp, dict):
        return 0
    if resp.get("imported"):
        info(f"waypoint inventory: imported {resp['imported']} place(s) from the watch")
    else:
        debug(f"waypoint inventory: {resp.get('found', 0)} place(s) on the watch, none new")
    return int(resp.get("imported") or 0)


def course_inventory_cycle(device, storage_id: int) -> int:
    """Report every file in GARMIN/Courses/ to the backend and download+ingest the
    ones it hasn't seen, so courses already on the watch (incl. ones NOT created by
    this app) show up in the UI with full geometry. Returns the count ingested."""
    import base64
    import tempfile

    folder_id = _mtp_navigate_path(device, storage_id, "GARMIN/Courses", create_missing=False)
    if folder_id is None:
        debug("course inventory: no GARMIN/Courses folder on watch")
        return 0

    listing = _mtp_list_sizes(device, storage_id, folder_id)
    fits = {iid: (name, size) for iid, (name, size) in listing.items()
            if name.lower().endswith(".fit")}
    if not fits:
        debug("course inventory: no .fit courses on watch")
        return 0

    inventory = [{"filename": name, "size": size} for (name, size) in fits.values()]
    resp = _backend_post_json("/maps/sync/course-inventory", inventory)
    if not resp:
        return 0
    needed = {fn.lower() for fn in resp.get("needed", [])}
    if not needed:
        info(f"course inventory: {len(fits)} course(s) on watch, none new")
        return 0

    ingested = 0
    for iid, (name, size) in fits.items():
        if name.lower() not in needed:
            continue
        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".fit", prefix="tracks_devcourse_")
        os.close(tmp_fd)
        try:
            if _mtp_download(device, iid, tmp_path.encode("utf-8")) != 0:
                warn(f"course inventory: failed to download {name!r} from watch")
                continue
            data = Path(tmp_path).read_bytes()
            r = _backend_post_json("/maps/sync/course-ingest", {
                "filename": name, "size": size,
                "fit_b64": base64.b64encode(data).decode(),
            })
            if r:
                ingested += 1
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    info(f"course inventory: ingested {ingested} external course(s) from watch")
    return ingested


# ---------------------------------------------------------------------------
# Cheap USB-level presence check (first-pass filter)
# ---------------------------------------------------------------------------

def usb_garmin_present() -> bool:
    """Does the kernel see ANY USB device with the configured vendor ID right now?

    Reads sysfs (/sys/bus/usb/devices/*/idVendor) directly rather than shelling
    out to lsusb. This matters for hotplug: sysfs reflects a device plugged in
    *after* the container started, live, even in this long-running process — but
    the /dev/bus/usb view that lsusb/libusb reads was observed to go stale for a
    process that started before the plug-in, so the old lsusb-based check silently
    missed hot-plugged watches and the poller sat idle forever. Falls back to
    lsusb only if sysfs is unavailable. The real "can we talk to it?" check is
    still _mtp_open_garmin (run in a fresh worker subprocess)."""
    import glob
    entries = glob.glob("/sys/bus/usb/devices/*/idVendor")
    if entries:
        for vf in entries:
            try:
                with open(vf) as fh:
                    if fh.read().strip().lower() == VENDOR_ID:
                        return True
            except OSError:
                continue
        return False
    return _lsusb_garmin_present()


def _lsusb_garmin_present() -> bool:
    """Fallback presence check via lsusb, for environments without sysfs."""
    try:
        r = subprocess.run(
            ["lsusb", "-d", f"{VENDOR_ID}:"],
            capture_output=True, text=True, timeout=10,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        warn(f"lsusb failed: {exc}")
        return False
    if r.returncode != 0:
        return False
    return bool(r.stdout.strip())


# ---------------------------------------------------------------------------
# Folder walk + sync
# ---------------------------------------------------------------------------

def _find_storage_id(device) -> Optional[int]:
    for sid, desc in _mtp_storages(device):
        debug(f"storage: {desc!r} id={sid:#x}")
        if desc == STORAGE_NAME:
            return sid
    return None


def _find_folder_id(device, storage_id: int, folder_path: str) -> Optional[int]:
    """Walk folder_path segment by segment from the storage root, returning
    the deepest folder's item_id, or None if any segment is missing."""
    segments = [s for s in folder_path.split("/") if s]
    if not segments:
        return None
    parent = LIBMTP_FILES_AND_FOLDERS_ROOT
    for seg in segments:
        match = None
        for item_id, name, is_folder in _mtp_list(device, storage_id, parent):
            if is_folder and name == seg:
                match = item_id
                break
        if match is None:
            debug(f"folder segment {seg!r} not found under parent {parent:#x}")
            return None
        parent = match
    return parent


def _read_unit_id(device) -> tuple[Optional[str], Optional[str]]:
    """The watch's unit id and model name, from GARMIN/GarminDevice.xml.

    The unit id is the number the watch writes as `serial_number` in every
    FIT file it records, which is what the server keys a watch's claim on —
    the USB/MTP serial string is a different identifier, so it is not used.
    Returns (None, None) when the file is missing or unreadable; the server
    then only accepts the files if the instance has a single account.

    Unverified on hardware at the time of writing: GarminDevice.xml is where
    Garmin Express reads the unit id, and the fenix 6X Pro's FIT serial is a
    ten-digit unit id, but the two have not yet been compared on a docked
    watch. If they differ, every watch reads as unclaimed and nothing is
    misfiled — files simply wait on the watch.
    """
    import xml.etree.ElementTree as ET

    storage_id = _find_storage_id(device)
    folder_id = _find_folder_id(device, storage_id, "GARMIN") if storage_id is not None else None
    if folder_id is None:
        return None, None
    item = next((item_id for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id)
                 if not is_folder and name.lower() == "garmindevice.xml"), None)
    if item is None:
        debug("GARMIN/GarminDevice.xml not found")
        return None, None
    dest = DEST / ".garmindevice.xml"
    try:
        if _mtp_download(device, item, str(dest).encode("utf-8")) != 0:
            return None, None
        root = ET.fromstring(dest.read_bytes())
    except (OSError, ET.ParseError) as exc:
        warn(f"could not read GarminDevice.xml: {exc}")
        return None, None
    finally:
        try:
            dest.unlink()
        except OSError:
            pass
    unit_id = (root.findtext("{*}Id") or "").strip()
    model = (root.findtext("{*}Model/{*}Description") or "").strip() or None
    return (unit_id if unit_id.isdigit() else None), model


def _list_target_files(device, storage_id: int, folder_id: int) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id):
        if is_folder:
            continue
        if name.lower().endswith(FILE_EXT):
            out.append((item_id, name))
    return out


def download_file(device, file_id: int, dest_final: Path) -> bool:
    """Download via libmtp to a .partial sibling, then atomically rename."""
    dest_partial = dest_final.with_name(dest_final.name + ".partial")
    if dest_partial.exists():
        try:
            dest_partial.unlink()
        except OSError as exc:
            warn(f"could not remove stale partial {dest_partial}: {exc}")

    ret = _mtp_download(device, file_id, str(dest_partial).encode("utf-8"))
    if ret != 0:
        warn(f"LIBMTP_Get_File_To_File({file_id}) returned {ret}")
        _cleanup_partial(dest_partial)
        return False

    if not dest_partial.exists():
        warn(f"LIBMTP_Get_File_To_File succeeded but {dest_partial} missing")
        return False
    try:
        size = dest_partial.stat().st_size
    except OSError as exc:
        warn(f"could not stat downloaded file {dest_partial}: {exc}")
        return False
    if size == 0:
        warn(f"downloaded file {dest_partial} is empty; treating as failure")
        _cleanup_partial(dest_partial)
        return False

    try:
        dest_partial.replace(dest_final)
    except OSError as exc:
        error(f"could not rename {dest_partial} -> {dest_final}: {exc}")
        return False

    _chown_if_needed(dest_final)
    info(f"downloaded {dest_final.name} ({size} bytes)")
    return True


def _cleanup_partial(p: Path) -> None:
    if p.exists():
        try:
            p.unlink()
        except OSError:
            pass


SYNC_DEVICE_DISCONNECTED = -2


def _sync_folder(device, state: State, storage_id: int, folder_path: str,
                  pubkey: Optional[bytes]) -> int:
    """Sync one folder: download each new file over MTP to local (ephemeral,
    container-only) disk, immediately seal it against `pubkey` and hand it
    to the backend, then remove the local plaintext copy — see
    _seal_and_ingest. Returns downloaded count or SYNC_DEVICE_DISCONNECTED."""
    if pubkey is None:
        debug(f"no ingestion key available; skipping {folder_path}")
        return 0

    folder_id = _find_folder_id(device, storage_id, folder_path)
    if folder_id is None:
        debug(f"folder {folder_path!r} not found on watch; skipping")
        return 0

    files = _list_target_files(device, storage_id, folder_id)
    info(f"found {len(files)} {FILE_EXT} file(s) in {STORAGE_NAME}/{folder_path}")

    new = [(fid, name) for fid, name in files if not state.has(name)]
    if not new:
        info(f"nothing new in {folder_path}")
        return 0

    info(f"{len(new)} new file(s) to download from {folder_path}")
    downloaded = 0
    for idx, (fid, name) in enumerate(new):
        if not _running:
            break
        dest = DEST / name
        if dest.exists():
            # Leftover plaintext from an interrupted previous run (a crash
            # between MTP download and sealing) — recover and seal it now
            # rather than leave it sitting on disk or silently mark it done.
            warn(f"{name} already on local disk from an interrupted run; sealing it now")
        elif not download_file(device, fid, dest):
            # Failure: if the watch is gone, bail before we log a flood of
            # warnings, one per remaining file.
            if not usb_garmin_present():
                remaining = len(new) - idx - 1
                warn(f"device disconnected mid-sync after {downloaded} file(s); "
                     f"{remaining} remaining will be retried on next plug-in")
                return SYNC_DEVICE_DISCONNECTED
            continue  # watch still present — single-file failure, keep going

        if _seal_and_ingest(pubkey, dest, name):
            state.mark(name)
            downloaded += 1
        # else: local plaintext already removed by _seal_and_ingest either
        # way; not marking synced means it's retried (re-downloaded) next cycle.
    return downloaded


def sync_cycle(device, state: State, pubkey: Optional[bytes]) -> int:
    """Sync all configured folders. Returns total downloaded count or SYNC_DEVICE_DISCONNECTED."""
    storage_id = _find_storage_id(device)
    if storage_id is None:
        warn(f"storage {STORAGE_NAME!r} not found on device")
        return 0

    total = 0
    for folder_path in FOLDER_PATHS:
        result = _sync_folder(device, state, storage_id, folder_path, pubkey)
        if result == SYNC_DEVICE_DISCONNECTED:
            return SYNC_DEVICE_DISCONNECTED
        total += result

    info(f"sync cycle complete: {total} file(s) downloaded across {len(FOLDER_PATHS)} folder(s)")
    return total


# ---------------------------------------------------------------------------
# Parent / worker entry points
# ---------------------------------------------------------------------------

_running = True


def _handle_signal(signum, _frame):
    global _running
    info(f"signal {signum} received; shutting down after current operation")
    _running = False


def _consume_trigger() -> bool:
    """Return True if a "sync now" request is pending on the backend
    (POST /training-plan/sync/trigger from the web UI). Used to be a file
    dropped on the shared fit-files volume — this container doesn't mount
    that volume at all anymore (it never touches shared disk; see the
    module docstring), so it's API-polled instead. The backend consumes the
    flag on read, so a positive result here always means "was pending,
    isn't anymore" — safe to call from a tight poll loop."""
    resp = _backend_get("/training-plan/sync/should-trigger")
    return bool(resp and resp.get("trigger"))


def ensure_dest() -> bool:
    try:
        DEST.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        error(f"cannot create dest dir {DEST}: {exc}")
        return False
    if not os.access(DEST, os.W_OK):
        error(f"dest dir {DEST} is not writable by this process "
              f"(uid={os.getuid()}, gid={os.getgid()})")
        return False
    return True


def _log_watch_dirs(device, storage_id: int) -> None:
    """Log the filenames in Workouts, Schedule and NewFiles for diagnostics.
    Note: the watch uses GARMIN/Schedule (singular) as the destination for
    processed schedule FIT files; GARMIN/Schedules (plural) does not exist."""
    for path in ("GARMIN/Workouts", "GARMIN/Schedule", "GARMIN/NewFiles"):
        fid = _mtp_navigate_path(device, storage_id, path, create_missing=False)
        if fid is None:
            info(f"watch {path}: (directory not found)")
            continue
        files = [name for _, name, is_dir in _mtp_list(device, storage_id, fid) if not is_dir]
        info(f"watch {path}: {', '.join(files) if files else '(empty)'}")


def music_sync_cycle(device, storage_id: int) -> int:
    """Put the user's carried music in Music/ at the storage root.

    Not under GARMIN/. Personal audio lives at the top level of the device and
    the watch's library scanner only looks there — files written into
    GARMIN/Music exist on disk and never appear in the player.

    Deliberately last in the sync: it is the only phase that moves megabytes,
    and a watch unplugged part-way through should already have its workouts,
    courses and calendar. Removals go first so they free space the additions
    are about to need.

    Note this is the *native* music library, which is a different place from
    where the Tracks Connect IQ app keeps its downloads. The backend tracks the
    two separately; see backend/app/api/music/sync.py.
    """
    import tempfile

    plan = _backend_get("/music/sync/upload-list")
    deletes = _backend_get("/music/sync/delete-list")
    items = (plan or {}).get("items") or []
    playlists = (plan or {}).get("playlists") or []
    removals = (deletes or {}).get("items") or []

    if not items and not playlists and not removals:
        debug("music: nothing to sync")
        return 0

    folder = (plan or deletes or {}).get("folder") or "Music"
    folder_id = _mtp_navigate_path(device, storage_id, folder, create_missing=True)
    if folder_id is None:
        warn(f"music: could not navigate to/create {folder} on watch")
        return 0

    on_watch = {name.lower(): item_id
                for item_id, name, is_folder in _mtp_list(device, storage_id, folder_id)
                if not is_folder}
    removed_ids = []
    for item in removals:
        filename = item.get("filename")
        if not filename:
            continue
        obj_id = on_watch.get(filename.lower())
        if obj_id is None:
            # Already gone — someone deleted it on the watch, or a previous run
            # removed it and could not report. Either way the server should
            # stop offering it.
            debug(f"music: {filename!r} not on watch; marking deleted")
            removed_ids.append(item["id"])
            continue
        ret = _libmtp.LIBMTP_Delete_Object(device, c_uint32(obj_id))
        if ret != 0:
            warn(f"music: LIBMTP_Delete_Object({filename!r}) returned {ret}; skipping")
            continue
        removed_ids.append(item["id"])
    if removed_ids:
        _backend_post_json("/music/sync/mark-deleted", {"ids": removed_ids})
        info(f"music: {len(removed_ids)} file(s) removed from watch")

    uploaded = []
    for item in items:
        filename = item.get("filename")
        track_id = item.get("id")
        if not filename or track_id is None:
            continue
        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".mp3", prefix="tracks_music_")
        os.close(tmp_fd)
        tmp = Path(tmp_path)
        try:
            if not _backend_get_bytes(f"/music/sync/audio/{track_id}", tmp):
                warn(f"music: could not fetch {filename!r}")
                continue
            if _mtp_write_file(device, storage_id, folder_id, filename, tmp):
                uploaded.append({"id": track_id, "filename": filename})
            else:
                warn(f"music: failed to write {filename!r} to watch")
        finally:
            try:
                tmp.unlink()
            except OSError:
                pass

    if uploaded:
        _backend_post_json("/music/sync/mark-uploaded", {"items": uploaded})
        info(f"music: {len(uploaded)} track(s) written to watch")

    # Playlists last, so every .m3u names files that are already there — a
    # playlist pointing at a missing file is unusable on some firmware.
    for pl in playlists:
        filename = pl.get("filename")
        content = pl.get("content")
        if not filename or not content:
            continue
        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".m3u", prefix="tracks_pl_")
        try:
            with os.fdopen(tmp_fd, "w") as f:
                f.write(content)
            if not _mtp_write_file(device, storage_id, folder_id, filename, Path(tmp_path)):
                warn(f"music: failed to write playlist {filename!r}")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    return len(uploaded)


def _worker_main() -> int:
    """One detect+open+sync+release attempt in this (fresh) process. Writes
    a single JSON line to stdout describing the outcome; logs go to stderr
    as usual. Invoked as a subprocess by the parent — see _run_sync_subprocess.

    The whole reason this exists as a separate process is that libmtp never
    calls libusb_exit, so a long-running parent's default libusb context
    goes stale and stops noticing newly-plugged-in devices. A fresh worker
    process always gets a fresh libusb context.

    Exit code is always 0 — the JSON status is the real result. Non-zero
    exit means the worker crashed before reaching its final print.
    """
    info("worker starting")
    if not ensure_dest():
        sys.stdout.write(json.dumps({"status": "ERROR",
                                     "error": "dest not writable"}) + "\n")
        sys.stdout.flush()
        return 0

    global _DEVICE_SERIAL
    state = State.load(DEST / STATE_FILENAME)

    device = _mtp_open_garmin()
    if device is None:
        sys.stdout.write(json.dumps({"status": "NOT_READY"}) + "\n")
        sys.stdout.flush()
        return 0

    try:
        info(f"connected to {_mtp_friendlyname(device)}")
        # Which watch, before anything is asked of the server: the answer
        # decides whose key files are sealed to and whose workouts go on it.
        _DEVICE_SERIAL, model = _read_unit_id(device)
        if _DEVICE_SERIAL:
            info(f"watch unit id {_DEVICE_SERIAL}")
            # So a watch the server has never seen shows up under Devices,
            # ready to be claimed.
            _backend_post_json("/sync/register-device", {
                "serial_number": _DEVICE_SERIAL, "manufacturer": "garmin",
                "manufacturer_id": 1, "product_name": model,
            })
        else:
            warn("could not read the watch's unit id; the server will accept "
                 "its files only if it has a single account")
        pubkey = _fetch_pubkey()
        if pubkey is None:
            warn("proceeding without an ingestion key this cycle — FIT downloads "
                 "will be skipped and retried once the watch is claimed and the "
                 "backend/token is reachable")
        _backend_post("/training-plan/sync/start")   # show spinner in the web UI
        result = sync_cycle(device, state, pubkey)

        # Workout upload/delete and AGPS run in the same MTP session immediately
        # after FIT download. Skip if the device disconnected mid-FIT-sync.
        if result != SYNC_DEVICE_DISCONNECTED:
            storage_id = _find_storage_id(device)
            if storage_id is not None:
                workout_delete_cycle(device, storage_id)
                workout_upload_cycle(device, storage_id)
                schedule_upload_cycle(device, storage_id)
                course_delete_cycle(device, storage_id)
                course_upload_cycle(device, storage_id)
                course_inventory_cycle(device, storage_id)
                waypoint_delete_cycle(device, storage_id)
                waypoint_upload_cycle(device, storage_id)
                waypoint_inventory_cycle(device, storage_id)
                _log_watch_dirs(device, storage_id)
                _backend_post("/training-plan/sync/watch-synced")
                agps_sync_cycle(device, storage_id)
                music_sync_cycle(device, storage_id)
            else:
                debug("storage not found; skipping workout sync and AGPS")
    except Exception as exc:
        _mtp_release(device)
        sys.stdout.write(json.dumps({"status": "ERROR",
                                     "error": f"sync_cycle raised: {exc}"}) + "\n")
        sys.stdout.flush()
        return 0
    _mtp_release(device)

    if result == SYNC_DEVICE_DISCONNECTED:
        out = {"status": "DISCONNECTED"}
    else:
        out = {"status": "OK", "downloaded": result}
    sys.stdout.write(json.dumps(out) + "\n")
    sys.stdout.flush()
    return 0


def _run_sync_subprocess() -> dict:
    """Spawn this script with --worker, return the worker's parsed JSON
    result. Worker stderr passes through to our stderr so its logs appear
    in docker logs in real time."""
    try:
        proc = subprocess.run(
            [sys.executable, "-u", __file__, "--worker"],
            stdout=subprocess.PIPE,
            stderr=None,
            text=True,
            timeout=WORKER_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        error(f"worker timed out after {WORKER_TIMEOUT}s")
        return {"status": "ERROR", "error": "timeout"}

    if proc.returncode != 0:
        return {"status": "ERROR",
                "error": f"worker exited with code {proc.returncode}"}

    lines = [l for l in (proc.stdout or "").splitlines() if l.strip()]
    if not lines:
        return {"status": "ERROR", "error": "worker produced no output"}
    try:
        return json.loads(lines[-1])
    except json.JSONDecodeError as exc:
        return {"status": "ERROR",
                "error": f"unparseable worker output: {exc}"}


def main() -> int:
    # Worker entry point — see _worker_main.
    if len(sys.argv) >= 2 and sys.argv[1] == "--worker":
        return _worker_main()

    info(f"garmin-sync starting; dest={DEST} poll={POLL_INTERVAL}s "
         f"vendor=0x{VENDOR_ID} storage={STORAGE_NAME!r} "
         f"folders={FOLDER_PATHS!r} ext={FILE_EXT}")

    if not ensure_dest():
        return 2

    signal.signal(signal.SIGTERM, _handle_signal)
    signal.signal(signal.SIGINT, _handle_signal)

    # State is loaded here purely for the startup log line — the worker
    # reads and writes the on-disk state file itself.
    state = State.load(DEST / STATE_FILENAME)
    info(f"loaded state: {len(state.synced)} previously-synced filename(s)")

    last_sync_complete_at = 0.0
    mtp_unavailable_logged = False
    was_present = None            # tri-state: None until the first poll resolves
    last_idle_heartbeat = 0.0

    info(f"watching for a Garmin watch on USB (vendor 0x{VENDOR_ID}); "
         f"plug the watch in with a data cable to sync")

    while _running:
        # Gate 1: is *anything* with this vendor on the USB bus? Cheap
        # (~ms) so we can poll often when nothing is plugged in.
        present = usb_garmin_present()
        debug(f"usb_garmin_present={present}")
        if not present:
            mtp_unavailable_logged = False
            now = time.monotonic()
            if was_present:
                info("watch unplugged; back to waiting")
                last_idle_heartbeat = now
            elif (IDLE_HEARTBEAT_INTERVAL > 0
                  and (now - last_idle_heartbeat) >= IDLE_HEARTBEAT_INTERVAL):
                # Periodic proof-of-life while idle so the container never looks
                # hung. Points at the usual culprits when a watch *is* plugged in.
                info(f"idle — no watch detected on USB (vendor 0x{VENDOR_ID}). "
                     f"If yours is plugged in: use a DATA cable (not charge-only), "
                     f"wake the watch, and set its USB mode to MTP.")
                last_idle_heartbeat = now
            was_present = False
            time.sleep(POLL_INTERVAL)
            continue

        if not was_present:
            info(f"Garmin device appeared on USB (vendor 0x{VENDOR_ID}); attempting sync")
        was_present = True

        # Gate 2: cooldown from a recently-finished sync of THIS plug-in,
        # so we don't re-enumerate every 15 s while the watch charges.
        # A force-sync trigger bypasses the cooldown immediately.
        now = time.monotonic()
        if (now - last_sync_complete_at) < RESYNC_COOLDOWN:
            if _consume_trigger():
                info("sync trigger detected; bypassing cooldown")
                last_sync_complete_at = 0.0
            else:
                debug("inside cooldown window; waiting for unplug")
                time.sleep(2)
                continue

        # Gate 3: hand off to a worker subprocess. The worker is what
        # actually talks to libmtp; it gets a fresh libusb context, which
        # is essential for hotplug visibility (see module docstring).
        result = _run_sync_subprocess()
        status = result.get("status", "ERROR")

        if status == "NOT_READY":
            # Watch is on USB but libmtp can't open it — either we caught
            # the watch mid-plug-in in a transitional USB descriptor state,
            # or USB Mode is set to "Garmin" instead of MTP. Retry; no need
            # to latch.
            if not mtp_unavailable_logged:
                warn("USB device present but libmtp can't open it yet; "
                     "will keep retrying. If this persists for more than a "
                     "few seconds, check that the watch's USB Mode is set "
                     "to MTP rather than Garmin.")
                mtp_unavailable_logged = True
            else:
                debug("worker reported NOT_READY; retrying")
            time.sleep(POLL_INTERVAL)
            continue

        mtp_unavailable_logged = False

        if status == "DISCONNECTED":
            # Watch was unplugged mid-sync. Don't arm cooldown — the user
            # is likely about to replug and we want the next sync to fire
            # immediately rather than wait for the cooldown to clear.
            # Tell the backend so the UI spinner doesn't stay latched.
            _backend_post("/training-plan/sync/abort")
            last_sync_complete_at = 0.0
            continue

        if status != "OK":
            error(f"worker error: {result.get('error', 'unknown')}")
            _backend_post("/training-plan/sync/abort")
            time.sleep(POLL_INTERVAL)
            continue

        # status == OK: successful sync. Arm cooldown and wait for unplug
        # before scanning again so we don't pester a charging watch.
        # A force-sync trigger breaks out of the wait and re-runs immediately.
        last_sync_complete_at = time.monotonic()
        while _running and usb_garmin_present():
            if _consume_trigger():
                info("sync trigger detected; re-syncing immediately")
                last_sync_complete_at = 0.0
                break
            time.sleep(2)
        else:
            if _running:
                info("device unplugged; back to polling")
                last_sync_complete_at = 0.0

    info("exited cleanly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
