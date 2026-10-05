#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Which watches Tracks Music is built for, and how the phone tells them apart.

The list is not hand-kept. Every device definition the Connect IQ SDK Manager
downloads (~/.Garmin/ConnectIQ/Devices/<id>/compiler.json) says which app types
that watch accepts and how much memory each gets. A watch whose definition has
an `audioContentProvider` entry can run this app; one without cannot install it
at all, so there is nothing to decide by hand except what this script reads.

The same file carries the watch's Garmin part numbers — `006-B3291-00` and its
regional siblings — and the middle number (3291) is the product number the
watch announces in its Bluetooth DEVICE_INFORMATION message. That is how the
phone picks the right build: by what the watch says it is, not by matching its
Bluetooth name, which the user can change and which several models share.

    ./devices.py list                 # device ids, one per line
    ./devices.py manifest             # rewrite the <iq:products> block
    ./devices.py manifest --check     # fail if the manifest is stale
    ./devices.py bundle <builds> <assets>   # pack builds/<id>/<id>.prg for the phone

Download every device in the SDK Manager before running `manifest`: a device
whose definition is absent is not a device this script can know about.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "manifest.xml"
DEVICES = Path(os.environ.get("CIQ_DEVICES", Path.home() / ".Garmin/ConnectIQ/Devices"))

BEGIN = "<!-- BEGIN devices.py -->"
END = "<!-- END devices.py -->"

PART_NUMBER = re.compile(r"^006-B(\d+)-\d\d$")


def definitions() -> dict[str, dict]:
    out = {}
    for d in sorted(DEVICES.iterdir()) if DEVICES.is_dir() else []:
        f = d / "compiler.json"
        if f.is_file():
            out[d.name] = json.loads(f.read_text())
    return out


def music_devices() -> dict[str, dict]:
    """Device id -> definition, for every watch that accepts an audio provider."""
    return {
        name: j for name, j in definitions().items()
        if any(t.get("type") == "audioContentProvider" for t in j.get("appTypes", []))
    }


def product_numbers(definition: dict) -> list[int]:
    """Every Garmin product number one definition covers.

    One definition is often several products — the fēnix 6X Pro definition is
    also the Sapphire, the Solar and the Asia-Pacific variants, each with its own
    part number — so this is a list, and all of them map to the same build.
    """
    nums = set()
    for pn in definition.get("partNumbers", []):
        m = PART_NUMBER.match(pn.get("number", ""))
        if m:
            nums.add(int(m.group(1)))
    return sorted(nums)


def write_manifest(check: bool) -> int:
    devices = music_devices()
    if not devices:
        print(f"No device definitions under {DEVICES}. Download them in the SDK Manager.",
              file=sys.stderr)
        return 1
    text = MANIFEST.read_text()
    start, end = text.index(BEGIN), text.index(END)
    indent = " " * (start - text.rindex("\n", 0, start) - 1)
    lines = [BEGIN] + [f'<iq:product id="{d}"/>' for d in devices] + [END]
    new = text[:start] + ("\n" + indent).join(lines) + text[end + len(END):]
    if new == text:
        return 0
    if check:
        print("manifest.xml is out of date with the SDK's device definitions; "
              "run ./devices.py manifest", file=sys.stderr)
        return 1
    MANIFEST.write_text(new)
    print(f"manifest.xml: {len(devices)} devices")
    return 0


def write_bundle(builds: Path, assets: Path) -> int:
    """Pack every build under `builds` into the bundle the phone app ships.

    Two files, both read by the phone's WatchAppBundle:

    - `TracksMusic.xz`: every .prg, back to back, in one xz stream. One stream
      rather than one file each, because the builds differ by a few bytes —
      device id, resource tables, signature — and per-file compression cannot
      see across files: 93 builds are 6.8 MB compressed separately and about
      0.2 MB together.
    - `index.json`: which product numbers each build is for, and where in the
      stream it starts and ends, with a sha256 the phone checks before sending
      anything to a watch. A wrong slice of this stream would otherwise install
      as a corrupt app.

    The dictionary is 4 MiB rather than xz's 64 MiB default for -9: matches only
    need to reach back one build (~190 KB), and the decoder allocates the whole
    dictionary up front, which on a phone is memory worth not asking for. It
    costs about 7% against 64 MiB.

    Fails rather than skips when one product number is claimed by two
    definitions: the phone would install whichever came last, and a build for
    the wrong screen is an install that fails on the watch with nothing on the
    phone to say why.
    """
    import hashlib
    import lzma

    devices = music_devices()
    built = [name for name in devices if (builds / name / f"{name}.prg").is_file()]
    if not built:
        print(f"no builds under {builds}", file=sys.stderr)
        return 1

    stream = bytearray()
    entries: dict[str, dict] = {}
    products: dict[str, str] = {}
    for name in built:
        prg = (builds / name / f"{name}.prg").read_bytes()
        entries[name] = {
            "name": devices[name].get("displayName", name),
            "offset": len(stream),
            "length": len(prg),
            "sha256": hashlib.sha256(prg).hexdigest(),
        }
        stream += prg
        for n in product_numbers(devices[name]):
            if str(n) in products and products[str(n)] != name:
                print(f"product {n} claimed by both {products[str(n)]} and {name}",
                      file=sys.stderr)
                return 1
            products[str(n)] = name

    packed = lzma.compress(bytes(stream), format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64,
                           filters=[{"id": lzma.FILTER_LZMA2,
                                     "preset": 9 | lzma.PRESET_EXTREME,
                                     "dict_size": 4 << 20}])
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "TracksMusic.xz").write_bytes(packed)
    index = {
        "format": 1,
        "builds": entries,
        "products": dict(sorted(products.items(), key=lambda kv: int(kv[0]))),
    }
    (assets / "index.json").write_text(json.dumps(index, indent=1, ensure_ascii=False) + "\n")
    print(f"Bundled {len(built)} builds ({len(products)} product numbers), "
          f"{len(stream)} -> {len(packed)} bytes -> {assets}")
    return 0


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "list"
    if cmd == "list":
        print("\n".join(music_devices()))
        return 0
    if cmd == "manifest":
        return write_manifest(check="--check" in argv)
    if cmd == "bundle" and len(argv) > 3:
        return write_bundle(Path(argv[2]), Path(argv[3]))
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
