# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Record the server importer's own rules, for the phone's importer to match.

The parsers are already held to the server by fit_parse.json. What the
importer adds on top is small but decides identity and training load: which
parser claims a file, the activity's uid (device serial + start second), and
the TSS it stores. Those are what this records, for every sample in
spec/fixtures/fit/, under three threshold settings.

Run inside the backend image (no database needed):

    docker run --rm -v $PWD/backend:/app -v $PWD/spec:/spec tracks-backend \
        python /spec/make_local_import_fixtures.py
"""
import hashlib
import json
from pathlib import Path

from app.calculators.activity_metrics import compute_power_tss
from app.services import fit_import
from app.calculators.training_load import scale_tss
from app.sync.registry import activity_uid

SPEC = Path(__file__).resolve().parent
SAMPLES = SPEC / "fixtures" / "fit"
OUT = SPEC / "fixtures" / "local_import.json"

SETTINGS = [
    {"name": "none", "ftp": None, "threshold_hr": None},
    {"name": "hr_only", "ftp": None, "threshold_hr": 160.0},
    {"name": "power_and_hr", "ftp": 250.0, "threshold_hr": 160.0},
]


def main() -> None:
    cases = []
    for path in sorted(SAMPLES.glob("*.fit")):
        raw = path.read_bytes()
        sha = hashlib.sha256(raw).hexdigest()
        parser = next((p for p in fit_import._PARSERS if _claims(p, path)), None)
        case = {"file": path.name, "sha256": sha, "parser": parser.__class__.__name__ if parser else None}
        try:
            parsed = parser.parse(path) if parser else None
        except Exception as e:  # noqa: BLE001 — recorded, like the importer records it
            case["error"] = type(e).__name__
            parsed = None
        if parsed is not None:
            case["type"] = parsed.get("type")
        if parsed is not None and parsed.get("type") == "activity":
            act = parsed["activity"]
            case["uid"] = activity_uid(parsed["device"].get("serial_number"), act.get("started_at"), sha)
            tss = {}
            for s in SETTINGS:
                d = dict(act)
                p = compute_power_tss(normalized_power=d.get("normalized_power"), ftp=s["ftp"],
                                      duration_seconds=d.get("duration_seconds"))
                if p is not None:
                    d["effective_tss"] = p
                elif d.get("training_stress_score") is None:
                    d["effective_tss"] = fit_import._compute_hr_tss(d, s["threshold_hr"],
                                                                    parsed.get("data_points"))
                # Stored unscaled: the sport multipliers apply when load is read.
                tss[s["name"]] = d.get("effective_tss")
            case["effective_tss"] = tss
        cases.append(case)
    scaling = [
        {"sport": sport, "tss": tss, "discipline": disc,
         "want": scale_tss(sport, tss, disc)}
        for sport in ("mountain_biking", "trail_biking", "indoor_cycling", "virtual_cycling",
                      "cycling", "running", None)
        for disc in (None, "xco", "xcm", "enduro", "trail", "unknown")
        for tss in (None, 0.0, 47.35, 100.0, 12.25)
    ]
    OUT.write_text(json.dumps({"settings": SETTINGS, "cases": cases, "tss_scaling": scaling},
                              indent=1, sort_keys=True) + "\n")
    print(f"wrote {len(cases)} cases to {OUT}")


def _claims(parser, path) -> bool:
    try:
        return parser.can_parse(path)
    except Exception:  # noqa: BLE001
        return False


if __name__ == "__main__":
    main()
