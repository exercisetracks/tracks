#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The backup file corpus: what a `.tracksbackup` is, byte for byte.

A backup made on the phone must restore in the browser and the other way
round, and both are written by separate code — Kotlin
(`mobile/core/.../backup/Backup.kt`, `mobile/app/.../backup/BackupCrypto.kt`)
and JavaScript (`frontend/src/lib/backup/`). So the expected bytes come from a
third, independent implementation — this file — rather than from either of
them, and each suite checks that it seals the same inputs to exactly these
bytes and opens what the others made.

What it pins:

- the key derivation (PBKDF2-HMAC-SHA256, the passphrase as UTF-8),
- the chunked seal (`TRKBAK02`: 1 MiB chunks, nonce = 7-byte prefix ·
  u32 index · last flag, header as associated data) at sizes either side of a
  chunk boundary, where the last-chunk rule is easiest to get wrong,
- the payload (u32 length · JSON manifest · each file u32 length · bytes), and
- the first format (`TRKBAK01`), which every client must still open.

Salts and nonces are fixed here so the output is reproducible; real backups
draw them at random. Regenerable — but a change to this output is a change to
the file format, and every backup already written must still open.

Usage (needs `cryptography`, which the backend image has):
    docker exec -i backend python3 - < spec/make_backup_fixtures.py > spec/fixtures/backup.json
"""

import base64
import hashlib
import json
import struct
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CHUNK = 1 << 20
TAG = 16
PASSPHRASE = "correct horse battery"
ITERATIONS = 10_000  # a real backup uses 600 000; the count is in the header
SALT = bytes(range(16))
PREFIX = bytes([0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6])


def key(passphrase: str, salt: bytes, iterations: int) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, iterations, 32)


def seal(plain: bytes, passphrase: str = PASSPHRASE, iterations: int = ITERATIONS,
         salt: bytes = SALT, prefix: bytes = PREFIX) -> bytes:
    header = b"TRKBAK02" + struct.pack(">I", iterations) + salt + prefix
    aead = AESGCM(key(passphrase, salt, iterations))
    # Every chunk full but the last, which may be short — or, for an empty
    # payload, empty. A payload that is a whole number of chunks has no extra
    # empty chunk: its last full one carries the flag.
    pieces = [plain[i:i + CHUNK] for i in range(0, len(plain), CHUNK)] or [b""]
    out = [header]
    for i, piece in enumerate(pieces):
        last = i == len(pieces) - 1
        nonce = prefix + struct.pack(">I", i) + bytes([1 if last else 0])
        out.append(aead.encrypt(nonce, piece, header))
    return b"".join(out)


def seal_v1(plain: bytes, nonce: bytes, passphrase: str = PASSPHRASE,
            iterations: int = ITERATIONS, salt: bytes = SALT) -> bytes:
    header = b"TRKBAK01" + struct.pack(">I", iterations) + salt + nonce
    return header + AESGCM(key(passphrase, salt, iterations)).encrypt(nonce, plain, header)


def pattern(n: int) -> bytes:
    return bytes((i * 31) & 0xFF for i in range(n))


# ── The payload ─────────────────────────────────────────────────────────────

STAMP_A = "0001791338526000-0000-a1b2c3d4e5f60718"
STAMP_B = "0001791338527000-0003-a1b2c3d4e5f60718"

PAYLOAD = {
    "created_at_ms": 1791338526000,
    "binding": {"server_id": "5e5e5e5e5e5e5e5e", "account": "1"},
    "rows": [
        # Shaped as Wire.encodeChange writes them: "deleted" only when set.
        {"entity": "waypoint", "uid": "wp-1", "fields": {
            "name": ["Café at the col ☕", STAMP_A],
            "lat": [46.5, STAMP_A],
            "visits": [3, STAMP_B],
            "note": [None, STAMP_B],
        }},
        {"entity": "waypoint", "uid": "wp-2", "fields": {}, "deleted": STAMP_B},
    ],
    "files": [
        {"name": hashlib.sha256(b"first fit").hexdigest(), "hex": b"first fit".hex()},
        # An unreadable blob is written empty and skipped on restore.
        {"name": hashlib.sha256(b"unreadable").hexdigest(), "hex": ""},
        {"name": hashlib.sha256(pattern(300)).hexdigest(), "hex": pattern(300).hex()},
    ],
}


def encode_payload(p: dict) -> bytes:
    manifest = {"format": "tracks-backup", "version": 1, "created_at_ms": p["created_at_ms"]}
    if p.get("binding"):
        manifest["binding"] = p["binding"]
    manifest["rows"] = p["rows"]
    manifest["files"] = [f["name"] for f in p["files"]]
    # Compact, keys in insertion order, non-ASCII as itself — as kotlinx
    # serialization and JSON.stringify both write it.
    body = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    out = [struct.pack(">I", len(body)), body]
    for f in p["files"]:
        data = bytes.fromhex(f["hex"])
        out += [struct.pack(">I", len(data)), data]
    return b"".join(out)


def main() -> None:
    encoded = encode_payload(PAYLOAD)
    legacy_nonce = bytes(range(100, 112))
    sizes = [0, 5, CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK + 7]
    fixture = {
        "_comment": "Generated by spec/make_backup_fixtures.py — do not edit.",
        "chunk_bytes": CHUNK,
        "passphrase": PASSPHRASE,
        "iterations": ITERATIONS,
        "salt": SALT.hex(),
        "prefix": PREFIX.hex(),
        "key": key(PASSPHRASE, SALT, ITERATIONS).hex(),
        # Pins the passphrase encoding: UTF-8, not Latin-1 or UTF-16.
        "unicode_passphrase": {
            "passphrase": "pässwörd ☕ 10",
            "key": key("pässwörd ☕ 10", SALT, ITERATIONS).hex(),
        },
        # The plaintext is (i * 31) & 0xff for each index i.
        "seals": [
            {"length": n, "sha256": hashlib.sha256(seal(pattern(n))).hexdigest(),
             "size": len(seal(pattern(n)))}
            for n in sizes
        ],
        "payload": PAYLOAD,
        "payload_encoded": base64.b64encode(encoded).decode(),
        "payload_sealed": base64.b64encode(seal(encoded)).decode(),
        "legacy_nonce": legacy_nonce.hex(),
        "payload_sealed_v1": base64.b64encode(seal_v1(encoded, legacy_nonce)).decode(),
    }
    json.dump(fixture, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
