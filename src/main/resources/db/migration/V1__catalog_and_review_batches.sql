CREATE SEQUENCE local_service_code START WITH 1;

CREATE TABLE catalog_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL DEFAULT 0,
    dataset_key VARCHAR(36) NOT NULL
);
INSERT INTO catalog_state (id, revision, dataset_key) VALUES (1, 0, 'initial');

CREATE TABLE catalog_service (
    id VARCHAR(36) PRIMARY KEY,
    codigo_servico VARCHAR(128) NOT NULL,
    codigo_fornecedor VARCHAR(128) NOT NULL DEFAULT '',
    descricao_item VARCHAR(300) NOT NULL,
    grupo_servico VARCHAR(160) NOT NULL DEFAULT '',
    unidade_medida VARCHAR(32) NOT NULL DEFAULT '',
    unidade_descricao VARCHAR(160) NOT NULL DEFAULT '',
    request_key VARCHAR(36) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT service_code_supplier_unique UNIQUE (codigo_servico, codigo_fornecedor)
);

CREATE TABLE review_batch (
    id VARCHAR(36) PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    sheet_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_item (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL REFERENCES review_batch(id),
    row_number INTEGER NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'INVALID', 'EXISTING', 'CREATED')),
    error_message VARCHAR(1000),
    service_id VARCHAR(36) REFERENCES catalog_service(id),
    decided_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (batch_id, row_number)
);
CREATE INDEX review_item_batch_status ON review_item(batch_id, status, row_number);
