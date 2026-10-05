# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Finding every street corner in an OSM extract.

A corner is a node that two differently-named roads both use. OSM guarantees
this: ways that meet are connected through a shared node, not merely drawn
crossing each other, because routing depends on it. So this never intersects
any geometry — it counts node references and keeps the ones that belong to more
than one street name.

The counting is done by Postgres rather than in Python, and that is the whole
design. A state-sized extract has millions of node references among its named
roads; holding them in a dict costs hundreds of megabytes and gets worse with
every larger region, while a COPY into an unlogged staging table and one GROUP
BY is bounded, interruptible and about as fast.

Two passes over the file, because a way names its nodes by id and the
coordinates live on the nodes themselves:

  1. ways   → (node id, street name) for every named road
  2. nodes  → coordinates, but only for the ids that turned out to be corners

COVERAGE, stated plainly, because it decides what this feature can answer:
this reads whatever PBF it is given, and the region cache under /map-data/osm
is not a general one. Its tags-filter (osm_source.config.TAGS_FILTER_EXPR)
keeps path, track, footway, service, unclassified and tertiary — the roads a
trail map needs — and drops residential, primary, secondary and motorway
entirely. Run against that cache, one mountain state yields ~19k corners, which are real
and correctly placed but skew rural: a corner of two tertiary streets is there,
"Maple and Broadway" is not, because Broadway is a secondary road the cache
never stored.

Full city coverage therefore does not need a change here. It needs a PBF that
still has streets in it — an unfiltered Geofabrik extract, passed to
build_junctions directly. That is why this takes a path rather than reaching
for the region cache itself: widening TAGS_FILTER_EXPR would re-download and
re-build every map region to serve a search feature, which is the wrong trade
to make on someone's behalf.
"""

from __future__ import annotations

import csv
import logging
import os
import tempfile

import osmium
from sqlalchemy import text

from app.database import SessionLocal

log = logging.getLogger(__name__)

# Roads people give directions with. Deliberately excludes footways, cycleways
# and service roads: "Maple and Broadway" never means the alley behind the
# shops, and including them buries real corners under driveways.
_NAMED_ROAD_TYPES = {
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street", "pedestrian",
    "motorway_link", "trunk_link", "primary_link", "secondary_link",
    "tertiary_link",
}


class _WayNodes(osmium.SimpleHandler):
    """Pass one: every (node id, name) pair belonging to a named road."""

    def __init__(self, sink):
        super().__init__()
        self.sink = sink
        self.ways = 0

    def way(self, w):
        tags = w.tags
        if tags.get("highway") not in _NAMED_ROAD_TYPES:
            return
        name = tags.get("name")
        if not name:
            return
        self.ways += 1
        write = self.sink.writerow
        for node in w.nodes:
            write((node.ref, name))


class _NodeCoords(osmium.SimpleHandler):
    """Pass two: coordinates for the nodes that turned out to be corners."""

    def __init__(self, wanted: set[int], sink):
        super().__init__()
        self.wanted = wanted
        self.sink = sink
        self.found = 0

    def node(self, n):
        if n.id not in self.wanted:
            return
        self.found += 1
        self.sink.writerow((n.id, n.location.lat, n.location.lon))


def _copy_from(conn, table: str, columns: str, path: str) -> None:
    """Stream a CSV file into a table with COPY, via the raw psycopg2 cursor."""
    with open(path, "r", encoding="utf-8") as fh:
        cursor = conn.connection.cursor()
        cursor.copy_expert(
            f"COPY {table} ({columns}) FROM STDIN WITH (FORMAT csv)", fh
        )


def build_junctions(pbf_path: str) -> int:
    """Rebuild street_junctions from one OSM extract. Returns the corner count.

    Replaces every junction the extract covers rather than merging: a rebuild is
    how a region is corrected, and a merge would keep corners that the new data
    says are gone. Rows outside this extract are untouched, so several regions
    can be imported one after another.
    """
    if not os.path.exists(pbf_path):
        raise FileNotFoundError(pbf_path)

    tmpdir = tempfile.mkdtemp(prefix="street-junctions-")
    refs_path = os.path.join(tmpdir, "refs.csv")
    coords_path = os.path.join(tmpdir, "coords.csv")

    try:
        log.info("street junctions: reading ways from %s", pbf_path)
        with open(refs_path, "w", encoding="utf-8", newline="") as fh:
            handler = _WayNodes(csv.writer(fh))
            handler.apply_file(pbf_path)
        log.info("street junctions: %d named roads", handler.ways)

        db = SessionLocal()
        try:
            # Unlogged: this table is scratch, and not writing WAL for millions
            # of rows is most of the speed. It is dropped either way.
            db.execute(text("DROP TABLE IF EXISTS _junction_refs"))
            db.execute(text(
                "CREATE UNLOGGED TABLE _junction_refs "
                "(node_id bigint NOT NULL, name text NOT NULL)"
            ))
            db.commit()

            conn = db.connection()
            _copy_from(conn, "_junction_refs", "node_id, name", refs_path)
            db.commit()

            # A corner is a node used by two or more *differently named* roads.
            # One road passing through a node many times is not a corner.
            db.execute(text("DROP TABLE IF EXISTS _junction_nodes"))
            db.execute(text("""
                CREATE UNLOGGED TABLE _junction_nodes AS
                SELECT node_id, array_agg(DISTINCT name ORDER BY name) AS names
                FROM _junction_refs
                GROUP BY node_id
                HAVING count(DISTINCT name) >= 2
            """))
            db.execute(text(
                "CREATE INDEX ON _junction_nodes (node_id)"
            ))
            db.commit()

            wanted = {
                row[0] for row in
                db.execute(text("SELECT node_id FROM _junction_nodes")).fetchall()
            }
            log.info("street junctions: %d corners to locate", len(wanted))
            if not wanted:
                return 0
        finally:
            db.close()

        log.info("street junctions: reading node positions")
        with open(coords_path, "w", encoding="utf-8", newline="") as fh:
            coords = _NodeCoords(wanted, csv.writer(fh))
            coords.apply_file(pbf_path)
        log.info("street junctions: located %d", coords.found)

        db = SessionLocal()
        try:
            db.execute(text("DROP TABLE IF EXISTS _junction_coords"))
            db.execute(text(
                "CREATE UNLOGGED TABLE _junction_coords "
                "(node_id bigint NOT NULL, lat double precision NOT NULL, "
                " lng double precision NOT NULL)"
            ))
            db.commit()
            _copy_from(db.connection(), "_junction_coords",
                       "node_id, lat, lng", coords_path)
            db.execute(text("CREATE INDEX ON _junction_coords (node_id)"))
            db.commit()

            # Tokens are built here, in SQL, from the same rules the query side
            # uses — see app.routes.poi.streets.street_tokens, which this must
            # agree with or a corner is stored under words nobody searches.
            inserted = _write_junctions(db)
            db.commit()
            log.info("street junctions: stored %d", inserted)
            return inserted
        finally:
            db.execute(text("DROP TABLE IF EXISTS _junction_refs"))
            db.execute(text("DROP TABLE IF EXISTS _junction_nodes"))
            db.execute(text("DROP TABLE IF EXISTS _junction_coords"))
            db.commit()
            db.close()
    finally:
        for path in (refs_path, coords_path):
            if os.path.exists(path):
                os.unlink(path)
        os.rmdir(tmpdir)


def _write_junctions(db) -> int:
    """Join names to coordinates, tokenise, and upsert."""
    from app.routes.poi.streets import street_tokens

    rows = db.execute(text("""
        SELECT n.node_id, c.lat, c.lng, n.names
        FROM _junction_nodes n
        JOIN _junction_coords c USING (node_id)
    """)).fetchall()

    payload = []
    for node_id, lat, lng, names in rows:
        tokens = sorted({t for name in names for t in street_tokens(name)})
        if len(names) < 2 or not tokens:
            continue
        payload.append({"id": node_id, "lat": lat, "lng": lng,
                        "names": list(names), "tokens": tokens})

    if not payload:
        return 0

    # Chunked so one statement never carries a whole state's corners.
    statement = text("""
        INSERT INTO street_junctions (id, lat, lng, names, tokens)
        VALUES (:id, :lat, :lng, :names, :tokens)
        ON CONFLICT (id) DO UPDATE
            SET lat = EXCLUDED.lat, lng = EXCLUDED.lng,
                names = EXCLUDED.names, tokens = EXCLUDED.tokens
    """)
    for start in range(0, len(payload), 5000):
        db.execute(statement, payload[start:start + 5000])
    return len(payload)
