CREATE TABLE abbreviation_correction (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(16) NOT NULL REFERENCES abbreviation(code),
    before_entry TEXT NOT NULL,
    after_entry TEXT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX abbreviation_correction_code ON abbreviation_correction(code);
