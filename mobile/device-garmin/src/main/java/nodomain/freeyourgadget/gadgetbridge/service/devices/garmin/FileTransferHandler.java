/*  Copyright (C) 2024-2025 Daniele Gobbetti, José Rebelo, Thomas Kuehne

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.FileDownloadedDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.CreateFileMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.DownloadRequestMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.FileTransferDataMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.FilterMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.SynchronizationMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.SystemEventMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.UploadRequestMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.CreateFileStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.DownloadRequestStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.FileTransferDataStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.FilterStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status.UploadRequestStatusMessage;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FileTransferHandler implements MessageHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FileTransferHandler.class);
    private final GarminSupport deviceSupport;
    private final Download download;
    private final Upload upload;
    private int maxPacketSize = 375;

    // The biggest upload message we dare send at the current BLE MTU, so one
    // FileTransferDataMessage always crosses the link inside the watch's own
    // per-message timeout. Unbounded until an MTU is known — see setLinkMtu.
    //
    // Why this exists: an app (.prg) still goes over this legacy upload path,
    // and on a fēnix 6X that negotiated MTU 23 — 18 payload bytes per BLE
    // write — a full 4000-byte message needs ~220 writes and roughly 20 s to
    // arrive, which is exactly when the watch abandons the transfer. Nothing
    // else uses this path any more: FIT files go over file-sync. A healthy MTU
    // leaves the cap above maxPacketSize, so this changes nothing there.
    private int maxUploadMessageSize = Integer.MAX_VALUE;

    // ~8 s of link time at any MTU, comfortably under the watch's timeout:
    // this many BLE fragments per upload message, times the fragment payload.
    private static final int MAX_FRAGMENTS_PER_UPLOAD_MESSAGE = 96;
    // But never so small that a real MTU crawls — a floor of one small message.
    private static final int MIN_UPLOAD_MESSAGE = 256;

    public FileTransferHandler(GarminSupport deviceSupport) {
        this.deviceSupport = deviceSupport;
        this.download = new Download();
        this.upload = new Upload();
    }

    public boolean isDownloading() {
        return download.getCurrentlyDownloading() != null;
    }

    public boolean isUploading() {
        return upload.getCurrentlyUploading() != null;
    }

    public void setMaxPacketSize(final int maxPacketSize) {
        this.maxPacketSize = maxPacketSize;
    }

    /**
     * Bound the upload message size to what the link can carry in time.
     *
     * The BLE write payload is roughly MTU minus the ATT (3) and MLR (2)
     * headers; an upload message that spans more than a few dozen of those
     * risks the watch's per-message timeout. Called on every MTU change.
     */
    public void setLinkMtu(final int mtu) {
        final int linkChunk = Math.max(1, mtu - 5);
        this.maxUploadMessageSize = Math.max(MIN_UPLOAD_MESSAGE, linkChunk * MAX_FRAGMENTS_PER_UPLOAD_MESSAGE);
    }

    @Override
    public GFDIMessage handle(GFDIMessage message) {
        if (message instanceof DownloadRequestStatusMessage)
            download.processDownloadRequestStatusMessage((DownloadRequestStatusMessage) message);
        else if (message instanceof FileTransferDataMessage)
            download.processDownloadChunkedMessage((FileTransferDataMessage) message);
        else if (message instanceof CreateFileStatusMessage)
            return upload.setCreateFileStatusMessage((CreateFileStatusMessage) message);
        else if (message instanceof UploadRequestStatusMessage)
            return upload.setUploadRequestStatusMessage((UploadRequestStatusMessage) message);
        else if (message instanceof FileTransferDataStatusMessage)
            return upload.processUploadProgress((FileTransferDataStatusMessage) message);
        else if (message instanceof SynchronizationMessage)
            return processSynchronizationMessage((SynchronizationMessage) message);
        else if (message instanceof FilterStatusMessage)
            return initiateDownload();
        return null;
    }

    private FilterMessage processSynchronizationMessage(SynchronizationMessage message) {
        if (message.shouldProceed())
            return new FilterMessage();

        return null;
    }

    public DownloadRequestMessage downloadDirectoryEntry(DirectoryEntry directoryEntry) {
        download.setCurrentlyDownloading(new FileFragment(directoryEntry));
        return new DownloadRequestMessage(directoryEntry.getFileIndex(), 0, DownloadRequestMessage.REQUEST_TYPE.NEW, 0, 0);
    }

    public DownloadRequestMessage initiateDownload() {
        download.setCurrentlyDownloading(new FileFragment(new DirectoryEntry(0, FileType.FILETYPE.DIRECTORY, 0, 0, 0, 0, null)));
        return new DownloadRequestMessage(0, 0, DownloadRequestMessage.REQUEST_TYPE.NEW, 0, 0);
    }

    public DirectoryEntry getDeviceXmlDirectoryEntry() {
        return new DirectoryEntry(0xFFFD, FileType.FILETYPE.DEVICE_XML, 0xFFFD, 0, 0, 0, new Date());
    }

//    public DownloadRequestMessage downloadSettings() {
//        download.setCurrentlyDownloading(new FileFragment(new DirectoryEntry(0, FileType.FILETYPE.SETTINGS, 0, 0, 0, 0, null)));
//        return new DownloadRequestMessage(0, 0, DownloadRequestMessage.REQUEST_TYPE.NEW, 0, 0);
//    }
//
public CreateFileMessage initiateUpload(byte[] fileAsByteArray, FileType.FILETYPE filetype) {
    upload.setCurrentlyUploading(new FileFragment(new DirectoryEntry(0, filetype, 0, 0, 0, fileAsByteArray.length, null), fileAsByteArray));
    return new CreateFileMessage(fileAsByteArray.length, filetype);
}

    public class Download {
        private FileFragment currentlyDownloading;

        public FileFragment getCurrentlyDownloading() {
            return currentlyDownloading;
        }

        public void setCurrentlyDownloading(FileFragment currentlyDownloading) {
            this.currentlyDownloading = currentlyDownloading;
        }

        private void processDownloadChunkedMessage(FileTransferDataMessage fileTransferDataMessage) {
            if (!isDownloading())
                throw new IllegalStateException("Received file transfer of unknown file");

            currentlyDownloading.append(fileTransferDataMessage);
            if (!currentlyDownloading.dataHolder.hasRemaining())
                processCompleteDownload();
            else
                deviceSupport.onFileDownloadProgress(currentlyDownloading.dataHolder.position());
        }

        private void processCompleteDownload() {
            currentlyDownloading.dataHolder.flip();

            if (FileType.FILETYPE.DIRECTORY.equals(currentlyDownloading.directoryEntry.filetype)) { //is a directory
                parseDirectoryEntries();
            } else {
                saveFileToExternalStorage();
            }

            currentlyDownloading = null;
        }

        public void processDownloadRequestStatusMessage(DownloadRequestStatusMessage downloadRequestStatusMessage) {
            if (null == currentlyDownloading)
                throw new IllegalStateException("Received file transfer of unknown file");
            if (downloadRequestStatusMessage.canProceed())
                currentlyDownloading.setSize(downloadRequestStatusMessage);
            else {
                // Signal to the support class that the download failed so it can also continue to the next one
                FileDownloadedDeviceEvent fileDownloadedDeviceEvent = new FileDownloadedDeviceEvent();
                fileDownloadedDeviceEvent.directoryEntry = currentlyDownloading.directoryEntry;
                fileDownloadedDeviceEvent.success = false;
                deviceSupport.evaluateGBDeviceEvent(fileDownloadedDeviceEvent);
                currentlyDownloading = null;
            }
        }

        private void saveFileToExternalStorage() {
            File deviceDir;
            File outputFile;
            try {
                deviceDir = deviceSupport.getWritableExportDirectory();
                outputFile = new File(deviceDir, currentlyDownloading.directoryEntry.getOutputPath());
                final File parentFile = outputFile.getParentFile();
                parentFile.mkdirs();
                FileUtils.copyStreamToFile(new ByteArrayInputStream(currentlyDownloading.dataHolder.array()), outputFile);
                final Date fileDate = currentlyDownloading.directoryEntry.fileDate;
                if (fileDate != null) {
                    outputFile.setLastModified(fileDate.getTime());
                }
            } catch (final IOException e) {
                LOG.error("Failed to save file", e);
                return; // do not signal file as saved
            }

            FileDownloadedDeviceEvent fileDownloadedDeviceEvent = new FileDownloadedDeviceEvent();
            fileDownloadedDeviceEvent.directoryEntry = currentlyDownloading.directoryEntry;
            fileDownloadedDeviceEvent.localPath = outputFile.getAbsolutePath();
            deviceSupport.evaluateGBDeviceEvent(fileDownloadedDeviceEvent);
        }

        private void parseDirectoryEntries() {
            LOG.debug("Parsing directory entries for {}", currentlyDownloading.directoryEntry);

            // ---- Tracks-local patch, not upstream ----
            if (deviceSupport.newSyncProtocol()) {
                // Signal to the support class that we got the directory - but ignore the entries
                // Well request them using the new sync protocol
                deviceSupport.addFileToDownloadList(currentlyDownloading.directoryEntry);
                return;
            }
            // ---- end Tracks-local patch ----

            if ((currentlyDownloading.getDataSize() % 16) != 0)
                throw new IllegalArgumentException("Invalid directory data length");
            final GarminByteBufferReader reader = new GarminByteBufferReader(currentlyDownloading.dataHolder.array());
            reader.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            final boolean fetchUnknownFiles = deviceSupport.getDevicePrefs().getFetchUnknownFiles();
            while (reader.remaining() > 0) {
                final int fileIndex = reader.readShort();//2
                final int fileDataType = reader.readByte();//3
                final int fileSubType = reader.readByte();//4
                final FileType.FILETYPE filetype = FileType.FILETYPE.fromDataTypeSubType(fileDataType, fileSubType);
                final int fileNumber = reader.readShort();//6
                final int specificFlags = reader.readByte();//7
                final int fileFlags = reader.readByte();//8
                final int fileSize = reader.readInt();//12
                // Wire 0 is the watch's "no date" sentinel; surface
                // it as null so {@link DirectoryEntry#fileDate} stays
                // unambiguous (no real activity recorded at the
                // Garmin epoch).
                final int wireTimestamp = reader.readInt();//16
                final Date fileDate = wireTimestamp == 0 ? null
                        : new Date(GarminTimeUtils.garminTimestampToJavaMillis(wireTimestamp));
                final DirectoryEntry directoryEntry = new DirectoryEntry(fileIndex, filetype, fileNumber, specificFlags, fileFlags, fileSize, fileDate);
                final FileType.FILETYPE fileType = directoryEntry.getFiletype();
                if (filetype == null) {
                    // discard unsupported files
                    LOG.warn("Unsupported directory entry of type {}/{}: {}", fileDataType, fileSubType, directoryEntry);
                    continue;
                }
                if (fileType != FileType.FILETYPE.DIRECTORY
                        && !FileType.FILETYPE.shouldPull(filetype)
                        && !fetchUnknownFiles) {
                    LOG.debug("Skipping directory entry: {}", directoryEntry);
                    continue;
                }
                if (fileIndex == 0 && fileDataType == 0 && fileSubType == 0 && fileNumber == 0 && specificFlags == 0 && fileFlags == 0 && fileSize == 0) {
                    LOG.warn("Ignoring {} to avoid infinite loop", directoryEntry);
                    continue;
                }
                LOG.debug("Queueing {} for download", directoryEntry);
                deviceSupport.addFileToDownloadList(directoryEntry);
            }
            currentlyDownloading = null;
        }

    }

    private void updateUploadProgress(final int percentage) {
        final LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(GBApplication.getContext());

        if (percentage < 0) {
            // Failure
            GB.updateInstallNotification(GBApplication.getContext().getString(R.string.installation_failed_), false, 100, GBApplication.getContext());
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_INFO_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, ""));
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, GBApplication.getContext().getString(R.string.installation_failed_)));
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_FINISHED));
        } else if (percentage >= 100) {
            // Success
            GB.updateInstallNotification(GBApplication.getContext().getString(R.string.installation_successful), false, 100, GBApplication.getContext());

            deviceSupport.getDevice().sendDeviceUpdateIntent(deviceSupport.getContext());

            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_INFO_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, ""));
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, GBApplication.getContext().getString(R.string.installation_successful)));
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_FINISHED));
        } else {
            // In Progress
            GB.updateInstallNotification(GBApplication.getContext().getString(R.string.uploading), true, percentage, GBApplication.getContext());

            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_INFO_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, ""));
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_TEXT).putExtra(GB.DISPLAY_MESSAGE_MESSAGE, GBApplication.getContext().getString(R.string.uploading)));
            broadcastManager.sendBroadcast(new Intent(GB.ACTION_SET_PROGRESS_BAR).putExtra(GB.PROGRESS_BAR_PROGRESS, percentage));
        }
    }

    /**
     * Why the watch last refused an upload, and at which step, or null.
     *
     * A string rather than one of the enums because there are three of them —
     * create, upload request, and data transfer each answer with their own —
     * and which step failed is as much of the answer as the code is. "The watch
     * rejected the file" was true at any of them and useful at none.
     */
    private volatile String lastRefusal;

    public String getLastRefusal() {
        return lastRefusal;
    }

    public void clearLastRefusal() {
        lastRefusal = null;
    }

    public class Upload {
        private FileFragment currentlyUploading;

        private UploadRequestMessage setCreateFileStatusMessage(CreateFileStatusMessage createFileStatusMessage) {
            if (createFileStatusMessage.canProceed()) {
                LOG.info("SENDING UPLOAD FILE");
                if (currentlyUploading.directoryEntry.filetype != FileType.FILETYPE.SETTINGS) {
                    updateUploadProgress(0);
                }
                return new UploadRequestMessage(createFileStatusMessage.getFileIndex(), currentlyUploading.getDataSize());
            } else {
                // Recorded and *reported*, both of which upstream omits. The
                // reason is the only thing that tells a permanent refusal from
                // a transient one; and without the progress update nothing ever
                // completes the caller's signal, so a refused upload used to
                // hang until its timeout rather than fail with an answer in
                // hand.
                lastRefusal = "creating the file: " + createFileStatusMessage.getCreateStatus();
                LOG.warn("Cannot proceed with upload: {}", lastRefusal);
                this.currentlyUploading = null;
                updateUploadProgress(-1);
            }
            return null;
        }

        private FileTransferDataMessage setUploadRequestStatusMessage(UploadRequestStatusMessage uploadRequestStatusMessage) {
            if (null == currentlyUploading)
                throw new IllegalStateException("Received upload request status transfer of unknown file");
            if (uploadRequestStatusMessage.canProceed()) {
                if (uploadRequestStatusMessage.getDataOffset() != currentlyUploading.dataHolder.position())
                    throw new IllegalStateException("Received upload request with unaligned offset");
                return currentlyUploading.take();
            } else {
                lastRefusal = "starting the transfer: " + uploadRequestStatusMessage.getUploadStatus();
                LOG.warn("Cannot proceed with upload: {}", lastRefusal);
                if (currentlyUploading.directoryEntry.filetype != FileType.FILETYPE.SETTINGS) {
                    updateUploadProgress(-1);
                }
                this.currentlyUploading = null;
            }
            return null;
        }

        private GFDIMessage processUploadProgress(FileTransferDataStatusMessage fileTransferDataStatusMessage) {
            final boolean showNotification = currentlyUploading.directoryEntry.filetype != FileType.FILETYPE.SETTINGS;

            if (currentlyUploading.getDataSize() <= fileTransferDataStatusMessage.getDataOffset()) {
                this.currentlyUploading = null;
                LOG.info("SENDING SYNC COMPLETE!!!");
                if (showNotification) {
                    updateUploadProgress(100);
                }

                return new SystemEventMessage(SystemEventMessage.GarminSystemEventType.SYNC_COMPLETE, 0);
            } else {
                if (fileTransferDataStatusMessage.canProceed()) {
                    LOG.info("SENDING NEXT CHUNK!!!");
                    if (showNotification) {
                        updateUploadProgress((100 * currentlyUploading.dataHolder.position()) / currentlyUploading.dataHolder.limit());
                    }
                    if (fileTransferDataStatusMessage.getDataOffset() != currentlyUploading.dataHolder.position())
                        throw new IllegalStateException("Received file transfer status with unaligned offset");
                    return currentlyUploading.take();
                } else {
                    lastRefusal = "sending the file: "
                            + fileTransferDataStatusMessage.getTransferStatus();
                    LOG.warn("Cannot proceed with upload: {}", lastRefusal);
                    updateUploadProgress(-1);
                    this.currentlyUploading = null;
                }

            }
            return null;
        }

        public FileFragment getCurrentlyUploading() {
            return this.currentlyUploading;
        }

        public void setCurrentlyUploading(FileFragment currentlyUploading) {
            this.currentlyUploading = currentlyUploading;
        }

    }

    public class FileFragment {
        private final DirectoryEntry directoryEntry;
        private int dataSize;
        private ByteBuffer dataHolder;
        private int runningCrc;

        FileFragment(DirectoryEntry directoryEntry) {
            this.directoryEntry = directoryEntry;
            this.setRunningCrc(0);
        }

        FileFragment(DirectoryEntry directoryEntry, byte[] contents) {
            this.directoryEntry = directoryEntry;
            this.setDataSize(contents.length);
            this.dataHolder = ByteBuffer.wrap(contents);
            this.dataHolder.flip(); //we'll be only reading from here on
            this.dataHolder.compact();
            this.setRunningCrc(0);
        }

        private void setSize(DownloadRequestStatusMessage downloadRequestStatusMessage) {
            if (0 != getDataSize())
                throw new IllegalStateException("Data size already set");

            this.setDataSize(downloadRequestStatusMessage.getMaxFileSize());
            this.dataHolder = ByteBuffer.allocate(getDataSize());
        }

        private void append(FileTransferDataMessage fileTransferDataMessage) {
            if (fileTransferDataMessage.getDataOffset() != dataHolder.position())
                throw new IllegalStateException("Received message that was already received");

            final int dataCrc = ChecksumCalculator.computeCrc(getRunningCrc(), fileTransferDataMessage.getMessage(), 0, fileTransferDataMessage.getMessage().length);
            if (fileTransferDataMessage.getCrc() != dataCrc)
                throw new IllegalStateException("Received message with invalid CRC");
            setRunningCrc(dataCrc);

            this.dataHolder.put(fileTransferDataMessage.getMessage());
        }

        private FileTransferDataMessage take() {
            final int currentOffset = this.dataHolder.position();
            final int budget = Math.min(maxPacketSize - 13, maxUploadMessageSize);
            final byte[] chunk = new byte[Math.min(this.dataHolder.remaining(), budget)]; //actual payload in FileTransferDataMessage
            this.dataHolder.get(chunk);
            setRunningCrc(ChecksumCalculator.computeCrc(getRunningCrc(), chunk, 0, chunk.length));
            return new FileTransferDataMessage(chunk, currentOffset, getRunningCrc());
        }

        private int getDataSize() {
            return dataSize;
        }

        private void setDataSize(int dataSize) {
            this.dataSize = dataSize;
        }

        private int getRunningCrc() {
            return runningCrc;
        }

        private void setRunningCrc(int runningCrc) {
            this.runningCrc = runningCrc;
        }
    }

    public static class DirectoryEntry {
        private final int fileIndex;
        private final FileType.FILETYPE filetype;
        private final int fileNumber;
        private final int specificFlags;
        private final int fileFlags;
        private final int fileSize;
        /** Null for entries whose wire timestamp was the watch's
         *  "no date" sentinel (0). Drives the missing-date fallback
         *  shape in {@link #getOutputPath}. */
        @Nullable
        private final Date fileDate;

        public DirectoryEntry(int fileIndex, FileType.FILETYPE filetype, int fileNumber, int specificFlags, int fileFlags, int fileSize, @Nullable Date fileDate) {
            this.fileIndex = fileIndex;
            this.filetype = filetype;
            this.fileNumber = fileNumber;
            this.specificFlags = specificFlags;
            this.fileFlags = fileFlags;
            this.fileSize = fileSize;
            this.fileDate = fileDate;
        }

        public int getFileIndex() {
            return fileIndex;
        }

        public FileType.FILETYPE getFiletype() {
            return filetype;
        }

        @Nullable
        public Date getFileDate() {
            return fileDate;
        }

        public int getFileSize() {
            return fileSize;
        }

        /**
         * Builds the output path.
         * Format: [FILE_TYPE]/[YEAR]/[FILE_TYPE]_[yyyy-MM-dd_HH-mm-ss]_[INDEX].[fit/bin]
         * (or [FILE_TYPE]/[FILE_TYPE]_[INDEX].[fit/bin] when the file has no valid date)
         */
        public String getOutputPath() {
            return GarminUtils.buildExportPath(getFiletype(), fileDate,
                    String.valueOf(getFileIndex()),
                    getFiletype().isFitFile() ? "fit" : "bin");
        }

        /** Just the basename of {@link #getOutputPath()}. */
        public String getFileName() {
            final String path = getOutputPath();
            return path.substring(path.lastIndexOf(File.separator) + 1);
        }

        @NonNull
        @Override
        public String toString() {
            return "DirectoryEntry{" +
                    "fileIndex=" + fileIndex +
                    ", fileType=" + filetype +
                    ", fileNumber=" + fileNumber +
                    ", specificFlags=" + specificFlags +
                    ", fileFlags=" + fileFlags +
                    ", fileSize=" + fileSize +
                    ", fileDate=" + fileDate +
                    '}';
        }
    }
}
