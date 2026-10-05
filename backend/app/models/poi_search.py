# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, BigInteger, String, Float, DateTime, Text, Index
from sqlalchemy.sql import func

from app.database import Base


class PoiSearch(Base):
    __tablename__ = "poi_search"

    id          = Column(BigInteger, primary_key=True, autoincrement=True)
    osm_id      = Column(BigInteger, nullable=True)
    gnis_id     = Column(BigInteger, nullable=True)
    name        = Column(Text, nullable=False)
    kind        = Column(String, nullable=True)
    kind_detail = Column(String, nullable=True)
    lat         = Column(Float, nullable=False)
    lng         = Column(Float, nullable=False)
    ele_ft      = Column(Float, nullable=True)
    state       = Column(String(2), nullable=True)
    county      = Column(String(100), nullable=True)
    source      = Column(String, nullable=True)
    min_zoom    = Column(Integer, default=15)
    created_at  = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (
        Index("idx_poi_search_source", "source"),
    )
    # Two indexes used to be declared here and are deliberately gone:
    #
    #   idx_poi_search_kind      never scanned once in the lifetime of the
    #                            database, and unscannable in principle — `kind`
    #                            only ever appears in NOT IN filters.
    #   idx_poi_search_lat_lng   superseded by idx_poi_search_geo, the GiST
    #                            index over point(lng, lat) that bbox queries
    #                            actually use. A b-tree cannot answer a
    #                            containment test at all.
    #
    # They are dropped by app.main._create_postgres_indexes, which is also
    # where idx_poi_search_geo is created — GiST over an expression is not
    # expressible as a declarative Index. Left declared here as well, every
    # autogenerate would propose recreating them and every startup would drop
    # them again.
