# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Writing a whole list (the web app's shape) onto children that sync.

The web app saves a flow's stretches or a workout's exercises as one list.
Deleting every row and inserting new ones would be correct and hostile to
sync: each save would tombstone every child and mint new uids, so a phone's
edit to one stretch, made meanwhile, would land on a row that no longer
exists — and a delete wins. Reusing rows by position keeps uids stable and
turns a save into ordinary field edits, which merge.
"""
from __future__ import annotations

from app.sync.order import keys_for


def write_list(db, existing: list, items: list[dict], make) -> list:
    """Make `existing` (ordered) hold `items`, reusing rows by position.

    `make()` returns a new, unattached row. Each item's keys are set as
    attributes; `order_index` is assigned from fresh fractional keys.
    Returns the rows in order.
    """
    keys = keys_for(len(items))
    rows = []
    for i, item in enumerate(items):
        row = existing[i] if i < len(existing) else None
        if row is None:
            row = make()
            db.add(row)
        for k, v in item.items():
            if getattr(row, k, None) != v:
                setattr(row, k, v)
        if row.order_index != keys[i]:
            row.order_index = keys[i]
        rows.append(row)
    for extra in existing[len(items):]:
        db.delete(extra)
    return rows
