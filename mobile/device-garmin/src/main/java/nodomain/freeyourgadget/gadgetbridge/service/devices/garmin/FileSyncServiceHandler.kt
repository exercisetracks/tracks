package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiFileSyncService
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.FileDownloadedDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.util.protobuf.buildWith
import android.util.Log
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class FileSyncServiceHandler(val deviceSupport: GarminSupport) {
    private var nextPageId: Int? = null
    private var cursorId: Int? = null

    /**
     * Tracks: a second listing, after the normal one, that includes files the
     * watch already calls synced and takes only recent activities Tracks has
     * never downloaded.
     *
     * The normal listing excludes synced files, so a recording flagged synced
     * by anything else — Garmin Connect, the watch's own Wi-Fi upload, an
     * older build of this app — would otherwise never reach Tracks, silently.
     * Automatic rather than a button because the user cannot know it
     * happened. Bounded three ways so it stays cheap: from the page the last
     * one ended on (an unfiltered listing from the start runs to months of
     * files, so that sweep runs only every few hours), only activities (the
     * irreplaceable kind), and only recent ones not already downloaded (see
     * GarminSupport.wantsRecoveredActivity). The page cursor is put back
     * afterwards so the next normal listing continues where it left off.
     */
    private var recovering = false
    private var pageBeforeRecovery: Int? = null
    private var recovered = 0
    private var recoveryListed = 0
    private var recoveryStartPage = 0

    fun handle(fileSyncService: GdiFileSyncService.FileSyncService): GdiFileSyncService.FileSyncService? {
        return when {
            fileSyncService.hasNewFileNotification() -> handleNewFileNotification(fileSyncService.newFileNotification)
            fileSyncService.hasFileListResponse() -> handleFileListResponse(fileSyncService.fileListResponse)
            fileSyncService.hasFileResponse() -> handleFileResponse(fileSyncService.fileResponse)
            fileSyncService.hasUploadResponse() -> handleUploadResponse(fileSyncService.uploadResponse)
            fileSyncService.hasTransferComplete() -> handleTransferComplete(fileSyncService.transferComplete)
            else -> {
                LOG.warn("Unhandled file sync service: {}", fileSyncService)
                return null
            }
        }
    }

    private fun handleNewFileNotification(newFileNotification: GdiFileSyncService.NewFileNotification): GdiFileSyncService.FileSyncService? {
        LOG.debug("Got new file notification: {}", newFileNotification)
        for(file in newFileNotification.fileList){
            // By code when the notice omits the name: see GarminSupport.resolveTypeName.
            val typeName = if (file.hasType()) deviceSupport.resolveTypeName(file.type) else null
            if (typeName == null) {
                LOG.warn("New file has no type name: {}", file)
                Log.i("TracksFileSync", "new-file notice with unknown type code ${file.type.code}")
                continue
            }
            val fetchUnknownFiles = deviceSupport.devicePrefs.fetchUnknownFiles
            val type = FileType.FILETYPE.findByTypeName(typeName)
            if (!FileType.FILETYPE.shouldPull(type) && !fetchUnknownFiles) {
                LOG.warn("Ignoring file type: {}", file)
                Log.i("TracksFileSync", "new-file notice ignored: $typeName")
                continue
            }
            Log.i("TracksFileSync", "new-file notice queued: $typeName")

            deviceSupport.addFileToDownloadList(file)
        }
        return null
    }

    private fun handleFileResponse(fileResponse: GdiFileSyncService.FileResponse): GdiFileSyncService.FileSyncService? {
        LOG.debug("Got file response: {}", fileResponse)

        if (fileResponse.status != 0) {
            LOG.warn("File download failed with status {}", fileResponse.status)
            // Signal to the support class that the download failed so it can also continue to the next one
            val fileDownloadedDeviceEvent = FileDownloadedDeviceEvent()
            fileDownloadedDeviceEvent.success = false
            deviceSupport.evaluateGBDeviceEvent(fileDownloadedDeviceEvent)
        } else {
            deviceSupport.downloadFileFromServiceV2(fileResponse.handle)
        }

        return null
    }

    private fun handleFileListResponse(fileListResponse: GdiFileSyncService.FileListResponse): GdiFileSyncService.FileSyncService? {
        LOG.debug(
            "Handling file list response with {} files, cursorId={}, nextPageId={}",
            fileListResponse.fileList.size,
            if (fileListResponse.hasCursorId()) fileListResponse.cursorId else null,
            fileListResponse.nextPageId,
        )

        val fetchUnknownFiles = deviceSupport.devicePrefs.fetchUnknownFiles

        // Only the first entry for a type ever carries its name — not the first
        // per page — so names come from GarminSupport's persisted map.
        // Tracks: a one-line tally per page, on logcat rather than slf4j (which
        // has no binding in this app, so LOG goes nowhere). What the watch
        // *offers* is the one thing a missing activity cannot be diagnosed
        // without, and it is otherwise invisible.
        val queued = sortedMapOf<String, Int>()
        val ignored = sortedMapOf<String, Int>()
        for (file in fileListResponse.fileList) {
            if (!file.hasType() || !file.type.hasCode()) {
                LOG.warn("Ignoring file with unknown type: {}", file)
                ignored.merge("code?", 1, Int::plus)
                continue
            }
            val typeName = deviceSupport.resolveTypeName(file.type)
            if (typeName == null) {
                LOG.warn("No type name found for {}", file)
                ignored.merge("code ${file.type.code}", 1, Int::plus)
                continue
            }

            val fileType = FileType.FILETYPE.findByTypeName(typeName)
            if (recovering) {
                if (fileType == FileType.FILETYPE.ACTIVITY && deviceSupport.wantsRecoveredActivity(file)) {
                    recovered++
                    deviceSupport.addFileToDownloadList(file)
                }
                continue
            }
            if (!FileType.FILETYPE.shouldPull(fileType) && !fetchUnknownFiles) {
                LOG.warn("Ignoring file type: {} {}", typeName, fileType)
                ignored.merge(typeName, 1, Int::plus)
                continue
            }

            LOG.debug("Adding to download: {}/{} ({})", file.id.id1, file.id.id2, typeName)
            queued.merge(typeName, 1, Int::plus)
            deviceSupport.addFileToDownloadList(file)
        }
        Log.i(
            "TracksFileSync",
            "listing page: ${fileListResponse.fileList.size} file(s), queued=$queued ignored=$ignored " +
                "cursor=${if (fileListResponse.hasCursorId()) fileListResponse.cursorId else null} " +
                "nextPage=${fileListResponse.nextPageId}",
        )

        // #5461 - some watches to not send the next page ID
        // however, from previous logs, it always seems to match the max seen across all sent items, so attempt
        // to fall back to that as a workaround so we can fetch the subsequent files
        nextPageId = fileListResponse.nextPageId

        // The device sets cursor_id when there are more items pending for *this same* listing
        // request. Keep pulling pages within this cursor immediately instead of stopping after
        // one page - otherwise anything past the first ~100 items (which can easily be all SPORTS
        // backlog) is never seen.
        cursorId = if (fileListResponse.hasCursorId()) fileListResponse.cursorId else null
        if (recovering) recoveryListed += fileListResponse.fileList.size
        // Tracks: every sync, from where the last one ended; from the start
        // when the periodic sweep is due or no sweep has finished yet (a
        // fresh install, or an upgrade from a build that kept no page). See
        // GarminSupport.activityRecoveryStartPage for why every sync.
        val recoverFrom = if (cursorId == null && !recovering) {
            val lastPage = deviceSupport.activityRecoveryStartPage()
            if (deviceSupport.activityRecoveryDue() || lastPage == null) 0 else lastPage
        } else {
            null
        }
        if (cursorId != null) {
            deviceSupport.sendProtobufRequest(
                "continue file list",
                GdiSmartProto.Smart.newBuilder().setFileSyncService(requestFileList()).build()
            )
        } else if (recoverFrom != null) {
            recovering = true
            pageBeforeRecovery = nextPageId
            nextPageId = recoverFrom.takeIf { it > 0 }
            recovered = 0
            recoveryListed = 0
            recoveryStartPage = recoverFrom
            deviceSupport.sendProtobufRequest(
                "recovery file list",
                GdiSmartProto.Smart.newBuilder().setFileSyncService(requestFileList()).build()
            )
        } else {
            if (recovering) {
                Log.i(
                    "TracksFileSync",
                    "recovery listing from page $recoveryStartPage: $recoveryListed listed, " +
                        "$recovered missed activity file(s) queued, next page ${fileListResponse.nextPageId}",
                )
                deviceSupport.rememberActivityRecoveryPage(fileListResponse.nextPageId)
                recovering = false
                nextPageId = pageBeforeRecovery
            }
            // Tracks: the listing is finished, and nothing else on the wire says
            // so. Upstream does not need to know, because it only ever ends a
            // sync run once a file has actually transferred; Tracks ends the run
            // when the queue drains, so without this a watch that offers an
            // empty list (the ordinary case, a day after the last sync) would
            // have its run declared finished while this request was still in
            // flight. See vendor/patches/0004-filesync-listing-complete.patch.
            deviceSupport.onFileListComplete()
        }

        return null
    }

    /**
     * Told the transfer handle the watch assigned an upload, or -1 if it refused.
     *
     * A listener rather than a return value because the answer arrives on the
     * BLE callback thread, one protobuf round trip after the request.
     */
    fun interface UploadListener {
        fun onUploadAccepted(handle: Int)
    }

    private var uploadListener: UploadListener? = null

    /**
     * Work waiting for the watch's TransferComplete on a handle to be *answered*.
     *
     * Garmin Connect never issues an upload request until it has answered the
     * previous file's TransferComplete. Across nineteen consecutive uploads in a
     * capture that verifiably built a training calendar the order is strictly
     * `transfer_complete_response` then `upload_request`, without one exception.
     * Tracks had it backwards on every single cycle — the next request went out
     * two to ten milliseconds *before* the answer — and the watch tolerated four
     * files before falling silent for a full five minutes on the fifth, never
     * answering the upload request at all. See AGENTS.md.
     *
     * Keyed by handle rather than kept in a single slot: a download's
     * TransferComplete can land between an upload's channel closing and its own,
     * and releasing the wrong file would put the pair back out of order.
     */
    private val transferCompleteWaiters = ConcurrentHashMap<Int, Runnable>()

    /** Handles whose answer has been built but not yet written to the wire. */
    private val answeredButUnsent = ConcurrentLinkedQueue<Int>()

    /** Run [action] once this handle's TransferComplete has been answered. */
    fun onTransferCompleteAnswered(handle: Int, action: Runnable) {
        transferCompleteWaiters[handle] = action
    }

    /** Give up on a handle whose transfer failed before it ever completed. */
    fun cancelTransferCompleteWaiter(handle: Int) {
        transferCompleteWaiters.remove(handle)
    }

    /**
     * Release everything whose answer has now reached the transport.
     *
     * Deliberately not called from [handleTransferComplete]. That method only
     * *builds* the response — the send happens one layer up — so a waiter fired
     * there would release the next upload request into the queue ahead of the
     * answer it is meant to follow, which is the exact inversion this exists to
     * remove.
     */
    fun flushAnsweredTransfers() {
        while (true) {
            val handle = answeredButUnsent.poll() ?: return
            transferCompleteWaiters.remove(handle)?.run()
        }
    }

    /**
     * Ask the watch to accept a new file.
     *
     * This is how Garmin Connect uploads, and it is not the path Tracks used
     * before: `CreateFileMessage`/`UploadRequestMessage` address a file by
     * numeric type and subtype, while this addresses it by the watch's own type
     * *name* and hands back a transfer handle for a separate channel. The
     * difference is not cosmetic — a schedule (FIT_TYPE_7) pushed the legacy way
     * is accepted, acknowledged in full, and never appears in the training
     * calendar. See AGENTS.md for the capture that established this.
     *
     * [contents] is the plain file; the bytes are deflated on the transfer
     * channel, but [size] here is the *uncompressed* length, which is what the
     * watch is told to expect.
     */
    fun uploadRequest(
        typeName: String,
        size: Int,
        listener: UploadListener,
    ): GdiFileSyncService.FileSyncService {
        uploadListener = listener
        // id1 all-ones is what Garmin sends for "this is a new file, you assign
        // the real id". id2 is the client's own handle on it, and only has to be
        // unique within a session; a counter off the clock is enough and cannot
        // collide with an earlier run.
        val clientId = nextClientFileId()
        LOG.debug("Requesting upload of a {} file, {} bytes (client id {})", typeName, size, clientId)
        return GdiFileSyncService.FileSyncService.newBuilder().buildWith {
            uploadRequest = GdiFileSyncService.UploadRequest.newBuilder().buildWith {
                file = GdiFileSyncService.File.newBuilder().buildWith {
                    id = GdiFileSyncService.FileId.newBuilder()
                        .setId1(NEW_FILE_ID)
                        .setId2(clientId)
                        .build()
                    type = GdiFileSyncService.FileType.newBuilder()
                        .setUnk1(0)
                        .setName(typeName)
                        .build()
                    this.size = size
                }
                unk2 = ACK_CONSTANT
                unk3 = 0
                unk4 = 15
            }
        }
    }

    /**
     * The watch has finished with a transfer, and is waiting to be told we heard.
     *
     * This is a request, not a notification: it arrives as a PROTOBUF_REQUEST
     * and the watch holds the transfer open until a PROTOBUF_RESPONSE comes back
     * on the same request id. Garmin Connect answers every one — twenty-two of
     * them in the single sync that was captured — and Tracks answered none, so
     * every file Tracks handed over stayed in limbo no matter how correct its
     * contents were. See AGENTS.md.
     */
    private fun handleTransferComplete(
        complete: GdiFileSyncService.TransferComplete,
    ): GdiFileSyncService.FileSyncService {
        // Logged in full because the trailing fields are still unread: Connect's
        // successful uploads carry none of them and the one Tracks upload that
        // died part-way carried four. The next capture of a transfer that works
        // is what tells us which is which, so leave the evidence in the log.
        LOG.debug("Transfer complete on handle {}: {}", complete.handle, complete)
        // Queued, not run: the answer below still has to be written. See
        // flushAnsweredTransfers.
        answeredButUnsent.add(complete.handle)

        // The last transfer of a burst is answered with an *empty*
        // TransferCompleteResponse -- six bytes on the wire, da 02 03 aa 01 00,
        // against the usual eight with unk1 = 34.
        //
        // Garmin Connect does this at the end of every burst in both captured
        // calendar pushes, seven times out of seven, and the watch replies
        // file_sync_service.20 {1:1} within 70-90 ms. A bare 20 {1:1} never
        // appears without it. Tracks always sent unk1 = 34, so the watch never
        // heard the session close and fell back to an idle timeout instead --
        // measured in our own logs at 30.21, 30.87, 30.27 and 30.96 seconds
        // after the last answered transfer.
        //
        // Whether an explicit close and a timed-out one differ to the watch is
        // exactly what this is here to find out: a timeout may well be treated
        // as an abandoned session, with whatever it was holding committed
        // file-by-file or dropped rather than resolved together. Unproven. The
        // cheap half of the check does not depend on the calendar at all -- if
        // 20 {1:1} arrives in ~100 ms instead of ~30 s, the message was
        // understood. See AGENTS.md.
        val endsBurst = deviceSupport.endsUploadBurst(complete.handle)
        if (endsBurst) {
            LOG.debug("Handle {} ends the burst; answering with an empty response", complete.handle)
        }
        return GdiFileSyncService.FileSyncService.newBuilder().buildWith {
            transferCompleteResponse = if (endsBurst) {
                GdiFileSyncService.TransferCompleteResponse.getDefaultInstance()
            } else {
                GdiFileSyncService.TransferCompleteResponse.newBuilder()
                    .setUnk1(ACK_CONSTANT)
                    .build()
            }
        }
    }

    private fun handleUploadResponse(
        response: GdiFileSyncService.UploadResponse,
    ): GdiFileSyncService.FileSyncService? {
        val listener = uploadListener
        uploadListener = null
        if (response.status != 0) {
            LOG.warn("Watch refused the upload with status {}", response.status)
            listener?.onUploadAccepted(-1)
            return null
        }
        LOG.debug("Watch accepted the upload on transfer handle {}", response.handle)
        listener?.onUploadAccepted(response.handle)
        return null
    }

    fun requestFileList(): GdiFileSyncService.FileSyncService {
        LOG.debug("Requesting file list starting at page {} (cursorId={})", nextPageId, cursorId)

        val fileListRequestBuilder = GdiFileSyncService.FileListRequest.newBuilder().apply {
            // Exclusion flags? If we omit this, it sends back already synced files going back months.
            // Which is exactly what a recovery listing wants.
            if (recovering) return@apply
            flags1 = GdiFileSyncService.FileId.newBuilder().setId1(FLAGS_SYNCED).setId2(FLAGS_SYNCED).build()
            flags2 = GdiFileSyncService.FileId.newBuilder().setId1(FLAGS_SYNCED).setId2(FLAGS_SYNCED).build()
        }

        val currentcursorId = cursorId
        if (currentcursorId != null) {
            fileListRequestBuilder.cursorId = currentcursorId
        } else {
            nextPageId?.let { fileListRequestBuilder.startPageId = it }
        }

        return GdiFileSyncService.FileSyncService.newBuilder().buildWith {
            fileListRequest = fileListRequestBuilder.build()
        }
    }

    fun requestFile(fileToRequest: GdiFileSyncService.File): GdiFileSyncService.FileSyncService {
        LOG.debug(
            "Requesting file: {}/{} ({})",
            fileToRequest.id.id1,
            fileToRequest.id.id2,
            fileToRequest.type.name
        )
        return GdiFileSyncService.FileSyncService.newBuilder().buildWith {
            fileRequest = GdiFileSyncService.FileRequest.newBuilder().buildWith {
                file = fileToRequest
                unk2 = 24
                unk3 = 0
                unk4 = 0
                unk5 = 15
            }
        }
    }

    fun markSynced(syncFile: GdiFileSyncService.File): GdiFileSyncService.FileSyncService? {
        // Checked unconditionally. Upstream ran this only when "fetch unknown
        // files" was on, which was sound while that was the only way a
        // non-pullable file could be downloaded at all. Tracks can now ask for
        // courses and locations specifically (FileType.FILETYPE.setAlsoPull),
        // so a file the user PUT on the watch can reach this method with that
        // preference off — and marking one synced is how a watch is told it may
        // reclaim the space. Deliberately tested against `pull` rather than
        // `shouldPull`: the widening says what may be *read*, never what may be
        // discarded.
        if (syncFile.type.name == null) {
            LOG.warn("Will not mark {}/{} as synced - unknown type", syncFile.id.id1, syncFile.id.id2)
            return null
        }
        val fileType = FileType.FILETYPE.findByTypeName(syncFile.type.name);
        if (fileType == null || !fileType.pull) {
            LOG.warn(
                "Will not mark {}/{} ({}) as synced - not a file to process",
                syncFile.id.id1,
                syncFile.id.id2,
                syncFile.type.name
            )
            return null
        }

        return GdiFileSyncService.FileSyncService.newBuilder().buildWith {
            fileSetFlags = GdiFileSyncService.FileSetFlags.newBuilder().buildWith {
                file = syncFile.id
                flags = GdiFileSyncService.FileId.newBuilder().setId1(FLAGS_SYNCED).setId2(FLAGS_SYNCED).build()
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FileSyncServiceHandler::class.java)

        private const val FLAGS_SYNCED = 42405L

        /**
         * The number Garmin Connect puts in UploadRequest.unk2 and in the answer
         * to every TransferComplete. Its meaning is unread; what is established
         * is that Connect always sends exactly this and the watch is content.
         */
        private const val ACK_CONSTANT = 34

        /** id1 sentinel meaning "new file, watch assigns the id". */
        private const val NEW_FILE_ID = -1L // 0xFFFFFFFFFFFFFFFF

        private var lastClientFileId = System.currentTimeMillis() / 1000L

        @Synchronized
        private fun nextClientFileId(): Long = ++lastClientFileId
    }
}
