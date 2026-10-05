package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class FileType {
    //common
    //128/4: FIT_TYPE_4, -> garmin/activity
    //128/32: FIT_TYPE_32,  -> garmin/monitor
    //128/44: FIT_TYPE_44, ->garmin/metrics
    //128/41: FIT_TYPE_41, ->garmin/chnglog
    //128/49: FIT_TYPE_49, -> garmin/sleep
    //255/245: ErrorShutdownReports,

    //Specific Instinct 2S:
    //128/38: FIT_TYPE_38, -> garmin/SCORCRDS
    //255/248: KPI,
    //128/58: FIT_TYPE_58, -> outputFromUnit garmin/device????
    //255/247: ULFLogs,
    //128/68: FIT_TYPE_68, -> garmin/HRVSTATUS
    //128/70: FIT_TYPE_70, -> garmin/HSA
    //128/72: FIT_TYPE_72, -> garmin/FBTBACKUP
    //128/74: FIT_TYPE_74


    private final FILETYPE fileType;
    private final String garminDeviceFileType;

    public FileType(int fileDataType, int fileSubType, String garminDeviceFileType) {
        this.fileType = FILETYPE.fromDataTypeSubType(fileDataType, fileSubType);
        this.garminDeviceFileType = garminDeviceFileType;
    }

    public FILETYPE getFileType() {
        return fileType;
    }

    // ---- Tracks-local patch, not upstream: see vendor/gadgetbridge.manifest ----
    // The watch's own name for this type ("FIT_TYPE_7", "ErrorShutdownReports")
    // is parsed by SupportedFileTypesStatusMessage and then never read again
    // upstream. It is the only place the numeric type/subtype pair is tied back
    // to the names GarminDevice.xml uses, which is what a push has to reason
    // about when deciding whether a file type is even accepted as input.
    public String getGarminDeviceFileType() {
        return garminDeviceFileType;
    }
    // ---- end Tracks-local patch ----

    public enum FILETYPE {
        // virtual/undocumented
        DIRECTORY(0, 0), // root directory is hardcoded: fileIndex = 0x0000 / 0
        UNKNOWN_1_0(1, 0), // venu 3, fileIndex=4096
        DEVICE_XML(8, 255), // hardcoded: fileIndex = 0xFFFD / 65533

        // fit files
        DEVICE_1(128, 1), // just "-"
        SETTINGS(128, 2),
        SPORTS(128, 3),
        ACTIVITY(128, 4, true),
        WORKOUTS(128, 5),
        COURSES(128, 6),
        SCHEDULES(128, 7),
        LOCATION(128, 8),
        WEIGHT(128, 9, true),
        TOTALS(128, 10),
        GOALS(128, 11),
        MAP(128, 12),
        DEBUG(128, 13),
        BLOOD_PRESSURE(128, 14),
        MONITOR_A(128, 15, true),
        FIT_TYPE_16(128, 16),
        FIT_TYPE_17(128, 17),
        FIT_TYPE_18(128, 18),
        FIT_TYPE_19(128, 19),
        SUMMARY(128, 20),
        GLUCOSE(128, 21),
        TRACKING_RECORDS(128, 22),
        TRACKING_EVENTS(128, 23),
        FIT_TYPE_24(128, 24),
        VECTOR(128, 25),
        FIT_TYPE_26(128, 26),
        FIT_TYPE_27(128, 27),
        MONITOR_DAILY(128, 28, true),
        RECORDS(128, 29),
        ALERT(128, 30),
        UNKNOWN_31(128, 31), // sent by HRM Pro Plus
        MONITOR(128, 32, true),
        MLT_SPORT(128, 33),
        SEGMENTS(128, 34),
        SEGMENT_LIST(128, 35, true),
        GOLF(128, 36),
        CLUBS(128, 37),
        SCORE(128, 38, true),
        ADJUSTMENTS(128, 39),
        HMD(128, 40),
        CHANGELOG(128, 41, true),
        FIT_TYPE_42(128, 42),
        FIT_TYPE_43(128, 43),
        METRICS(128, 44, true),
        BAT_SWING(128, 45),
        ROSTER(128, 46),
        DIVE_PLAN(128, 47),
        HSA_DATA(128, 48),
        SLEEP(128, 49, true),
        SOFTWARE(128, 50),
        CHALLENGE_RESULT(128, 51),
        USER_BEHAVIOR_LOG(128, 52, true),
        CHRONO_ROUND(128, 53),
        CHRONO_SHOT(128, 54), // Garmin Xero C1 Pro Chronograph
        CHRONO_SCORECARD(128, 55),
        PACE_BANDS(128, 56),
        SPORTS_BACKUP(128, 57, true), // Garmin Edge 530 - #5265, Garmin Edge 830
        DEVICE_58(128, 58, true), // just "Device" in Fenix 7s
        MUSCLE_MAP(128, 59),
        RUNNING_TRACK(128, 60),
        ECG(128, 61, true),
        BENCHMARK(128, 62),
        POWER_GUIDANCE(128, 63),
        FIT_TYPE_64(128, 64),
        CALENDAR(128, 65),
        FIT_TYPE_66(128, 66, true),
        FIT_TYPE_67(128, 67),
        HRV_STATUS(128, 68, true),
        HSA(128, 70, true),
        COM_ACT(128, 71, true),
        FBT_BACKUP(128, 72, true),
        SKIN_TEMP(128, 73, true),
        FBT_PTD_BACKUP(128, 74, true),
        FIT_TYPE_75(128, 75),
        FIT_TYPE_76(128, 76),
        SCHEDULE(128, 77, true),
        FIT_TYPE_78(128, 78),
        SLP_DISR(128, 79, true),
        FIT_TYPE_80(128, 80),
        FIT_TYPE_81(128, 81),
        AREA_COURSES(128, 82, true), // #5824
        FIT_TYPE_83(128, 83),
        FIT_TYPE_85(128, 85),
        FIT_TYPE_86(128, 86),
        GEAR(128, 87), // #5824
        FIT_TYPE_88(128, 88),
        FIT_TYPE_89(128, 89),
        FIT_TYPE_90(128, 90),
        FIT_TYPE_91(128, 91),
        FIT_TYPE_92(128, 92),
        FIT_TYPE_93(128, 93),
        FIT_TYPE_94(128, 94),
        FIT_TYPE_95(128, 95),
        FIT_TYPE_96(128, 96),
        FIT_TYPE_97(128, 97),
        FIT_TYPE_98(128, 98),
        FIT_TYPE_99(128, 99),

        // Other files
        DOWNLOAD_COURSE(255, 4),
        UNKNOWN_255_008(255, 8), // HRM Pro Plus
        PRG(255, 17),
        UNKNOWN_255_020(255, 20), // HRM Pro Plus
        UNKNOWN_255_022(255, 22), // HRM Pro Plus
        ERROR_SHUTDOWN_REPORTS(255, 245, true, "ErrorShutdownReports"),
        IQ_ERROR_REPORTS(255, 244, true, "IQErrorReports"),
        GOLF_SCORECARD(255, 246, true, "GOLF_SCORECARD"), // Garmin vívoactive 5 - #4522
        ULF_LOGS(255, 247, true, "ULFLogs"),
        KPI(255, 248, true, "KPI"), // Garmin Instinct Solar Tactical Edition - #5803

        // unknown type and subtype
        ACTIVITY_GCPD(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "ACTIVITY_GCPD"),
        BACKUP_PRIMARY(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "BACKUP_PRIMARY"),
        BACKUP_SUPPLEMENTARY(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "BACKUP_SUPPLEMENTARY"),
        BLE_LOGS(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "BLELogs"),
        FITNESS_HISTORY(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "FitnessHistory"),
        GPS_DATA(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "GPSData"),
        RAM_DUMP(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "RAMDump"),
        WELLNESS_TYPE_1(Integer.MIN_VALUE, Integer.MIN_VALUE, true, "WELLNESS_TYPE_1"),

        ;

        private final int type;
        private final int subtype;
        public final boolean pull;
        public final String typeName;

        /**
         * Types Tracks wants this sync to fetch on top of {@link #pull}.
         *
         * Courses and saved locations are marked non-pullable upstream, and for
         * a fitness tracker that is right: they are things the user PUT on the
         * watch, not recordings it produced, so a sync that dragged them back
         * every time would move the same unchanging files forever.
         *
         * Tracks wants them exactly once — when the user asks what is already
         * on their watch — so this is a deliberate, temporary widening rather
         * than a change to the table. Set it, sync, clear it.
         *
         * Volatile because it is written from the app's own thread and read on
         * the BLE callback thread that parses the directory.
         */
        private static volatile Set<FILETYPE> alsoPull = Collections.emptySet();

        /**
         * Types every sync fetches, on top of the table's own {@link #pull}.
         *
         * The training calendar is here rather than in {@link #alsoPull}
         * because it answers a question every sync raises: the watch rewrites
         * Schedule.fit whenever it ingests a schedule, so reading it back is the
         * only direct evidence that a push became a calendar. Behind the
         * user-triggered widening it was evidence nobody collected, because the
         * run that pushes a schedule is a different button from the run that
         * reads one.
         *
         * Deliberately NOT the {@code pull} flag on SCHEDULES itself. That flag
         * is what {@code markSynced} tests before telling the watch it may
         * reclaim a file, and a calendar the watch has been invited to discard
         * is the one outcome worse than not reading it at all.
         */
        private static final Set<FILETYPE> ALWAYS_ALSO_PULL =
                Collections.unmodifiableSet(EnumSet.of(SCHEDULES));

        /** Ask for these in addition to the normally-pulled types. */
        public static void setAlsoPull(final Set<FILETYPE> types) {
            alsoPull = types == null || types.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(types));
        }

        /**
         * Whether a sync should download this type.
         *
         * The one question every download filter asks, in one place — there are
         * two file-sync protocols and three call sites, and having them disagree
         * would mean a watch's courses arriving over one transport and not the
         * other.
         */
        public static boolean shouldPull(@Nullable final FILETYPE type) {
            return type != null
                    && (type.pull || ALWAYS_ALSO_PULL.contains(type) || alsoPull.contains(type));
        }

        FILETYPE(final int type, final int subtype) {
            this(type, subtype, false);
        }

        FILETYPE(final int type, final int subtype, boolean pull) {
            this(type, subtype, pull, type==128 ? "FIT_TYPE_" + subtype : null);
        }

        FILETYPE(final int type, final int subtype, boolean pull, final String typeName) {
            this.type = type;
            this.subtype = subtype;
            this.typeName = typeName;
            this.pull = pull;
        }

        @Nullable
        public static FILETYPE fromDataTypeSubType(int dataType, int subType) {
            for (FILETYPE ft :
                    FILETYPE.values()) {
                if (ft.type == dataType && ft.subtype == subType)
                    return ft;
            }
            return null;
        }

        @Nullable
        public static FILETYPE findByTypeName(String name) {
            if(name == null || name.length() < 1){
                return null;
            }
            for(FILETYPE type : values()){
                if(type.typeName != null && name.contentEquals(type.typeName)){
                    return type;
                }
            }
            return null;
        }

        public int getType() {
            return type;
        }

        public int getSubType() {
            return subtype;
        }

        public boolean isFitFile() {
            return type == 128;
        }
    }
}
