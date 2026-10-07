// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Sealing a backup under a passphrase — the same files the phone writes.
 *
 * One file format for both, so a backup made in the browser restores on a
 * phone and the other way round. The phone's side is
 * `mobile/app/.../backup/BackupCrypto.kt`, whose comments carry the
 * reasoning; spec/fixtures/backup.json pins the bytes both must produce, and
 * this module's tests check them. In short:
 *
 * - PBKDF2-HMAC-SHA256 over the passphrase as UTF-8, 600 000 iterations
 *   stored in the header so they can rise later.
 * - `TRKBAK02`: the payload cut into 1 MiB chunks, each its own AES-256-GCM
 *   message (the STREAM construction). Nonce = random 7-byte prefix · u32
 *   chunk index · 1 on the last chunk, else 0; the header is every chunk's
 *   associated data. Reordered, dropped or cut-off chunks fail the tag.
 * - `TRKBAK01`, the first format — one GCM message over everything — is still
 *   opened, since phones wrote it before the chunked format existed.
 *
 * ## Why in the browser, not on the server
 *
 * The passphrase never leaves this page: the server sends the rows and files
 * it already serves to a signed-in session, and the sealing happens here, as
 * it does on the phone. A backup passphrase sent to the server would be one
 * more secret it held, and people reuse passphrases.
 *
 * Layout: `TRKBAK02` · iterations (u32) · salt (16) · nonce prefix (7) ·
 * chunks, each ciphertext+tag, all but the last of exactly CHUNK bytes of
 * plaintext.
 */

export const CHUNK = 1 << 20;
export const ITERATIONS = 600_000;
const TAG = 16;
const SALT_BYTES = 16;
const PREFIX_BYTES = 7;
const NONCE_BYTES = 12;
const MAGIC = new TextEncoder().encode("TRKBAK02");
const MAGIC_V1 = new TextEncoder().encode("TRKBAK01");
const HEADER = 8 + 4 + SALT_BYTES + PREFIX_BYTES;
const HEADER_V1 = 8 + 4 + SALT_BYTES + NONCE_BYTES;

export class WrongPassphraseOrDamaged extends Error {
  constructor() {
    super("That passphrase does not open this backup, or the file is damaged.");
    this.name = "WrongPassphraseOrDamaged";
  }
}

export class NotABackup extends Error {
  constructor() {
    super("This file is not a Tracks backup.");
    this.name = "NotABackup";
  }
}

/** The raw 32-byte key — exported for the fixture test; everything else uses aesKey. */
export async function deriveKeyBytes(passphrase, salt, iterations) {
  const base = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(passphrase), "PBKDF2", false, ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations }, base, 256,
  );
  return new Uint8Array(bits);
}

async function aesKey(passphrase, salt, iterations) {
  const raw = await deriveKeyBytes(passphrase, salt, iterations);
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

function nonce(prefix, index, last) {
  // 2^32 chunks of 1 MiB is four petabytes; reaching it would mean a bug.
  if (index > 0xffffffff) throw new Error("backup too large");
  const n = new Uint8Array(NONCE_BYTES);
  n.set(prefix);
  new DataView(n.buffer).setUint32(PREFIX_BYTES, index);
  n[11] = last ? 1 : 0;
  return n;
}

/** A header claiming absurd work is a damaged (or hostile) file; refuse before deriving for minutes. */
function checkIterations(iterations) {
  if (iterations < 10_000 || iterations > 10_000_000) throw new WrongPassphraseOrDamaged();
}

/**
 * Seals a payload a piece at a time. Write `header` first, then whatever
 * `write` returns, then what `finish` returns. A write that fails partway
 * never reaches `finish`, so its file has no last chunk and does not open.
 *
 * `salt` and `prefix` are drawn at random; the tests pass the fixture's.
 */
export async function createSealer(passphrase, { iterations = ITERATIONS, salt, prefix } = {}) {
  salt ??= crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  prefix ??= crypto.getRandomValues(new Uint8Array(PREFIX_BYTES));
  const header = new Uint8Array(HEADER);
  header.set(MAGIC);
  new DataView(header.buffer).setUint32(8, iterations);
  header.set(salt, 12);
  header.set(prefix, 12 + SALT_BYTES);
  const key = await aesKey(passphrase, salt, iterations);

  const buf = new Uint8Array(CHUNK);
  let filled = 0;
  let index = 0;
  let finished = false;

  async function emit(last) {
    const sealed = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce(prefix, index++, last), additionalData: header, tagLength: TAG * 8 },
      key, buf.subarray(0, filled),
    );
    filled = 0;
    return new Uint8Array(sealed);
  }

  return {
    header,
    /** Sealed chunks that are complete after adding `bytes` (often none). */
    async write(bytes) {
      if (finished) throw new Error("already finished");
      const out = [];
      let from = 0;
      while (from < bytes.length) {
        // A full chunk goes out only once more data arrives, so the one that
        // turns out to be last is still here to be flagged.
        if (filled === CHUNK) out.push(await emit(false));
        const take = Math.min(bytes.length - from, CHUNK - filled);
        buf.set(bytes.subarray(from, from + take), filled);
        filled += take;
        from += take;
      }
      return out;
    },
    /** The last chunk. Without it the file does not open. */
    async finish() {
      const last = await emit(true);
      finished = true;
      return last;
    },
  };
}

async function bytesOf(blob) {
  return new Uint8Array(await blob.arrayBuffer());
}

async function decrypt(key, iv, header, data) {
  try {
    return new Uint8Array(await crypto.subtle.decrypt(
      { name: "AES-GCM", iv, additionalData: header, tagLength: TAG * 8 }, key, data,
    ));
  } catch {
    throw new WrongPassphraseOrDamaged();
  }
}

/**
 * Open a sealed backup (a File or Blob) and return its plaintext as a source
 * of chunks: `next()` resolves to the next decrypted piece, or null at the
 * end. Read one chunk at a time from the file, so a backup of any size fits.
 */
export async function openBackup(blob, passphrase) {
  if (blob.size < MAGIC.length) throw new NotABackup();
  const magic = await bytesOf(blob.slice(0, MAGIC.length));
  const is = (m) => m.every((b, i) => magic[i] === b);

  if (is(MAGIC_V1)) {
    // Phones wrote this before the chunked format, and only for backups small
    // enough to write in one piece, so reading one whole is fine.
    const all = await bytesOf(blob);
    if (all.length < HEADER_V1) throw new WrongPassphraseOrDamaged();
    const view = new DataView(all.buffer);
    const iterations = view.getUint32(8);
    checkIterations(iterations);
    const salt = all.slice(12, 12 + SALT_BYTES);
    const iv = all.slice(12 + SALT_BYTES, HEADER_V1);
    const key = await aesKey(passphrase, salt, iterations);
    const plain = await decrypt(key, iv, all.subarray(0, HEADER_V1), all.subarray(HEADER_V1));
    let given = false;
    return { next: async () => (given ? null : ((given = true), plain)) };
  }
  if (!is(MAGIC)) throw new NotABackup();
  if (blob.size < HEADER) throw new WrongPassphraseOrDamaged();

  const header = await bytesOf(blob.slice(0, HEADER));
  const iterations = new DataView(header.buffer).getUint32(8);
  checkIterations(iterations);
  const salt = header.slice(12, 12 + SALT_BYTES);
  const prefix = header.slice(12 + SALT_BYTES, HEADER);
  const key = await aesKey(passphrase, salt, iterations);

  let pos = HEADER;
  let index = 0;
  let done = false;
  return {
    async next() {
      if (done) return null;
      // A chunk is the last one exactly when it reaches the end of the file:
      // only the last can be short, and a full one is last only if nothing
      // follows. The flag is in the nonce, so a file cut at a chunk boundary
      // decrypts its new "last" chunk under the wrong flag and fails.
      const end = Math.min(pos + CHUNK + TAG, blob.size);
      const last = end === blob.size;
      const sealed = await bytesOf(blob.slice(pos, end));
      pos = end;
      const plain = await decrypt(key, nonce(prefix, index++, last), header, sealed);
      done = last;
      return plain;
    },
  };
}
