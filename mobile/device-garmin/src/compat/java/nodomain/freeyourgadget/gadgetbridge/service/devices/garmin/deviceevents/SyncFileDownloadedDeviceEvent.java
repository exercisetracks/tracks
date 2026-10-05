// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents;

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiFileSyncService;

/**
 * A file pulled over the newer protobuf sync protocol.
 *
 * <p>A subclass rather than a second event type, so everything already
 * listening for a downloaded file keeps working — the two protocols differ in
 * how a file is *addressed*, not in what has happened.
 *
 * <p>And addressing is exactly why the extra field is needed. The older
 * protocol names a file by a 16-bit directory index, which is what
 * {@link FileDownloadedDeviceEvent#directoryEntry} carries; the newer one names
 * it by a 128-bit id and a type name, with no directory and no conversion
 * between the two. Synthesising a {@code DirectoryEntry} to fit the existing
 * field would mean inventing an index — and index 0 is the directory itself, so
 * a later "you may reclaim this file" would be aimed at the wrong thing
 * entirely.
 *
 * <p>Lives in src/compat because it is ours: the pull script deletes the
 * vendored package tree wholesale, and this shares that tree's package name
 * only so it can extend an upstream class.
 */
public class SyncFileDownloadedDeviceEvent extends FileDownloadedDeviceEvent {
    /** How the watch refers to this file, and how it is later told to release it. */
    public GdiFileSyncService.File syncFile;
}
