// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Minimal MTP (Media Transfer Protocol) client over WebUSB, read-only.
//
// MTP is a superset of PTP (ISO 15740): 12-byte little-endian containers
// exchanged over USB bulk endpoints. This implements just enough of the
// protocol to enumerate a Garmin watch's filesystem and download files:
// OpenSession, GetDeviceInfo, GetStorageIDs, GetObjectHandles,
// GetObjectInfo, GetObject, CloseSession.
//
// Protocol references: PIMA 15740 (PTP), USB MTP spec v1.1, and the
// tidepool-org/webmtp implementation (BSD-2-Clause), which proved this
// approach works from the browser.
//
// Chromium-only: navigator.usb requires a secure context (HTTPS/localhost)
// and does not exist in Firefox/Safari.

export const GARMIN_VENDOR_ID = 0x091e;

// Container types
const TYPE_COMMAND = 1;
const TYPE_DATA = 2;
const TYPE_RESPONSE = 3;

// Operation codes
const OP = {
  GetDeviceInfo: 0x1001,
  OpenSession: 0x1002,
  CloseSession: 0x1003,
  GetStorageIDs: 0x1004,
  GetObjectHandles: 0x1007,
  GetObjectInfo: 0x1008,
  GetObject: 0x1009,
  DeleteObject: 0x100b,
  SendObjectInfo: 0x100c,
  SendObject: 0x100d,
};

// Response codes we name for error messages
const RESPONSE_NAMES = {
  0x2001: "OK",
  0x2002: "GeneralError",
  0x2003: "SessionNotOpen",
  0x2005: "OperationNotSupported",
  0x2006: "ParameterNotSupported",
  0x2007: "IncompleteTransfer",
  0x2009: "InvalidObjectHandle",
  0x2013: "StoreNotAvailable",
  0x2019: "DeviceBusy",
  0x201e: "SessionAlreadyOpen",
};

const RC_OK = 0x2001;
const RC_SESSION_ALREADY_OPEN = 0x201e;

// Object format code for folders ("associations" in PTP terms)
export const FORMAT_ASSOCIATION = 0x3001;

// GetObjectHandles magic parameter values
const ALL_STORAGES = 0xffffffff;
export const PARENT_ROOT = 0xffffffff;

// Read size per bulk transfer. Larger reads amortise per-transfer latency,
// which dominates when pulling many small FIT files.
const READ_CHUNK = 1024 * 1024;

// Data-out chunk size, in endpoint packets. 512 × 512 B = 256 KB, big enough
// that per-transfer overhead is irrelevant and small enough to keep progress
// updates frequent on a multi-megabyte track.
const CHUNK_PACKETS = 512;

export class MtpError extends Error {
  constructor(message, { responseCode = null, cause = null } = {}) {
    super(message);
    this.name = "MtpError";
    this.responseCode = responseCode;
    this.cause = cause;
  }
}

function responseName(code) {
  return RESPONSE_NAMES[code] || `0x${code.toString(16)}`;
}

// --- dataset parsing helpers -----------------------------------------------

// Sequential little-endian reader for PTP datasets.
export class DataReader {
  constructor(bytes) {
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.offset = 0;
  }
  u8() { return this.view.getUint8(this.offset++); }
  u16() { const v = this.view.getUint16(this.offset, true); this.offset += 2; return v; }
  u32() { const v = this.view.getUint32(this.offset, true); this.offset += 4; return v; }
  // PTP array: u32 element count, then elements
  u16Array() {
    const n = this.u32();
    const out = new Array(n);
    for (let i = 0; i < n; i++) out[i] = this.u16();
    return out;
  }
  u32Array() {
    const n = this.u32();
    const out = new Array(n);
    for (let i = 0; i < n; i++) out[i] = this.u32();
    return out;
  }
  // PTP string: u8 count of UTF-16 code units (incl. NUL terminator), then
  // that many little-endian code units. Empty string is a single 0x00.
  string() {
    const n = this.u8();
    if (n === 0) return "";
    let s = "";
    for (let i = 0; i < n; i++) {
      const cu = this.u16();
      if (cu !== 0) s += String.fromCharCode(cu);
    }
    return s;
  }
}

// PTP datetime strings look like "20260715T083000" (optionally with .s and
// timezone suffix). Returns a Date or null.
export function parsePtpDate(s) {
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})/.exec(s || "");
  if (!m) return null;
  const [, y, mo, d, h, mi, se] = m;
  const date = new Date(+y, +mo - 1, +d, +h, +mi, +se);
  return isNaN(date.getTime()) ? null : date;
}

// ObjectInfo dataset → plain object. Exported for tests.
export function parseObjectInfo(bytes) {
  const r = new DataReader(bytes);
  const info = {
    storageId: r.u32(),
    format: r.u16(),
    protectionStatus: r.u16(),
    size: r.u32(),
  };
  r.u16(); // thumb format
  r.u32(); // thumb compressed size
  r.u32(); // thumb pix width
  r.u32(); // thumb pix height
  r.u32(); // image pix width
  r.u32(); // image pix height
  r.u32(); // image bit depth
  info.parent = r.u32();
  info.associationType = r.u16();
  r.u32(); // association desc
  r.u32(); // sequence number
  info.filename = r.string();
  info.captureDate = parsePtpDate(r.string());
  info.modificationDate = parsePtpDate(r.string());
  info.isFolder = info.format === FORMAT_ASSOCIATION;
  return info;
}

// DeviceInfo dataset → { manufacturer, model, deviceVersion, serialNumber }.
export function parseDeviceInfo(bytes) {
  const r = new DataReader(bytes);
  r.u16(); // standard version
  r.u32(); // vendor extension id
  r.u16(); // vendor extension version
  r.string(); // vendor extension desc
  r.u16(); // functional mode
  r.u16Array(); // operations supported
  r.u16Array(); // events supported
  r.u16Array(); // device properties supported
  r.u16Array(); // capture formats
  r.u16Array(); // playback formats
  return {
    manufacturer: r.string(),
    model: r.string(),
    deviceVersion: r.string(),
    serialNumber: r.string(),
  };
}

// Build a command container. Exported for tests.
export function buildCommand(opCode, transactionId, params = []) {
  const buf = new ArrayBuffer(12 + params.length * 4);
  const view = new DataView(buf);
  view.setUint32(0, buf.byteLength, true);
  view.setUint16(4, TYPE_COMMAND, true);
  view.setUint16(6, opCode, true);
  view.setUint32(8, transactionId, true);
  params.forEach((p, i) => view.setUint32(12 + i * 4, p >>> 0, true));
  return buf;
}

// Little-endian writer mirroring DataReader; used to build ObjectInfo
// datasets for SendObjectInfo.
class DataWriter {
  constructor() {
    this.bytes = [];
  }
  u8(v) { this.bytes.push(v & 0xff); return this; }
  u16(v) { this.bytes.push(v & 0xff, (v >> 8) & 0xff); return this; }
  u32(v) {
    this.bytes.push(v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >>> 24) & 0xff);
    return this;
  }
  // PTP string: u8 count of UTF-16 code units incl. NUL terminator.
  string(s) {
    if (!s) { this.u8(0); return this; }
    this.u8(s.length + 1);
    for (const ch of s) this.u16(ch.charCodeAt(0));
    this.u16(0);
    return this;
  }
  build() { return new Uint8Array(this.bytes); }
}

// ObjectInfo dataset for SendObjectInfo. Folders use FORMAT_ASSOCIATION with
// associationType 1 (generic folder). Exported for tests (round-trips through
// parseObjectInfo).
export function buildObjectInfo({ storageId = 0, format = 0x3000, size = 0, parent = 0, filename, isFolder = false }) {
  const w = new DataWriter();
  w.u32(storageId);
  w.u16(isFolder ? FORMAT_ASSOCIATION : format);
  w.u16(0); // protection
  w.u32(size);
  w.u16(0); // thumb format
  w.u32(0).u32(0).u32(0); // thumb size / w / h
  w.u32(0).u32(0).u32(0); // image w / h / depth
  w.u32(parent);
  w.u16(isFolder ? 1 : 0); // association type (1 = generic folder)
  w.u32(0); // association desc
  w.u32(0); // sequence number
  w.string(filename);
  w.string(""); // capture date
  w.string(""); // modification date
  w.string(""); // keywords
  return w.build();
}

// --- the client --------------------------------------------------------------

export function isWebUsbSupported() {
  return typeof navigator !== "undefined" && !!navigator.usb;
}

/**
 * Prompt the user to pick a Garmin USB device. Must be called from a user
 * gesture (click handler). Returns a USBDevice or throws (NotFoundError if
 * the user cancelled the picker).
 */
export async function requestGarminDevice() {
  return navigator.usb.requestDevice({
    filters: [{ vendorId: GARMIN_VENDOR_ID }],
  });
}

/** Previously-authorised Garmin devices (no picker needed). */
export async function getAuthorizedGarminDevices() {
  const devices = await navigator.usb.getDevices();
  return devices.filter((d) => d.vendorId === GARMIN_VENDOR_ID);
}

export class MtpDevice {
  constructor(usbDevice) {
    this.usb = usbDevice;
    this.interfaceNumber = null;
    this.endpointIn = null;
    this.endpointOut = null;
    this.transactionId = 0;
    this.sessionOpen = false;
    this._pending = null;
  }

  /**
   * Open the device and claim its MTP interface. Throws MtpError with a
   * user-actionable message when the OS already owns the interface (Linux
   * GVFS, the Windows MTP driver, ...).
   */
  async open() {
    await this.usb.open();
    if (this.usb.configuration === null) {
      await this.usb.selectConfiguration(1);
    }

    // MTP interfaces advertise either class 6 (Still Image / PTP) or
    // vendor-specific 0xFF; find one exposing bulk-in + bulk-out endpoints.
    // Class 6 is checked first: Garmin devices in "Garmin mode" expose a
    // proprietary vendor-specific interface that is not MTP.
    let match = null;
    for (const wantedClass of [6, 0xff]) {
      for (const iface of this.usb.configuration.interfaces) {
        for (const alt of iface.alternates) {
          if (alt.interfaceClass !== wantedClass) continue;
          const bulkIn = alt.endpoints.find((e) => e.type === "bulk" && e.direction === "in");
          const bulkOut = alt.endpoints.find((e) => e.type === "bulk" && e.direction === "out");
          if (bulkIn && bulkOut) {
            match = { iface, alt, bulkIn, bulkOut };
            break;
          }
        }
        if (match) break;
      }
      if (match) break;
    }
    if (!match) {
      throw new MtpError(
        "No MTP interface found on this device. If it is an older watch it " +
        "connects as a USB drive instead — use the mounted-folder option."
      );
    }

    this.interfaceNumber = match.iface.interfaceNumber;
    this.endpointIn = match.bulkIn.endpointNumber;
    this.endpointOut = match.bulkOut.endpointNumber;
    this.packetSizeOut = match.bulkOut.packetSize || 512;

    try {
      await this.usb.claimInterface(this.interfaceNumber);
    } catch (e) {
      throw new MtpError(
        "Could not claim the device's USB interface — something else on this " +
        "computer is using it. On Linux, eject/unmount the watch in your file " +
        "manager and retry. If the Tracks server runs on this same computer, " +
        "its cable sync may have the watch — wait for it to finish and retry. " +
        "On Windows, the built-in MTP driver blocks browser access; use a " +
        "Linux/macOS machine or the mounted-folder option.",
        { cause: e }
      );
    }
  }

  nextTransactionId() {
    this.transactionId = (this.transactionId + 1) >>> 0 || 1;
    return this.transactionId;
  }

  async _send(buf) {
    const result = await this.usb.transferOut(this.endpointOut, buf);
    if (result.status !== "ok") {
      throw new MtpError(`USB write failed (${result.status})`);
    }
  }

  /**
   * Write the host→device data phase in chunks.
   *
   * One `transferOut` of the whole container was fine while the only things
   * pushed were FIT files of a few kilobytes. A music track is megabytes: the
   * single buffer doubles peak memory (payload plus the container copy) and
   * gives the caller nothing to draw a progress bar from.
   *
   * The USB rule that shapes the chunking: a transfer shorter than the
   * endpoint's packet size *is* the end-of-data signal, so every chunk except
   * the last must be an exact multiple of packetSizeOut. That is also why the
   * 12-byte header cannot be written on its own — alone it is a short packet,
   * and the device would consider the data phase over before any payload
   * arrived. It rides at the front of the first chunk instead.
   */
  async _sendDataPhase(opCode, txid, payload, onProgress = null) {
    const data = payload instanceof Uint8Array ? payload : new Uint8Array(payload);
    const total = 12 + data.byteLength;
    const chunkSize = Math.max(this.packetSizeOut * CHUNK_PACKETS, this.packetSizeOut);

    const first = new Uint8Array(Math.min(chunkSize, total));
    const view = new DataView(first.buffer);
    view.setUint32(0, total, true);
    view.setUint16(4, TYPE_DATA, true);
    view.setUint16(6, opCode, true);
    view.setUint32(8, txid, true);
    first.set(data.subarray(0, first.byteLength - 12), 12);
    await this._send(first);

    let sent = first.byteLength - 12;
    if (onProgress) onProgress(sent, data.byteLength);

    while (sent < data.byteLength) {
      const end = Math.min(sent + chunkSize, data.byteLength);
      await this._send(data.subarray(sent, end));
      sent = end;
      if (onProgress) onProgress(sent, data.byteLength);
    }

    // A container ending exactly on a packet boundary needs a zero-length
    // packet, otherwise the device is still waiting for more.
    if (total % this.packetSizeOut === 0) {
      await this._send(new ArrayBuffer(0));
    }
  }

  // One chunk of bulk-in bytes: leftover from a previous read if any,
  // otherwise a fresh USB transfer. Skips zero-length packets. Times out
  // rather than hanging forever — a watch sitting on its "Transfer Mode?"
  // prompt simply doesn't answer.
  async _fill() {
    if (this._pending && this._pending.byteLength > 0) {
      const p = this._pending;
      this._pending = null;
      return p;
    }
    for (;;) {
      const r = await Promise.race([
        this.usb.transferIn(this.endpointIn, READ_CHUNK),
        new Promise((_, reject) =>
          setTimeout(
            () =>
              reject(
                new MtpError(
                  "The device didn't respond. Make sure the watch is in MTP " +
                  "mode (Settings → System → USB Mode → MTP) — most watches " +
                  "don't prompt on their own — then unplug, replug, and retry."
                )
              ),
            30000
          )
        ),
      ]);
      if (r.status !== "ok") throw new MtpError(`USB read failed (${r.status})`);
      if (r.data.byteLength > 0) {
        return new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
      }
    }
  }

  // Read one complete container (data or response). Returns
  // { type, code, transactionId, payload: Uint8Array }.
  // onProgress(receivedBytes, totalBytes) fires as payload bytes arrive.
  //
  // A device may pack the next container into the same bulk stream when a
  // container ends exactly on a packet boundary, so any bytes past the end
  // of this container are stashed in this._pending for the next call.
  async _readContainer(onProgress = null) {
    let buf = await this._fill();
    while (buf.byteLength < 12) {
      const next = await this._fill();
      const merged = new Uint8Array(buf.byteLength + next.byteLength);
      merged.set(buf, 0);
      merged.set(next, buf.byteLength);
      buf = merged;
    }

    const head = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const totalLength = head.getUint32(0, true);
    const type = head.getUint16(4, true);
    const code = head.getUint16(6, true);
    const transactionId = head.getUint32(8, true);

    const payloadLength = Math.max(0, totalLength - 12);
    const payload = new Uint8Array(payloadLength);
    let received = 0;
    let chunk = buf.subarray(12);

    for (;;) {
      const take = Math.min(chunk.byteLength, payloadLength - received);
      payload.set(chunk.subarray(0, take), received);
      received += take;
      if (take < chunk.byteLength) {
        this._pending = chunk.subarray(take);
      }
      onProgress?.(received, payloadLength);
      if (received >= payloadLength) break;
      chunk = await this._fill();
    }

    return { type, code, transactionId, payload };
  }

  /**
   * Run one MTP transaction: command → (optional data phase) → response.
   * Pass `dataOut` (Uint8Array) for host→device data, or `wantData` for
   * device→host. Returns { responseCode, responseParams, data }.
   */
  async transact(opCode, params = [], { wantData = false, onProgress = null, dataOut = null } = {}) {
    const txid = this.nextTransactionId();
    await this._send(buildCommand(opCode, txid, params));

    if (dataOut !== null) {
      await this._sendDataPhase(opCode, txid, dataOut, onProgress);
    }

    let data = null;
    let container = await this._readContainer(wantData ? onProgress : null);
    if (container.type === TYPE_DATA) {
      data = container.payload;
      container = await this._readContainer();
    }
    if (container.type !== TYPE_RESPONSE) {
      throw new MtpError(`Expected response container, got type ${container.type}`);
    }
    const responseParams = [];
    const rv = new DataView(container.payload.buffer, container.payload.byteOffset, container.payload.byteLength);
    for (let o = 0; o + 4 <= container.payload.byteLength; o += 4) {
      responseParams.push(rv.getUint32(o, true));
    }
    return { responseCode: container.code, responseParams, data };
  }

  async _expectOk(opCode, params, opts) {
    const res = await this.transact(opCode, params, opts);
    if (res.responseCode !== RC_OK) {
      throw new MtpError(
        `MTP operation 0x${opCode.toString(16)} failed: ${responseName(res.responseCode)}`,
        { responseCode: res.responseCode }
      );
    }
    return res;
  }

  async openSession() {
    const res = await this.transact(OP.OpenSession, [1]);
    if (res.responseCode !== RC_OK && res.responseCode !== RC_SESSION_ALREADY_OPEN) {
      throw new MtpError(`OpenSession failed: ${responseName(res.responseCode)}`, {
        responseCode: res.responseCode,
      });
    }
    this.sessionOpen = true;
  }

  async getDeviceInfo() {
    const res = await this._expectOk(OP.GetDeviceInfo, [], { wantData: true });
    return parseDeviceInfo(res.data);
  }

  async getStorageIds() {
    const res = await this._expectOk(OP.GetStorageIDs, [], { wantData: true });
    return new DataReader(res.data).u32Array();
  }

  /** Object handles directly under `parent` (PARENT_ROOT for the root). */
  async getObjectHandles(storageId = ALL_STORAGES, parent = PARENT_ROOT) {
    const res = await this._expectOk(OP.GetObjectHandles, [storageId, 0, parent], {
      wantData: true,
    });
    return new DataReader(res.data).u32Array();
  }

  async getObjectInfo(handle) {
    const res = await this._expectOk(OP.GetObjectInfo, [handle], { wantData: true });
    const info = parseObjectInfo(res.data);
    info.handle = handle;
    return info;
  }

  /** Download a file's bytes. onProgress(received, total) as it streams. */
  async getObject(handle, onProgress = null) {
    const res = await this._expectOk(OP.GetObject, [handle], {
      wantData: true,
      onProgress,
    });
    return res.data;
  }

  /**
   * Create a file on the device. SendObjectInfo reserves the object (the
   * device answers with its new handle), SendObject delivers the bytes; the
   * two must be consecutive within the session. Returns the new handle.
   */
  async sendFile(storageId, parentHandle, filename, bytes, onProgress = null) {
    const info = buildObjectInfo({
      storageId,
      size: bytes.byteLength,
      parent: parentHandle,
      filename,
    });
    const res = await this._expectOk(OP.SendObjectInfo, [storageId, parentHandle], {
      dataOut: info,
    });
    const newHandle = res.responseParams[2];
    await this._expectOk(OP.SendObject, [], { dataOut: bytes, onProgress });
    return newHandle;
  }

  /** Create a folder; returns its handle. */
  async createFolder(storageId, parentHandle, name) {
    const info = buildObjectInfo({
      storageId,
      parent: parentHandle,
      filename: name,
      isFolder: true,
    });
    const res = await this._expectOk(OP.SendObjectInfo, [storageId, parentHandle], {
      dataOut: info,
    });
    return res.responseParams[2];
  }

  async deleteObject(handle) {
    await this._expectOk(OP.DeleteObject, [handle, 0]);
  }

  /**
   * End the MTP session but keep the device open and the interface claimed.
   * Garmin watches treat the session close as "sync over" — it's what makes
   * them process GARMIN/NewFiles (the cable sync gets this from libmtp's
   * release). openSession() starts a fresh session afterwards.
   */
  async closeSession() {
    if (!this.sessionOpen) return;
    await this.transact(OP.CloseSession, []);
    this.sessionOpen = false;
    // Transaction IDs are per-session; reset so the next openSession() looks
    // exactly like a first connect (which the watch demonstrably accepts).
    this.transactionId = 0;
  }

  async close() {
    try {
      await this.closeSession();
    } catch {
      // Device may already be gone; release what we can below.
    }
    try {
      await this.usb.releaseInterface(this.interfaceNumber);
    } catch { /* ignore */ }
    try {
      await this.usb.close();
    } catch { /* ignore */ }
  }
}
