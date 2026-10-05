"""A FIT record reader small enough to trust in a test.

Deliberately not the encoder's own SDK: a test that round-trips through the
same library would pass just as happily if the library changed what the fields
mean. This walks the bytes.
"""
from __future__ import annotations

import struct

_SIZES = {0: 1, 1: 1, 2: 1, 3: 2, 4: 2, 5: 4, 6: 4, 7: 1, 8: 4, 9: 8,
          10: 1, 11: 2, 12: 4, 13: 1, 14: 8, 15: 8, 16: 8}
_FMTS = {0: "B", 1: "b", 2: "B", 3: "h", 4: "H", 5: "i", 6: "I", 8: "f",
         9: "d", 10: "B", 11: "H", 12: "I", 13: "B", 14: "q", 15: "Q", 16: "Q"}


def read_messages(raw: bytes) -> list[dict]:
    """Return each data record as {"mesg_num": n, <field_def>: value}."""
    header_size = raw[0]
    end = header_size + struct.unpack("<I", raw[4:8])[0]
    pos = header_size
    definitions: dict[int, tuple] = {}
    out: list[dict] = []

    while pos < end:
        record_header = raw[pos]
        pos += 1
        local = record_header & 0x0F

        if record_header & 0x40:                       # definition record
            endian = ">" if raw[pos + 1] else "<"
            pos += 2
            global_num = struct.unpack(endian + "H", raw[pos:pos + 2])[0]
            pos += 2
            count = raw[pos]
            pos += 1
            fields = []
            for _ in range(count):
                fields.append((raw[pos], raw[pos + 1], raw[pos + 2] & 0x1F))
                pos += 3
            if record_header & 0x20:                   # developer fields
                dev_count = raw[pos]
                pos += 1
                fields_dev = [(raw[pos + i * 3 + 1]) for i in range(dev_count)]
                pos += dev_count * 3
            else:
                fields_dev = []
            definitions[local] = (global_num, endian, fields, fields_dev)
            continue

        global_num, endian, fields, fields_dev = definitions[local]
        message = {"mesg_num": global_num}
        for field_def, size, base_type in fields:
            chunk = raw[pos:pos + size]
            pos += size
            fmt = _FMTS.get(base_type)
            if fmt is None or size % _SIZES[base_type]:
                message[field_def] = chunk
                continue
            values = struct.unpack(endian + fmt * (size // _SIZES[base_type]), chunk)
            message[field_def] = values[0] if len(values) == 1 else list(values)
        pos += sum(fields_dev)
        out.append(message)

    return out
