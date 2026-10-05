# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Paragraph library: the pre-written coaching copy, loaded from JSON and rendered
with per-user values slotted in.

Files live in ``app/data/coaching_paragraphs/*.json`` and are keyed by situation
key (see ``situations.py``). Each entry holds a ``full`` list (for the hero slot)
and a ``compact`` list (for the shorter alternate slots); every string is a
template with ``{slots}`` filled at render time. Multiple variants per form give
the day-to-day freshness that gets the library to ~50-100 paragraphs without
50-100 distinct situations.

Loading is fail-open (a malformed file is logged and skipped, never crashing a
request) and eager at import, so a bad edit surfaces in logs at startup.
"""

from __future__ import annotations

import json
import logging
import os
from random import Random

logger = logging.getLogger(__name__)

_PARAGRAPH_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(__file__))),  # app/
    "data", "coaching_paragraphs",
)


class _SafeSlots(dict):
    """Missing keys render empty rather than raising; guards against typos."""

    def __missing__(self, key):  # noqa: D401
        return ""


def _load_all() -> dict[str, dict[str, list[str]]]:
    out: dict[str, dict[str, list[str]]] = {}
    if not os.path.isdir(_PARAGRAPH_DIR):
        logger.warning("coaching paragraph dir missing: %s", _PARAGRAPH_DIR)
        return out
    for fname in sorted(os.listdir(_PARAGRAPH_DIR)):
        if not fname.endswith(".json"):
            continue
        path = os.path.join(_PARAGRAPH_DIR, fname)
        try:
            with open(path) as f:
                data = json.load(f)
            for key, forms in data.items():
                bucket = out.setdefault(key, {})
                for form, variants in forms.items():
                    bucket.setdefault(form, []).extend(variants)
        except Exception as e:  # a bad file must never break recommendations
            logger.error("skipping invalid coaching paragraph file %s: %s", fname, e)
    return out


# Loaded once at import.
PARAGRAPHS: dict[str, dict[str, list[str]]] = _load_all()


def _variants(key: str, form: str) -> list[str]:
    bucket = PARAGRAPHS.get(key, {})
    return bucket.get(form) or bucket.get("full") or bucket.get("compact") or []


def render(key: str, form: str, slots: dict, seed: str = "") -> str:
    """Render one paragraph for ``key``/``form`` with ``slots`` filled in.

    ``seed`` makes variant choice stable within a day (the caller passes the
    date), so a user does not see the wording churn on every refresh.
    """
    variants = _variants(key, form)
    if not variants:
        return str(slots.get("_fallback", ""))
    choice = Random(f"{seed}:{key}:{form}").randrange(len(variants))
    template = variants[choice]
    try:
        return template.format_map(_SafeSlots(slots)).strip()
    except Exception as e:
        logger.error("failed to render coaching paragraph %s/%s: %s", key, form, e)
        return str(slots.get("_fallback", template))
