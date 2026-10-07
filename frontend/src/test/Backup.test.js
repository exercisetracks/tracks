// @vitest-environment node
// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The web app's backups against spec/fixtures/backup.json — bytes written by
 * an independent Python implementation, and checked by the phone's suite too
 * (BackupFixtureTest in mobile/core and mobile/app). Both passing is what
 * makes a backup from the browser restore on a phone, and the other way round.
 *
 * Node's environment rather than jsdom's: the sealing is WebCrypto, which
 * Node provides as browsers do.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";
import {
  CHUNK, NotABackup, WrongPassphraseOrDamaged, createSealer, deriveKeyBytes, openBackup,
} from "../lib/backup/crypto";
import { BackupFormatError, BackupReader, writeBackup } from "../lib/backup/format";

// Beside the checkout when run on a host, /spec inside the dev container.
const PATH = [join(__dirname, "../../../spec/fixtures/backup.json"), "/spec/fixtures/backup.json"].find(existsSync);
const fx = PATH ? JSON.parse(readFileSync(PATH, "utf8")) : null;

const hex = (s) => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));
const toHex = (b) => Buffer.from(b).toString("hex");
const b64 = (s) => new Uint8Array(Buffer.from(s, "base64"));
const sha = (b) => createHash("sha256").update(b).digest("hex");
const pattern = (n) => Uint8Array.from({ length: n }, (_, i) => (i * 31) & 0xff);
const concat = (parts) => new Uint8Array(Buffer.concat(parts.map((p) => Buffer.from(p))));

async function seal(plain, passphrase = fx.passphrase, opts = {}) {
  const s = await createSealer(passphrase, {
    iterations: fx.iterations, salt: hex(fx.salt), prefix: hex(fx.prefix), ...opts,
  });
  return concat([s.header, ...(await s.write(plain)), await s.finish()]);
}

async function openAll(bytes, passphrase = fx.passphrase) {
  const source = await openBackup(new Blob([bytes]), passphrase);
  const parts = [];
  for (let c = await source.next(); c !== null; c = await source.next()) parts.push(c);
  return concat(parts);
}

describe.skipIf(!fx)("backups, against the shared fixture", () => {
  it("uses the chunk size the fixture was made with", () => {
    expect(CHUNK).toBe(fx.chunk_bytes);
  });

  it("derives the key with PBKDF2 over the passphrase as UTF-8", async () => {
    expect(toHex(await deriveKeyBytes(fx.passphrase, hex(fx.salt), fx.iterations))).toBe(fx.key);
    const u = fx.unicode_passphrase;
    expect(toHex(await deriveKeyBytes(u.passphrase, hex(fx.salt), fx.iterations))).toBe(u.key);
  });

  it("seals byte-for-byte as the phone does, either side of every chunk boundary", async () => {
    for (const c of fx.seals) {
      const sealed = await seal(pattern(c.length));
      expect({ length: c.length, sha: sha(sealed) }).toEqual({ length: c.length, sha: c.sha256 });
    }
  });

  it("seals the same bytes however the payload is split across writes", async () => {
    const plain = pattern(CHUNK + 1000);
    const s = await createSealer(fx.passphrase, { iterations: fx.iterations, salt: hex(fx.salt), prefix: hex(fx.prefix) });
    const parts = [s.header];
    for (let i = 0; i < plain.length; i += 7777) parts.push(...(await s.write(plain.subarray(i, i + 7777))));
    parts.push(await s.finish());
    expect(sha(concat(parts))).toBe(sha(await seal(plain)));
  });

  it("writes the fixture payload byte-for-byte", async () => {
    const p = fx.payload;
    const files = new Map(p.files.map((f) => [f.name, hex(f.hex)]));
    const parts = [];
    await writeBackup(async (b) => parts.push(b), {
      binding: p.binding, rows: p.rows, names: p.files.map((f) => f.name), createdAtMs: p.created_at_ms,
      // The empty one stands for a blob that could not be read.
      read: async (name) => (files.get(name).length ? files.get(name) : null),
    });
    expect(Buffer.from(concat(parts)).toString("base64")).toBe(fx.payload_encoded);
  });

  it("opens the fixture backup and reads every row and file", async () => {
    const source = await openBackup(new Blob([b64(fx.payload_sealed)]), fx.passphrase);
    const reader = await BackupReader.open(source);
    expect(reader.binding).toEqual(fx.payload.binding);
    expect(reader.rows).toEqual(fx.payload.rows);
    expect(reader.createdAtMs).toBe(fx.payload.created_at_ms);
    const got = [];
    await reader.forEachFile(async (name, bytes) => got.push({ name, hex: toHex(bytes) }));
    expect(got).toEqual(fx.payload.files);
  });

  it("seals the fixture payload to the fixture file", async () => {
    expect(Buffer.from(await seal(b64(fx.payload_encoded))).toString("base64")).toBe(fx.payload_sealed);
  });

  it("opens a backup in the first format, which phones wrote before chunking", async () => {
    expect(toHex(await openAll(b64(fx.payload_sealed_v1)))).toBe(toHex(b64(fx.payload_encoded)));
  });
});

describe("refusing what is not a whole backup", () => {
  const iterations = 10_000;
  const sealWith = async (plain) => {
    const s = await createSealer("pw", { iterations });
    return concat([s.header, ...(await s.write(plain)), await s.finish()]);
  };

  it("refuses a wrong passphrase rather than decoding garbage", async () => {
    const sealed = await sealWith(pattern(10));
    await expect(openAll(sealed, "not it")).rejects.toBeInstanceOf(WrongPassphraseOrDamaged);
  });

  /** Cut exactly between chunks every chunk left is intact; only the last-chunk flag catches it. */
  it("refuses a file cut at a chunk boundary", async () => {
    const sealed = await sealWith(pattern(3 * CHUNK));
    const header = sealed.length - 3 * (CHUNK + 16);
    await expect(openAll(sealed.subarray(0, header + 2 * (CHUNK + 16)), "pw")).rejects.toBeInstanceOf(WrongPassphraseOrDamaged);
  });

  it("refuses a backup whose writing never finished", async () => {
    const s = await createSealer("pw", { iterations });
    const unfinished = concat([s.header, ...(await s.write(pattern(2 * CHUNK + 5)))]);
    await expect(openAll(unfinished, "pw")).rejects.toBeInstanceOf(WrongPassphraseOrDamaged);
  });

  it("says when a file is not a backup at all", async () => {
    await expect(openAll(new TextEncoder().encode("PK\u0003\u0004 a zip"), "pw")).rejects.toBeInstanceOf(NotABackup);
  });

  it("refuses a payload with trailing data or cut short", async () => {
    const parts = [];
    await writeBackup(async (b) => parts.push(b), {
      binding: null, rows: [], names: ["a"], createdAtMs: 0, read: async () => pattern(100),
    });
    const encoded = concat(parts);
    const source = (bytes) => { let given = false; return { next: async () => (given ? null : ((given = true), bytes)) }; };
    const all = async (bytes) => (await BackupReader.open(source(bytes))).forEachFile(async () => {});
    await expect(all(concat([encoded, new Uint8Array([0])]))).rejects.toBeInstanceOf(BackupFormatError);
    await expect(all(encoded.subarray(0, encoded.length - 10))).rejects.toBeInstanceOf(BackupFormatError);
  });
});
