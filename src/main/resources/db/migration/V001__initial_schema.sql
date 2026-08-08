CREATE TABLE track_node (
    id TEXT PRIMARY KEY,
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    node_type TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE track_segment (
    id TEXT PRIMARY KEY,
    from_node_id TEXT NOT NULL REFERENCES track_node(id),
    to_node_id TEXT NOT NULL REFERENCES track_node(id),
    length_blocks REAL NOT NULL CHECK (length_blocks > 0),
    speed_limit_blocks_per_second REAL NOT NULL CHECK (speed_limit_blocks_per_second > 0),
    geometry_json TEXT NOT NULL DEFAULT '[]',
    chunk_corridor_json TEXT NOT NULL DEFAULT '[]',
    CHECK (from_node_id <> to_node_id)
);

CREATE INDEX idx_track_segment_from_node ON track_segment(from_node_id);
CREATE INDEX idx_track_segment_to_node ON track_segment(to_node_id);

CREATE TABLE block_section (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE block_section_segment (
    block_section_id TEXT NOT NULL REFERENCES block_section(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL CHECK (sequence_number >= 0),
    track_segment_id TEXT NOT NULL REFERENCES track_segment(id),
    PRIMARY KEY (block_section_id, sequence_number),
    UNIQUE (block_section_id, track_segment_id)
);

CREATE TABLE station (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    world_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE platform (
    id TEXT NOT NULL,
    station_id TEXT NOT NULL REFERENCES station(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    berth_tolerance_blocks REAL NOT NULL DEFAULT 1.5 CHECK (berth_tolerance_blocks > 0),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (station_id, id)
);

CREATE TABLE line (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    color_rgb INTEGER NOT NULL CHECK (color_rgb BETWEEN 0 AND 16777215),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE route_variant (
    id TEXT PRIMARY KEY,
    line_id TEXT NOT NULL REFERENCES line(id),
    display_name TEXT NOT NULL,
    direction_name TEXT NOT NULL,
    destination_name TEXT,
    circular INTEGER NOT NULL CHECK (circular IN (0, 1)),
    anchor_station_id TEXT REFERENCES station(id),
    terminal_policy TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK ((circular = 1 AND anchor_station_id IS NOT NULL) OR circular = 0)
);

CREATE TABLE route_variant_segment (
    route_variant_id TEXT NOT NULL REFERENCES route_variant(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL CHECK (sequence_number >= 0),
    track_segment_id TEXT NOT NULL REFERENCES track_segment(id),
    PRIMARY KEY (route_variant_id, sequence_number)
);

CREATE TABLE service_pattern (
    id TEXT PRIMARY KEY,
    route_variant_id TEXT NOT NULL REFERENCES route_variant(id),
    display_name TEXT NOT NULL,
    priority_class INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE service_pattern_call (
    service_pattern_id TEXT NOT NULL REFERENCES service_pattern(id) ON DELETE CASCADE,
    visit_index INTEGER NOT NULL CHECK (visit_index >= 0),
    station_id TEXT NOT NULL REFERENCES station(id),
    platform_id TEXT,
    stop_policy TEXT NOT NULL,
    dwell_seconds INTEGER NOT NULL DEFAULT 0 CHECK (dwell_seconds >= 0),
    PRIMARY KEY (service_pattern_id, visit_index),
    FOREIGN KEY (station_id, platform_id) REFERENCES platform(station_id, id)
);

CREATE TABLE trip (
    id TEXT PRIMARY KEY,
    service_pattern_id TEXT NOT NULL REFERENCES service_pattern(id),
    service_date TEXT NOT NULL,
    scheduled_start TEXT NOT NULL,
    destination_name TEXT,
    state TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX idx_trip_service_date_state ON trip(service_date, state);

CREATE TABLE train_unit (
    id TEXT PRIMARY KEY,
    managed_name TEXT NOT NULL UNIQUE,
    traincarts_name TEXT UNIQUE,
    template_id TEXT,
    fleet_number TEXT,
    state TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE train_trip_assignment (
    train_unit_id TEXT NOT NULL REFERENCES train_unit(id),
    trip_id TEXT NOT NULL REFERENCES trip(id),
    assigned_at TEXT NOT NULL,
    released_at TEXT,
    PRIMARY KEY (train_unit_id, trip_id)
);

CREATE UNIQUE INDEX idx_one_active_trip_per_train
    ON train_trip_assignment(train_unit_id)
    WHERE released_at IS NULL;

CREATE TABLE receiver (
    id TEXT PRIMARY KEY,
    receiver_type TEXT NOT NULL,
    station_id TEXT,
    platform_id TEXT,
    world_name TEXT NOT NULL,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (station_id, platform_id) REFERENCES platform(station_id, id)
);

CREATE UNIQUE INDEX idx_receiver_location
    ON receiver(world_name, block_x, block_y, block_z);

CREATE TABLE operational_event (
    id TEXT PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    event_type TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    recorded_at TEXT NOT NULL
);

CREATE INDEX idx_operational_event_type_time
    ON operational_event(event_type, occurred_at);

CREATE TABLE operational_event_attribute (
    event_id TEXT NOT NULL REFERENCES operational_event(id) ON DELETE CASCADE,
    attribute_key TEXT NOT NULL,
    attribute_value TEXT NOT NULL,
    PRIMARY KEY (event_id, attribute_key)
);

CREATE TABLE audit_record (
    id TEXT PRIMARY KEY,
    correlation_id TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT,
    before_json TEXT,
    after_json TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_audit_record_correlation ON audit_record(correlation_id);
CREATE INDEX idx_audit_record_created_at ON audit_record(created_at);

