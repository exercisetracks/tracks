#!/usr/bin/env python3
"""Generate the player's control-menu icons as PNGs.

No drawing library here, so this rasterises by hand: thick lines via a
distance test, filled triangles for arrowheads, and a ring for repeat. White
with an alpha channel, which is what the media player expects to tint.
"""
import struct
import zlib
import math
import sys

S = 36          # canvas, square
W = (255, 255, 255)   # set per run; see main


def canvas():
    return [[(0, 0, 0, 0) for _ in range(S)] for _ in range(S)]


def put(img, x, y, a=255):
    if 0 <= x < S and 0 <= y < S:
        _, _, _, old = img[y][x]
        if a > old:
            img[y][x] = (W[0], W[1], W[2], a)


def seg(img, x0, y0, x1, y1, width=3.0):
    """Thick anti-aliased line segment."""
    half = width / 2.0
    minx, maxx = int(min(x0, x1) - half - 2), int(max(x0, x1) + half + 2)
    miny, maxy = int(min(y0, y1) - half - 2), int(max(y0, y1) + half + 2)
    dx, dy = x1 - x0, y1 - y0
    ll = dx * dx + dy * dy
    for y in range(miny, maxy + 1):
        for x in range(minx, maxx + 1):
            px, py = x + 0.5, y + 0.5
            t = 0.0 if ll == 0 else max(0.0, min(1.0, ((px - x0) * dx + (py - y0) * dy) / ll))
            cx, cy = x0 + t * dx, y0 + t * dy
            d = math.hypot(px - cx, py - cy)
            if d <= half - 0.5:
                put(img, x, y, 255)
            elif d < half + 0.5:
                put(img, x, y, int(255 * (half + 0.5 - d)))


def tri(img, pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    for y in range(int(min(ys)) - 1, int(max(ys)) + 2):
        for x in range(int(min(xs)) - 1, int(max(xs)) + 2):
            px, py = x + 0.5, y + 0.5
            inside = True
            sign = None
            for i in range(3):
                ax, ay = pts[i]
                bx, by = pts[(i + 1) % 3]
                cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
                s = cross > 0
                if sign is None:
                    sign = s
                elif s != sign:
                    inside = False
                    break
            if inside:
                put(img, x, y, 255)


def ring(img, cx, cy, r, width=3.0, start=0.0, end=360.0):
    half = width / 2.0
    for y in range(S):
        for x in range(S):
            px, py = x + 0.5, y + 0.5
            d = math.hypot(px - cx, py - cy)
            if abs(d - r) > half + 0.5:
                continue
            ang = (math.degrees(math.atan2(py - cy, px - cx)) + 360) % 360
            lo, hi = start % 360, end % 360
            ok = (lo <= ang <= hi) if lo <= hi else (ang >= lo or ang <= hi)
            if not ok:
                continue
            e = abs(d - r)
            put(img, x, y, 255 if e <= half - 0.5 else int(255 * (half + 0.5 - e)))


def write_png(path, img):
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("BBBB", *img[y][x]) for x in range(S))
        for y in range(S)
    )

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    open(path, "wb").write(png)
    print("wrote", path, len(png), "bytes")


def arrow_right(img, tipx, y, size=6):
    tri(img, [(tipx, y), (tipx - size, y - size), (tipx - size, y + size)])


def shuffle(crossed=True):
    """Two arrows crossing (on) or running straight (off)."""
    img = canvas()
    if crossed:
        seg(img, 4, 10, 26, 26)
        seg(img, 4, 26, 26, 10)
    else:
        seg(img, 4, 10, 26, 10)
        seg(img, 4, 26, 26, 26)
    arrow_right(img, 32, 26)
    arrow_right(img, 32, 10)
    return img


def repeat(one=False, off=False):
    """A loop open at the top, an arrowhead on the open end.

    "1" inside for repeat-one; a slash across it for off, because off and all
    were otherwise the same drawing and the menu showed no difference.
    """
    img = canvas()
    c = S / 2.0
    # Gap across the top so the arrowhead has somewhere to sit.
    ring(img, c, c, 11, 3.2, start=310, end=230)
    # Arrowhead at the clockwise end of the arc, pointing along the tangent.
    tri(img, [(28.5, 12.5), (20.0, 7.0), (23.5, 15.5)])
    if one:
        seg(img, c, 13, c, 24, 3.0)
        seg(img, c - 3.5, 16, c, 13, 3.0)
    if off:
        seg(img, 7, 29, 29, 7, 3.4)
    return img


def library():
    """Stacked bars: a list of things to play."""
    img = canvas()
    for i, y in enumerate((9, 18, 27)):
        seg(img, 7, y, 29, y, 3.4)
    return img


if __name__ == "__main__":
    out = sys.argv[1].rstrip("/")
    # Two of everything. The player draws these on a light row normally and on
    # a dark one when the row is selected, and it does not tint them, so a
    # single colour is invisible half the time — which is how the first set
    # came out only showing when highlighted.
    for suffix, colour in (("", (0, 0, 0)), ("_hi", (255, 255, 255))):
        W = colour
        globals()["W"] = colour
        write_png(f"{out}/icon_shuffle_on{suffix}.png", shuffle(True))
        write_png(f"{out}/icon_shuffle_off{suffix}.png", shuffle(False))
        write_png(f"{out}/icon_repeat{suffix}.png", repeat(False))
        write_png(f"{out}/icon_repeat_off{suffix}.png", repeat(False, True))
        write_png(f"{out}/icon_repeat_one{suffix}.png", repeat(True))
        write_png(f"{out}/icon_library{suffix}.png", library())
