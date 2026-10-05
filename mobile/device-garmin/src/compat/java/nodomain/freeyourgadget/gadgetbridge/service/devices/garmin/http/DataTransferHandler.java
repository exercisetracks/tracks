// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shim for Garmin's chunked data-transfer bookkeeping.
 *
 * <p>The watch cannot receive an arbitrarily large protobuf response in one
 * message, so a large payload is registered here, given an id, and then pulled
 * in chunks the watch asks for by that id. That mechanism is vendor protocol
 * rather than Gadgetbridge policy, so it is reimplemented rather than declined.
 *
 * <p>Kept static because the vendored call sites are static, and concurrent
 * because registration happens on the caller's thread while chunk requests
 * arrive on the BLE callback thread.
 */
public class DataTransferHandler {
    private static final Map<Integer, byte[]> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    public static int registerData(final byte[] data) {
        final int id = NEXT_ID.getAndIncrement();
        PENDING.put(id, data == null ? new byte[0] : data);
        return id;
    }

    public static byte[] getData(final int id) {
        return PENDING.get(id);
    }

    /**
     * Drop a completed transfer.
     *
     * <p>Not optional housekeeping: without it every image ever sent to the
     * watch stays in memory for the life of the process.
     */
    public static void onDataChunkSuccessfullyReceived(final int id) {
        PENDING.remove(id);
    }

    /**
     * Serve the chunk the watch asked for.
     *
     * <p>Returns null: the only producer of registered data in the code Tracks
     * keeps is the HTTP proxy, which is disabled, so there is never anything
     * outstanding to serve. Wired rather than removed because the bookkeeping
     * above is real protocol and a later feature may register data here.
     */
    public nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiDataTransferService.DataTransferService
            handle(final nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiDataTransferService.DataTransferService request,
                   final int requestId) {
        return null;
    }
}
