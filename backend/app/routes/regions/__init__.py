# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Map-region download/management REST API.

Split out of a single ~600-line module into focused, loosely-coupled pieces:
  bbox.py      request validation (parse_bbox) + MAX_NAME_LEN
  crud.py      the REST endpoints (suggest-name / estimate / download / list /
               get / delete / progress / global-downloads) — owns `router`
  download.py  download_and_merge: the background download → merge → install
               pipeline with cancellation, launched by POST /regions/download
  thematic.py  build_thematic: streaming + legacy overlay (trails/water/areas/
               infra/landuse + POIs) builders
  purge.py     purge_region / purge_cancelled_region — region teardown shared by
               user deletes and download-cancel cleanup

`main.py` mounts `app.routes.regions.router`; the route definitions live in
crud.py and are re-exported here. Route ordering (static `/regions/*` before
dynamic `/regions/{region_id}`) is handled inside crud.py.
"""

from .crud import router

__all__ = ["router"]
