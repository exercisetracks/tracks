// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from "vitest";
import {
  DataReader,
  MtpDevice,
  FORMAT_ASSOCIATION,
  buildCommand,
  buildObjectInfo,
  parseObjectInfo,
  parsePtpDate,
} from "../lib/mtp";

// PTP string: u8 count of UTF-16 code units incl. NUL, then the code units.
function ptpString(s) {
  if (!s) return [0];
  const bytes = [s.length + 1];
  for (const ch of s) {
    const cu = ch.charCodeAt(0);
    bytes.push(cu & 0xff, cu >> 8);
  }
  bytes.push(0, 0);
  return bytes;
}

function u16le(v) { return [v & 0xff, (v >> 8) & 0xff]; }
function u32le(v) { return [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >>> 24) & 0xff]; }

describe("buildCommand", () => {
  it("encodes a 12-byte header plus params, little-endian", () => {
    const buf = buildCommand(0x1007, 5, [0xffffffff, 0, 42]);
    const v = new DataView(buf);
    expect(buf.byteLength).toBe(24);
    expect(v.getUint32(0, true)).toBe(24); // container length
    expect(v.getUint16(4, true)).toBe(1); // type = command
    expect(v.getUint16(6, true)).toBe(0x1007); // op code
    expect(v.getUint32(8, true)).toBe(5); // transaction id
    expect(v.getUint32(12, true)).toBe(0xffffffff);
    expect(v.getUint32(16, true)).toBe(0);
    expect(v.getUint32(20, true)).toBe(42);
  });
});

describe("DataReader", () => {
  it("reads PTP arrays and strings", () => {
    const bytes = new Uint8Array([
      ...u32le(2), ...u32le(0x00010001), ...u32le(0x00020002), // u32 array
      ...ptpString("GARMIN"),
    ]);
    const r = new DataReader(bytes);
    expect(r.u32Array()).toEqual([0x00010001, 0x00020002]);
    expect(r.string()).toBe("GARMIN");
  });

  it("treats a lone zero byte as the empty string", () => {
    expect(new DataReader(new Uint8Array([0])).string()).toBe("");
  });
});

describe("parsePtpDate", () => {
  it("parses PTP datetime strings", () => {
    const d = parsePtpDate("20260714T063012");
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(6);
    expect(d.getDate()).toBe(14);
    expect(d.getHours()).toBe(6);
  });
  it("returns null on junk", () => {
    expect(parsePtpDate("")).toBeNull();
    expect(parsePtpDate("not-a-date")).toBeNull();
  });
});

function objectInfoBytes({ storageId, format, size, parent, filename, modDate = "" }) {
  return new Uint8Array([
    ...u32le(storageId),
    ...u16le(format),
    ...u16le(0), // protection
    ...u32le(size),
    ...u16le(0), // thumb format
    ...u32le(0), ...u32le(0), ...u32le(0), // thumb size/w/h
    ...u32le(0), ...u32le(0), ...u32le(0), // image w/h/depth
    ...u32le(parent),
    ...u16le(0), // association type
    ...u32le(0), // association desc
    ...u32le(0), // sequence number
    ...ptpString(filename),
    ...ptpString(""), // capture date
    ...ptpString(modDate),
    ...ptpString(""), // keywords
  ]);
}

describe("parseObjectInfo", () => {
  it("parses a FIT file entry", () => {
    const info = parseObjectInfo(objectInfoBytes({
      storageId: 0x10001,
      format: 0x3000, // undefined/binary
      size: 48213,
      parent: 7,
      filename: "2026-07-14-06-30-12.fit",
      modDate: "20260714T063012",
    }));
    expect(info.filename).toBe("2026-07-14-06-30-12.fit");
    expect(info.size).toBe(48213);
    expect(info.parent).toBe(7);
    expect(info.isFolder).toBe(false);
    expect(info.modificationDate.getFullYear()).toBe(2026);
  });

  it("flags associations as folders", () => {
    const info = parseObjectInfo(objectInfoBytes({
      storageId: 0x10001,
      format: FORMAT_ASSOCIATION,
      size: 0,
      parent: 0,
      filename: "Activity",
    }));
    expect(info.isFolder).toBe(true);
    expect(info.filename).toBe("Activity");
  });
});

describe("buildObjectInfo", () => {
  it("round-trips a file dataset through parseObjectInfo", () => {
    const info = parseObjectInfo(buildObjectInfo({
      storageId: 0x10001,
      size: 9876,
      parent: 42,
      filename: "TRK_7.fit",
    }));
    expect(info.filename).toBe("TRK_7.fit");
    expect(info.size).toBe(9876);
    expect(info.parent).toBe(42);
    expect(info.storageId).toBe(0x10001);
    expect(info.isFolder).toBe(false);
  });

  it("round-trips a folder dataset", () => {
    const info = parseObjectInfo(buildObjectInfo({
      storageId: 0x10001,
      parent: 0,
      filename: "NewFiles",
      isFolder: true,
    }));
    expect(info.isFolder).toBe(true);
    expect(info.filename).toBe("NewFiles");
    expect(info.associationType).toBe(1);
  });
});

// ── chunked data-out phase ───────────────────────────────────────────────────
// Music tracks pushed the single-transfer write past what it was designed for.
// The chunking has one rule it must never break: a bulk transfer shorter than
// the endpoint's packet size *is* the end-of-data signal, so any interior chunk
// that ends short would truncate the file on the device.

function fakeMtp({ packetSize = 512 } = {}) {
  const writes = [];
  const device = Object.create(MtpDevice.prototype);
  device.endpointOut = 2;
  device.packetSizeOut = packetSize;
  device.usb = {
    async transferOut(_ep, buf) {
      const view = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
      writes.push(view.slice());
      return { status: "ok" };
    },
  };
  return { device, writes };
}

function concat(chunks) {
  const total = chunks.reduce((n, c) => n + c.byteLength, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.byteLength; }
  return out;
}

/**
 * Byte-for-byte equality, asserted in a way that stays quick on large buffers.
 *
 * `expect(a).toEqual(b)` on a 700 KB Uint8Array walks it as a generic
 * array-like and takes about five seconds — two thirds of the entire frontend
 * suite for one assertion, on data a plain loop compares in three
 * milliseconds. This checks exactly as much, and when it does fail it names
 * the offending byte rather than dumping both buffers.
 */
function expectBytesEqual(actual, expected) {
  expect(actual.byteLength).toBe(expected.byteLength);
  for (let i = 0; i < expected.length; i++) {
    if (actual[i] !== expected[i]) {
      throw new Error(
        `bytes differ at ${i}: got ${actual[i]}, expected ${expected[i]}`,
      );
    }
  }
}

describe("MtpDevice data-out chunking", () => {
  it("writes a container header followed by the whole payload", async () => {
    const { device, writes } = fakeMtp();
    const payload = new Uint8Array(1000).map((_, i) => i & 0xff);

    await device._sendDataPhase(0x100d, 7, payload);

    const all = concat(writes);
    const v = new DataView(all.buffer);
    expect(v.getUint32(0, true)).toBe(12 + payload.byteLength);
    expect(v.getUint16(4, true)).toBe(2); // TYPE_DATA
    expect(v.getUint16(6, true)).toBe(0x100d);
    expect(v.getUint32(8, true)).toBe(7);
    expect(all.slice(12)).toEqual(payload);
  });

  it("keeps every interior chunk a whole number of packets", async () => {
    const { device, writes } = fakeMtp({ packetSize: 512 });
    // Comfortably larger than one chunk (512 × 512 = 256 KB), and not a
    // multiple of it, so the final chunk is genuinely short.
    const payload = new Uint8Array(700 * 1024).map((_, i) => i & 0xff);

    await device._sendDataPhase(0x100d, 1, payload);

    expect(writes.length).toBeGreaterThan(1);
    for (const chunk of writes.slice(0, -1)) {
      // A short interior packet would end the transfer early and truncate the
      // file on the watch.
      expect(chunk.byteLength % 512).toBe(0);
    }
    expectBytesEqual(concat(writes).slice(12), payload);
  });

  it("never sends the 12-byte header on its own", async () => {
    const { device, writes } = fakeMtp({ packetSize: 512 });
    await device._sendDataPhase(0x100d, 1, new Uint8Array(4096));

    // Alone the header is a short packet, so the device would treat the data
    // phase as finished before any audio arrived.
    expect(writes[0].byteLength).toBeGreaterThan(12);
  });

  it("appends a zero-length packet when the container ends on a boundary", async () => {
    const { device, writes } = fakeMtp({ packetSize: 512 });
    // 12-byte header + 500 = 512 exactly.
    await device._sendDataPhase(0x100d, 1, new Uint8Array(500));

    expect(writes[writes.length - 1].byteLength).toBe(0);
  });

  it("omits the zero-length packet when the last chunk is already short", async () => {
    const { device, writes } = fakeMtp({ packetSize: 512 });
    await device._sendDataPhase(0x100d, 1, new Uint8Array(501));

    expect(writes[writes.length - 1].byteLength).not.toBe(0);
  });

  it("reports progress against the payload, not the container", async () => {
    const { device } = fakeMtp({ packetSize: 512 });
    const payload = new Uint8Array(700 * 1024);
    const seen = [];

    await device._sendDataPhase(0x100d, 1, payload, (sent, total) => seen.push([sent, total]));

    expect(seen.length).toBeGreaterThan(1);
    expect(seen.every(([, total]) => total === payload.byteLength)).toBe(true);
    expect(seen[seen.length - 1][0]).toBe(payload.byteLength);
    // Monotonic, so a progress bar never jumps backwards.
    const sent = seen.map(([s]) => s);
    expect([...sent].sort((a, b) => a - b)).toEqual(sent);
  });

  it("handles a payload smaller than one chunk in a single write", async () => {
    const { device, writes } = fakeMtp({ packetSize: 512 });
    await device._sendDataPhase(0x100d, 1, new Uint8Array(30));

    expect(writes.length).toBe(1);
    expect(writes[0].byteLength).toBe(42);
  });
});
