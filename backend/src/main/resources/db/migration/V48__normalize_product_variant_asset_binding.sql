-- Give product specifications a stable structured identity and explicit provenance.
ALTER TABLE product_variants
    ADD COLUMN spec_key VARCHAR(240) NULL AFTER variant_name,
    ADD COLUMN baseline BOOLEAN NOT NULL DEFAULT FALSE AFTER spec_key,
    ADD COLUMN origin VARCHAR(30) NOT NULL DEFAULT 'LEGACY' AFTER baseline,
    ADD COLUMN based_on_variant_id BIGINT NULL AFTER origin;

UPDATE product_variants
SET spec_key = LOWER(CONCAT(TRIM(COALESCE(output_count, '')), '|', TRIM(COALESCE(box_or_mold_shape, ''))))
WHERE NULLIF(TRIM(COALESCE(output_count, '')), '') IS NOT NULL
   OR NULLIF(TRIM(COALESCE(box_or_mold_shape, '')), '') IS NOT NULL;

-- Existing duplicate records remain available, but only the oldest active record owns the identity.
UPDATE product_variants duplicate_variant
JOIN product_variants canonical
  ON canonical.model_id = duplicate_variant.model_id
 AND canonical.spec_key = duplicate_variant.spec_key
 AND canonical.id < duplicate_variant.id
 AND canonical.deleted_at IS NULL
SET duplicate_variant.spec_key = NULL
WHERE duplicate_variant.deleted_at IS NULL
  AND duplicate_variant.spec_key IS NOT NULL;

UPDATE product_variants variant
JOIN (
    SELECT model_id, MIN(id) AS baseline_id
    FROM product_variants
    WHERE deleted_at IS NULL
    GROUP BY model_id
) selected ON selected.baseline_id = variant.id
SET variant.baseline = TRUE,
    variant.origin = 'BASELINE';

ALTER TABLE product_variants
    ADD COLUMN active_spec_key VARCHAR(240)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN spec_key ELSE NULL END) STORED,
    ADD CONSTRAINT fk_product_variant_based_on
        FOREIGN KEY (based_on_variant_id) REFERENCES product_variants(id) ON DELETE SET NULL,
    ADD UNIQUE INDEX uk_product_variant_active_spec (model_id, active_spec_key),
    ADD INDEX idx_product_variant_baseline (model_id, baseline);

-- From this version onward product_assets.tags stores content tags only. Specification
-- labels are derived from the linked variant in API responses.
UPDATE product_assets asset
JOIN product_variants variant ON variant.id = asset.variant_id
SET asset.tags = TRIM(BOTH ',' FROM
    REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(asset.tags, ''),
        COALESCE(variant.output_count, ''), ''),
        COALESCE(variant.box_or_mold_shape, ''), ''), ',,', ','), '，', ','));
