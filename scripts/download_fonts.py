# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download Noto Sans Regular and Noto Serif Regular font glyphs.

Noto Sans Regular is downloaded from the Protomaps CDN.
Noto Serif Regular is downloaded from Google Fonts as TTF, then converted
to PBF glyphs using fontnik (Node.js).
"""

import subprocess
import urllib.request
from pathlib import Path
from urllib.parse import quote

FONTS_DIR = Path(__file__).resolve().parent.parent / "map-data" / "fonts"
CDN_BASE = "https://protomaps.github.io/basemaps-assets/fonts"
TTF_URL = ("https://github.com/google/fonts/raw/main/ofl/notoserif/"
           "NotoSerif%5Bwdth%2Cwght%5D.ttf")

STACKS = ["Noto Sans Regular", "Noto Serif Regular"]
RANGES = ["0-255"]


def _ensure_fontnik():
    p = Path("/tmp/node_modules/fontnik")
    if not p.exists():
        subprocess.run(["npm", "install", "--prefix", "/tmp", "fontnik"],
                       capture_output=True, check=True)


def _download(url: str, dest: Path) -> bool:
    if dest.exists():
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        urllib.request.urlretrieve(url, dest)
        print(f"  OK ({dest.stat().st_size} bytes)")
        return True
    except Exception as e:
        print(f"  Failed: {e}")
        return False


def download_protomaps(stack: str):
    encoded = quote(stack)
    for r in RANGES:
        dest = FONTS_DIR / stack / f"{r}.pbf"
        if dest.exists():
            print(f"  Skipping {stack}/{r} (exists)")
            continue
        print(f"  Downloading {stack}/{r}...")
        _download(f"{CDN_BASE}/{encoded}/{r}.pbf", dest)


def generate_serif(stack: str):
    stack_dir = FONTS_DIR / stack
    stack_dir.mkdir(parents=True, exist_ok=True)

    ttf_path = stack_dir / "NotoSerif.ttf"

    all_exist = all((stack_dir / f"{r}.pbf").exists() for r in RANGES)
    if all_exist:
        print(f"  All PBFs exist for {stack}, skipping")
        return

    if not ttf_path.exists():
        print(f"  Downloading Noto Serif TTF...")
        if not _download(TTF_URL, ttf_path):
            return

    _ensure_fontnik()

    for r in RANGES:
        dest = stack_dir / f"{r}.pbf"
        if dest.exists():
            continue
        start, end = r.split("-")
        print(f"  Generating {stack}/{r}...")
        js = f"""
        const fontnik = require('fontnik');
        const fs = require('fs');
        const ttf = fs.readFileSync('{ttf_path}');
        fontnik.range({{font: ttf, start: {start}, end: {end}}},
          (err, data) => {{
            if (err) {{ console.error(err.message); process.exit(1); }}
            fs.writeFileSync('{dest}', data);
            console.log('OK (' + data.length + ' bytes)');
          }}
        );
        """
        r2 = subprocess.run(["node", "-e", js], capture_output=True,
                            text=True, timeout=60,
                            env={"NODE_PATH": "/tmp/node_modules"})
        if r2.returncode != 0:
            print(f"    Failed: {r2.stderr.strip()}")
        else:
            for line in r2.stdout.strip().split("\n"):
                if line.strip():
                    print(f"    {line.strip()}")


def main():
    for stack in STACKS:
        print(f"Processing: {stack}")
        if stack == "Noto Sans Regular":
            download_protomaps(stack)
        else:
            generate_serif(stack)
        print()


if __name__ == "__main__":
    main()
