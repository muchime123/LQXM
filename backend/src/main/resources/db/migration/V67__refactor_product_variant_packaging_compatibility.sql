ALTER TABLE product_variants
    ADD COLUMN mold_size_type VARCHAR(20) NULL COMMENT 'RECT / ROUND / CUSTOM',
    ADD COLUMN mold_length_mm VARCHAR(40) NULL COMMENT '方形封口口径长边',
    ADD COLUMN mold_width_mm VARCHAR(40) NULL COMMENT '方形封口口径短边',
    ADD COLUMN mold_diameter_mm VARCHAR(40) NULL COMMENT '圆形封口口径直径',
    ADD COLUMN mold_size_label VARCHAR(100) NULL COMMENT '标准化模具尺寸展示文本';

CREATE TABLE product_variant_packaging_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_variant_id BIGINT NOT NULL,
    packaging_variant_id BIGINT NOT NULL,
    compatibility_status VARCHAR(20) NOT NULL DEFAULT 'MATCHED',
    match_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    override_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pvpl_product_variant
        FOREIGN KEY (product_variant_id) REFERENCES product_variants(id) ON DELETE CASCADE,
    CONSTRAINT fk_pvpl_packaging_variant
        FOREIGN KEY (packaging_variant_id) REFERENCES packaging_variants(id) ON DELETE RESTRICT,
    CONSTRAINT uk_pvpl_variant_packaging UNIQUE (product_variant_id, packaging_variant_id),
    INDEX idx_pvpl_packaging_variant (packaging_variant_id)
);

-- Preserve every distinct packaging association created by the abandoned slot design.
INSERT INTO product_variant_packaging_links
    (product_variant_id, packaging_variant_id, compatibility_status, match_source)
SELECT DISTINCT product_variant_id, packaging_variant_id, 'MATCHED', 'V66_MIGRATION'
FROM product_variant_packaging_slots;

-- Auto-migrate only abandoned slot records whose packages all share one sealing aperture.
UPDATE product_variants pv
JOIN (
    SELECT
        s.product_variant_id,
        COUNT(*) AS cavity_count,
        COUNT(DISTINCT CASE
            WHEN COALESCE(NULLIF(TRIM(pv2.top_diameter_mm), ''), NULLIF(TRIM(pv2.bottom_diameter_mm), '')) IS NOT NULL
                THEN CONCAT('ROUND:', CAST(CAST(COALESCE(NULLIF(TRIM(pv2.top_diameter_mm), ''), NULLIF(TRIM(pv2.bottom_diameter_mm), '')) AS DECIMAL(12,3)) AS DOUBLE))
            WHEN NULLIF(TRIM(pv2.length_mm), '') IS NOT NULL AND NULLIF(TRIM(pv2.width_mm), '') IS NOT NULL
                THEN CONCAT('RECT:', CAST(LEAST(CAST(pv2.length_mm AS DECIMAL(12,3)), CAST(pv2.width_mm AS DECIMAL(12,3))) AS DOUBLE),
                            'x', CAST(GREATEST(CAST(pv2.length_mm AS DECIMAL(12,3)), CAST(pv2.width_mm AS DECIMAL(12,3))) AS DOUBLE))
            ELSE CONCAT('UNKNOWN:', pv2.id)
        END) AS aperture_count,
        MAX(CASE WHEN COALESCE(NULLIF(TRIM(pv2.top_diameter_mm), ''), NULLIF(TRIM(pv2.bottom_diameter_mm), '')) IS NOT NULL THEN 'ROUND' ELSE 'RECT' END) AS size_type,
        MAX(pv2.length_mm) AS length_mm,
        MAX(pv2.width_mm) AS width_mm,
        MAX(COALESCE(NULLIF(TRIM(pv2.top_diameter_mm), ''), NULLIF(TRIM(pv2.bottom_diameter_mm), ''))) AS diameter_mm
    FROM product_variant_packaging_slots s
    JOIN packaging_variants pv2 ON pv2.id = s.packaging_variant_id
    GROUP BY s.product_variant_id
) migrated ON migrated.product_variant_id = pv.id
SET
    pv.output_count = CONCAT('一出', CASE migrated.cavity_count
        WHEN 1 THEN '一' WHEN 2 THEN '二' WHEN 3 THEN '三' WHEN 4 THEN '四'
        WHEN 5 THEN '五' WHEN 6 THEN '六' WHEN 7 THEN '七' WHEN 8 THEN '八'
        WHEN 9 THEN '九' WHEN 10 THEN '十' ELSE CAST(migrated.cavity_count AS CHAR) END),
    pv.box_or_mold_shape = COALESCE(NULLIF(pv.box_or_mold_shape, ''), CASE WHEN migrated.size_type = 'ROUND' THEN '圆模具' ELSE '方模具' END),
    pv.mold_size_type = migrated.size_type,
    pv.mold_length_mm = CASE WHEN migrated.size_type = 'RECT' THEN migrated.length_mm ELSE NULL END,
    pv.mold_width_mm = CASE WHEN migrated.size_type = 'RECT' THEN migrated.width_mm ELSE NULL END,
    pv.mold_diameter_mm = CASE WHEN migrated.size_type = 'ROUND' THEN migrated.diameter_mm ELSE NULL END,
    pv.mold_size_label = CASE
        WHEN migrated.size_type = 'ROUND' THEN CONCAT('Ø', migrated.diameter_mm, 'mm')
        ELSE CONCAT(migrated.length_mm, '×', migrated.width_mm, 'mm')
    END,
    pv.mouth_spec = CASE
        WHEN migrated.size_type = 'ROUND' THEN CONCAT('Ø', migrated.diameter_mm, 'mm')
        ELSE CONCAT(migrated.length_mm, '×', migrated.width_mm, 'mm')
    END,
    pv.variant_name = CONCAT(
        COALESCE(NULLIF(pv.box_or_mold_shape, ''), CASE WHEN migrated.size_type = 'ROUND' THEN '圆模具' ELSE '方模具' END),
        '｜一出', CASE migrated.cavity_count
            WHEN 1 THEN '一' WHEN 2 THEN '二' WHEN 3 THEN '三' WHEN 4 THEN '四'
            WHEN 5 THEN '五' WHEN 6 THEN '六' WHEN 7 THEN '七' WHEN 8 THEN '八'
            WHEN 9 THEN '九' WHEN 10 THEN '十' ELSE CAST(migrated.cavity_count AS CHAR) END,
        '｜', CASE WHEN migrated.size_type = 'ROUND'
            THEN CONCAT('Ø', migrated.diameter_mm, 'mm')
            ELSE CONCAT(migrated.length_mm, '×', migrated.width_mm, 'mm') END),
    pv.spec_key = CONCAT(
        REPLACE(COALESCE(NULLIF(pv.box_or_mold_shape, ''), CASE WHEN migrated.size_type = 'ROUND' THEN '圆模具' ELSE '方模具' END), '模具', ''),
        '|', migrated.cavity_count, '|',
        CASE WHEN migrated.size_type = 'ROUND'
            THEN CONCAT('ROUND:', CAST(CAST(migrated.diameter_mm AS DECIMAL(12,3)) AS DOUBLE))
            ELSE CONCAT('RECT:', CAST(LEAST(CAST(migrated.length_mm AS DECIMAL(12,3)), CAST(migrated.width_mm AS DECIMAL(12,3))) AS DOUBLE),
                        'x', CAST(GREATEST(CAST(migrated.length_mm AS DECIMAL(12,3)), CAST(migrated.width_mm AS DECIMAL(12,3))) AS DOUBLE)) END)
WHERE migrated.aperture_count = 1;

UPDATE product_variant_packaging_links l
JOIN product_variants pv ON pv.id = l.product_variant_id
SET l.compatibility_status = 'REVIEW'
WHERE pv.mold_size_label IS NULL;

-- The old table remains only as migration evidence. Runtime code no longer reads or writes it.
