CREATE TABLE mold_import_batches (
    id BIGINT NOT NULL,
    source_file VARCHAR(500),
    source_sheet VARCHAR(200),
    status VARCHAR(20) NOT NULL DEFAULT 'running',
    mold_count INT NOT NULL DEFAULT 0,
    variant_link_count INT NOT NULL DEFAULT 0,
    accessory_count INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_mold_import_batch_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mold_import_batch_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT,
    operation_type VARCHAR(20) NOT NULL,
    entity_label VARCHAR(300),
    before_snapshot LONGTEXT,
    after_snapshot LONGTEXT,
    rollback_status VARCHAR(20) NOT NULL DEFAULT 'not_requested',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_mold_import_item_batch (batch_id),
    INDEX idx_mold_import_item_entity (entity_type, entity_id),
    CONSTRAINT fk_mold_import_item_batch FOREIGN KEY (batch_id)
        REFERENCES mold_import_batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
