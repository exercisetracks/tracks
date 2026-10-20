// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Just enough ZIP to export an account's FIT files and to import them back —
 * or a Garmin Connect export's — without a dependency.
 *
 * Writing streams: each entry goes to the sink as it is added, so an export of
 * years of files holds one file in memory at a time plus the central
 * directory (~80 bytes an entry). The whole file is in hand before its header
 * is written, so sizes and CRC go in the local header and no data descriptor
 * is needed. ZIP64 records are written only where a value overflows: 25,000
 * files is under the 65,535-entry limit, but a long history passes 4 GB.
 *
 * Reading goes through the central directory with Blob.slice, so a
 * multi-gigabyte archive is never read whole either. Only stored and deflated
 * entries are supported — the two every zip tool writes by default.
 *
 * Deflate is the browser's CompressionStream("deflate-raw"); where it is
 * missing entries are stored, which every unzipper reads just the same.
 *
 * ## Hostile archives
 *
 * An imported zip is opened here, in the browser, and never reaches the
 * server: each FIT file in it is uploaded exactly as a picked file would be,
 * so the server's exposure is unchanged, and nothing in an archive is ever
 * executed or written to disk (entry paths go no further than their bare
 * name, so `../` in one is inert). What a hostile zip can still attack is
 * this tab's memory, and the reader bounds it:
 *  - an entry declaring more than MAX_ENTRY_BYTES is refused before it is
 *    read — the server refuses a FIT file that size anyway;
 *  - inflating stops the moment an entry yields more than it declared, so a
 *    bomb that lies about its size costs at most that size;
 *  - entries whose data overlap are refused: that is how a "non-recursive"
 *    bomb makes thousands of entries out of one compressed block;
 *  - offsets and lengths past the end of the file are refused.
 * Zips inside a zip are not opened (sources.js keeps only .fit entries), so
 * nesting gains a bomb nothing.
 */

const LOCAL = 0x04034b50;
const CENTRAL = 0x02014b50;
const END = 0x06054b50;
const END64 = 0x06064b50;
const LOCATOR64 = 0x07064b50;
const UTF8_NAMES = 0x0800;
const MAX16 = 0xffff;
const MAX32 = 0xffffffff;
// The server's per-file limit (backend/app/api/fit_upload.py, _MAX_FILE_BYTES).
export const MAX_ENTRY_BYTES = 50 * 1024 * 1024;

export class ZipError extends Error {
  constructor(message) {
    super(message);
    this.name = "ZipError";
  }
}

// ── CRC-32 ──────────────────────────────────────────────────────────────────

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

// ── Deflate through the browser ─────────────────────────────────────────────

function canStream(kind) {
  try {
    // Constructing is the only reliable test: older engines have the class
    // but not the "deflate-raw" format.
    new (kind === "compress" ? CompressionStream : DecompressionStream)("deflate-raw");
    return true;
  } catch {
    return false;
  }
}

async function through(bytes, stream) {
  const out = new Blob([bytes]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(out).arrayBuffer());
}

/** Inflate, giving up as soon as the output passes `limit` bytes (see "Hostile archives"). */
async function inflateAtMost(bytes, limit, name) {
  const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw")).getReader();
  const out = new Uint8Array(limit);
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return out.subarray(0, n);
    if (n + value.length > limit) {
      await reader.cancel().catch(() => {});
      throw new ZipError(`${name} unpacks to more than it claims to — refused`);
    }
    out.set(value, n);
    n += value.length;
  }
}

// ── Writing ─────────────────────────────────────────────────────────────────

function dosDateTime(date) {
  const d = date instanceof Date && !Number.isNaN(date.getTime()) ? date : new Date();
  const year = Math.min(Math.max(d.getFullYear(), 1980), 2107);
  return {
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
    date: ((year - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
  };
}

class Bytes {
  constructor(size) {
    this.buf = new Uint8Array(size);
    this.view = new DataView(this.buf.buffer);
    this.at = 0;
  }
  u16(v) { this.view.setUint16(this.at, v, true); this.at += 2; return this; }
  u32(v) { this.view.setUint32(this.at, v, true); this.at += 4; return this; }
  u64(v) { this.view.setBigUint64(this.at, BigInt(v), true); this.at += 8; return this; }
  raw(b) { this.buf.set(b, this.at); this.at += b.length; return this; }
}

/**
 * Writes a zip to `sink`, an async function taking a Uint8Array. add() each
 * entry, then finish(). `bytesWritten` is the archive's size so far, which an
 * export watches to start a new part.
 */
export class ZipWriter {
  constructor(sink, { compress = canStream("compress") } = {}) {
    this.sink = sink;
    this.compress = compress;
    this.bytesWritten = 0;
    this.entries = [];
  }

  async write(bytes) {
    await this.sink(bytes);
    this.bytesWritten += bytes.length;
  }

  async add(name, bytes, modified = new Date()) {
    const nameBytes = new TextEncoder().encode(name);
    const crc = crc32(bytes);
    let method = 0;
    let body = bytes;
    if (this.compress && bytes.length > 0) {
      const deflated = await through(bytes, new CompressionStream("deflate-raw"));
      // Some files do not shrink; storing those is smaller and as readable.
      if (deflated.length < bytes.length) { method = 8; body = deflated; }
    }
    // Entries are FIT files, capped at 50 MB by the upload, so sizes always
    // fit 32 bits; only the offset can need ZIP64.
    if (body.length >= MAX32 || bytes.length >= MAX32) throw new ZipError(`${name} is too large for this archive`);
    const { time, date } = dosDateTime(modified);
    const entry = { nameBytes, crc, method, time, date, csize: body.length, usize: bytes.length, offset: this.bytesWritten };
    const header = new Bytes(30 + nameBytes.length)
      .u32(LOCAL).u16(20).u16(UTF8_NAMES).u16(method).u16(time).u16(date)
      .u32(crc).u32(entry.csize).u32(entry.usize).u16(nameBytes.length).u16(0)
      .raw(nameBytes);
    await this.write(header.buf);
    await this.write(body);
    this.entries.push(entry);
  }

  async finish() {
    const cdStart = this.bytesWritten;
    for (const e of this.entries) {
      const big = e.offset >= MAX32;
      const rec = new Bytes(46 + e.nameBytes.length + (big ? 12 : 0))
        .u32(CENTRAL).u16(45).u16(big ? 45 : 20).u16(UTF8_NAMES).u16(e.method).u16(e.time).u16(e.date)
        .u32(e.crc).u32(e.csize).u32(e.usize).u16(e.nameBytes.length).u16(big ? 12 : 0).u16(0)
        .u16(0).u16(0).u32(0).u32(big ? MAX32 : e.offset)
        .raw(e.nameBytes);
      if (big) rec.u16(0x0001).u16(8).u64(e.offset);
      await this.write(rec.buf);
    }
    const cdSize = this.bytesWritten - cdStart;
    const count = this.entries.length;
    const needs64 = count >= MAX16 || cdStart >= MAX32 || cdSize >= MAX32;
    if (needs64) {
      const end64At = this.bytesWritten;
      await this.write(new Bytes(56)
        .u32(END64).u64(44).u16(45).u16(45).u32(0).u32(0)
        .u64(count).u64(count).u64(cdSize).u64(cdStart).buf);
      await this.write(new Bytes(20).u32(LOCATOR64).u32(0).u64(end64At).u32(1).buf);
    }
    await this.write(new Bytes(22)
      .u32(END).u16(0).u16(0)
      .u16(needs64 ? MAX16 : count).u16(needs64 ? MAX16 : count)
      .u32(needs64 ? MAX32 : cdSize).u32(needs64 ? MAX32 : cdStart).u16(0).buf);
  }
}

// ── Reading ─────────────────────────────────────────────────────────────────

async function sliceView(blob, start, end) {
  const buf = await blob.slice(start, end).arrayBuffer();
  return new DataView(buf);
}

const u64 = (view, at) => Number(view.getBigUint64(at, true));

/**
 * The entries of a zip `blob` (a File from a picker or a drop), each as
 * { name, size, read() } — read() resolving to the entry's bytes, checked
 * against its CRC. Directories are left out. Throws ZipError for something
 * that is not a zip.
 */
export async function readZipEntries(blob) {
  // The end record sits in the last 22 bytes plus at most a 64 KB comment.
  const tailStart = Math.max(0, blob.size - (22 + MAX16));
  const tail = await sliceView(blob, tailStart, blob.size);
  let endAt = -1;
  for (let i = tail.byteLength - 22; i >= 0; i--) {
    if (tail.getUint32(i, true) === END) { endAt = i; break; }
  }
  if (endAt < 0) throw new ZipError("Not a zip file");

  let count = tail.getUint16(endAt + 10, true);
  let cdSize = tail.getUint32(endAt + 12, true);
  let cdStart = tail.getUint32(endAt + 16, true);
  if (count === MAX16 || cdSize === MAX32 || cdStart === MAX32) {
    const locAt = endAt - 20;
    if (locAt < 0 || tail.getUint32(locAt, true) !== LOCATOR64) throw new ZipError("Damaged zip: ZIP64 locator missing");
    const end64At = u64(tail, locAt + 8);
    const end64 = await sliceView(blob, end64At, end64At + 56);
    if (end64.getUint32(0, true) !== END64) throw new ZipError("Damaged zip: ZIP64 end record missing");
    count = u64(end64, 32);
    cdSize = u64(end64, 40);
    cdStart = u64(end64, 48);
  }

  if (cdStart + cdSize > blob.size) throw new ZipError("Damaged zip: central directory past the end of the file");
  const cd = await sliceView(blob, cdStart, cdStart + cdSize);
  const spans = [];
  const entries = [];
  let at = 0;
  for (let n = 0; n < count; n++) {
    if (at + 46 > cd.byteLength || cd.getUint32(at, true) !== CENTRAL) throw new ZipError("Damaged zip: bad central directory");
    const flags = cd.getUint16(at + 8, true);
    const method = cd.getUint16(at + 10, true);
    const crc = cd.getUint32(at + 16, true);
    let csize = cd.getUint32(at + 20, true);
    let usize = cd.getUint32(at + 24, true);
    const nameLen = cd.getUint16(at + 28, true);
    const extraLen = cd.getUint16(at + 30, true);
    const commentLen = cd.getUint16(at + 32, true);
    let offset = cd.getUint32(at + 42, true);
    const name = new TextDecoder().decode(new Uint8Array(cd.buffer, cd.byteOffset + at + 46, nameLen));
    // ZIP64 extra: only the overflowed fields are present, in this order.
    for (let x = at + 46 + nameLen, xEnd = x + extraLen; x + 4 <= xEnd;) {
      const id = cd.getUint16(x, true);
      const len = cd.getUint16(x + 2, true);
      if (id === 0x0001) {
        let p = x + 4;
        if (usize === MAX32) { usize = u64(cd, p); p += 8; }
        if (csize === MAX32) { csize = u64(cd, p); p += 8; }
        if (offset === MAX32) { offset = u64(cd, p); }
      }
      x += 4 + len;
    }
    at += 46 + nameLen + extraLen + commentLen;
    // 30 + csize is a lower bound on the entry's span (the local name and
    // extra come on top), which is enough to catch entries sharing data.
    if (offset + 30 + csize > blob.size) throw new ZipError(`Damaged zip: ${name} runs past the end of the file`);
    spans.push([offset, offset + 30 + csize]);
    if (name.endsWith("/")) continue;
    entries.push({
      name,
      size: usize,
      read: async () => {
        if (flags & 0x0001) throw new ZipError(`${name} is encrypted`);
        if (usize > MAX_ENTRY_BYTES) throw new ZipError(`${name} is larger than the 50 MB a FIT file may be`);
        if (method !== 0 && method !== 8) throw new ZipError(`${name} uses an unsupported compression method`);
        // The local header's name and extra lengths can differ from the
        // central directory's, so the data's start is read from it.
        const local = await sliceView(blob, offset, offset + 30);
        if (local.getUint32(0, true) !== LOCAL) throw new ZipError(`Damaged zip: no header for ${name}`);
        const start = offset + 30 + local.getUint16(26, true) + local.getUint16(28, true);
        const body = new Uint8Array(await blob.slice(start, start + csize).arrayBuffer());
        if (method === 8 && !canStream("decompress")) throw new ZipError("This browser cannot unpack compressed zips");
        const bytes = method === 8 ? await inflateAtMost(body, usize, name) : body;
        if (bytes.length !== usize || crc32(bytes) !== crc) throw new ZipError(`${name} is damaged (checksum mismatch)`);
        return bytes;
      },
    });
  }
  spans.sort((a, b) => a[0] - b[0]);
  for (let i = 1; i < spans.length; i++) {
    if (spans[i][0] < spans[i - 1][1]) throw new ZipError("Entries in this zip overlap — refused (the shape of a zip bomb)");
  }
  return entries;
}
