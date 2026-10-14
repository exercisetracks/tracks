# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Who may remove a map area from the server."""

from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.routes.regions import crud


def test_a_non_admin_cannot_remove_a_shared_area(monkeypatch):
    """An area is shared by every account and device on the server. Any
    signed-in user, or a phone's "Cancel" on a row it was only saving locally,
    used to delete it for all of them (audit F4)."""
    touched = []
    monkeypatch.setattr(crud.region_registry, "get", lambda rid: touched.append(rid) or {"status": "installed"})
    with pytest.raises(HTTPException) as e:
        crud.delete_region(13, user=SimpleNamespace(is_admin=False))
    assert e.value.status_code == 403
    assert touched == []
