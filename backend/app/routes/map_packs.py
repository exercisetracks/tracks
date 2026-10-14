# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Map packs for phones: a manifest per area, and the pack files themselves.

Signed in, unlike the tile endpoints: a phone saving a state takes gigabytes,
and these are whole archives a mirror could take in a few requests. Served by
the backend rather than Caddy so the phone's own token is all it needs.

A pack's URL carries its version. Asking for a version that has since been
replaced is a 409 with the current one, never a quietly different file: the
phone verifies each download against the sha256 sent as its ETag, and a file
that changed between the manifest and the download would fail that for no
reason the phone could act on.
"""

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse, JSONResponse

from app.routes.regions.bbox import parse_bbox
from app.services import map_packs

router = APIRouter(prefix="/maps/packs", tags=["maps"])


@router.get("/manifest")
def pack_manifest(bbox: str, prebuild: bool = True):
    doc = map_packs.manifest(parse_bbox(bbox))
    # A phone asking what to save is about to fetch it: start cutting now.
    if prebuild:
        map_packs.prebuild(doc)
    return doc


@router.get("/{key:path}")
def pack(key: str, v: str = Query(..., description="The version from the manifest")):
    try:
        path = map_packs.pack_file(key, v)
    except map_packs.StaleVersion as e:
        return JSONResponse(status_code=409, content={"detail": "This pack has been rebuilt",
                                                      "current": str(e)})
    except LookupError:
        raise HTTPException(404, "No such pack")
    meta = path.with_suffix(".json")
    import json
    sha = json.loads(meta.read_text())["sha256"]
    return FileResponse(
        path,
        media_type="application/zip" if path.suffix == ".zip" else "application/vnd.pmtiles",
        # Content-addressed by version: the bytes at this URL never change, so
        # a resumed download (Range + If-Range) can trust what it already has.
        headers={"ETag": f'"{sha}"', "Cache-Control": "private, max-age=31536000, immutable",
                 "X-Pack-Sha256": sha},
    )
