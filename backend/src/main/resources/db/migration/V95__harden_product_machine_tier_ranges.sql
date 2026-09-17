-- 机型能力范围需要支持 178.7 等小数口径；同时补齐 V94 遗漏的关系外键。
ALTER TABLE product_machine_tiers
    MODIFY COLUMN round_diameter_min DECIMAL(10,2) NULL,
    MODIFY COLUMN round_diameter_max DECIMAL(10,2) NULL,
    MODIFY COLUMN rect_length_min DECIMAL(10,2) NULL,
    MODIFY COLUMN rect_length_max DECIMAL(10,2) NULL,
    MODIFY COLUMN rect_width_min DECIMAL(10,2) NULL,
    MODIFY COLUMN rect_width_max DECIMAL(10,2) NULL;

ALTER TABLE product_variants
    ADD CONSTRAINT fk_product_variant_machine_tier
        FOREIGN KEY (machine_tier_id) REFERENCES product_machine_tiers(id) ON DELETE RESTRICT;

-- 同一口径可能同时适配多个机型，规格唯一身份必须包含机型档位。
UPDATE product_variants
SET spec_key = CONCAT('tier:', machine_tier_id, '|', spec_key)
WHERE machine_tier_id IS NOT NULL
  AND spec_key IS NOT NULL
  AND spec_key NOT LIKE 'tier:%';
