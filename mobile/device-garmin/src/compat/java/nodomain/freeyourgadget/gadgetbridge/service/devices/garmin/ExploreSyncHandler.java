// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.ExploreSyncService;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.LineDigest;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.LineDigestReadResponse;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.LineDigestWriteRequest;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.LineDigestWriteResponse;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.LineReadResponse;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.ReadStatus;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.StartSyncStatus;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.WriteStatus;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * Garmin Explore sync — the channel courses and waypoints actually travel on.
 *
 * <p>This replaces a shim that declined every Explore sync outright. Declining
 * was defensible while the only thing on offer was upstream's use of the
 * service (pulling recorded activities into a database Tracks does not have),
 * but it was hiding the answer to a different question. The watch describes its
 * saved tracks, routes and courses here as a digest of {@code LineReference}s,
 * and a {@code LineReference} carries an explicit {@code deleted} boolean.
 * That is the shape of a two-way delete, and it is the only such shape anywhere
 * in the reverse-engineered protocol.
 *
 * <p>What this build does is deliberately only half of that: it accepts the
 * session, answers everything the watch needs answered to keep talking, and
 * <em>logs the digest</em>. It does not yet send a digest back. The reason is
 * that the phone→watch direction is exactly the part nobody has implemented —
 * upstream answers {@code LineDigestReadRequest} with a bare status and a
 * comment calling itself a read-only client, and the proto's
 * {@code LineDigestReadResponse} is stubbed to {@code {status}} accordingly, so
 * the field number a digest would travel in is not established fact. Reading
 * the watch's own digest first gets the course UUIDs, confirms whether a
 * watch-side delete really does surface as {@code deleted=true}, and costs
 * nothing if the answer is no.
 *
 * <p>Every response below other than the digest logging is upstream's, kept
 * because the watch stalls the session without them.
 */
public class ExploreSyncHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ExploreSyncHandler.class);

    private static final int PROTOCOL_VERSION = 2;

    /**
     * Stable per-app identity, sixteen ASCII bytes.
     *
     * <p>The watch keys its per-client transaction cursor on this, so it has to
     * be constant across connections and distinct from any other app's — using
     * Gadgetbridge's would make the watch believe Tracks and Gadgetbridge were
     * one client and hand each of them the other's incremental position.
     */
    private static final ByteString APP_UUID = ByteString.copyFrom(
            "Tracks Explore!!".getBytes(StandardCharsets.US_ASCII));

    /**
     * {@code SYNC_CAPABILITY_ACTIVE_LINES | SYNC_CAPABILITY_COURSES} — the same
     * pair this watch advertises when it initiates a sync itself.
     */
    private static final int SYNC_CAPABILITIES = 31;

    /** The individual capabilities Explore declares, in the order it sends them. */
    private static final int[] EXERCISED_CAPABILITIES = {4, 8, 2};

    /** Set once a sync has finished cleanly; see {@link #startSyncRequest}. */
    private static final String PREF_BASELINE_ESTABLISHED = "tracks_explore_sync_baseline";

    /** Watch UUID to course name, so a delete can be addressed by name. */
    private static final String PREF_COURSE_NAMES = "tracks_explore_course_names";

    /** Courses tombstoned but not yet confirmed gone from the watch. */
    private static final String PREF_TOMBSTONES = "tracks_explore_tombstones";

    /**
     * Tracks' own collection.
     *
     * <p>Fixed rather than generated: the watch keys a collection by UUID, and a
     * phone that invented a new one on every install would leave the old one
     * behind on the watch with no way to name it again.
     */
    private static final ByteString COLLECTION_UUID = ByteString.copyFrom(new byte[]{
            (byte) 0x54, (byte) 0x72, (byte) 0x61, (byte) 0x63,
            (byte) 0x6b, (byte) 0x73, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
    });

    /** {@code item_kind} for a line (course); 0 is a waypoint. */
    private static final int ITEM_KIND_LINE = 3;

    private boolean sessionOpen;

    /**
     * Whether the session in progress is a FULL sync.
     *
     * Load-bearing, not diagnostic. A FULL digest is the watch's entire state,
     * so a course missing from it is a course the watch no longer has — which
     * is the only evidence this handler ever gets that a removal took. An
     * INCREMENTAL digest is a change list, and pruning against one would read
     * "nothing changed" as "everything is gone".
     */
    private boolean sessionIsFullSync;

    /** Courses whose SUMMARY part we still want, this session. */
    private final java.util.Queue<ByteString> pendingSummaries =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Courses the phone wants gone, by watch UUID (hex). Persisted. */
    private final java.util.Set<String> tombstones =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Watch UUID (hex) -> the course name shown on the watch. */
    private final java.util.Map<String, String> courseNames =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final GarminSupport deviceSupport;

    public ExploreSyncHandler(final GarminSupport deviceSupport) {
        this.deviceSupport = deviceSupport;
    }

    public void onDisconnected() {
        sessionOpen = false;
        // Cleared with the session, not left standing. A digest that arrived
        // outside any session we know the type of must not be treated as the
        // watch's whole state — pruning against a partial one would forget
        // course names and pending removals that are still live.
        sessionIsFullSync = false;
    }

    /**
     * Ask the watch to start an Explore sync, rather than waiting to be asked.
     *
     * <p>Needed because the watch stops offering. It asked repeatedly while
     * Tracks was declining, then gave up, and a client that only ever answers
     * is at the mercy of whatever makes the watch feel like offering again.
     * Garmin's own app initiates, and so must this.
     *
     * <p>{@code sync_capabilities} is <strong>31</strong>, copied from what
     * Garmin Explore sends, not assembled from the capability bits that look
     * relevant. Tracks previously sent 10 (courses plus active lines) and the
     * watch completed a sync but never once asked the phone for anything; with
     * Explore's 31 it asks for line digests, collection contents and waypoint
     * digests. Whatever in the remaining bits unlocks the reverse direction, an
     * educated subset was not enough.
     *
     * <p>FULL until one has completed, INCREMENTAL after. The distinction is
     * not an optimisation: a FULL digest is the watch's current state, so a
     * course deleted on the watch simply is not in it, while an INCREMENTAL
     * digest is a change list and is the only place a tombstone
     * ({@code deleted=true}) can appear. Watching the watch produce its own
     * tombstone is how the shape of a delete gets established rather than
     * guessed at.
     */
    public ExploreSyncService startSyncRequest() {
        // FULL when there is no course-name map yet, not merely when there is
        // no cursor. An incremental digest carries only what changed, so a
        // phone that has never learned the watch's course names cannot learn
        // them incrementally — the courses it needs are old news to the watch
        // and will never appear again. Asking for one full listing is the only
        // way to bootstrap, and it happens once.
        loadState();
        // A pending removal forces FULL too. An incremental sync is a
        // conversation about what changed on the *watch*; the phone's library is
        // only re-read in full, so a tombstone would otherwise sit unsent until
        // something unrelated happened to trigger a full run.
        final boolean haveBaseline = deviceSupport.getDevicePrefs()
                .getBoolean(PREF_BASELINE_ESTABLISHED, false)
                && !courseNames.isEmpty()
                && tombstones.isEmpty();
        final GdiExploreSyncService.SyncType syncType = haveBaseline
                ? GdiExploreSyncService.SyncType.SYNC_TYPE_INCREMENTAL
                : GdiExploreSyncService.SyncType.SYNC_TYPE_FULL;
        sessionIsFullSync = syncType == GdiExploreSyncService.SyncType.SYNC_TYPE_FULL;
        LOG.info("ExploreSync: requesting a {} sync", syncType);
        // Shaped exactly like Garmin Explore's: the capability bitmask, then one
        // detail per capability actually being exercised, and NO
        // actual_capabilities. Tracks sent the bitmask twice and no details, and
        // the watch completed every sync without once asking the phone what it
        // held.
        final GdiExploreSyncService.StartSyncRequest.Builder request =
                GdiExploreSyncService.StartSyncRequest.newBuilder()
                        .setSyncType(syncType)
                        .setProtocolVersion(PROTOCOL_VERSION)
                        .setAppUuid(APP_UUID)
                        .setSyncCapabilities(SYNC_CAPABILITIES);
        for (final int capability : EXERCISED_CAPABILITIES) {
            request.addCapabilityDetail(GdiExploreSyncService.CapabilityDetail.newBuilder()
                    .setCapability(capability)
                    .setUnk2(0));
        }
        return ExploreSyncService.newBuilder().setStartSyncRequest(request).build();
    }

    /**
     * The watch's reply to {@link #startSyncRequest}.
     */
    @Nullable
    private ExploreSyncService handleStartSyncResponse(
            final GdiExploreSyncService.StartSyncResponse resp) {
        if (resp.getStatus() == StartSyncStatus.START_SYNC_ACCEPTED) {
            sessionOpen = true;
            LOG.info("ExploreSync: watch accepted our sync request (protocol {})",
                    resp.getSupportedProtocolVersion());
        } else {
            sessionOpen = false;
            LOG.warn("ExploreSync: watch refused our sync request with {}", resp.getStatus());
        }
        return null;
    }

    @Nullable
    public ExploreSyncService handle(final ExploreSyncService req) {
        if (req.hasStartSyncRequest()) {
            sessionOpen = true;
            sessionIsFullSync = req.getStartSyncRequest().getSyncType()
                    == GdiExploreSyncService.SyncType.SYNC_TYPE_FULL;
            LOG.info("ExploreSync: accepting watch-initiated sync, type={} caps={}",
                    req.getStartSyncRequest().getSyncType(),
                    req.getStartSyncRequest().getSyncCapabilities());
            return ExploreSyncService.newBuilder()
                    .setStartSyncResponse(GdiExploreSyncService.StartSyncResponse.newBuilder()
                            .setStatus(StartSyncStatus.START_SYNC_ACCEPTED)
                            .setSupportedProtocolVersion(PROTOCOL_VERSION)
                            .setAppUuid(APP_UUID))
                    .build();
        }

        if (req.hasStartSyncResponse()) {
            return handleStartSyncResponse(req.getStartSyncResponse());
        }

        if (req.hasLineDigestWriteResponse()) {
            // The watch's verdict on a digest *we* wrote — the answer to
            // whether a phone-side delete is accepted at all.
            LOG.info("ExploreSync: watch answered our digest write with {}",
                    req.getLineDigestWriteResponse().getStatus());
            return null;
        }

        if (req.hasLineDigestWriteRequest()) {
            return handleLineDigestWrite(req.getLineDigestWriteRequest());
        }

        if (req.hasSyncFinishedNotification()) {
            final GdiExploreSyncService.SyncFinishedStatus status =
                    req.getSyncFinishedNotification().getStatus();
            LOG.info("ExploreSync: watch finished sync with status {}", status);
            if (status == GdiExploreSyncService.SyncFinishedStatus.SYNC_FINISHED_OK) {
                // Only a clean finish establishes the cursor. An interrupted
                // full sync leaves the watch's idea of what we have seen
                // incomplete, and resuming incrementally from that would skip
                // whatever the interruption swallowed.
                deviceSupport.getDevicePrefs().getPreferences().edit()
                        .putBoolean(PREF_BASELINE_ESTABLISHED, true).apply();
            }
            sessionOpen = false;
            return null;
        }

        // Stateless acks. The watch stalls the session without them, and none
        // of them carry anything Tracks reads yet.
        if (req.hasLineDigestReadRequest()) {
            return lineDigestReadResponse();
        }
        if (req.hasCollectionDigestReadRequest()) {
            return collectionDigestReadResponse();
        }
        if (req.hasCollectionReadRequest()) {
            return collectionReadResponse();
        }
        if (req.hasLineReadRequest()) {
            return ExploreSyncService.newBuilder()
                    .setLineReadResponse(LineReadResponse.newBuilder()
                            .setStatus(ReadStatus.READ_STATUS_SUCCESS))
                    .build();
        }
        if (req.hasLineDataReadyRequest()) {
            return handleLineDataReady(req.getLineDataReadyRequest());
        }
        if (req.hasCollectionListWriteRequest()) {
            return ack(ExploreSyncService.newBuilder().setCollectionListWriteResponse(
                    GdiExploreSyncService.CollectionListWriteResponse.newBuilder()
                            .setStatus(WriteStatus.WRITE_STATUS_SUCCESS)));
        }
        if (req.hasCollectionDigestWriteRequest()) {
            return ack(ExploreSyncService.newBuilder().setCollectionDigestWriteResponse(
                    GdiExploreSyncService.CollectionDigestWriteResponse.newBuilder()
                            .setStatus(WriteStatus.WRITE_STATUS_SUCCESS)));
        }
        if (req.hasWaypointDigestWriteRequest()) {
            return ack(ExploreSyncService.newBuilder().setWaypointDigestWriteResponse(
                    GdiExploreSyncService.WaypointDigestWriteResponse.newBuilder()
                            .setStatus(WriteStatus.WRITE_STATUS_SUCCESS)));
        }
        if (req.hasWaypointDigestReadRequest()) {
            return ack(ExploreSyncService.newBuilder().setWaypointDigestReadResponse(
                    GdiExploreSyncService.WaypointDigestReadResponse.newBuilder()
                            .setStatus(ReadStatus.READ_STATUS_SUCCESS)));
        }
        if (req.hasWaypointReadRequest()) {
            return ack(ExploreSyncService.newBuilder().setWaypointReadResponse(
                    GdiExploreSyncService.WaypointReadResponse.newBuilder()
                            .setStatus(ReadStatus.READ_STATUS_SUCCESS)));
        }
        if (req.hasActiveLineDigestWriteRequest()) {
            return ack(ExploreSyncService.newBuilder().setActiveLineDigestWriteResponse(
                    GdiExploreSyncService.ActiveLineDigestWriteResponse.newBuilder()
                            .setStatus(WriteStatus.WRITE_STATUS_SUCCESS)));
        }
        if (req.hasChangeSummaryRequest()) {
            return changeSummaryResponse(req.getChangeSummaryRequest());
        }

        LOG.warn("ExploreSync: unhandled message {}", req);
        return null;
    }

    private ExploreSyncService ack(final ExploreSyncService.Builder builder) {
        return builder.build();
    }

    /**
     * The watch's own list of saved lines — the point of this whole build.
     *
     * <p>Logged one line per entry rather than dumped as protobuf text so a
     * course can be picked out of a session that also carries every activity
     * the watch has recorded. The UUID is what a delete would have to name, and
     * {@code deleted} is what a watch-side delete is expected to set.
     */
    private ExploreSyncService handleLineDigestWrite(final LineDigestWriteRequest req) {
        final LineDigest digest = req.getDigest();
        LOG.info("ExploreSync: watch digest carries {} line(s)", digest.getLinesCount());
        for (final LineDigest.LineReference line : digest.getLinesList()) {
            LOG.info("ExploreSync:   uuid={} type={} deleted={} summaryStamp={}",
                    GB.hexdump(line.getUuid().toByteArray()),
                    line.getLineType(),
                    line.getDeleted(),
                    line.hasSummaryPartVersionStamp()
                            ? line.getSummaryPartVersionStamp().getEditTime() : null);
        }
        // Course names are the missing link between the two halves of this
        // feature: the app addresses a course by filename, the watch addresses
        // it by UUID, and a digest carries neither a name nor anything to join
        // on. Asking for each course's SUMMARY part is what closes that gap —
        // the reply carries summary_part.name.
        pendingSummaries.clear();
        final java.util.Set<String> present = new java.util.HashSet<>();
        for (final LineDigest.LineReference line : digest.getLinesList()) {
            if (line.getLineType() == GdiExploreSyncService.LineType.LINE_TYPE_COURSE) {
                pendingSummaries.add(line.getUuid());
                present.add(GB.hexdump(line.getUuid().toByteArray()));
            }
        }

        // This digest is the only evidence a removal ever took.
        //
        // A tombstone is the phone saying "this course is gone"; it has to keep
        // saying it, and keep forcing a FULL sync to be heard, until the watch
        // agrees. The watch never acknowledges one directly — it just stops
        // listing the course. So the course vanishing from a FULL digest *is*
        // the acknowledgement, and this is where it gets noticed.
        //
        // Only on a FULL sync: an incremental digest carries what changed, so
        // pruning against one would read a quiet sync as an empty watch and
        // throw away every name and every pending removal at once.
        if (pruneToWatchState(courseNames, tombstones, present, sessionIsFullSync)) {
            LOG.info("ExploreSync: watch lists {} course(s); forgot the rest, and any "
                    + "removal it has finished", present.size());
            saveState();
        }

        // No line_read_op: Tracks is not pulling line bodies here. The digest
        // is the finding; fetching every activity's points would turn a
        // diagnostic into a long transfer.
        final LineDigestWriteResponse.Builder response = LineDigestWriteResponse.newBuilder()
                .setStatus(WriteStatus.WRITE_STATUS_SUCCESS);
        final GdiExploreSyncService.LineDataRequestOp op = nextSummaryRequest();
        if (op != null) {
            response.setLineReadOp(op);
        }
        return ExploreSyncService.newBuilder().setLineDigestWriteResponse(response).build();
    }

    /**
     * Reconcile what the phone believes the watch holds with what it just said.
     *
     * <p>Static and package-visible so the rule can be checked without a watch —
     * see {@code ExploreSyncPruneTest}. It is the only place a removal is ever
     * confirmed, and it used to be unreachable, so it is worth pinning.
     *
     * <p>{@code present} is every course UUID in the watch's digest.
     * {@code fullSync} says whether that digest is the watch's whole state or
     * only what changed; **pruning against an incremental digest would read a
     * quiet sync as an empty watch** and throw away every course name and every
     * pending removal at once, so this does nothing unless the sync is full.
     *
     * <p>Returns whether anything was dropped, so the caller knows whether the
     * persisted state needs rewriting.
     */
    static boolean pruneToWatchState(
            final java.util.Map<String, String> courseNames,
            final java.util.Set<String> tombstones,
            final java.util.Set<String> present,
            final boolean fullSync) {
        if (!fullSync) {
            return false;
        }
        // A tombstone is the phone saying "this course is gone" and having to
        // keep saying it — forcing a full sync each time to be heard — because
        // the watch never acknowledges one. It just stops listing the course.
        // So the course leaving the digest is the acknowledgement, and dropping
        // the tombstone here is what lets syncs go back to being incremental.
        final boolean changed = courseNames.keySet().retainAll(present)
                | tombstones.retainAll(present);
        return changed;
    }

    /**
     * Ask for the next course's summary, or null when the queue is drained.
     *
     * <p>Only {@code LINE_PART_SUMMARY} — a course's points are the expensive
     * part of it and Tracks already has its own copy of every course it pushed.
     * The name is the only field this needs.
     */
    @Nullable
    private GdiExploreSyncService.LineDataRequestOp nextSummaryRequest() {
        final ByteString uuid = pendingSummaries.poll();
        if (uuid == null) {
            return null;
        }
        return GdiExploreSyncService.LineDataRequestOp.newBuilder()
                .setUuid(uuid)
                .setPart(GdiExploreSyncService.LinePart.LINE_PART_SUMMARY)
                .build();
    }

    /**
     * A line body the watch sent because we asked for it.
     *
     * <p>Answering with the next request rather than an empty response is what
     * keeps the walk going: the watch sends one line per round trip and stops
     * as soon as the phone stops asking.
     */
    private ExploreSyncService handleLineDataReady(
            final GdiExploreSyncService.LineDataReadyRequest req) {
        if (req.hasLine() && req.getLine().hasSummaryPart()) {
            final GdiExploreSyncService.Line line = req.getLine();
            final String name = line.getSummaryPart().getName();
            final String uuid = GB.hexdump(line.getUuid().toByteArray());
            courseNames.put(uuid, name);
            LOG.info("ExploreSync: course \"{}\" is {}", name, uuid);
        }

        final GdiExploreSyncService.LineDataReadyResponse.Builder response =
                GdiExploreSyncService.LineDataReadyResponse.newBuilder();
        final GdiExploreSyncService.LineDataRequestOp op = nextSummaryRequest();
        if (op != null) {
            response.setNextDataRequest(op);
        } else if (!courseNames.isEmpty()) {
            LOG.info("ExploreSync: know {} course name(s)", courseNames.size());
            saveState();
        }
        return ExploreSyncService.newBuilder().setLineDataReadyResponse(response).build();
    }

    /**
     * Tell the watch whether this client has anything it has not seen.
     *
     * <p>This is the message that decides whether the rest of the session
     * happens at all. The watch names the transaction it last saw from us; if we
     * name a later one, it reads the range between them and asks for our
     * collections, waypoints and lines. If we answer with nothing — which is
     * what a bare {@code getDefaultInstance()} says — the range is empty and the
     * watch never asks. Every earlier attempt to be read from failed here, one
     * message before the reads would have started.
     *
     * <p>Only claims a change when there is one. Advertising unconditionally
     * would make every sync drag the whole library across for nothing; a pending
     * tombstone is the one thing Tracks currently has to say.
     */
    private ExploreSyncService changeSummaryResponse(
            final GdiExploreSyncService.ChangeSummaryRequest req) {
        loadState();
        final int starting = req.getStartingTransactionId();
        final GdiExploreSyncService.ChangeSummaryResponse.Builder response =
                GdiExploreSyncService.ChangeSummaryResponse.newBuilder();
        if (tombstones.isEmpty()) {
            // Nothing to offer: echo the watch's own position so it reads nothing
            // rather than reading an empty range and deciding we are broken.
            response.setEndingTransactionId(starting);
            LOG.debug("ExploreSync: no changes to advertise (transaction {})", starting);
        } else {
            response.setEndingTransactionId(starting + 1)
                    .setHasCollections(1)
                    .setHasWaypoints(1)
                    .setHasLines(1);
            LOG.info("ExploreSync: advertising changes at transaction {} -> {} ({} tombstone(s))",
                    starting, starting + 1, tombstones.size());
        }
        return ExploreSyncService.newBuilder().setChangeSummaryResponse(response).build();
    }

    /**
     * What the phone holds, as a digest of lines.
     *
     * <p>A tombstoned course is simply left out. Removal is expressed in two
     * places at once — absent here, and flagged in the collection — which is
     * what Garmin Explore does and what a real deletion correlated with
     * exactly.
     */
    private ExploreSyncService lineDigestReadResponse() {
        loadState();
        final LineDigest.Builder digest = LineDigest.newBuilder();
        int served = 0;
        for (final java.util.Map.Entry<String, String> entry : courseNames.entrySet()) {
            // A tombstoned course is listed with deleted = true, not omitted.
            // Omitting it was modelled on Garmin Explore, whose deleted lines
            // are absent here and flagged in the collection instead — but this
            // watch never asks Tracks for a collection's contents, only for its
            // digest, so that half of the signal has nowhere to travel. A digest
            // is also plausibly additive rather than authoritative, in which
            // case absence says nothing at all and only the explicit flag does.
            final boolean removed = tombstones.contains(entry.getKey());
            digest.addLines(LineDigest.LineReference.newBuilder()
                    .setUuid(fromHex(entry.getKey()))
                    .setLineType(GdiExploreSyncService.LineType.LINE_TYPE_COURSE)
                    .setDeleted(removed));
            served++;
        }
        LOG.info("ExploreSync: serving a line digest of {} course(s), {} marked deleted",
                served, tombstones.size());
        return ExploreSyncService.newBuilder()
                .setLineDigestReadResponse(LineDigestReadResponse.newBuilder()
                        .setStatus(ReadStatus.READ_STATUS_SUCCESS)
                        .setDigest(digest))
                .build();
    }

    /** The phone owns exactly one collection; this names it. */
    private ExploreSyncService collectionDigestReadResponse() {
        return ExploreSyncService.newBuilder()
                .setCollectionDigestReadResponse(
                        GdiExploreSyncService.CollectionDigestReadResponse.newBuilder()
                                .setStatus(ReadStatus.READ_STATUS_SUCCESS)
                                .setDigest(GdiExploreSyncService.CollectionDigest.newBuilder()
                                        .addCollection(collectionRef())))
                .build();
    }

    private GdiExploreSyncService.CollectionRef collectionRef() {
        return GdiExploreSyncService.CollectionRef.newBuilder()
                .setUuid(COLLECTION_UUID)
                .setUnk2(0)
                .setUnk3(1)
                .setVersionStamp(stampNow())
                .build();
    }

    /**
     * The collection's contents — where a deletion actually lives.
     *
     * <p>A removed course keeps its entry and gains {@code tombstone = true}
     * with a fresh version stamp, rather than vanishing. That is the part that
     * tells the watch something was taken away instead of merely never having
     * been mentioned.
     */
    private ExploreSyncService collectionReadResponse() {
        loadState();
        final GdiExploreSyncService.CollectionItems.Builder items =
                GdiExploreSyncService.CollectionItems.newBuilder();
        for (final java.util.Map.Entry<String, String> entry : courseNames.entrySet()) {
            final boolean removed = tombstones.contains(entry.getKey());
            items.addItem(GdiExploreSyncService.CollectionItem.newBuilder()
                    .setUuid(fromHex(entry.getKey()))
                    .setVersionStamp(stampNow())
                    .setTombstone(removed)
                    .setItemKind(ITEM_KIND_LINE));
        }
        LOG.info("ExploreSync: serving our collection, {} item(s)", items.getItemCount());
        return ExploreSyncService.newBuilder()
                .setCollectionReadResponse(GdiExploreSyncService.CollectionReadResponse.newBuilder()
                        .setStatus(ReadStatus.READ_STATUS_SUCCESS)
                        .setCollection(GdiExploreSyncService.Collection.newBuilder()
                                .setUuid(COLLECTION_UUID)
                                .setItems(items)))
                .build();
    }

    /**
     * Garmin epoch seconds. Version stamps are how the watch decides which side
     * of a sync is newer, so a tombstone with a stale stamp would be ignored in
     * favour of the copy the watch already has.
     */
    private GdiExploreSyncService.VersionStamp stampNow() {
        return GdiExploreSyncService.VersionStamp.newBuilder()
                .setEditTime(GarminTimeUtils.javaMillisToGarminTimestamp(System.currentTimeMillis()))
                .build();
    }

    private static ByteString fromHex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return ByteString.copyFrom(out);
    }

    /**
     * Mark a course for removal at the next sync, by the name it shows on the
     * watch. False when this phone has never learned that name — which is the
     * honest answer, since without a UUID there is nothing to tombstone.
     */
    public boolean tombstoneCourseNamed(final String name) {
        loadState();
        final String uuid = uuidForCourseName(name);
        if (uuid == null) {
            LOG.warn("ExploreSync: no watch UUID known for course \"{}\"", name);
            return false;
        }
        tombstones.add(uuid);
        saveState();
        LOG.info("ExploreSync: tombstoned course \"{}\" ({})", name, uuid);
        return true;
    }

    /**
     * The name map, persisted.
     *
     * <p>Kept in the device's own preferences rather than rebuilt each
     * connection because rebuilding costs a full sync and one round trip per
     * course, and the answer barely changes. Stored as {@code uuid=name}
     * newline-separated: a course name can contain almost anything, but a
     * newline is not reachable from the watch or from Tracks' own naming.
     */
    private void saveState() {
        saveCourseNames();
        deviceSupport.getDevicePrefs().getPreferences().edit()
                .putString(PREF_TOMBSTONES, String.join(",", tombstones)).apply();
    }

    private void loadState() {
        loadCourseNames();
        if (!tombstones.isEmpty()) {
            return;
        }
        final String stored = deviceSupport.getDevicePrefs().getString(PREF_TOMBSTONES, "");
        if (stored != null && !stored.isEmpty()) {
            java.util.Collections.addAll(tombstones, stored.split(","));
        }
    }

    private void saveCourseNames() {
        final StringBuilder out = new StringBuilder();
        for (final java.util.Map.Entry<String, String> e : courseNames.entrySet()) {
            out.append(e.getKey()).append('=').append(e.getValue().replace('\n', ' ')).append('\n');
        }
        deviceSupport.getDevicePrefs().getPreferences().edit()
                .putString(PREF_COURSE_NAMES, out.toString()).apply();
    }

    private void loadCourseNames() {
        if (!courseNames.isEmpty()) {
            return;
        }
        final String stored = deviceSupport.getDevicePrefs()
                .getString(PREF_COURSE_NAMES, "");
        if (stored == null || stored.isEmpty()) {
            return;
        }
        for (final String row : stored.split("\n")) {
            final int split = row.indexOf('=');
            if (split > 0) {
                courseNames.put(row.substring(0, split), row.substring(split + 1));
            }
        }
        LOG.debug("ExploreSync: {} course name(s) remembered from a previous sync",
                courseNames.size());
    }

    /** The watch's UUID for a course, by the name it shows on the watch. */
    @Nullable
    public String uuidForCourseName(final String name) {
        for (final java.util.Map.Entry<String, String> entry : courseNames.entrySet()) {
            if (entry.getValue().equals(name)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
