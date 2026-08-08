CREATE TABLE route_plan (
    id TEXT PRIMARY KEY,
    line_id TEXT NOT NULL REFERENCES line(id),
    display_name TEXT NOT NULL,
    direction_name TEXT NOT NULL,
    destination_name TEXT,
    circular INTEGER NOT NULL DEFAULT 0 CHECK (circular IN (0, 1)),
    anchor_station_id TEXT REFERENCES station(id),
    terminal_policy TEXT NOT NULL CHECK (terminal_policy IN (
        'TURNBACK',
        'CONTINUE_AS',
        'DEPOT',
        'DEADHEAD',
        'DESPAWN',
        'LOOP_CONTINUE'
    )),
    track_assignment_state TEXT NOT NULL DEFAULT 'UNASSIGNED' CHECK (
        track_assignment_state IN ('UNASSIGNED', 'PARTIAL', 'COMPLETE')
    ),
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK ((circular = 1 AND anchor_station_id IS NOT NULL) OR circular = 0),
    CHECK ((circular = 1 AND terminal_policy = 'LOOP_CONTINUE')
        OR (circular = 0 AND terminal_policy <> 'LOOP_CONTINUE')),
    CHECK (enabled = 0 OR track_assignment_state = 'COMPLETE')
);

CREATE INDEX idx_route_plan_line ON route_plan(line_id, enabled);

CREATE TABLE route_plan_call (
    route_plan_id TEXT NOT NULL REFERENCES route_plan(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL CHECK (sequence_number >= 0),
    station_id TEXT NOT NULL REFERENCES station(id),
    platform_id TEXT,
    stop_policy TEXT NOT NULL CHECK (stop_policy IN (
        'STOP',
        'REQUEST',
        'PASS',
        'NON_SERVICE'
    )),
    dwell_seconds INTEGER NOT NULL DEFAULT 0 CHECK (dwell_seconds >= 0),
    PRIMARY KEY (route_plan_id, sequence_number),
    FOREIGN KEY (station_id, platform_id) REFERENCES platform(station_id, id)
);

CREATE INDEX idx_route_plan_call_station
    ON route_plan_call(station_id, route_plan_id, sequence_number);
