// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * What a backup holds, before sealing — the phone's format exactly
 * (`mobile/core/.../backup/Backup.kt`, where the reasoning lives), so either
 * can restore the other's.
 *
 * A u32 length and a JSON manifest — format, version, created_at_ms, the
 * account binding, every synced row with its per-field stamps and tombstones,
 * and the FIT files' names — then each file as a u32 length and its bytes, in
 * manifest order. Lengths are big-endian.
 *
 * Streamed both ways: one FIT file in memory at a time, however long the
 * history. spec/fixtures/backup.json pins the bytes.
 */

export const FORMAT = "tracks-backup";
export const VERSION = 1;

// The lengths are authenticated with the rest, so a huge one is a bug rather
// than an attack — but refusing it beats an allocation failure that names
// nothing. No FIT file, and no manifest, comes near this.
const MAX_LENGTH = 256 << 20;

export class BackupFormatError extends Error {
  constructor(message) {
    super(message);
    this.name = "BackupFormatError";
  }
}

function u32(n) {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, n);
  return b;
}

/**
 * A row as the phone writes it (Wire.encodeChange): "deleted" only when set,
 * and only fields that carry a stamp. The server's pull spells a live row's
 * "deleted" as null; dropping it keeps the two byte-identical.
 */
export function canonicalChange(change) {
  const fields = {};
  for (const [name, pair] of Object.entries(change.fields ?? {})) {
    if (Array.isArray(pair) && pair.length === 2 && pair[1] != null) fields[name] = pair;
  }
  const out = { entity: change.entity, uid: change.uid, fields };
  if (change.deleted != null) out.deleted = change.deleted;
  return out;
}

/**
 * Encode a backup into `sink` (an async function taking a Uint8Array).
 * `read(name)` fetches each file; one that cannot be read is written empty
 * rather than left out — the names are already in the manifest — and is
 * skipped on restore. `onProgress(done, total)` hears every file.
 */
export async function writeBackup(sink, { binding, rows, names, createdAtMs, read, onProgress = () => {} }) {
  const manifest = { format: FORMAT, version: VERSION, created_at_ms: createdAtMs };
  if (binding) manifest.binding = { server_id: binding.server_id, account: binding.account };
  manifest.rows = rows.map(canonicalChange);
  manifest.files = names;
  const body = new TextEncoder().encode(JSON.stringify(manifest));
  await sink(u32(body.length));
  await sink(body);
  onProgress(0, names.length);
  for (let i = 0; i < names.length; i++) {
    const bytes = (await read(names[i])) ?? new Uint8Array(0);
    await sink(u32(bytes.length));
    await sink(bytes);
    onProgress(i + 1, names.length);
  }
}

/**
 * Reads a backup from a source of plaintext chunks (see crypto.openBackup):
 * the manifest at once, the files one at a time.
 */
export class BackupReader {
  constructor(source) {
    this.source = source;
    this.pending = [];
    this.offset = 0;
  }

  /** Read and check the manifest. Throws BackupFormatError on anything malformed. */
  static async open(source) {
    const reader = new BackupReader(source);
    let manifest;
    try {
      const body = await reader.bytes(await reader.int());
      manifest = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(body));
    } catch (e) {
      if (e instanceof BackupFormatError || e?.name === "WrongPassphraseOrDamaged") throw e;
      throw new BackupFormatError("The backup's contents are unreadable.");
    }
    if (manifest?.format !== FORMAT) throw new BackupFormatError("This is not a Tracks backup.");
    if ((manifest.version ?? 0) > VERSION) {
      throw new BackupFormatError("This backup was made by a newer version of Tracks.");
    }
    if (!Array.isArray(manifest.rows) || !Array.isArray(manifest.files)) {
      throw new BackupFormatError("The backup's contents are unreadable.");
    }
    reader.binding = manifest.binding
      ? { server_id: String(manifest.binding.server_id), account: String(manifest.binding.account) }
      : null;
    reader.rows = manifest.rows;
    reader.names = manifest.files.map(String);
    reader.createdAtMs = Number(manifest.created_at_ms ?? 0);
    return reader;
  }

  /** Every file in order, then a check that nothing follows them. Call once. */
  async forEachFile(action) {
    for (const name of this.names) await action(name, await this.bytes(await this.int()));
    if (!(await this.atEnd())) throw new BackupFormatError("The backup has trailing data.");
  }

  // ── Bytes across chunk boundaries ─────────────────────────────────────────

  async fill() {
    const chunk = await this.source.next();
    if (chunk === null) return false;
    if (chunk.length) this.pending.push(chunk);
    return true;
  }

  async bytes(n) {
    const out = new Uint8Array(n);
    let got = 0;
    while (got < n) {
      if (!this.pending.length && !(await this.fill())) {
        throw new BackupFormatError("The backup is truncated.");
      }
      if (!this.pending.length) continue;
      const head = this.pending[0];
      const take = Math.min(n - got, head.length - this.offset);
      out.set(head.subarray(this.offset, this.offset + take), got);
      got += take;
      this.offset += take;
      if (this.offset === head.length) {
        this.pending.shift();
        this.offset = 0;
      }
    }
    return out;
  }

  async int() {
    const v = new DataView((await this.bytes(4)).buffer).getUint32(0);
    if (v > MAX_LENGTH) throw new BackupFormatError("The backup is corrupt.");
    return v;
  }

  async atEnd() {
    while (!this.pending.length) {
      if (!(await this.fill())) return true;
    }
    return false;
  }
}
