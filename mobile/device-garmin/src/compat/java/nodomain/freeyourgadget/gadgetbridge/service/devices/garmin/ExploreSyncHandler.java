// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin;

import androidx.annotation.Nullable;

import com.tracks.device.garmin.explore.ActivePoint;
import com.tracks.device.garmin.explore.DesiredCourse;
import com.tracks.device.garmin.explore.DesiredWaypoint;
import com.tracks.device.garmin.explore.ExploreBridge;
import com.tracks.device.garmin.explore.ExploreSession;
import com.tracks.device.garmin.explore.ReconcileReport;
import com.tracks.device.garmin.live.LiveActivity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import kotlin.Pair;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiExploreSyncService.ExploreSyncService;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto;

/**
 * The one seam between Gadgetbridge's protobuf plumbing and Tracks' own Explore
 * sync ({@link ExploreSession}).
 *
 * <p>It moves bytes and nothing else: the parsed message is serialised back to
 * the raw bytes of {@code Smart} field 22 (protobuf-lite keeps every field it
 * does not know, which is most of the ones that matter here), handed to the
 * session, and the reply parsed back. All protocol knowledge lives in
 * {@code com.tracks.device.garmin.explore}, which imports nothing from
 * Gadgetbridge, so replacing the transport later replaces only this file.
 */
public class ExploreSyncHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ExploreSyncHandler.class);

    private static final String PREF_LIBRARY = "tracks_explore_library";

    private final ExploreSession session;

    public ExploreSyncHandler(final GarminSupport deviceSupport) {
        session = new ExploreSession(new ExploreSession.Host() {
            @Override
            public String load() {
                return deviceSupport.getDevicePrefs().getString(PREF_LIBRARY, "");
            }

            @Override
            public void save(final String state) {
                deviceSupport.getDevicePrefs().getPreferences().edit().putString(PREF_LIBRARY, state).apply();
            }

            @Override
            public void send(final byte[] message) {
                try {
                    deviceSupport.sendProtobufRequest("explore sync", GdiSmartProto.Smart.newBuilder()
                            .setExploreSyncService(ExploreSyncService.parseFrom(message))
                            .build());
                } catch (final Exception e) {
                    LOG.warn("ExploreSync: could not send", e);
                }
            }

            @Override
            public void log(final String message) {
                LOG.info("ExploreSync: {}", message);
            }

            @Override
            @Nullable
            public Pair<List<DesiredCourse>, List<DesiredWaypoint>> wanted() {
                final ExploreBridge.Library bridge = ExploreBridge.INSTANCE.getLibrary();
                return bridge != null ? bridge.wanted() : null;
            }

            @Override
            public void onReport(final ReconcileReport report) {
                final ExploreBridge.Library bridge = ExploreBridge.INSTANCE.getLibrary();
                if (bridge != null) bridge.onReport(report);
            }

            @Override
            public long nowSeconds() {
                return System.currentTimeMillis() / 1000L;
            }

            @Override
            public void onActiveTrack(final List<ActivePoint> points) {
                final List<LiveActivity.Point> track = new java.util.ArrayList<>(points.size());
                for (final ActivePoint p : points) {
                    track.add(new LiveActivity.Point(p.getLat(), p.getLng(), p.getElevation(), null));
                }
                LiveActivity.INSTANCE.onTrackSoFar(track);
            }
        });
    }

    public ExploreSession getSession() {
        return session;
    }

    @Nullable
    public ExploreSyncService handle(final ExploreSyncService req) {
        final byte[] reply = session.handle(req.toByteArray());
        if (reply == null) return null;
        try {
            return ExploreSyncService.parseFrom(reply);
        } catch (final Exception e) {
            LOG.warn("ExploreSync: reply did not parse", e);
            return null;
        }
    }
}
