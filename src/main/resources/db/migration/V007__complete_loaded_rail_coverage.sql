ALTER TABLE rail_discovery_run
    ADD COLUMN discovery_mode TEXT NOT NULL DEFAULT 'SEEDED_COMPONENTS';

ALTER TABLE rail_discovery_run
    ADD COLUMN chunks_considered INTEGER NOT NULL DEFAULT 0 CHECK (chunks_considered >= 0);

ALTER TABLE rail_discovery_run
    ADD COLUMN chunks_scanned INTEGER NOT NULL DEFAULT 0 CHECK (chunks_scanned >= 0);

ALTER TABLE rail_discovery_run
    ADD COLUMN block_checks INTEGER NOT NULL DEFAULT 0 CHECK (block_checks >= 0);
