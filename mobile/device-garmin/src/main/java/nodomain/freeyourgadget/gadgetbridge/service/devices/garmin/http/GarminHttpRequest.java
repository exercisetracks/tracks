// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http;

import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiHttpService;

/**
 * One request the watch asked the phone to make, in either of the two shapes
 * the protocol has for it.
 *
 * <p>A {@code RawRequest} is what native firmware features use; a
 * {@code WebRequest} is what a Connect IQ app's {@code makeWebRequest}
 * becomes, and its headers arrive Garmin-JSON encoded rather than as a list.
 * Callers see one map of lower-cased header names either way.
 */
public class GarminHttpRequest {
    private static final String TAG = "GarminHttpRequest";
    private final GdiHttpService.HttpService.RawRequest rawRequest;
    private final GdiHttpService.HttpService.WebRequest webRequest;
    private final int messageRequestId;
    private final String method;
    private final Uri uri;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public GarminHttpRequest(final GdiHttpService.HttpService.RawRequest rawRequest, final int messageRequestId) {
        this.messageRequestId = messageRequestId;
        this.rawRequest = rawRequest;
        this.webRequest = null;
        this.method = rawRequest.hasMethod() ? rawRequest.getMethod().name() : "GET";
        this.uri = Uri.parse(rawRequest.getUrl());
        for (final GdiHttpService.HttpService.Header header : rawRequest.getHeaderList()) {
            headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue());
        }
    }

    public GarminHttpRequest(final GdiHttpService.HttpService.WebRequest webRequest, final int messageRequestId) throws GarminJsonException {
        this.messageRequestId = messageRequestId;
        this.rawRequest = null;
        this.webRequest = webRequest;
        this.method = webRequest.hasMethod() ? webRequest.getMethod().name() : "GET";
        this.uri = Uri.parse(webRequest.getUrl());
        if (!webRequest.getHeaders().isEmpty()) {
            final Object decoded = GarminJson.decode(webRequest.getHeaders().toByteArray());
            if (decoded instanceof JSONObject) {
                final JSONObject map = (JSONObject) decoded;
                final Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    headers.put(key.toLowerCase(Locale.ROOT), String.valueOf(map.opt(key)));
                }
            }
        }
    }

    public int getMessageRequestId() {
        return messageRequestId;
    }

    public GdiHttpService.HttpService.RawRequest getRawRequest() {
        return rawRequest;
    }

    public GdiHttpService.HttpService.WebRequest getWebRequest() {
        return webRequest;
    }

    public boolean isWebRequest() {
        return webRequest != null;
    }

    public Uri getUri() {
        return uri;
    }

    public String getHost() {
        return uri.getHost();
    }

    public String getUrl() {
        return rawRequest != null ? rawRequest.getUrl() : webRequest.getUrl();
    }

    public String getPath() {
        return uri.getPath();
    }

    /**
     * The body to send on, in the encoding the destination server expects.
     *
     * A Connect IQ {@code makeWebRequest} hands its parameters over in Garmin's
     * binary JSON, the same as its headers — so forwarding those bytes straight
     * to a real server posts a binary blob where it expects a document, and the
     * server answers 400. That reads on the watch as "server not reachable",
     * because a failed POST is indistinguishable from an unreachable host from
     * where the app is standing. Found against Navidrome's login, which is the
     * first POST the watch ever makes.
     *
     * The encoding announces itself with a magic number, so anything that is
     * not Garmin-JSON — a native firmware request, an already-plain body — is
     * passed through untouched.
     */
    public byte[] getBody() {
        if (rawRequest != null) {
            return rawRequest.getRawBody().toByteArray();
        }
        final byte[] body = webRequest.getBody().toByteArray();
        if (!GarminJson.looksEncoded(body)) {
            return body;
        }
        try {
            return reencode(GarminJson.decode(body));
        } catch (final GarminJsonException e) {
            // Better to send what the watch gave us than to drop the request.
            Log.w(TAG, "could not decode the watch's request body; sending it as-is", e);
            return body;
        }
    }

    /** Garmin-JSON turned back into whatever this request's content type says. */
    private byte[] reencode(final Object decoded) {
        final String contentType = headers.get("content-type");
        if (contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")
                && decoded instanceof JSONObject) {
            return formEncode((JSONObject) decoded).getBytes(StandardCharsets.UTF_8);
        }
        return String.valueOf(decoded).getBytes(StandardCharsets.UTF_8);
    }

    private static String formEncode(final JSONObject object) {
        final StringBuilder out = new StringBuilder();
        final Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(Uri.encode(key)).append('=').append(Uri.encode(String.valueOf(object.opt(key))));
        }
        return out.toString();
    }

    public String getMethod() {
        return method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /** The largest body the watch will accept, for a web request; unbounded otherwise. */
    public int getMaxResponseLength() {
        if (webRequest != null && webRequest.hasMaxResponseLength()) {
            return webRequest.getMaxResponseLength();
        }
        return Integer.MAX_VALUE;
    }
}
