# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""POI search and map-feature endpoints.

Split out of a single ~410-line module into focused pieces:
  search_query.py  abbreviation expansion + to_tsquery / trigram query builders
  labels.py        kind → display-label map + reveal-zoom / suppress constants
  endpoints.py     the /search, /features, /reindex HTTP handlers

``router`` is re-exported here so ``app.routes.poi.router`` keeps working
unchanged (main.py imports ``poi as poi_routes`` and includes ``.router``).
"""

from .endpoints import router

__all__ = ["router"]
