CREATE TABLE infrastructure_discovery_run (
    id TEXT PRIMARY KEY,
    source_scan_id TEXT REFERENCES legacy_scan_run(id),
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL,
    status TEXT NOT NULL,
    source_station_signs INTEGER NOT NULL CHECK (source_station_signs >= 0),
    source_spawn_signs INTEGER NOT NULL CHECK (source_spawn_signs >= 0),
    station_candidates INTEGER NOT NULL CHECK (station_candidates >= 0),
    platform_candidates INTEGER NOT NULL CHECK (platform_candidates >= 0),
    departure_candidates INTEGER NOT NULL CHECK (departure_candidates >= 0),
    track_control_candidates INTEGER NOT NULL CHECK (track_control_candidates >= 0),
    error_message TEXT
);

CREATE TABLE infrastructure_source_sign_snapshot (
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    source_sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id),
    world_name TEXT NOT NULL,
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
    evidence_fingerprint TEXT NOT NULL,
    evidence_revision INTEGER NOT NULL CHECK (evidence_revision > 0),
    PRIMARY KEY (discovery_run_id, source_sign_id),
    UNIQUE (
        discovery_run_id,
        source_sign_id,
        evidence_fingerprint,
        evidence_revision
    )
);

CREATE TABLE infrastructure_source_sign_snapshot_attribute (
    discovery_run_id TEXT NOT NULL,
    source_sign_id TEXT NOT NULL,
    attribute_key TEXT NOT NULL,
    attribute_value TEXT NOT NULL,
    PRIMARY KEY (discovery_run_id, source_sign_id, attribute_key),
    FOREIGN KEY (discovery_run_id, source_sign_id)
        REFERENCES infrastructure_source_sign_snapshot(discovery_run_id, source_sign_id)
        ON DELETE CASCADE
);

CREATE TABLE station_candidate (
    id TEXT PRIMARY KEY,
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    suggested_name TEXT,
    normalized_name TEXT,
    world_name TEXT NOT NULL,
    center_x INTEGER NOT NULL,
    center_y INTEGER NOT NULL,
    center_z INTEGER NOT NULL,
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    review_status TEXT NOT NULL DEFAULT 'UNREVIEWED',
    created_at TEXT NOT NULL,
    UNIQUE (discovery_run_id, id)
);

CREATE INDEX idx_station_candidate_run
    ON station_candidate(discovery_run_id, world_name);

CREATE TABLE platform_candidate (
    id TEXT PRIMARY KEY,
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    station_candidate_id TEXT NOT NULL,
    source_sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id),
    suggested_label TEXT NOT NULL,
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    review_status TEXT NOT NULL DEFAULT 'UNREVIEWED',
    created_at TEXT NOT NULL,
    UNIQUE (discovery_run_id, source_sign_id),
    UNIQUE (discovery_run_id, id, source_sign_id),
    FOREIGN KEY (discovery_run_id, station_candidate_id)
        REFERENCES station_candidate(discovery_run_id, id)
        ON DELETE CASCADE,
    FOREIGN KEY (discovery_run_id, source_sign_id)
        REFERENCES infrastructure_source_sign_snapshot(discovery_run_id, source_sign_id)
);

CREATE INDEX idx_platform_candidate_station
    ON platform_candidate(station_candidate_id);

CREATE TABLE departure_candidate (
    id TEXT PRIMARY KEY,
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    source_sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id),
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    direction_hint TEXT,
    spawn_pattern TEXT,
    interval_text TEXT,
    launch_force REAL,
    always_powered INTEGER CHECK (always_powered IN (0, 1)),
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    review_status TEXT NOT NULL DEFAULT 'UNREVIEWED',
    created_at TEXT NOT NULL,
    UNIQUE (discovery_run_id, source_sign_id),
    UNIQUE (discovery_run_id, id, source_sign_id),
    FOREIGN KEY (discovery_run_id, source_sign_id)
        REFERENCES infrastructure_source_sign_snapshot(discovery_run_id, source_sign_id)
);

CREATE INDEX idx_departure_candidate_run
    ON departure_candidate(discovery_run_id, world_name);

CREATE TABLE track_control_candidate (
    id TEXT PRIMARY KEY,
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    source_sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id),
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    suggested_role TEXT NOT NULL,
    instruction_text TEXT,
    delay_text TEXT,
    launch_distance_text TEXT,
    center_offset_text TEXT,
    confidence REAL NOT NULL CHECK (confidence BETWEEN 0.0 AND 1.0),
    review_status TEXT NOT NULL DEFAULT 'UNREVIEWED',
    created_at TEXT NOT NULL,
    UNIQUE (discovery_run_id, source_sign_id),
    UNIQUE (discovery_run_id, id, source_sign_id),
    FOREIGN KEY (discovery_run_id, source_sign_id)
        REFERENCES infrastructure_source_sign_snapshot(discovery_run_id, source_sign_id)
);

CREATE INDEX idx_track_control_candidate_run
    ON track_control_candidate(discovery_run_id, world_name, suggested_role);

CREATE TABLE infrastructure_candidate_identity (
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    candidate_type TEXT NOT NULL CHECK (candidate_type IN (
        'STATION',
        'PLATFORM',
        'DEPARTURE',
        'TRACK_CONTROL'
    )),
    candidate_id TEXT NOT NULL,
    station_candidate_id TEXT,
    platform_candidate_id TEXT,
    departure_candidate_id TEXT,
    track_control_candidate_id TEXT,
    source_sign_id TEXT,
    evidence_fingerprint TEXT,
    evidence_revision INTEGER CHECK (evidence_revision > 0),
    PRIMARY KEY (discovery_run_id, candidate_type, candidate_id),
    UNIQUE (
        discovery_run_id,
        candidate_type,
        candidate_id,
        source_sign_id,
        evidence_fingerprint,
        evidence_revision
    ),
    CHECK (
        (candidate_type = 'STATION'
            AND station_candidate_id = candidate_id
            AND platform_candidate_id IS NULL
            AND departure_candidate_id IS NULL
            AND track_control_candidate_id IS NULL
            AND source_sign_id IS NULL
            AND evidence_fingerprint IS NULL
            AND evidence_revision IS NULL)
        OR (candidate_type = 'PLATFORM'
            AND station_candidate_id IS NULL
            AND platform_candidate_id = candidate_id
            AND departure_candidate_id IS NULL
            AND track_control_candidate_id IS NULL
            AND source_sign_id IS NOT NULL
            AND evidence_fingerprint IS NOT NULL
            AND evidence_revision IS NOT NULL)
        OR (candidate_type = 'DEPARTURE'
            AND station_candidate_id IS NULL
            AND platform_candidate_id IS NULL
            AND departure_candidate_id = candidate_id
            AND track_control_candidate_id IS NULL
            AND source_sign_id IS NOT NULL
            AND evidence_fingerprint IS NOT NULL
            AND evidence_revision IS NOT NULL)
        OR (candidate_type = 'TRACK_CONTROL'
            AND station_candidate_id IS NULL
            AND platform_candidate_id IS NULL
            AND departure_candidate_id IS NULL
            AND track_control_candidate_id = candidate_id
            AND source_sign_id IS NOT NULL
            AND evidence_fingerprint IS NOT NULL
            AND evidence_revision IS NOT NULL)
    ),
    FOREIGN KEY (discovery_run_id, station_candidate_id)
        REFERENCES station_candidate(discovery_run_id, id),
    FOREIGN KEY (discovery_run_id, platform_candidate_id, source_sign_id)
        REFERENCES platform_candidate(discovery_run_id, id, source_sign_id),
    FOREIGN KEY (discovery_run_id, departure_candidate_id, source_sign_id)
        REFERENCES departure_candidate(discovery_run_id, id, source_sign_id),
    FOREIGN KEY (discovery_run_id, track_control_candidate_id, source_sign_id)
        REFERENCES track_control_candidate(discovery_run_id, id, source_sign_id),
    FOREIGN KEY (
        discovery_run_id,
        source_sign_id,
        evidence_fingerprint,
        evidence_revision
    ) REFERENCES infrastructure_source_sign_snapshot(
        discovery_run_id,
        source_sign_id,
        evidence_fingerprint,
        evidence_revision
    )
);

CREATE TABLE infrastructure_candidate_evidence (
    discovery_run_id TEXT NOT NULL REFERENCES infrastructure_discovery_run(id) ON DELETE CASCADE,
    candidate_type TEXT NOT NULL CHECK (candidate_type IN (
        'STATION',
        'PLATFORM',
        'DEPARTURE',
        'TRACK_CONTROL'
    )),
    candidate_id TEXT NOT NULL,
    evidence_kind TEXT NOT NULL,
    source_sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id),
    distance_blocks REAL,
    evidence_text TEXT,
    PRIMARY KEY (discovery_run_id, candidate_type, candidate_id, evidence_kind, source_sign_id),
    FOREIGN KEY (discovery_run_id, candidate_type, candidate_id)
        REFERENCES infrastructure_candidate_identity(discovery_run_id, candidate_type, candidate_id)
        ON DELETE CASCADE,
    FOREIGN KEY (discovery_run_id, source_sign_id)
        REFERENCES infrastructure_source_sign_snapshot(discovery_run_id, source_sign_id)
);

CREATE INDEX idx_infrastructure_candidate_evidence_source
    ON infrastructure_candidate_evidence(source_sign_id);

CREATE TABLE rail_discovery_run (
    id TEXT PRIMARY KEY,
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL,
    status TEXT NOT NULL,
    seeds_considered INTEGER NOT NULL CHECK (seeds_considered >= 0),
    seeds_resolved INTEGER NOT NULL CHECK (seeds_resolved >= 0),
    nodes_discovered INTEGER NOT NULL CHECK (nodes_discovered >= 0),
    edges_discovered INTEGER NOT NULL CHECK (edges_discovered >= 0),
    unloaded_boundaries INTEGER NOT NULL CHECK (unloaded_boundaries >= 0),
    node_limit_reached INTEGER NOT NULL CHECK (node_limit_reached IN (0, 1)),
    error_message TEXT
);

CREATE TABLE rail_candidate_node (
    id TEXT PRIMARY KEY,
    discovery_run_id TEXT NOT NULL REFERENCES rail_discovery_run(id) ON DELETE CASCADE,
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    material_name TEXT NOT NULL,
    rail_shape TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (discovery_run_id, world_name, block_x, block_y, block_z)
);

CREATE INDEX idx_rail_candidate_node_run
    ON rail_candidate_node(discovery_run_id, world_name, block_x, block_z);

CREATE TABLE rail_candidate_edge (
    discovery_run_id TEXT NOT NULL REFERENCES rail_discovery_run(id) ON DELETE CASCADE,
    first_node_id TEXT NOT NULL REFERENCES rail_candidate_node(id) ON DELETE CASCADE,
    second_node_id TEXT NOT NULL REFERENCES rail_candidate_node(id) ON DELETE CASCADE,
    PRIMARY KEY (discovery_run_id, first_node_id, second_node_id),
    CHECK (first_node_id < second_node_id)
);

CREATE TABLE rail_discovery_seed (
    discovery_run_id TEXT NOT NULL REFERENCES rail_discovery_run(id) ON DELETE CASCADE,
    source_sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id),
    seed_type TEXT NOT NULL,
    resolution_status TEXT NOT NULL,
    rail_node_id TEXT REFERENCES rail_candidate_node(id),
    distance_blocks REAL,
    PRIMARY KEY (discovery_run_id, source_sign_id)
);

CREATE INDEX idx_rail_discovery_seed_status
    ON rail_discovery_seed(discovery_run_id, resolution_status);
