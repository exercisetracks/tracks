#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build a USGS-style icon sprite sheet for MapLibre.

Downloads base Protomaps v4 light sprites (if not already present), then
appends USGS-specific POI icons. Outputs to /map-data/sprite/.
"""

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

# Canonical output: backend/sprite_defaults/ — baked into the Docker image.
# The entrypoint seeds /map-data/sprite/ from this directory on every startup
# so wiping the map-data bind-mount is fully recoverable.
SPRITE_DIR = Path(__file__).resolve().parent.parent / "backend" / "sprite_defaults"
CDN_BASE = "https://protomaps.github.io/basemaps-assets/sprites/v4"
ICON_SIZE = 19
ICON_SIZE_2X = 38
K = (0, 0, 0, 255)  # opaque black

USGS_ICONS = [
    "campground",
    "trailhead",
    "ranger_station",
    "viewpoint",
    "picnic",
    "information",
    "parking",
    "shelter",
    "alpine_hut",
    "mine",
    "spring",
    "cemetery",
    "place_of_worship",
    "fuel",
    "fire_station",
    "police",
    "laundry",
    "pharmacy",
    "dentist",
    "veterinary",
]

ALIASES = {
    "camp_site": "campground",
    "wilderness_hut": "alpine_hut",
}


def draw_icon_1x(draw, icon_id):
    """Draw a USGS-style icon (black lines on transparent) at 19x19."""
    margin = 1
    cx, cy = ICON_SIZE // 2, ICON_SIZE // 2
    left, right, top, bot = margin, ICON_SIZE - 1 - margin, margin, ICON_SIZE - 1 - margin
    mid = ICON_SIZE // 2

    if icon_id == "campground":
        draw.polygon([(cx, top + 1), (left + 1, bot), (right - 1, bot)], outline=K, width=2)
        draw.line([(cx, top + 1), (cx, bot)], fill=K, width=1)

    elif icon_id == "trailhead":
        draw.ellipse([cx - 3, top, cx + 3, top + 6], outline=K, width=2)
        draw.line([(cx, top + 6), (cx, bot - 2)], fill=K, width=2)
        draw.line([(cx, mid), (left + 2, bot)], fill=K, width=1)
        draw.line([(cx, mid), (right - 2, bot)], fill=K, width=1)
        draw.line([(cx, mid + 4), (left + 1, mid + 2)], fill=K, width=1)

    elif icon_id == "ranger_station":
        draw.rectangle([left + 1, mid, right - 1, bot], outline=K, width=2)
        draw.polygon([(left, mid), (cx, top + 1), (right, mid)], outline=K, width=2)
        draw.line([(right - 1, top + 1), (right - 1, mid)], fill=K, width=1)
        draw.polygon([(right - 1, top + 2), (right + 4, top + 5), (right - 1, top + 8)], outline=K, width=1)

    elif icon_id == "viewpoint":
        pts = []
        for i in range(10):
            angle = math.pi / 2 * 3 + i * math.pi / 5
            r = 7 if i % 2 == 0 else 3
            pts.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
        draw.polygon(pts, outline=K, width=2)

    elif icon_id == "picnic":
        draw.rectangle([left + 1, cy - 3, right - 1, cy + 1], outline=K, width=2)
        draw.line([(left + 3, cy + 1), (left + 1, bot)], fill=K, width=2)
        draw.line([(right - 3, cy + 1), (right - 1, bot)], fill=K, width=2)
        draw.line([(left + 3, cy - 3), (left + 1, top + 2)], fill=K, width=1)
        draw.line([(right - 3, cy - 3), (right - 1, top + 2)], fill=K, width=1)

    elif icon_id == "information":
        draw.ellipse([left + 1, top + 1, right - 1, bot - 1], outline=K, width=2)
        draw.line([(cx, cy - 3), (cx, cy - 3)], fill=K, width=2)
        draw.line([(cx, cy - 1), (cx, cy + 4)], fill=K, width=2)

    elif icon_id == "parking":
        draw.ellipse([left + 1, top + 1, right - 1, bot - 1], outline=K, width=2)
        draw.rectangle([cx - 3, cy - 5, cx + 2, cy + 5], fill=K)
        draw.rectangle([cx - 3, cy - 5, cx - 1, cy + 5], fill=None, outline=K, width=1)

    elif icon_id == "shelter":
        draw.polygon([(left, cy + 2), (cx, top + 1), (right, cy + 2)], outline=K, width=2)
        draw.line([(cx, top + 1), (cx, bot)], fill=K, width=2)
        draw.line([(left + 3, bot), (right - 3, bot)], fill=K, width=1)

    elif icon_id == "alpine_hut":
        draw.rectangle([left + 2, cy, right - 2, bot], outline=K, width=2)
        draw.polygon([(left + 1, cy), (cx, top + 2), (right - 1, cy)], outline=K, width=2)
        draw.line([(cx - 1, bot), (cx - 1, cy + 3)], fill=K, width=1)
        draw.line([(cx + 1, bot), (cx + 1, cy + 3)], fill=K, width=1)
        draw.arc([cx - 3, cy + 1, cx + 3, cy + 6], 180, 0, fill=K, width=1)

    elif icon_id == "mine":
        draw.line([(left + 1, bot), (right - 1, top)], fill=K, width=2)
        draw.line([(right - 1, top), (right - 1, top + 4)], fill=K, width=3)
        draw.line([(right - 1, bot), (left + 1, top)], fill=K, width=2)
        draw.rectangle([left + 1, top, left + 4, top + 4], fill=K)

    elif icon_id == "spring":
        pts = [(cx, top + 1)]
        for i in range(0, 181):
            a = math.radians(i)
            r = 6 * math.sin(a / 2)
            x = cx + r * math.cos(a)
            y = cy + 5 - r * math.sin(a)
            pts.append((x, y))
        draw.polygon(pts, outline=K, width=2)

    elif icon_id == "cemetery":
        draw.rectangle([cx - 1, top + 3, cx + 2, bot - 2], fill=K)
        draw.rectangle([left + 3, cy - 2, right - 3, cy + 1], fill=K)

    elif icon_id == "place_of_worship":
        draw.rectangle([left + 3, mid, right - 3, bot], outline=K, width=2)
        draw.polygon([(left + 2, mid), (cx, top + 2), (right - 2, mid)], outline=K, width=2)
        draw.line([(cx, top + 2), (cx, top - 1)], fill=K, width=2)
        draw.line([(cx - 2, top), (cx + 2, top)], fill=K, width=2)

    elif icon_id == "fuel":
        draw.rectangle([cx - 2, top + 3, cx + 4, bot - 1], outline=K, width=2)
        draw.rectangle([cx - 2, top + 3, cx + 1, mid], fill=K)
        draw.line([(cx + 4, mid - 2), (cx + 6, mid - 4)], fill=K, width=2)
        draw.rectangle([cx + 5, mid - 5, cx + 7, mid - 3], fill=K)

    elif icon_id == "fire_station":
        draw.polygon([(cx, top + 1), (left + 3, bot - 3), (right - 3, bot - 3)], outline=K, width=2)
        draw.ellipse([cx - 3, cy - 1, cx + 3, cy + 5], outline=K, width=2)
        draw.line([(right + 2, top), (right + 2, bot)], fill=K, width=1)

    elif icon_id == "police":
        pts = [(cx, top + 1)]
        for i in range(0, 361, 15):
            a = math.radians(i - 90)
            if 60 <= i % 360 <= 120:
                r = 6
            elif 240 <= i % 360 <= 300:
                r = 6
            else:
                r = 8
            pts.append((cx + r * math.cos(a), cy + 1 + r * math.sin(a)))
        draw.polygon(pts[::2], outline=K, width=2)

    elif icon_id == "laundry":
        draw.rectangle([left + 1, top + 3, right - 1, bot - 1], outline=K, width=2)
        draw.ellipse([cx - 4, cy - 2, cx + 4, cy + 4], outline=K, width=2)
        draw.ellipse([cx - 2, cy, cx + 2, cy + 3], outline=K, width=1)

    elif icon_id == "pharmacy":
        draw.text((cx - 4, cy - 5), "R", fill=K)
        rx = cx + 3
        draw.line([(rx, cy - 5), (rx + 4, cy + 1)], fill=K, width=1)
        draw.line([(rx, cy), (rx + 4, cy - 5)], fill=K, width=1)

    elif icon_id == "dentist":
        pts = [
            (cx - 6, cy - 3), (cx - 7, cy + 1), (cx - 5, cy + 5),
            (cx - 2, cy + 4), (cx, cy + 6), (cx + 2, cy + 4),
            (cx + 5, cy + 5), (cx + 7, cy + 1), (cx + 6, cy - 3),
            (cx + 3, cy - 5), (cx - 3, cy - 5),
        ]
        draw.polygon(pts, outline=K, width=2)

    elif icon_id == "veterinary":
        draw.ellipse([cx - 6, cy - 4, cx - 2, cy], outline=K, width=2)
        draw.ellipse([cx + 2, cy - 4, cx + 6, cy], outline=K, width=2)
        draw.ellipse([cx - 7, cy + 1, cx - 2, cy + 5], outline=K, width=2)
        draw.ellipse([cx + 2, cy + 1, cx + 7, cy + 5], outline=K, width=2)
        draw.ellipse([cx - 3, cy + 2, cx + 3, cy + 7], outline=K, width=2)


def make_icon_1x(icon_id):
    img = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw_icon_1x(draw, icon_id)
    return img


def make_icon_2x(icon_id):
    img1 = make_icon_1x(icon_id)
    return img1.resize((ICON_SIZE_2X, ICON_SIZE_2X), Image.NEAREST)


def append_icons(sprite_json, sprite_img, icons, scale=1):
    """Append icons to the sprite image and return updated JSON + image."""
    size = ICON_SIZE if scale == 1 else ICON_SIZE_2X
    w, h = sprite_img.size
    icons_per_row = w // size
    num_new = len(icons)
    rows_needed = (num_new + icons_per_row - 1) // icons_per_row
    new_h = h + rows_needed * size

    expanded = Image.new("RGBA", (w, new_h), (0, 0, 0, 0))
    expanded.paste(sprite_img, (0, 0))

    for i, icon_id in enumerate(icons):
        col = i % icons_per_row
        row = i // icons_per_row
        x = col * size
        y = h + row * size

        icon_img = make_icon_1x(icon_id) if scale == 1 else make_icon_2x(icon_id)
        expanded.paste(icon_img, (x, y), icon_img)

        sprite_json[icon_id] = {
            "x": x,
            "y": y,
            "width": size,
            "height": size,
            "pixelRatio": scale,
        }

    for alias, target in ALIASES.items():
        if target in sprite_json:
            sprite_json[alias] = dict(sprite_json[target])

    return sprite_json, expanded


def main():
    SPRITE_DIR.mkdir(parents=True, exist_ok=True)

    light_json_path = SPRITE_DIR / "light.json"
    light_png_path = SPRITE_DIR / "light.png"
    light2x_json_path = SPRITE_DIR / "light@2x.json"
    light2x_png_path = SPRITE_DIR / "light@2x.png"

    # Restore from backups if available (for re-runs)
    bak_json = SPRITE_DIR / "light.json.bak"
    bak_png = SPRITE_DIR / "light.png.bak"
    bak2x_json = SPRITE_DIR / "light@2x.json.bak"
    bak2x_png = SPRITE_DIR / "light@2x.png.bak"
    if bak_json.exists():
        import shutil
        shutil.copy2(bak_json, light_json_path)
        shutil.copy2(bak_png, light_png_path)
        shutil.copy2(bak2x_json, light2x_json_path)
        shutil.copy2(bak2x_png, light2x_png_path)

    with open(light_json_path) as f:
        sprite_json = json.load(f)
    with open(light2x_json_path) as f:
        sprite2x_json = json.load(f)

    light_img = Image.open(light_png_path).convert("RGBA")
    light2x_img = Image.open(light2x_png_path).convert("RGBA")

    new_icons_1x = [i for i in USGS_ICONS if i not in sprite_json]
    new_icons_2x = [i for i in USGS_ICONS if i not in sprite2x_json]

    sprite_json, light_img = append_icons(sprite_json, light_img, new_icons_1x, scale=1)
    sprite2x_json, light2x_img = append_icons(sprite2x_json, light2x_img, new_icons_2x, scale=2)

    with open(light_json_path, "w") as f:
        json.dump(sprite_json, f, separators=(",", ":"))
    with open(light2x_json_path, "w") as f:
        json.dump(sprite2x_json, f, separators=(",", ":"))

    light_img.save(light_png_path)
    light2x_img.save(light2x_png_path)

    print(f"Added {len(new_icons_1x)} new icons to 1x sprite ({light_img.size})")
    print(f"Added {len(new_icons_2x)} new icons to 2x sprite ({light2x_img.size})")
    print(f"Total icons in 1x: {len(sprite_json)}")
    print(f"Total icons in 2x: {len(sprite2x_json)}")
    print(f"Files written to {SPRITE_DIR}/")


if __name__ == "__main__":
    main()
