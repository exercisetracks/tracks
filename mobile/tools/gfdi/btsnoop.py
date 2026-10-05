# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Android btsnoop capture -> ATT writes and notifications.

Deliberately not tshark. tshark would do the L2CAP reassembly for us, but it
is a build-time dependency on a machine that does not have it, and the part it
would save is ~80 lines. Everything above ATT — the Garmin multi-link demux,
COBS, GFDI — has to be written here regardless, so the saving was never the
interesting half.

btsnoop is the format Android's HCI snoop log is written in: a 16-byte file
header, then one record per HCI packet. With the H4 datalink the record data
begins with a one-byte packet type, so ACL data is 0x02.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass

BTSNOOP_MAGIC = b"btsnoop\x00"

# Packet flags, per record.
FLAG_RECEIVED = 0x01  # controller -> host, i.e. the watch talking to us
FLAG_COMMAND = 0x02  # HCI command/event rather than data

HCI_ACL = 0x02

# ATT opcodes we care about: the two that carry payload towards the watch, the
# two that carry it back, and the two that let us name the handles involved.
ATT_FIND_INFO_RSP = 0x05
ATT_READ_BY_TYPE_RSP = 0x09
ATT_WRITE_REQ = 0x12
ATT_HANDLE_VALUE_NOTIFICATION = 0x1B
ATT_HANDLE_VALUE_INDICATION = 0x1D
ATT_WRITE_CMD = 0x52

L2CAP_CID_ATT = 0x0004


@dataclass
class AttPacket:
    """One ATT PDU that carried a characteristic value."""

    timestamp_us: int
    from_watch: bool
    conn_handle: int
    att_handle: int
    value: bytes


def _records(data: bytes):
    if not data.startswith(BTSNOOP_MAGIC):
        raise ValueError("not a btsnoop file")
    (version, datalink) = struct.unpack_from(">II", data, 8)
    if version != 1:
        raise ValueError(f"unsupported btsnoop version {version}")
    pos = 16
    while pos + 24 <= len(data):
        orig_len, incl_len, flags, drops, ts = struct.unpack_from(">IIIIq", data, pos)
        pos += 24
        payload = data[pos : pos + incl_len]
        pos += incl_len
        if len(payload) < incl_len:
            break
        yield flags, ts, payload, datalink


def _acl_payloads(data: bytes):
    """ACL fragments, as (from_watch, timestamp, conn_handle, pb_flag, payload)."""
    for flags, ts, payload, datalink in _records(data):
        if flags & FLAG_COMMAND:
            continue
        if datalink in (1001, 1002):  # H1/H4: leading packet-type byte
            if not payload or payload[0] != HCI_ACL:
                continue
            payload = payload[1:]
        if len(payload) < 4:
            continue
        handle_flags, length = struct.unpack_from("<HH", payload, 0)
        body = payload[4 : 4 + length]
        yield (
            bool(flags & FLAG_RECEIVED),
            ts,
            handle_flags & 0x0FFF,
            (handle_flags >> 12) & 0x03,
            body,
        )


def att_packets(path: str) -> list[AttPacket]:
    """Every ATT PDU in the capture that carried a characteristic value.

    L2CAP reassembly is per (connection, direction): a continuation fragment
    belongs to whichever message was last started on that pair. That is the
    whole of the state machine — BLE never interleaves two L2CAP messages on
    one CID in one direction.
    """
    with open(path, "rb") as handle:
        data = handle.read()

    pending: dict[tuple[int, bool], tuple[bytes, int, int]] = {}
    out: list[AttPacket] = []

    def emit(from_watch: bool, ts: int, conn: int, frame: bytes) -> None:
        if len(frame) < 4:
            return
        l2_len, cid = struct.unpack_from("<HH", frame, 0)
        if cid != L2CAP_CID_ATT:
            return
        pdu = frame[4 : 4 + l2_len]
        if not pdu:
            return
        op = pdu[0]
        if op in (
            ATT_WRITE_REQ,
            ATT_WRITE_CMD,
            ATT_HANDLE_VALUE_NOTIFICATION,
            ATT_HANDLE_VALUE_INDICATION,
        ):
            if len(pdu) < 3:
                return
            (att_handle,) = struct.unpack_from("<H", pdu, 1)
            out.append(AttPacket(ts, from_watch, conn, att_handle, pdu[3:]))

    for from_watch, ts, conn, pb, body in _acl_payloads(data):
        key = (conn, from_watch)
        if pb == 0x01:  # continuation
            buffered = pending.get(key)
            if buffered is None:
                continue
            frame, want, start_ts = buffered
            frame += body
            if len(frame) >= want:
                emit(from_watch, start_ts, conn, frame)
                pending.pop(key, None)
            else:
                pending[key] = (frame, want, start_ts)
            continue
        # start fragment
        pending.pop(key, None)
        if len(body) < 4:
            continue
        (l2_len,) = struct.unpack_from("<H", body, 0)
        if len(body) >= l2_len + 4:
            emit(from_watch, ts, conn, body)
        else:
            pending[key] = (body, l2_len + 4, ts)

    return out


def handle_uuids(path: str) -> dict[int, str]:
    """value-handle -> characteristic UUID, learned from GATT discovery.

    Only populated when the capture starts before the app discovers services,
    which is why the capture instructions say to cycle Bluetooth first. Callers
    fall back to content sniffing when this comes back empty.
    """
    with open(path, "rb") as handle:
        data = handle.read()

    uuids: dict[int, str] = {}
    for from_watch, ts, conn, pb, body in _acl_payloads(data):
        if pb == 0x01 or len(body) < 5:
            continue
        l2_len, cid = struct.unpack_from("<HH", body, 0)
        if cid != L2CAP_CID_ATT:
            continue
        pdu = body[4 : 4 + l2_len]
        if not pdu:
            continue
        if pdu[0] == ATT_READ_BY_TYPE_RSP and len(pdu) > 2:
            item_len = pdu[1]
            for off in range(2, len(pdu) - item_len + 1, item_len):
                item = pdu[off : off + item_len]
                if len(item) < 5:
                    continue
                # characteristic declaration: decl handle, properties,
                # value handle, UUID
                (value_handle,) = struct.unpack_from("<H", item, 3)
                uuids[value_handle] = _uuid_str(item[5:])
        elif pdu[0] == ATT_FIND_INFO_RSP and len(pdu) > 1:
            fmt = pdu[1]
            item_len = 4 if fmt == 1 else 18
            for off in range(2, len(pdu) - item_len + 1, item_len):
                item = pdu[off : off + item_len]
                (att_handle,) = struct.unpack_from("<H", item, 0)
                uuids.setdefault(att_handle, _uuid_str(item[2:]))
    return uuids


def _uuid_str(raw: bytes) -> str:
    if len(raw) == 2:
        return f"0000{struct.unpack('<H', raw)[0]:04x}-0000-1000-8000-00805f9b34fb"
    if len(raw) == 16:
        b = raw[::-1]
        return (
            f"{b[0:4].hex()}-{b[4:6].hex()}-{b[6:8].hex()}-{b[8:10].hex()}-{b[10:16].hex()}"
        )
    return raw.hex()
