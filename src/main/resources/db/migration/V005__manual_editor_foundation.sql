CREATE TABLE infrastructure_symbol_decision (
    source_sign_id TEXT PRIMARY KEY REFERENCES legacy_sign_inventory(id) ON DELETE CASCADE,
    decision TEXT NOT NULL CHECK (decision IN ('INCLUDED', 'IGNORED')),
    assigned_role TEXT CHECK (assigned_role IN (
        'PASSENGER_PLATFORM',
        'DEPARTURE_POINT',
        'REVERSING_CONTROL',
        'MOTION_CONTROL',
        'TRACK_MARKER',
        'OTHER'
    )),
    target_id TEXT,
    note TEXT,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    decided_by TEXT NOT NULL,
    decided_at TEXT NOT NULL,
    CHECK (
        (decision = 'IGNORED' AND assigned_role IS NULL AND target_id IS NULL)
        OR (decision = 'INCLUDED' AND assigned_role IS NOT NULL)
    )
);

CREATE INDEX idx_infrastructure_symbol_decision_state
    ON infrastructure_symbol_decision(decision, assigned_role);

CREATE TABLE departure_point (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    direction_hint TEXT,
    clearance_blocks REAL NOT NULL DEFAULT 2.0 CHECK (clearance_blocks > 0),
    permitted_templates_json TEXT NOT NULL DEFAULT '[]',
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (world_name, block_x, block_y, block_z)
);

CREATE TABLE track_control_point (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    control_role TEXT NOT NULL CHECK (control_role IN (
        'REVERSING_CONTROL',
        'MOTION_CONTROL',
        'HOLDING_POINT',
        'OTHER'
    )),
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    legacy_sign_id TEXT REFERENCES legacy_sign_inventory(id),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (world_name, block_x, block_y, block_z)
);

CREATE TABLE fare_gate (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    station_id TEXT NOT NULL REFERENCES station(id),
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    gate_mode TEXT NOT NULL CHECK (gate_mode IN ('ENTRY', 'EXIT', 'BIDIRECTIONAL')),
    sensor_definition_json TEXT NOT NULL DEFAULT '{}',
    output_definition_json TEXT NOT NULL DEFAULT '{}',
    authorization_timeout_seconds INTEGER NOT NULL DEFAULT 10
        CHECK (authorization_timeout_seconds > 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (world_name, block_x, block_y, block_z)
);

CREATE INDEX idx_fare_gate_station ON fare_gate(station_id, enabled);

CREATE TABLE fare_zone (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    color_rgb INTEGER NOT NULL DEFAULT 8421504 CHECK (color_rgb BETWEEN 0 AND 16777215),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE station_fare_zone (
    station_id TEXT NOT NULL REFERENCES station(id) ON DELETE CASCADE,
    fare_zone_id TEXT NOT NULL REFERENCES fare_zone(id) ON DELETE CASCADE,
    PRIMARY KEY (station_id, fare_zone_id)
);

CREATE TABLE fare_rule (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    rule_type TEXT NOT NULL CHECK (rule_type IN (
        'FLAT',
        'STATION_PAIR',
        'ZONE_PAIR',
        'DISTANCE'
    )),
    origin_station_id TEXT REFERENCES station(id),
    destination_station_id TEXT REFERENCES station(id),
    origin_zone_id TEXT REFERENCES fare_zone(id),
    destination_zone_id TEXT REFERENCES fare_zone(id),
    line_id TEXT REFERENCES line(id),
    amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
    currency_code TEXT NOT NULL DEFAULT 'HKD',
    priority INTEGER NOT NULL DEFAULT 0,
    bidirectional INTEGER NOT NULL DEFAULT 1 CHECK (bidirectional IN (0, 1)),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK (
        (rule_type = 'FLAT')
        OR (rule_type = 'STATION_PAIR'
            AND origin_station_id IS NOT NULL
            AND destination_station_id IS NOT NULL)
        OR (rule_type = 'ZONE_PAIR'
            AND origin_zone_id IS NOT NULL
            AND destination_zone_id IS NOT NULL)
        OR (rule_type = 'DISTANCE')
    )
);

CREATE INDEX idx_fare_rule_priority ON fare_rule(enabled, priority DESC);
