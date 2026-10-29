ALTER TABLE catalog_service ADD COLUMN cleaned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE catalog_service ADD COLUMN cleanup_id VARCHAR(36);
ALTER TABLE catalog_service ADD COLUMN replaced_by VARCHAR(36) REFERENCES catalog_service(id);
CREATE INDEX catalog_active_cleanup ON catalog_service(replaced_by, cleaned_at);
CREATE TABLE catalog_cleanup_run (
    id VARCHAR(36) PRIMARY KEY,
    source_revision VARCHAR(100) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
