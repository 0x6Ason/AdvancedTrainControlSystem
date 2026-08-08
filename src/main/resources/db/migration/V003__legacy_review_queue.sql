ALTER TABLE legacy_sign_inventory ADD COLUMN reviewed_by TEXT;
ALTER TABLE legacy_sign_inventory ADD COLUMN reviewed_at TEXT;
ALTER TABLE legacy_sign_inventory ADD COLUMN review_note TEXT;

CREATE TABLE legacy_sign_issue (
    sign_id TEXT NOT NULL REFERENCES legacy_sign_inventory(id) ON DELETE CASCADE,
    issue_code TEXT NOT NULL,
    severity TEXT NOT NULL,
    details TEXT NOT NULL,
    detected_at TEXT NOT NULL,
    PRIMARY KEY (sign_id, issue_code)
);

CREATE INDEX idx_legacy_sign_issue_code
    ON legacy_sign_issue(issue_code, severity);

CREATE INDEX idx_legacy_sign_review_status
    ON legacy_sign_inventory(review_status, present);
