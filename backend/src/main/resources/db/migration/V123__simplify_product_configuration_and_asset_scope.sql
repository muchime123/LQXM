-- 产品配置简化：产品规格不再由模具形状/出数组成。
-- 旧 product_variants 保留为历史兼容来源；新业务使用机型档位与包材兼容关系。

ALTER TABLE product_assets
    ADD COLUMN machine_tier_id BIGINT NULL AFTER variant_id,
    ADD COLUMN feature_tags VARCHAR(500) NULL AFTER video_content_type,
    ADD COLUMN demonstration_tags VARCHAR(500) NULL AFTER feature_tags,
    ADD INDEX idx_product_asset_machine_tier (machine_tier_id),
    ADD CONSTRAINT fk_product_asset_machine_tier
        FOREIGN KEY (machine_tier_id) REFERENCES product_machine_tiers(id) ON DELETE SET NULL;

CREATE TABLE product_machine_tier_packaging_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    machine_tier_id BIGINT NOT NULL,
    packaging_variant_id BIGINT NOT NULL,
    relation_type VARCHAR(20) NOT NULL DEFAULT 'COMPATIBLE',
    match_source VARCHAR(20) NOT NULL DEFAULT 'MIGRATED',
    remark VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_pmtpl_tier_packaging UNIQUE (machine_tier_id, packaging_variant_id),
    CONSTRAINT fk_pmtpl_tier FOREIGN KEY (machine_tier_id) REFERENCES product_machine_tiers(id) ON DELETE CASCADE,
    CONSTRAINT fk_pmtpl_packaging FOREIGN KEY (packaging_variant_id) REFERENCES packaging_variants(id) ON DELETE CASCADE,
    INDEX idx_pmtpl_packaging (packaging_variant_id)
);

CREATE TABLE product_model_packaging_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_model_id BIGINT NOT NULL,
    packaging_variant_id BIGINT NOT NULL,
    relation_type VARCHAR(20) NOT NULL DEFAULT 'COMPATIBLE',
    match_source VARCHAR(20) NOT NULL DEFAULT 'MIGRATED',
    remark VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_pmpl_model_packaging UNIQUE (product_model_id, packaging_variant_id),
    CONSTRAINT fk_pmpl_model FOREIGN KEY (product_model_id) REFERENCES product_models(id) ON DELETE CASCADE,
    CONSTRAINT fk_pmpl_packaging FOREIGN KEY (packaging_variant_id) REFERENCES packaging_variants(id) ON DELETE CASCADE,
    INDEX idx_pmpl_packaging (packaging_variant_id)
);

-- 同一机型档位下原有多个“模具/出数规格”的包材关系合并去重。
INSERT IGNORE INTO product_machine_tier_packaging_links
    (machine_tier_id, packaging_variant_id, relation_type, match_source, remark, created_at, updated_at)
SELECT DISTINCT pv.machine_tier_id, old_link.packaging_variant_id,
       old_link.relation_type, 'MIGRATED', old_link.remark, NOW(6), NOW(6)
FROM product_variant_packaging_links old_link
JOIN product_variants pv ON pv.id = old_link.product_variant_id
WHERE pv.machine_tier_id IS NOT NULL AND pv.deleted_at IS NULL;

-- 无机型档位的历史兼容关系迁移为产品级关系。
INSERT IGNORE INTO product_model_packaging_links
    (product_model_id, packaging_variant_id, relation_type, match_source, remark, created_at, updated_at)
SELECT DISTINCT pv.model_id, old_link.packaging_variant_id,
       old_link.relation_type, 'MIGRATED', old_link.remark, NOW(6), NOW(6)
FROM product_variant_packaging_links old_link
JOIN product_variants pv ON pv.id = old_link.product_variant_id
WHERE pv.machine_tier_id IS NULL AND pv.deleted_at IS NULL;

-- 素材从旧规格继承机型档位；variant_id 暂留给文件归属与历史接口兼容。
UPDATE product_assets asset
JOIN product_variants pv ON pv.id = asset.variant_id
SET asset.machine_tier_id = pv.machine_tier_id
WHERE asset.machine_tier_id IS NULL AND pv.machine_tier_id IS NOT NULL;
