-- Phase 1 product-domain boundary refactor.
-- This migration is intentionally additive. It does not remove legacy columns.

-- Product assets: manual uploads do not always come from an Excel row.
ALTER TABLE product_assets
    MODIFY COLUMN source_row_number INT NULL;

ALTER TABLE product_assets
    ADD COLUMN original_file_name VARCHAR(500) NULL AFTER file_name,
    ADD COLUMN storage_key VARCHAR(700) NULL AFTER original_file_name,
    ADD COLUMN file_url VARCHAR(1000) NULL AFTER storage_key,
    ADD COLUMN mime_type VARCHAR(120) NULL AFTER file_url,
    ADD COLUMN file_size BIGINT NULL AFTER mime_type;

-- Machine components can be model-level defaults or variant-specific overrides.
ALTER TABLE product_machine_components
    ADD COLUMN variant_id BIGINT NULL AFTER model_id;

ALTER TABLE product_machine_components
    ADD INDEX idx_component_variant_id (variant_id),
    ADD INDEX idx_component_scope (model_id, variant_id, component_key, sort_order),
    ADD CONSTRAINT fk_component_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Generated manuals need stable version metadata and relational integrity.
ALTER TABLE product_manual_versions
    ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at,
    ADD COLUMN published_by VARCHAR(100) NULL AFTER published_at,
    ADD COLUMN change_summary VARCHAR(500) NULL AFTER published_by;

ALTER TABLE product_manual_versions
    ADD INDEX idx_manual_versions_variant (variant_id),
    ADD UNIQUE INDEX uk_manual_model_variant_version (model_id, variant_id, version_number),
    ADD CONSTRAINT fk_manual_version_model
        FOREIGN KEY (model_id) REFERENCES product_models(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_manual_version_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Product documents may also apply only to a specific variant/configuration.
ALTER TABLE product_documents
    ADD COLUMN variant_id BIGINT NULL AFTER model_id,
    ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at;

ALTER TABLE product_documents
    DROP INDEX uk_product_document,
    ADD UNIQUE INDEX uk_product_document_scope (model_id, variant_id, document_id, doc_type),
    ADD INDEX idx_product_document_variant (variant_id),
    ADD CONSTRAINT fk_pd_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

-- General product relationships replace the narrower consumable-only relation model.
CREATE TABLE IF NOT EXISTS product_relations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_model_id BIGINT NOT NULL,
    source_variant_id BIGINT NULL,
    target_model_id BIGINT NOT NULL,
    target_variant_id BIGINT NULL,
    relation_type VARCHAR(60) NOT NULL,
    remark VARCHAR(500) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_product_relation_scope (
        source_model_id,
        source_variant_id,
        target_model_id,
        target_variant_id,
        relation_type
    ),
    INDEX idx_product_relation_source (source_model_id, source_variant_id),
    INDEX idx_product_relation_target (target_model_id, target_variant_id),
    CONSTRAINT fk_pr_source_model FOREIGN KEY (source_model_id)
        REFERENCES product_models(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pr_source_variant FOREIGN KEY (source_variant_id)
        REFERENCES product_variants(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pr_target_model FOREIGN KEY (target_model_id)
        REFERENCES product_models(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pr_target_variant FOREIGN KEY (target_variant_id)
        REFERENCES product_variants(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO product_relations (
    source_model_id,
    target_model_id,
    relation_type,
    created_at
)
SELECT
    product_id,
    consumable_id,
    COALESCE(NULLIF(relation_type, ''), 'compatible_with'),
    CURRENT_TIMESTAMP(6)
FROM product_consumable_relations old_rel
WHERE NOT EXISTS (
    SELECT 1
    FROM product_relations new_rel
    WHERE new_rel.source_model_id = old_rel.product_id
      AND new_rel.target_model_id = old_rel.consumable_id
      AND new_rel.source_variant_id IS NULL
      AND new_rel.target_variant_id IS NULL
      AND new_rel.relation_type = COALESCE(NULLIF(old_rel.relation_type, ''), 'compatible_with')
);
