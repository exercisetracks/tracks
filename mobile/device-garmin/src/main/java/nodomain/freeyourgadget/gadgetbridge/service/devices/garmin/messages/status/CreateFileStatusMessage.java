package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status;

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType;

public class CreateFileStatusMessage extends GFDIStatusMessage {
    private final Status status;
    private final CreateStatus createStatus;
    private final FileType.FILETYPE filetype;
    private final int fileIndex;
    private final int fileNumber;

    public CreateFileStatusMessage(GarminMessage garminMessage, Status status, CreateStatus createStatus, int fileIndex, FileType.FILETYPE filetype, int fileNumber) {
        this.garminMessage = garminMessage;
        this.status = status;
        this.createStatus = createStatus;
        this.fileIndex = fileIndex;
        this.filetype = filetype;
        this.fileNumber = fileNumber;
    }

    public static CreateFileStatusMessage parseIncoming(MessageReader reader, GarminMessage garminMessage) {
        final Status status = Status.fromCode(reader.readByte());

        if (!status.equals(Status.ACK)) {
            return null;
        }
        final CreateStatus createStatus = CreateStatus.fromId(reader.readByte());
        int fileIndex = reader.readShort();
        final int dataType = reader.readByte();
        final int subType = reader.readByte();
        final FileType.FILETYPE filetype = FileType.FILETYPE.fromDataTypeSubType(dataType, subType);
        final int fileNumber = reader.readShort();
        if (!CreateStatus.OK.equals(createStatus)) {
            LOG.warn("Received {} / {} for message {}", status, createStatus, garminMessage);
        } else {
            LOG.info("Received {} / {} for message {}", status, createStatus, garminMessage);
        }
        return new CreateFileStatusMessage(garminMessage, status, createStatus, fileIndex, filetype, fileNumber);
    }

    /**
     * Why the watch refused, in its own words.
     *
     * Tracks surfaces this. Upstream logs it and drops it, which is reasonable
     * for a client that only ever pushes workouts; it is not reasonable when
     * the answer distinguishes "this watch cannot store that kind of file at
     * all" from "it is full" — one is permanent and one clears itself, and
     * "the watch rejected the file" says neither.
     */
    public CreateStatus getCreateStatus() {
        return createStatus;
    }

    public int getFileIndex() {
        return fileIndex;
    }

    public int getFileNumber() {
        return fileNumber;
    }

    public boolean canProceed() {
        return status.equals(Status.ACK) && createStatus.equals(CreateStatus.OK);
    }

    public enum CreateStatus {
        OK,
        DUPLICATE,
        NO_SPACE,
        UNSUPPORTED,
        NO_SLOTS,
        NO_SPACE_FOR_TYPE,
        ;

        public static CreateStatus fromId(int id) {
            for (CreateStatus createStatus :
                    CreateStatus.values()) {
                if (createStatus.ordinal() == id) {
                    return createStatus;
                }
            }
            return null;
        }
    }
}
