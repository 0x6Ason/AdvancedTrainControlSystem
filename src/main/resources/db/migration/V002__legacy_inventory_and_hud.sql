CREATE TABLE legacy_scan_run (
    id TEXT PRIMARY KEY,
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL,
    status TEXT NOT NULL,
    chunks_scanned INTEGER NOT NULL CHECK (chunks_scanned >= 0),
    chunks_skipped INTEGER NOT NULL CHECK (chunks_skipped >= 0),
    signs_found INTEGER NOT NULL CHECK (signs_found >= 0),
    error_message TEXT
);

CREATE TABLE legacy_scan_chunk (
    scan_run_id TEXT NOT NULL REFERENCES legacy_scan_run(id) ON DELETE CASCADE,
    world_name TEXT NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    PRIMARY KEY (scan_run_id, world_name, chunk_x, chunk_z)
);

CREATE TABLE legacy_sign_inventory (
    id TEXT PRIMARY KEY,
    world_name TEXT NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    sign_side TEXT NOT NULL,
    header_line TEXT NOT NULL,
    action_type TEXT NOT NULL,
    raw_line_1 TEXT NOT NULL,
    raw_line_2 TEXT NOT NULL,
    raw_line_3 TEXT NOT NULL,
    raw_line_4 TEXT NOT NULL,
    parser_version INTEGER NOT NULL CHECK (parser_version > 0),
    review_status TEXT NOT NULL DEFAULT 'UNREVIEWED',
    present INTEGER NOT NULL DEFAULT 1 CHECK (present IN (0, 1)),
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    last_scan_id TEXT NOT NULL REFERENCES legacy_scan_run(id),
    UNIQUE (world_name, block_x, block_y, block_z, sign_side)
);

CREATE INDEX idx_legacy_sign_action_present
    ON legacy_sign_inventory(action_type, present);

CREATE INDEX idx_legacy_sign_chunk
    ON legacy_sign_inventory(world_name, chunk_x, chunk_z);

CREATE TABLE legacy_sign_attribute (
    sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id) ON DELETE CASCADE,
    attribute_key TEXT NOT NULL,
    attribute_value TEXT NOT NULL,
    PRIMARY KEY (sign_id, attribute_key)
);

