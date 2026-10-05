// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Ported from Gadgetbridge's GarminJson (AGPL-3.0-or-later) onto org.json,
// which Android ships, so the module does not have to take on Gson for one
// codec. The wire format is unchanged.
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Garmin's binary JSON, as used by the watch's HTTP proxy.
 *
 * <p>A Connect IQ {@code makeWebRequest} does not travel as text. The watch
 * sends its request headers, and expects any JSON response body, in this
 * encoding: a string table (every distinct string once, null-terminated,
 * length-prefixed) followed by a data section that references strings by
 * offset and lays containers out breadth-first. Sending the watch plain JSON
 * text where it expects this produces a parse failure on the watch, not an
 * error the phone ever sees — which is why this exists.
 *
 * <p>Values are the org.json vocabulary: {@link JSONObject}, {@link JSONArray},
 * {@link String}, {@link Number}, {@link Boolean}, and {@link JSONObject#NULL}.
 */
public final class GarminJson {
    private static final byte[] STRING_SECTION_MAGIC = {(byte) 0xab, (byte) 0xcd, (byte) 0xab, (byte) 0xcd};
    private static final byte[] DATA_SECTION_MAGIC = {(byte) 0xda, (byte) 0x7a, (byte) 0xda, (byte) 0x7a};

    private static final byte TYPE_NULL = 0x00;
    private static final byte TYPE_SINT32 = 0x01;
    private static final byte TYPE_FLOAT = 0x02;
    private static final byte TYPE_STRING = 0x03;
    private static final byte TYPE_ARRAY = 0x05;
    private static final byte TYPE_BOOL = 0x09;
    private static final byte TYPE_MAP = 0x0b;
    private static final byte TYPE_SINT64 = 0x0e;
    private static final byte TYPE_DOUBLE = 0x0f;

    private GarminJson() {
    }

    /**
     * True when these bytes are a Garmin-JSON document rather than plain text.
     *
     * Both sections start with a magic number, and a document always begins
     * with one of them, so this is a decision rather than a guess. Needed
     * because a Connect IQ request body arrives in this encoding while the
     * server it is bound for expects the real thing.
     */
    public static boolean looksEncoded(final byte[] bytes) {
        return startsWith(bytes, STRING_SECTION_MAGIC) || startsWith(bytes, DATA_SECTION_MAGIC);
    }

    private static boolean startsWith(final byte[] bytes, final byte[] magic) {
        if (bytes == null || bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    public static byte[] encode(final Object value) throws GarminJsonException {
        // First pass: every distinct string, in first-seen order.
        final Set<String> strings = new LinkedHashSet<>();
        collectStrings(value, strings);

        final ByteArrayOutputStream stringSection = new ByteArrayOutputStream();
        final Map<String, Integer> offsets = new LinkedHashMap<>();
        int offset = 0;
        for (final String str : strings) {
            offsets.put(str, offset);
            final byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            final int length = bytes.length + 1; // null terminator
            stringSection.write((length >> 8) & 0xFF);
            stringSection.write(length & 0xFF);
            stringSection.write(bytes, 0, bytes.length);
            stringSection.write(0x00);
            offset += 2 + length;
        }

        final ByteArrayOutputStream dataSection = new ByteArrayOutputStream();
        encodeBreadthFirst(value, dataSection, offsets);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (stringSection.size() > 0) {
            out.write(STRING_SECTION_MAGIC, 0, 4);
            writeUint32BE(out, stringSection.size());
            final byte[] s = stringSection.toByteArray();
            out.write(s, 0, s.length);
        }
        out.write(DATA_SECTION_MAGIC, 0, 4);
        writeUint32BE(out, dataSection.size());
        final byte[] d = dataSection.toByteArray();
        out.write(d, 0, d.length);
        return out.toByteArray();
    }

    private static boolean isNull(final Object o) {
        return o == null || o == JSONObject.NULL;
    }

    private static void collectStrings(final Object root, final Set<String> strings) {
        final Queue<Object> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final Object obj = queue.poll();
            if (obj instanceof JSONObject) {
                final JSONObject map = (JSONObject) obj;
                final Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    strings.add(key);
                    final Object v = map.opt(key);
                    if (v instanceof String) {
                        strings.add((String) v);
                    } else if (v instanceof JSONObject || v instanceof JSONArray) {
                        queue.add(v);
                    }
                }
            } else if (obj instanceof JSONArray) {
                final JSONArray array = (JSONArray) obj;
                for (int i = 0; i < array.length(); i++) {
                    final Object v = array.opt(i);
                    if (v instanceof String) {
                        strings.add((String) v);
                    } else if (v instanceof JSONObject || v instanceof JSONArray) {
                        queue.add(v);
                    }
                }
            } else if (obj instanceof String) {
                strings.add((String) obj);
            }
        }
    }

    private static void encodeBreadthFirst(final Object root,
                                           final ByteArrayOutputStream out,
                                           final Map<String, Integer> offsets) throws GarminJsonException {
        final Queue<Object> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final Object obj = queue.poll();
            if (isNull(obj)) {
                out.write(TYPE_NULL);
            } else if (obj instanceof Boolean) {
                out.write(TYPE_BOOL);
                out.write((Boolean) obj ? 0x01 : 0x00);
            } else if (obj instanceof Number) {
                writeNumber((Number) obj, out);
            } else if (obj instanceof String) {
                out.write(TYPE_STRING);
                final Integer at = offsets.get(obj);
                if (at == null) {
                    throw new GarminJsonException("String not in table: " + obj);
                }
                writeUint32BE(out, at);
            } else if (obj instanceof JSONArray) {
                final JSONArray array = (JSONArray) obj;
                out.write(TYPE_ARRAY);
                writeUint32BE(out, array.length());
                for (int i = 0; i < array.length(); i++) {
                    queue.add(array.opt(i));
                }
            } else if (obj instanceof JSONObject) {
                final JSONObject map = (JSONObject) obj;
                out.write(TYPE_MAP);
                writeUint32BE(out, map.length());
                final Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    queue.add(key);
                    queue.add(map.opt(key));
                }
            } else {
                throw new GarminJsonException("Unsupported type: " + obj.getClass().getName());
            }
        }
    }

    private static void writeNumber(final Number num, final ByteArrayOutputStream out) {
        final double d = num.doubleValue();
        final long l = num.longValue();
        final boolean fractional = d != l || num instanceof Float || num instanceof Double;
        if (fractional) {
            if (num instanceof Float || (Math.abs(d) < Float.MAX_VALUE && d == (float) d)) {
                out.write(TYPE_FLOAT);
                writeUint32BE(out, Float.floatToIntBits((float) d));
            } else {
                out.write(TYPE_DOUBLE);
                writeUint64BE(out, Double.doubleToLongBits(d));
            }
        } else if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            out.write(TYPE_SINT32);
            writeUint32BE(out, (int) l);
        } else {
            out.write(TYPE_SINT64);
            writeUint64BE(out, l);
        }
    }

    public static Object decode(final byte[] bytes) throws GarminJsonException {
        if (bytes.length < 4 + 4 + 1) {
            throw new GarminJsonException("Not enough bytes for GarminJson: " + bytes.length);
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        final Map<Integer, String> strings = new LinkedHashMap<>();
        final byte[] magic = new byte[4];
        buffer.get(magic);
        if (Arrays.equals(magic, STRING_SECTION_MAGIC)) {
            final int sectionLength = buffer.getInt();
            final int sectionStart = buffer.position();
            final int sectionEnd = sectionStart + sectionLength;
            while (buffer.position() < sectionEnd) {
                final int at = buffer.position() - sectionStart;
                final int length = buffer.getShort() & 0xFFFF;
                final byte[] str = new byte[length - 1];
                buffer.get(str);
                buffer.get(); // null terminator
                strings.put(at, new String(str, StandardCharsets.UTF_8));
            }
            buffer.get(magic);
        }
        if (!Arrays.equals(magic, DATA_SECTION_MAGIC)) {
            throw new GarminJsonException("Expected data section magic");
        }
        final int dataLength = buffer.getInt();
        if (buffer.position() + dataLength > bytes.length) {
            throw new GarminJsonException("Data section runs past the end: " + dataLength);
        }

        final Object root = decodeValue(buffer, strings);

        // Containers come back as placeholders whose children follow later in
        // the stream, breadth-first — same order they were written.
        final Queue<Object> queue = new LinkedList<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final Object current = queue.poll();
            if (current instanceof MapPlaceholder) {
                final MapPlaceholder mp = (MapPlaceholder) current;
                for (int i = 0; i < mp.size; i++) {
                    final Object key = decodeValue(buffer, strings);
                    final Object value = decodeValue(buffer, strings);
                    try {
                        mp.obj.put(String.valueOf(unwrap(key)), unwrap(value));
                    } catch (final JSONException e) {
                        throw new GarminJsonException("Bad map entry", e);
                    }
                    enqueueIfContainer(value, queue);
                }
            } else if (current instanceof ArrayPlaceholder) {
                final ArrayPlaceholder ap = (ArrayPlaceholder) current;
                for (int i = 0; i < ap.length; i++) {
                    final Object value = decodeValue(buffer, strings);
                    ap.array.put(unwrap(value));
                    enqueueIfContainer(value, queue);
                }
            }
        }
        return unwrap(root);
    }

    private static void enqueueIfContainer(final Object value, final Queue<Object> queue) {
        if (value instanceof MapPlaceholder || value instanceof ArrayPlaceholder) {
            queue.add(value);
        }
    }

    private static Object unwrap(final Object value) {
        if (value instanceof MapPlaceholder) {
            return ((MapPlaceholder) value).obj;
        }
        if (value instanceof ArrayPlaceholder) {
            return ((ArrayPlaceholder) value).array;
        }
        return value;
    }

    private static Object decodeValue(final ByteBuffer buffer, final Map<Integer, String> strings) throws GarminJsonException {
        final byte type = buffer.get();
        switch (type) {
            case TYPE_NULL:
                return JSONObject.NULL;
            case TYPE_BOOL:
                return buffer.get() != 0x00;
            case TYPE_SINT32:
                return buffer.getInt();
            case TYPE_SINT64:
                return buffer.getLong();
            case TYPE_FLOAT:
                return buffer.getFloat();
            case TYPE_DOUBLE:
                return buffer.getDouble();
            case TYPE_STRING: {
                final int at = buffer.getInt();
                final String str = strings.get(at);
                if (str == null) {
                    throw new GarminJsonException("String offset not in table: " + at);
                }
                return str;
            }
            case TYPE_ARRAY:
                return new ArrayPlaceholder(new JSONArray(), buffer.getInt());
            case TYPE_MAP:
                return new MapPlaceholder(new JSONObject(), buffer.getInt());
            default:
                throw new GarminJsonException("Unknown type: 0x" + Integer.toHexString(type & 0xFF));
        }
    }

    private static void writeUint32BE(final ByteArrayOutputStream out, final int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeUint64BE(final ByteArrayOutputStream out, final long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((value >> shift) & 0xFF));
        }
    }

    private static final class MapPlaceholder {
        final JSONObject obj;
        final int size;

        MapPlaceholder(final JSONObject obj, final int size) {
            this.obj = obj;
            this.size = size;
        }
    }

    private static final class ArrayPlaceholder {
        final JSONArray array;
        final int length;

        ArrayPlaceholder(final JSONArray array, final int length) {
            this.array = array;
            this.length = length;
        }
    }
}
