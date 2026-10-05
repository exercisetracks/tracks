# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""btsnoop capture -> decoded GFDI transcript, for apps that are not ours.

Tracks logs its own GFDI frames, so this exists for the other side of the
question: what Garmin's apps send. The layers between an ATT write and a GFDI
message are Garmin's multi-link demux (a one-byte service handle, or a two-byte
reliable-transport header when the high bit is set) and a COBS framing whose
variant carries a leading zero as well as a trailing one.

Cross-checked against Tracks' own logcat before being pointed at anything else:
if this and the app disagree about the app's own traffic, this is wrong.
"""
from __future__ import annotations

import sys
import zlib
from collections import defaultdict

sys.path.insert(0, __file__.rsplit("/", 1)[0])

import btsnoop  # noqa: E402
from decode_log import GFDI_MESSAGES, describe_gfdi  # noqa: E402

MLR_FLAG = 0x80
MLR_HANDLE_MASK = 0x70
MLR_HANDLE_SHIFT = 4

# CommunicatorV2.Service
SERVICES = {
    1: "GFDI", 4: "REGISTRATION", 6: "REALTIME_HR", 7: "REALTIME_STEPS",
    8: "REALTIME_CALORIES", 10: "REALTIME_INTENSITY", 12: "REALTIME_HRV",
    13: "REALTIME_STRESS", 16: "REALTIME_ACCEL", 19: "REALTIME_SPO2",
    20: "REALTIME_BODY_BATTERY", 21: "REALTIME_RESPIRATION",
    0x2018: "FILE_TRANSFER_2", 0x4018: "FILE_TRANSFER_4", 0x6018: "FILE_TRANSFER_6",
    0xA018: "FILE_TRANSFER_A", 0xC018: "FILE_TRANSFER_C", 0xE018: "FILE_TRANSFER_E",
}

REQUEST_TYPES = [
    "REGISTER_ML_REQ", "REGISTER_ML_RESP", "CLOSE_HANDLE_REQ", "CLOSE_HANDLE_RESP",
    "UNK_HANDLE", "CLOSE_ALL_REQ", "CLOSE_ALL_RESP", "UNK_REQ", "UNK_RESP",
]


class CobsStream:
    """Garmin's COBS variant, fed a fragment at a time.

    Differs from textbook COBS by a leading zero byte, so a message is framed
    by zeros on both ends rather than only at the end.
    """

    def __init__(self) -> None:
        self.buf = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self.buf.extend(data)
        out: list[bytes] = []
        while True:
            message = self._take()
            if message is None:
                return out
            if message:
                out.append(message)

    def _take(self) -> bytes | None:
        # Find a complete zero-delimited frame.
        try:
            start = self.buf.index(0)
        except ValueError:
            return None
        try:
            end = self.buf.index(0, start + 1)
        except ValueError:
            return None
        frame = bytes(self.buf[start + 1 : end])
        # Consume up to but NOT including the closing zero: Garmin frames as
        # 00 <cobs> 00, so back-to-back messages share a delimiter and the zero
        # that ends this one begins the next. Eating it made the parser lock
        # onto the wrong parity for any stream that began mid-message — which
        # is every capture, since the ring buffer starts wherever it starts —
        # and from then on it only ever saw empty frames.
        del self.buf[:end]
        return self._decode(frame)

    @staticmethod
    def _decode(frame: bytes) -> bytes:
        out = bytearray()
        pos = 0
        while pos < len(frame):
            code = frame[pos]
            pos += 1
            if code == 0:
                break
            take = code - 1
            out.extend(frame[pos : pos + take])
            pos += take
            if code != 0xFF and pos < len(frame):
                out.append(0)
        return bytes(out)


def main() -> int:
    path = sys.argv[1]
    packets = btsnoop.att_packets(path)
    uuids = btsnoop.handle_uuids(path)

    # Garmin's multi-link characteristics are 6A4E28xx. When GATT discovery is
    # not in the capture, fall back to whichever handles carry the most traffic
    # — on a watch connection that is overwhelmingly this service.
    garmin_handles = {h for h, u in uuids.items() if u.lower().startswith("6a4e28")}
    if not garmin_handles:
        counts: dict[int, int] = defaultdict(int)
        for packet in packets:
            counts[packet.att_handle] += len(packet.value)
        garmin_handles = {h for h, _ in sorted(counts.items(), key=lambda kv: -kv[1])[:2]}
        print(f"# no GATT discovery in capture; guessing handles {[hex(h) for h in garmin_handles]}",
              file=sys.stderr)

    streams: dict[tuple[bool, int], CobsStream] = defaultdict(CobsStream)
    transfer_streams: dict[tuple[bool, int], bytearray] = defaultdict(bytearray)
    transfer_handles: set[int] = set()
    gfdi_seen: set[int] = set()
    service_by_handle: dict[int, str] = {}
    first_ts = packets[0].timestamp_us if packets else 0

    for packet in packets:
        if packet.att_handle not in garmin_handles or not packet.value:
            continue
        value = packet.value
        byte0 = value[0]

        if byte0 & MLR_FLAG:
            handle = (byte0 & MLR_HANDLE_MASK) >> MLR_HANDLE_SHIFT
            payload = value[2:]
        elif byte0 == 0x00:
            _handle_management(value, service_by_handle, transfer_handles)
            continue
        else:
            handle = byte0
            payload = value[1:]

        if not payload:
            continue

        # Only GFDI is COBS-framed. The file-transfer services write raw bytes
        # (see CommunicatorV2.MlServiceWriter, which prefixes a handle byte and
        # nothing else), so COBS-decoding them turns a perfectly good zlib
        # stream into noise. A transfer channel opens with a 6-byte header —
        # 00 <00 download | 01 upload> <transfer handle LE> 00 00 — and then
        # carries the deflated file.
        transfer_streams[(packet.from_watch, handle)].extend(payload)
        if handle in transfer_handles:
            continue

        for message in streams[(packet.from_watch, handle)].feed(payload):
            when = (packet.timestamp_us - first_ts) / 1_000_000
            who = "watch->phone" if packet.from_watch else "phone->watch"
            service = service_by_handle.get(handle, f"handle {handle}")
            print(f"[{when:9.3f}] {who}  ({service})")
            # Only a *recognised* message type proves this handle is GFDI.
            # COBS-decoding a raw transfer stream still yields "frames", they
            # are just nonsense, so counting any output would mark every
            # channel as GFDI and hide the file transfers.
            if len(message) >= 4:
                msg_type = int.from_bytes(message[2:4], "little")
                if msg_type & 0x8000:
                    msg_type = (msg_type & 0xFF) + 5000
                if msg_type in GFDI_MESSAGES:
                    gfdi_seen.add(handle)
                else:
                    continue
            for line in describe_gfdi(message):
                print(line)

    # A handle that never produced a GFDI frame was not GFDI. Classifying by
    # outcome rather than by registration matters because a ring-buffer capture
    # usually starts after the services were registered, so the REGISTER_ML_RESP
    # that would have named them is long gone.
    for (from_watch, handle), blob in sorted(transfer_streams.items()):
        if handle in gfdi_seen or len(blob) < 6:
            continue
        who = "watch->phone" if from_watch else "phone->watch"
        print(f"\n[transfer] {who} ML handle {handle}: {len(blob)} bytes")
        print("  " + _describe_transfer(bytes(blob)))
    return 0


def _describe_transfer(blob: bytes) -> str:
    """A transfer channel: 6-byte header then a zlib stream."""
    if len(blob) < 6:
        return f"too short: {blob.hex()}"
    direction = "upload" if blob[1] == 1 else "download"
    target = int.from_bytes(blob[2:4], "little")
    try:
        body = zlib.decompress(blob[6:])
    except zlib.error as exc:
        return f"{direction} handle {target}: could not inflate ({exc})"
    kind = "FIT" if body[8:12] == b".FIT" else "unknown"
    return f"{direction} handle {target}: {len(body)} bytes, {kind}"


def _handle_management(value: bytes, service_by_handle: dict[int, str],
                       transfer_handles: set[int]) -> None:
    if len(value) < 11:
        return
    request = value[1]
    name = REQUEST_TYPES[request] if request < len(REQUEST_TYPES) else f"REQ_{request}"
    if name == "REGISTER_ML_RESP" and len(value) >= 15:
        code = int.from_bytes(value[10:12], "little")
        status = value[12]
        handle = value[13]
        if status == 0:
            service = SERVICES.get(code, f"service 0x{code:04x}")
            service_by_handle[handle] = service
            if service.startswith("FILE_TRANSFER"):
                transfer_handles.add(handle)


if __name__ == "__main__":
    raise SystemExit(main())
