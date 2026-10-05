# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Turn a Tracks logcat into a readable GFDI transcript.

The app already logs every GFDI frame it sends and receives as hex — that is
Gadgetbridge's own debug logging, not something added for this — so for our
own traffic there is nothing to capture at the HCI level. What was missing was
a reader.

The part that earns this tool: protobuf sub-messages the vendored .proto files
do not describe get dropped from the generated lite classes' toString(), so an
undocumented service shows up in the log as an empty object. Decoding the hex
generically shows the fields anyway, which is exactly where an unknown
operation would first become visible.
"""
from __future__ import annotations

import re
import sys

# GFDIMessage.GarminMessage, kept here rather than parsed out of the Java so
# this runs standalone. Gaps in the numbering are real: they are message types
# nobody has identified yet, and an unrecognised id in a transcript is a
# finding rather than a bug.
GFDI_MESSAGES = {
    5000: "RESPONSE", 5002: "DOWNLOAD_REQUEST", 5003: "UPLOAD_REQUEST",
    5004: "FILE_TRANSFER_DATA", 5005: "CREATE_FILE", 5007: "FILTER",
    5008: "SET_FILE_FLAG", 5009: "FILE_AVAILABLE", 5011: "FIT_DEFINITION",
    5012: "FIT_DATA", 5014: "WEATHER_REQUEST", 5023: "BATTERY_STATUS",
    5024: "DEVICE_INFORMATION", 5026: "DEVICE_SETTINGS", 5030: "SYSTEM_EVENT",
    5031: "SUPPORTED_FILE_TYPES_REQUEST", 5033: "NOTIFICATION_UPDATE",
    5034: "NOTIFICATION_CONTROL", 5035: "NOTIFICATION_DATA",
    5036: "NOTIFICATION_SUBSCRIPTION", 5037: "SYNCHRONIZATION",
    5039: "FIND_MY_PHONE_REQUEST", 5040: "FIND_MY_PHONE_CANCEL",
    5041: "MUSIC_CONTROL", 5042: "MUSIC_CONTROL_CAPABILITIES",
    5043: "PROTOBUF_REQUEST", 5044: "PROTOBUF_RESPONSE",
    5049: "MUSIC_CONTROL_ENTITY_UPDATE", 5050: "CONFIGURATION",
    5052: "CURRENT_TIME_REQUEST", 5101: "AUTH_NEGOTIATION",
}

# Smart (gdi_smart_proto.proto) field number -> service, so a protobuf blob can
# be named without a compiled descriptor.
SMART_SERVICES = {
    1: "calendar_service", 2: "http_service", 3: "installed_apps_service",
    4: "app_config_service", 7: "data_transfer_service", 8: "device_status_service",
    12: "find_my_watch_service", 13: "core_service", 16: "sms_notification_service",
    22: "explore_sync_service", 27: "authentication_service", 39: "ecg_service",
    42: "settings_service", 43: "file_sync_service", 49: "notifications_service",
}

EXPLORE_SYNC_FIELDS = {
    1: "start_sync_request", 2: "start_sync_response", 3: "sync_finished_notification",
    4: "collection_list_write_request", 5: "collection_list_write_response",
    6: "collection_read_request", 7: "collection_read_response",
    8: "collection_digest_write_request", 9: "collection_digest_write_response",
    10: "collection_digest_read_request", 11: "collection_digest_read_response",
    12: "waypoint_read_request", 13: "waypoint_read_response",
    14: "waypoint_digest_write_request", 15: "waypoint_digest_write_response",
    16: "waypoint_digest_read_request", 17: "waypoint_digest_read_response",
    18: "change_summary_request", 19: "change_summary_response",
    24: "line_digest_write_request", 25: "line_digest_write_response",
    26: "line_digest_read_request", 27: "line_digest_read_response",
    28: "line_data_ready_request", 29: "line_data_ready_response",
    30: "line_read_request", 31: "line_read_response",
    32: "active_line_digest_write_request", 33: "active_line_digest_write_response",
}

FILE_SYNC_FIELDS = {
    1: "file_request", 2: "file_response", 9: "file_list_request",
    10: "file_list_response", 12: "new_file_notification", 15: "file_set_flags",
    17: "file_update_notification",
}


def read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while pos < len(buf):
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7
    raise ValueError("truncated varint")


def decode_protobuf(buf: bytes, depth: int = 0, names: dict | None = None) -> list[str]:
    """Best-effort field dump. Length-delimited fields are shown as a nested
    message when they parse cleanly as one and as bytes/string otherwise —
    the usual ambiguity, resolved the usual way."""
    out: list[str] = []
    pad = "  " * depth
    pos = 0
    while pos < len(buf):
        try:
            key, pos = read_varint(buf, pos)
        except ValueError:
            out.append(f"{pad}<trailing {buf[pos:].hex()}>")
            break
        field, wire = key >> 3, key & 0x07
        label = f"{field}"
        if names and field in names:
            label = f"{field} ({names[field]})"
        if wire == 0:
            value, pos = read_varint(buf, pos)
            out.append(f"{pad}{label}: {value}")
        elif wire == 1:
            value = int.from_bytes(buf[pos : pos + 8], "little")
            pos += 8
            out.append(f"{pad}{label}: fixed64 {value} (0x{value:x})")
        elif wire == 5:
            value = int.from_bytes(buf[pos : pos + 4], "little")
            pos += 4
            out.append(f"{pad}{label}: fixed32 {value} (0x{value:x})")
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            body = buf[pos : pos + length]
            pos += length
            nested = _try_nested(body)
            if nested is not None and body:
                sub_names = None
                if names is SMART_SERVICES:
                    if field == 43:
                        sub_names = FILE_SYNC_FIELDS
                    elif field == 22:
                        sub_names = EXPLORE_SYNC_FIELDS
                out.append(f"{pad}{label} {{")
                out.extend(decode_protobuf(body, depth + 1, sub_names))
                out.append(f"{pad}}}")
            elif _printable(body):
                out.append(f'{pad}{label}: "{body.decode("utf-8", "replace")}"')
            else:
                out.append(f"{pad}{label}: bytes[{len(body)}] {body.hex()}")
        else:
            out.append(f"{pad}<unsupported wire type {wire}>")
            break
    return out


def _try_nested(body: bytes):
    if not body:
        return None
    try:
        pos = 0
        seen = 0
        while pos < len(body):
            key, pos = read_varint(body, pos)
            wire = key & 0x07
            if key >> 3 == 0:
                return None
            if wire == 0:
                _, pos = read_varint(body, pos)
            elif wire == 1:
                pos += 8
            elif wire == 5:
                pos += 4
            elif wire == 2:
                length, pos = read_varint(body, pos)
                pos += length
            else:
                return None
            seen += 1
        return True if pos == len(body) and seen else None
    except ValueError:
        return None


def _printable(body: bytes) -> bool:
    if not body:
        return False
    try:
        text = body.decode("ascii")
    except UnicodeDecodeError:
        return False
    return all(32 <= ord(c) < 127 for c in text)


def describe_gfdi(raw: bytes) -> list[str]:
    """A GFDI frame: length, message type, body, CRC."""
    if len(raw) < 6:
        return [f"  <short frame {raw.hex()}>"]
    length = int.from_bytes(raw[0:2], "little")
    msg_type = int.from_bytes(raw[2:4], "little")
    if msg_type & 0x8000:
        msg_type = (msg_type & 0xFF) + 5000
    name = GFDI_MESSAGES.get(msg_type, "UNKNOWN")
    body = raw[4:-2]
    lines = [f"  {msg_type} {name}  len={length} body={len(body)}B"]
    if msg_type in (5043, 5044) and len(body) >= 14:
        # ProtobufMessage: requestId (2), dataOffset (4), totalLength (4),
        # chunkLength (4), then the payload. A chunked message carries only a
        # slice here; reassembly across chunks is left to the reader, since a
        # transcript wants to show what each frame actually said.
        request_id = int.from_bytes(body[0:2], "little")
        total = int.from_bytes(body[6:10], "little")
        chunk = int.from_bytes(body[10:14], "little")
        payload = body[14 : 14 + chunk]
        note = "" if chunk == total else f" (chunk of {total}B)"
        lines.append(f"    protobuf #{request_id}, {len(payload)}B{note}")
        lines.extend("    " + s for s in decode_protobuf(payload, 0, SMART_SERVICES))
    elif msg_type == 5008 and len(body) >= 3:
        index = int.from_bytes(body[0:2], "little")
        lines.append(f"    fileIndex={index} flags=0x{body[2]:02x}")
    elif msg_type == 5005 and len(body) >= 6:
        size = int.from_bytes(body[0:4], "little")
        lines.append(f"    size={size} type={body[4]}/{body[5]}")
    else:
        lines.append(f"    {body.hex()}")
    return lines


LINE = re.compile(r"(OUTGOING|INCOMING) ([^:]+): ([0-9a-f]+)", re.I)
PROTO = re.compile(r"protobuf message #(\d+), (\d+)B: ([0-9a-f]+)", re.I)


def main() -> int:
    source = open(sys.argv[1]) if len(sys.argv) > 1 else sys.stdin
    for line in source:
        stamp = line[:18].strip()
        match = LINE.search(line)
        if match:
            direction, task, hexdata = match.groups()
            print(f"{stamp} {direction:<8} {task.strip()}")
            for out in describe_gfdi(bytes.fromhex(hexdata)):
                print(out)
            continue
        match = PROTO.search(line)
        if match:
            request_id, _, hexdata = match.groups()
            print(f"{stamp} PROTOBUF #{request_id}")
            for out in decode_protobuf(bytes.fromhex(hexdata), 1, SMART_SERVICES):
                print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
