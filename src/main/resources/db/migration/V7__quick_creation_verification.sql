ALTER TABLE review_batch ADD COLUMN creation_mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE review_batch ADD CONSTRAINT batch_creation_mode CHECK (creation_mode IN ('NORMAL','QUICK'));
ALTER TABLE catalog_service ADD COLUMN creation_kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE catalog_service ADD COLUMN cleanup_passes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE catalog_service ADD COLUMN required_cleanup_passes INTEGER NOT NULL DEFAULT 1;
UPDATE catalog_service SET cleanup_passes=1 WHERE cleaned_at IS NOT NULL;
ALTER TABLE catalog_service ADD CONSTRAINT service_verification CHECK (
    creation_kind IN ('STANDARD','QUICK') AND required_cleanup_passes IN (1,2)
    AND cleanup_passes BETWEEN 0 AND required_cleanup_passes
    AND (creation_kind <> 'QUICK' OR required_cleanup_passes=2));
CREATE TABLE catalog_cleanup_review (
    cleanup_id VARCHAR(36) NOT NULL REFERENCES catalog_cleanup_run(id),
    service_id VARCHAR(36) NOT NULL REFERENCES catalog_service(id),
    PRIMARY KEY (cleanup_id,service_id)
);
