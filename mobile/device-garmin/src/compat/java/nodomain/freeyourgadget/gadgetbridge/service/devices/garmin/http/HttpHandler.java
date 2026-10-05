// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http;

import com.google.protobuf.ByteString;
import com.tracks.device.garmin.WatchAppConfig;
import com.tracks.device.garmin.WatchHttpProxy;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiHttpService;
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.GarminSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.ProtobufResponseEvent;

/**
 * Garmin's on-watch HTTP proxy. Answers two kinds of request and refuses the rest.
 *
 * <p>The watch asks the phone to fetch URLs on its behalf: weather tiles, map
 * data, Connect API calls. Honouring all of that would make Tracks an open
 * proxy for whatever the watch names, reaching Garmin's servers from the user's
 * phone — for a self-hosted app whose entire premise is that the user's data
 * stays on their own server, that is the wrong default. So the rule is an
 * allow-list, and it has exactly two entries:
 *
 * <ol>
 *   <li><b>{@code https://tracks.invalid/ciq/config}</b> — the Tracks music app
 *   on the watch asking who it belongs to. Answered from memory, see
 *   {@link WatchAppConfig}. No socket is opened; the host cannot resolve.</li>
 *   <li><b>The user's own music server</b> — the one the config names. The
 *   music app lists playlists through this when the watch is not on Wi-Fi.
 *   Same scheme, host and port as the configured server, nothing looser, and
 *   the credentials the watch sends are the user's own for that server.</li>
 * </ol>
 *
 * <p>Everything else gets an error reply, promptly, so the app on the watch
 * sees a failure rather than a timeout.
 *
 * <h2>The encoding</h2>
 *
 * <p>A Connect IQ {@code makeWebRequest} arrives as a {@code WebRequest}, and
 * the watch expects a JSON reply in Garmin's binary JSON, not as text —
 * {@link GarminJson}. Native firmware requests arrive as a {@code RawRequest}
 * and take the body as-is. Both shapes are handled, as upstream does.
 *
 * <h2>This is still where AGPS would go</h2>
 *
 * <p>Over BLE the watch <em>pulls</em> ephemeris through this same proxy, from
 * {@code api.gcs.garmin.com/ephemeris/...}. Upstream answers that request from
 * a file already on the phone. The remaining work is an interceptor sourcing
 * bytes from Tracks' own {@code /device-sync/agps} — {@code GarminAgpsFile} is
 * vendored and unreferenced, kept for this.
 */
public class HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HttpHandler.class);

    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAVAILABLE = 503;

    private final GarminSupport deviceSupport;

    public HttpHandler(final GarminSupport deviceSupport) {
        this.deviceSupport = deviceSupport;
    }

    /**
     * Handle one request. Returns the reply to send now, or null when it will
     * arrive later through {@link ProtobufResponseEvent} (the proxied case).
     */
    public GdiHttpService.HttpService handle(final GdiHttpService.HttpService httpService,
                                             final int requestId) {
        final GarminHttpRequest request;
        try {
            if (httpService.hasRawRequest()) {
                request = new GarminHttpRequest(httpService.getRawRequest(), requestId);
            } else if (httpService.hasWebRequest()) {
                request = new GarminHttpRequest(httpService.getWebRequest(), requestId);
            } else {
                LOG.warn("Unsupported http service request");
                return null;
            }
        } catch (final GarminJsonException e) {
            LOG.warn("Could not decode a watch web request's headers", e);
            return null;
        }

        final String url = request.getUrl();

        if (WatchAppConfig.INSTANCE.matches(url)) {
            return reply(request, configResponse());
        }

        if (WatchAppConfig.INSTANCE.isProxyable(url)) {
            LOG.debug("Proxying {} {} for the watch", request.getMethod(), url);
            WatchHttpProxy.INSTANCE.fetch(
                    request.getMethod(),
                    url,
                    request.getHeaders(),
                    request.getBody(),
                    request.getMaxResponseLength(),
                    result -> {
                        final GdiHttpService.HttpService reply = (result == null)
                                ? errorReply(request)
                                : reply(request, fromProxy(result));
                        deviceSupport.evaluateGBDeviceEvent(new ProtobufResponseEvent(
                                GdiSmartProto.Smart.newBuilder().setHttpService(reply).build(),
                                request.getMessageRequestId()));
                        return kotlin.Unit.INSTANCE;
                    });
            return null;
        }

        LOG.debug("Declining watch proxy request to {} — not the configured music server", request.getHost());
        return errorReply(request);
    }

    // ── the local answer ──────────────────────────────────────────────────

    /**
     * The config body, or an empty 503 when the phone has nothing to give.
     *
     * <p>503 rather than declining: a request this class recognises deserves an
     * answer. "Not set up yet" is a state the watch app can retry out of.
     */
    private GarminHttpResponse configResponse() {
        final GarminHttpResponse response = new GarminHttpResponse();
        final String json = WatchAppConfig.INSTANCE.respond();
        if (json == null) {
            LOG.info("Watch asked for its music config, but this phone has none yet");
            response.setStatus(HTTP_UNAVAILABLE);
            return response;
        }
        LOG.info("Answering the watch's music config request");
        response.setStatus(HTTP_OK);
        response.getHeaders().put("content-type", "application/json");
        response.setBody(json.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private GarminHttpResponse fromProxy(final WatchHttpProxy.Result result) {
        final GarminHttpResponse response = new GarminHttpResponse();
        response.setStatus(result.getStatus());
        response.getHeaders().putAll(result.getHeaders());
        response.setBody(result.getBody());
        if (result.getTooLarge()) {
            response.getHeaders().put("x-tracks-too-large", "1");
        }
        return response;
    }

    // ── protobuf shapes ───────────────────────────────────────────────────

    private GdiHttpService.HttpService reply(final GarminHttpRequest request, final GarminHttpResponse response) {
        if (request.isWebRequest()) {
            final GdiHttpService.HttpService.WebResponse web = webResponse(request, response);
            return GdiHttpService.HttpService.newBuilder().setWebResponse(web).build();
        }
        return GdiHttpService.HttpService.newBuilder().setRawResponse(rawResponse(response)).build();
    }

    private GdiHttpService.HttpService errorReply(final GarminHttpRequest request) {
        if (request.isWebRequest()) {
            return GdiHttpService.HttpService.newBuilder()
                    .setWebResponse(GdiHttpService.HttpService.WebResponse.newBuilder()
                            .setStatus(GdiHttpService.HttpService.Status.UNKNOWN_STATUS)
                            .setHttpStatus(0))
                    .build();
        }
        return GdiHttpService.HttpService.newBuilder()
                .setRawResponse(GdiHttpService.HttpService.RawResponse.newBuilder()
                        .setStatus(GdiHttpService.HttpService.Status.UNKNOWN_STATUS))
                .build();
    }

    private GdiHttpService.HttpService.RawResponse rawResponse(final GarminHttpResponse response) {
        final List<GdiHttpService.HttpService.Header> headers = new ArrayList<>();
        for (final Map.Entry<String, String> h : response.getHeaders().entrySet()) {
            headers.add(GdiHttpService.HttpService.Header.newBuilder()
                    .setKey(h.getKey()).setValue(h.getValue()).build());
        }
        return GdiHttpService.HttpService.RawResponse.newBuilder()
                .setStatus(GdiHttpService.HttpService.Status.OK)
                .setHttpStatus(response.getStatus())
                .setBody(ByteString.copyFrom(response.getBody()))
                .addAllHeader(headers)
                .build();
    }

    /**
     * A Connect IQ app's reply. The body must be Garmin-JSON: a JSON document
     * when the server sent one, otherwise the text as a single string. Headers
     * ride along the same way when the watch asked for them.
     */
    private GdiHttpService.HttpService.WebResponse webResponse(final GarminHttpRequest request,
                                                               final GarminHttpResponse response) {
        final GdiHttpService.HttpService.WebRequest webRequest = request.getWebRequest();
        try {
            if (response.getHeaders().containsKey("x-tracks-too-large")) {
                return GdiHttpService.HttpService.WebResponse.newBuilder()
                        .setStatus(GdiHttpService.HttpService.Status.FILE_TOO_LARGE)
                        .setHttpStatus(0)
                        .build();
            }

            final Object bodyValue;
            final String contentType = response.getHeaders().get("content-type");
            if (contentType != null && contentType.startsWith("application/json") && response.getBody().length > 0) {
                bodyValue = new JSONTokener(new String(response.getBody(), StandardCharsets.UTF_8)).nextValue();
            } else if (webRequest.getResponseType() == GdiHttpService.HttpService.ResponseType.JSON
                       && response.getBody().length > 0) {
                LOG.warn("Watch wanted JSON but the server sent {}", contentType);
                return GdiHttpService.HttpService.WebResponse.newBuilder()
                        .setStatus(GdiHttpService.HttpService.Status.DATA_TRANSFER_ITEM_FAILURE)
                        .setHttpStatus(response.getStatus())
                        .build();
            } else {
                bodyValue = new String(response.getBody(), StandardCharsets.UTF_8);
            }

            final GdiHttpService.HttpService.WebResponse.Builder builder = GdiHttpService.HttpService.WebResponse.newBuilder()
                    .setStatus(GdiHttpService.HttpService.Status.OK)
                    .setHttpStatus(response.getStatus())
                    .setBody(ByteString.copyFrom(GarminJson.encode(bodyValue)))
                    .setSize(0); // 0: not compressed

            if (webRequest.getHttpHeadersInResponse()) {
                final JSONObject headers = new JSONObject();
                for (final Map.Entry<String, String> h : response.getHeaders().entrySet()) {
                    headers.put(h.getKey(), h.getValue());
                }
                builder.setHeaders(ByteString.copyFrom(GarminJson.encode(headers)));
            }
            return builder.build();
        } catch (final JSONException | GarminJsonException e) {
            LOG.error("Could not encode a reply for the watch", e);
            return GdiHttpService.HttpService.WebResponse.newBuilder()
                    .setStatus(GdiHttpService.HttpService.Status.DATA_TRANSFER_ITEM_FAILURE)
                    .setHttpStatus(0)
                    .build();
        }
    }
}
