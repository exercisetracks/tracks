"""Extract every file-transfer payload out of a btsnoop capture."""
import sys, zlib, os
from collections import defaultdict
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import btsnoop
from decode_capture import CobsStream, MLR_FLAG, MLR_HANDLE_MASK, MLR_HANDLE_SHIFT, _handle_management
from decode_log import GFDI_MESSAGES

path, outdir = sys.argv[1], sys.argv[2]
os.makedirs(outdir, exist_ok=True)
packets = btsnoop.att_packets(path)
uuids = btsnoop.handle_uuids(path)
gh = {h for h, u in uuids.items() if u.lower().startswith("6a4e28")}
if not gh:
    counts = defaultdict(int)
    for p in packets: counts[p.att_handle] += len(p.value)
    gh = {h for h, _ in sorted(counts.items(), key=lambda kv: -kv[1])[:2]}

streams = defaultdict(CobsStream)
blobs = defaultdict(bytearray)
transfer_handles, gfdi_seen = set(), set()
svc = {}
for p in packets:
    if p.att_handle not in gh or not p.value: continue
    v = p.value; b0 = v[0]
    if b0 & MLR_FLAG:
        h = (b0 & MLR_HANDLE_MASK) >> MLR_HANDLE_SHIFT; pay = v[2:]
    elif b0 == 0x00:
        _handle_management(v, svc, transfer_handles); continue
    else:
        h = b0; pay = v[1:]
    if not pay: continue
    blobs[(p.from_watch, h)].extend(pay)
    if h in transfer_handles: continue
    for m in streams[(p.from_watch, h)].feed(pay):
        if len(m) >= 4:
            t = int.from_bytes(m[2:4], "little")
            if t & 0x8000: t = (t & 0xFF) + 5000
            if t in GFDI_MESSAGES: gfdi_seen.add(h)

for (from_watch, h), blob in sorted(blobs.items()):
    if h in gfdi_seen or len(blob) < 6: continue
    who = "watch" if from_watch else "phone"
    blob = bytes(blob)
    direction = "upload" if blob[1] == 1 else "download"
    target = int.from_bytes(blob[2:4], "little")
    name = f"{who}-ml{h}-{direction}-t{target}"
    # There may be several transfers concatenated on one ML handle: walk zlib
    # streams one after another.
    rest, n = blob[6:], 0
    while rest:
        d = zlib.decompressobj()
        try: body = d.decompress(rest)
        except zlib.error as e:
            print(f"{name}: stop ({e}) with {len(rest)} bytes left"); break
        if not body: break
        out = os.path.join(outdir, f"{name}-{n}.fit")
        open(out, "wb").write(body)
        print(f"{name}-{n}: {len(body)} bytes -> {out}  hdr={body[8:12]}")
        rest = d.unused_data; n += 1
        if rest[:6].hex().startswith("00") and len(rest) > 6 and rest[6:8] == b"\x78\x9c":
            rest = rest[6:]
