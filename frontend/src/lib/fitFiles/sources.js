// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Turning what someone picked or dropped into the FIT files to import.
 *
 * ## Why folders, drops and zips, and not just a multi-file picker
 *
 * Selecting thousands of files in a desktop file dialog fails before the
 * browser sees any of them. KDE's dialog writes the selection into its
 * filename box as `"a.fit" "b.fit" …`, and that box (a Qt QLineEdit) holds at
 * most 32,767 characters: a Garmin export's names are 35 characters each, so
 * past about 860 files the list is cut mid-name and the dialog reports that
 * the truncated last file "cannot be found". Other dialogs have limits of
 * their own. A folder is one name in any dialog; a drop or a zip skips the
 * dialog's list entirely. Every one of them walks recursively, so pointing at
 * a whole Garmin Connect export finds the files under
 * DI_CONNECT/DI-Connect-Uploaded-Files without the person knowing that path.
 */
import { MAX_ENTRY_BYTES, readZipEntries } from "./zip";

const isFit = (name) => name.toLowerCase().endsWith(".fit");
const isZip = (name) => name.toLowerCase().endsWith(".zip");
const baseName = (path) => path.slice(path.lastIndexOf("/") + 1);
// macOS's resource-fork shadows ("._x.fit", under "__MACOSX/") end in .fit but
// are not FIT files; zips made on a Mac are full of them.
const isShadow = (path) => baseName(path).startsWith("._") || path.startsWith("__MACOSX/");

/** The Files a drop carries, folders walked. Call it in the drop handler itself: the items die after it returns. */
export function filesFromDrop(dataTransfer) {
  const entries = [...(dataTransfer.items ?? [])]
    .filter((i) => i.kind === "file")
    .map((i) => i.webkitGetAsEntry?.())
    .filter(Boolean);
  if (entries.length === 0) return Promise.resolve([...(dataTransfer.files ?? [])]);
  return walkEntries(entries);
}

async function walkEntries(entries) {
  const out = [];
  const stack = [...entries];
  while (stack.length) {
    const entry = stack.pop();
    if (entry.isFile) {
      out.push(await new Promise((resolve, reject) => entry.file(resolve, reject)));
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      // readEntries hands a directory over in chunks (100 in Chromium) and
      // signals the end with an empty one; one call would see only the first.
      for (;;) {
        const chunk = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
        if (chunk.length === 0) break;
        stack.push(...chunk);
      }
    }
  }
  return out;
}

/**
 * Expand picked/dropped Files into upload entries — { name, size, read() } as
 * uploadFiles takes them — opening any zips (Tracks' own export, or the
 * UploadedFiles parts of a Garmin export) and keeping only FIT files.
 * Returns { entries, ignored, failures } — `ignored` counts files that were
 * neither (zips inside zips included: they are not opened), `failures` names
 * zips that could not be opened and files over the size limit.
 */
export async function expandSources(files, { onStatus = null } = {}) {
  const entries = [];
  const failures = [];
  let ignored = 0;
  for (const file of files) {
    const path = file.webkitRelativePath || file.name;
    if (isShadow(path)) continue;
    if (isFit(file.name)) {
      // Not read just to be refused: the server's limit, checked here.
      if (file.size > MAX_ENTRY_BYTES) { failures.push({ name: file.name, error: "Larger than the 50 MB a FIT file may be" }); continue; }
      entries.push({ name: file.name, size: file.size, read: async () => new Uint8Array(await file.arrayBuffer()) });
    } else if (isZip(file.name)) {
      onStatus?.(`Opening ${file.name}`);
      try {
        for (const e of await readZipEntries(file)) {
          if (isShadow(e.name)) continue;
          if (isFit(e.name) && e.size > MAX_ENTRY_BYTES) failures.push({ name: baseName(e.name), error: "Larger than the 50 MB a FIT file may be" });
          else if (isFit(e.name)) entries.push({ name: baseName(e.name), size: e.size, read: e.read });
          else ignored++;
        }
      } catch (e) {
        failures.push({ name: file.name, error: e.message || String(e) });
      }
    } else {
      ignored++;
    }
  }
  return { entries, ignored, failures };
}
