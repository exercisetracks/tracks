# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Street junctions — the corners you can ask for by name.

"Maple and Cedar" is a question a gazetteer of points cannot answer: a street
is a line, a corner is where two of them cross, and `poi_search` holds one
representative point per street, usually somewhere near its middle.

What makes this cheap is a property of how OSM is built rather than any
geometry: where two roads meet, they *share a node*. So a junction is not
something to compute by intersecting lines — it is a node that appears in two
differently-named ways, and finding one is an array lookup.

Only nodes carrying at least two distinct street names are stored. The
overwhelming majority of nodes in a road network are just shape points on a
single way, and keeping them would multiply this table by a hundred for no
question anybody asks.
"""

from sqlalchemy import BigInteger, Column, Float, Index, String
from sqlalchemy.dialects.postgresql import ARRAY

from app.database import Base


class StreetJunction(Base):
    __tablename__ = "street_junctions"

    # The OSM node id, kept as the primary key so a re-import of the same
    # region updates rows in place instead of duplicating every corner.
    id = Column(BigInteger, primary_key=True, autoincrement=False)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)

    # Display names, as OSM spells them: ["Maple Street", "North Broadway"].
    names = Column(ARRAY(String), nullable=False)

    # The same names reduced to searchable words — lower-cased, with the street
    # type dropped, so "Maple Street" is found by "maple" and "Maple St" alike.
    # Directionals are kept, because "North Broadway" and "South Broadway" are
    # genuinely different streets; a query for plain "broadway" still matches
    # either, since containment only asks for the words it was given.
    tokens = Column(ARRAY(String), nullable=False)

    __table_args__ = (
        # The lookup is "which junction has both of these words", which is an
        # array containment test — GIN is the index for it.
        Index("idx_street_junctions_tokens", "tokens", postgresql_using="gin"),
    )
