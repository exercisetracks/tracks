package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.status;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.MessageWriter;

public class FitDefinitionStatusMessage extends GFDIStatusMessage {

    private final Status status;
    private final FitDefinitionStatusCode fitDefinitionStatusCode;
    private final boolean sendOutgoing;

    public FitDefinitionStatusMessage(GarminMessage garminMessage, Status status, FitDefinitionStatusCode fitDefinitionStatusCode, boolean sendOutgoing) {
        this.garminMessage = garminMessage;
        this.status = status;
        this.fitDefinitionStatusCode = fitDefinitionStatusCode;
        this.sendOutgoing = sendOutgoing;
    }

    public static FitDefinitionStatusMessage parseIncoming(MessageReader reader, GarminMessage garminMessage) {
        final Status status = Status.fromCode(reader.readByte());
        // A watch that rejects the definition outright answers with the status alone and no
        // definition status code (a fenix 6X sends LENGTH_ERROR this way). Not applied, then.
        if (reader.remaining() == 0) {
            LOG.warn("FIT DEFINITION RETURNED {} with no definition status code", status);
            return new FitDefinitionStatusMessage(garminMessage, status, null, false);
        }
        final int fitDefinitionStatusCodeByte = reader.readByte();
        final FitDefinitionStatusCode fitDefinitionStatusCode = FitDefinitionStatusCode.fromCode(fitDefinitionStatusCodeByte);
        if (fitDefinitionStatusCode == null) {
            LOG.warn("Unknown fit definition status code {}", fitDefinitionStatusCodeByte);
            return null;
        }
        switch (fitDefinitionStatusCode) {
            case APPLIED:
                LOG.info("FIT DEFINITION RETURNED STATUS: {}", fitDefinitionStatusCode.name());
                break;
            default:
                LOG.warn("FIT DEFINITION RETURNED STATUS: {}", fitDefinitionStatusCode.name());
        }
        return new FitDefinitionStatusMessage(garminMessage, status, fitDefinitionStatusCode, false);
    }

    /** Null when the watch sent no definition status code: the definition was not applied. */
    @Nullable
    public FitDefinitionStatusCode getFitDefinitionStatusCode() {
        return fitDefinitionStatusCode;
    }

    @Override
    protected boolean generateOutgoing() {
        if (!sendOutgoing) {
            return false; // a status the watch sent: nothing to write, and it may have no code
        }
        final MessageWriter writer = new MessageWriter(response);
        writer.writeShort(0); // packet size will be filled below
        writer.writeShort(GarminMessage.RESPONSE.getId());
        writer.writeShort(garminMessage.getId());
        writer.writeByte(status.ordinal());
        writer.writeByte(fitDefinitionStatusCode.ordinal());
        return sendOutgoing;
    }

    public enum FitDefinitionStatusCode {
        APPLIED,
        NOT_UNIQUE,
        OUT_OF_RANGE,
        NOT_READY,
        ;

        @Nullable
        public static FitDefinitionStatusCode fromCode(final int code) {
            for (final FitDefinitionStatusCode fitDefinitionStatusCode : FitDefinitionStatusCode.values()) {
                if (fitDefinitionStatusCode.ordinal() == code) {
                    return fitDefinitionStatusCode;
                }
            }
            return null;
        }
    }
}
