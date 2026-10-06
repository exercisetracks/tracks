CREATE TABLE local_activity (
    uid                   TEXT NOT NULL PRIMARY KEY,
    sha256                TEXT NOT NULL,
    device_serial         TEXT,
    started_at            TEXT,
    sport                 TEXT,
    sub_sport             TEXT,
    name                  TEXT,
    duration_seconds      INTEGER,
    distance_meters       REAL,
    avg_heart_rate        INTEGER,
    max_heart_rate        INTEGER,
    total_calories        INTEGER,
    total_ascent          REAL,
    avg_speed             REAL,
    training_stress_score REAL,
    effective_tss         REAL,
    vo2max_estimate       REAL,
    avg_power             INTEGER,
    normalized_power      INTEGER,
    summary               TEXT NOT NULL,
    detail                TEXT NOT NULL
)
-- statement --
CREATE TABLE local_day (
    date    TEXT NOT NULL PRIMARY KEY,
    metrics TEXT NOT NULL,
    extra   TEXT NOT NULL
)
-- statement --
CREATE TABLE local_file (
    sha256       TEXT NOT NULL PRIMARY KEY,
    kind         TEXT,
    activity_uid TEXT,
    uploaded     INTEGER NOT NULL DEFAULT 0
)
-- statement --
CREATE TABLE local_upload_server (
    id        INTEGER NOT NULL PRIMARY KEY CHECK (id = 0),
    server_id TEXT NOT NULL
)
-- statement --
CREATE TABLE replica_state (
    id             INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
    -- 16 hex digits, generated once per install and never reused. Reusing a
    -- node id on another install would let two devices mint identical stamps.
    node           TEXT NOT NULL,
    -- The last stamp issued or received; the clock resumes from here after a
    -- restart, so it can never issue one below what it has already seen.
    last_hlc       TEXT,
    -- The server's arrival sequence number, 0 before the first pull.
    pull_cursor    INTEGER NOT NULL DEFAULT 0,
    -- The server this cursor belongs to, as its pulls last named it. A pull
    -- naming a different one means the server was recreated: re-push.
    server_id      TEXT,
    -- The account's epoch as last seen. A higher one means the user deleted
    -- their data on purpose: this phone deletes its copy too.
    epoch          INTEGER,
    -- The account this phone's data belongs to, fixed at the first sign-in.
    -- Signing out keeps the data and this binding; signing in to anyone else
    -- is refused until the phone is explicitly erased.
    bound_server   TEXT,
    bound_account  TEXT
)
-- statement --
CREATE TABLE synced_row (
    entity      TEXT NOT NULL,
    uid         TEXT NOT NULL,
    -- JSON object of written fields. A field that is absent was never written;
    -- one written as null is present with a JSON null.
    fields      TEXT NOT NULL,
    -- JSON object: field → HLC stamp of its last write.
    clock       TEXT NOT NULL,
    -- HLC stamp of the delete, or null. A tombstone has `{}` fields and clock.
    deleted     TEXT,
    -- JSON array of fields edited here that the server has not acknowledged;
    -- "_deleted" stands for the delete itself.
    dirty       TEXT NOT NULL DEFAULT '[]',
    -- Denormalised so "every flow_stretch of flow X" is an index lookup rather
    -- than a scan decoding every row's JSON. Mirrors the field named by the
    -- entity's children_of; null for entities without one.
    parent_uid  TEXT,
    -- Kept alongside `dirty` so finding work to push is an index lookup.
    is_dirty    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (entity, uid)
)
-- statement --
CREATE TABLE uid_alias (
    alias INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    uid   TEXT NOT NULL UNIQUE
)
-- statement --
CREATE INDEX local_activity_started ON local_activity(started_at DESC)
-- statement --
CREATE INDEX synced_row_dirty ON synced_row(is_dirty)
-- statement --
CREATE INDEX synced_row_parent ON synced_row(entity, parent_uid)
-- statement --
