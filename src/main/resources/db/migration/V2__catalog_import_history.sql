-- Conserva a carga original, inclusive combinações repetidas que não sobrescrevem o catálogo.
CREATE TABLE catalog_import_history (
    id VARCHAR(36) PRIMARY KEY,
    source VARCHAR(40) NOT NULL,
    original_items TEXT NOT NULL,
    imported_count INTEGER NOT NULL,
    existing_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
