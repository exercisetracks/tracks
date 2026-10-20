// @vitest-environment node
// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Bulk FIT import and export: the zip reader and writer (lib/fitFiles/zip.js),
 * expanding what was picked into FIT files (sources.js), and the names files
 * get in an export (job.js).
 *
 * Node's environment rather than jsdom's: CompressionStream and Blob.slice are
 * Node's, as they are the browser's.
 */
import { describe, expect, it } from "vitest";
import { ZipError, ZipWriter, crc32, readZipEntries } from "../lib/fitFiles/zip";
import { expandSources } from "../lib/fitFiles/sources";
import { exportNames } from "../lib/fitFiles/job";

// Written by Python's zipfile — an independent implementation, so the reader
// is checked against more than its own writer. A deflated FIT file in a
// Garmin-export-shaped folder, the folder's own entry, a stored text file,
// and a macOS resource-fork shadow of the FIT file.
const PYTHON_ZIP = "UEsDBBQAAAAIAFRLVF2d571kCwAAAFgCAAAhAAAAREktQ29ubmVjdC1VcGxvYWRlZC1GaWxlcy9hXzEuZml0c/MMcRtFo4jaCABQSwMEFAAAAAAAVEtUXQAAAAAAAAAAAAAAABoAAABESS1Db25uZWN0LVVwbG9hZGVkLUZpbGVzL1BLAwQUAAAAAABUS1RdhqYQNgUAAAAFAAAACQAAAG5vdGVzLnR4dGhlbGxvUEsDBBQAAAAAAFRLVF05nPsGBAAAAAQAAAAsAAAAX19NQUNPU1gvREktQ29ubmVjdC1VcGxvYWRlZC1GaWxlcy8uX2FfMS5maXRqdW5rUEsBAhQDFAAAAAgAVEtUXZ3nvWQLAAAAWAIAACEAAAAAAAAAAAAAAIABAAAAAERJLUNvbm5lY3QtVXBsb2FkZWQtRmlsZXMvYV8xLmZpdFBLAQIUAxQAAAAAAFRLVF0AAAAAAAAAAAAAAAAaAAAAAAAAAAAAEAD9QUoAAABESS1Db25uZWN0LVVwbG9hZGVkLUZpbGVzL1BLAQIUAxQAAAAAAFRLVF2GphA2BQAAAAUAAAAJAAAAAAAAAAAAAACAAYIAAABub3Rlcy50eHRQSwECFAMUAAAAAABUS1RdOZz7BgQAAAAEAAAALAAAAAAAAAAAAAAAgAGuAAAAX19NQUNPU1gvREktQ29ubmVjdC1VcGxvYWRlZC1GaWxlcy8uX2FfMS5maXRQSwUGAAAAAAQABAAoAQAA/AAAAAAA";

const b64 = (s) => new Uint8Array(Buffer.from(s, "base64"));
const text = (bytes) => new TextDecoder().decode(bytes);
const pattern = (n) => Uint8Array.from({ length: n }, (_, i) => (i * 31) & 0xff);

async function zipOf(files, opts) {
  const parts = [];
  const zip = new ZipWriter(async (b) => { parts.push(b); }, opts);
  for (const [name, bytes] of files) await zip.add(name, bytes, new Date(2024, 5, 1, 12, 30));
  await zip.finish();
  return new Blob(parts);
}

describe("zip", () => {
  it("computes the standard CRC-32", () => {
    expect(crc32(new TextEncoder().encode("123456789"))).toBe(0xcbf43926);
  });

  it("reads a zip another implementation wrote, leaving directories out", async () => {
    const entries = await readZipEntries(new Blob([b64(PYTHON_ZIP)]));
    expect(entries.map((e) => e.name)).toEqual([
      "DI-Connect-Uploaded-Files/a_1.fit", "notes.txt", "__MACOSX/DI-Connect-Uploaded-Files/._a_1.fit",
    ]);
    expect(text(await entries[0].read())).toBe("FIT".repeat(200));
    expect(text(await entries[1].read())).toBe("hello");
  });

  it.each([[true], [false]])("gives back what it wrote (deflate: %s)", async (compress) => {
    const files = [["activity/a.fit", pattern(5000)], ["sleep/b.fit", new Uint8Array(0)], ["c.fit", pattern(7)]];
    const entries = await readZipEntries(await zipOf(files, { compress }));
    expect(entries.map((e) => [e.name, e.size])).toEqual(files.map(([n, b]) => [n, b.length]));
    for (let i = 0; i < files.length; i++) expect(await entries[i].read()).toEqual(files[i][1]);
  });

  it("writes ZIP64 end records past 65,535 entries, and reads them", async () => {
    // A long history can pass the classic format's limits; without the ZIP64
    // records the entry count wraps and the archive opens as a fraction of itself.
    const files = Array.from({ length: 65_600 }, (_, i) => [`${i}.fit`, new Uint8Array(0)]);
    const entries = await readZipEntries(await zipOf(files, { compress: false }));
    expect(entries.length).toBe(65_600);
    expect(entries.at(-1).name).toBe("65599.fit");
  });

  it("refuses a damaged entry rather than importing garbage", async () => {
    const bytes = new Uint8Array(await (await zipOf([["a.fit", pattern(100)]], { compress: false })).arrayBuffer());
    bytes[30 + "a.fit".length + 10] ^= 0xff; // a byte of the stored data
    const [entry] = await readZipEntries(new Blob([bytes]));
    await expect(entry.read()).rejects.toThrow(/damaged/);
  });

  it("says plainly when a file is not a zip", async () => {
    await expect(readZipEntries(new Blob([pattern(1000)]))).rejects.toBeInstanceOf(ZipError);
  });
});

// Central-directory records of a zip, by byte offset, for tampering with.
function centralRecords(bytes) {
  const v = new DataView(bytes.buffer);
  const at = [];
  for (let i = 0; i + 4 <= bytes.length; i++) if (v.getUint32(i, true) === 0x02014b50) at.push(i);
  return { v, at };
}

describe("hostile zips", () => {
  // Imported zips are opened in the browser; these are the ways one could
  // take the tab down. See "Hostile archives" in zip.js.

  it("stops inflating an entry that unpacks to more than it declares", async () => {
    const bytes = new Uint8Array(await (await zipOf([["bomb.fit", new Uint8Array(20 * 1024 * 1024)]])).arrayBuffer());
    const { v, at } = centralRecords(bytes);
    v.setUint32(at[0] + 24, 1000, true); // claims 1 KB; 20 MB of zeros inside
    const [entry] = await readZipEntries(new Blob([bytes]));
    await expect(entry.read()).rejects.toThrow(/more than it claims/);
  });

  it("refuses entries that share their data — the many-from-one bomb", async () => {
    const bytes = new Uint8Array(await (await zipOf([["a.fit", pattern(500)], ["b.fit", pattern(500)]], { compress: false })).arrayBuffer());
    const { v, at } = centralRecords(bytes);
    v.setUint32(at[1] + 42, v.getUint32(at[0] + 42, true), true); // b points at a's data
    await expect(readZipEntries(new Blob([bytes]))).rejects.toThrow(/overlap/);
  });

  it("refuses an entry pointing past the end of the file", async () => {
    const bytes = new Uint8Array(await (await zipOf([["a.fit", pattern(500)]], { compress: false })).arrayBuffer());
    const { v, at } = centralRecords(bytes);
    v.setUint32(at[0] + 20, 0x7fffffff, true); // compressed size: 2 GB
    await expect(readZipEntries(new Blob([bytes]))).rejects.toThrow(/past the end/);
  });

  it("skips a FIT entry over the server's 50 MB limit without reading it", async () => {
    const bytes = new Uint8Array(await (await zipOf([["huge.fit", pattern(10)], ["ok.fit", pattern(10)]], { compress: false })).arrayBuffer());
    const { v, at } = centralRecords(bytes);
    v.setUint32(at[0] + 24, 60 * 1024 * 1024, true);
    const { entries, failures } = await expandSources([new File([bytes], "export.zip")]);
    expect(entries.map((e) => e.name)).toEqual(["ok.fit"]);
    expect(failures.map((f) => f.name)).toEqual(["huge.fit"]);
  });
});

describe("expanding what was picked", () => {
  it("opens zips, keeps only FIT files, and drops macOS shadows", async () => {
    const files = [
      new File([b64(PYTHON_ZIP)], "UploadedFiles_0-_Part1.zip"),
      new File([pattern(10)], "loose.FIT"),
      new File([pattern(10)], "._loose.fit"),
      new File(["<gpx/>"], "route.gpx"),
    ];
    const { entries, ignored, failures } = await expandSources(files);
    // Uploaded under its bare name: the server keeps only that, and the
    // precheck matches on it.
    expect(entries.map((e) => e.name)).toEqual(["a_1.fit", "loose.FIT"]);
    expect(text(await entries[0].read())).toBe("FIT".repeat(200));
    expect(ignored).toBe(2); // notes.txt and route.gpx
    expect(failures).toEqual([]);
  });

  it("names a zip it cannot open and carries on with the rest", async () => {
    const { entries, failures } = await expandSources([new File([pattern(50)], "broken.zip"), new File([pattern(10)], "a.fit")]);
    expect(entries.map((e) => e.name)).toEqual(["a.fit"]);
    expect(failures.map((f) => f.name)).toEqual(["broken.zip"]);
  });
});

describe("export names", () => {
  const row = (sha, fields) => ({ fields: { sha256: [sha, 1], ...fields } });

  it("files each kind in its own folder under its original name", () => {
    expect(exportNames([
      row("aa".repeat(32), { filename: ["123.fit", 1], file_kind: ["activity", 1] }),
      row("bb".repeat(32), { filename: ["C:\\watch\\456.FIT", 1], file_kind: ["monitoring", 1] }),
      row("cc".repeat(32), {}),
    ])).toEqual(["activity/123.fit", "monitoring/456.fit", `other/${"cc".repeat(32)}.fit`]);
  });

  it("keeps two files of the same name apart", () => {
    // The same name from two watches is common (each numbers its own files);
    // a zip with both under one name loses one on extraction.
    const names = exportNames([
      row("aa".repeat(32), { filename: ["1.fit", 1], file_kind: ["activity", 1] }),
      row("bb".repeat(32), { filename: ["1.fit", 1], file_kind: ["activity", 1] }),
    ]);
    expect(names).toEqual(["activity/1.fit", "activity/1-bbbbbbbb.fit"]);
  });
});
