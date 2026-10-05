// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Persist the picked device-folder handle across sessions so a remembered
// mass-storage watch can re-sync with one click (or zero, if the browser
// still considers the permission granted). FileSystemHandles are
// structured-cloneable, so IndexedDB is the only place they can live.

const DB_NAME = "tracks-device-sync";
const STORE = "handles";
const KEY = "deviceRoot";

function withStore(mode, fn) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      const db = req.result;
      const tx = db.transaction(STORE, mode);
      const r = fn(tx.objectStore(STORE));
      tx.oncomplete = () => { db.close(); resolve(r?.result); };
      tx.onerror = () => { db.close(); reject(tx.error); };
    };
  });
}

export async function saveDeviceFolderHandle(handle) {
  try {
    await withStore("readwrite", (s) => s.put(handle, KEY));
  } catch { /* private browsing etc. — remembering is best-effort */ }
}

export async function loadDeviceFolderHandle() {
  try {
    return (await withStore("readonly", (s) => s.get(KEY))) || null;
  } catch {
    return null;
  }
}
