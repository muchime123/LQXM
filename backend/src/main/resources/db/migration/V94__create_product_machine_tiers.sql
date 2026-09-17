-- 机型档位表：夹在产品与规格型号之间的中间层
-- 同一台机器按物理尺寸划分多个机型档位（330 / 390 / 500），每个机型档位决定能配套的模具封口口径范围
CREATE TABLE product_machine_tiers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    model_id BIGINT NOT NULL,
    tier_code VARCHAR(40) NOT NULL COMMENT '机型档位编码，如 330 / 390 / 500',
    tier_label VARCHAR(80) NULL COMMENT '机型展示名，如 330机型',
    round_diameter_min INT NULL COMMENT '圆形封口口径下限 mm（含）',
    round_diameter_max INT NULL COMMENT '圆形封口口径上限 mm（含）',
    rect_length_min INT NULL COMMENT '方形封口长边下限 mm（含）',
    rect_length_max INT NULL COMMENT '方形封口长边上限 mm（含）',
    rect_width_min INT NULL COMMENT '方形封口短边下限 mm（含）',
    rect_width_max INT NULL COMMENT '方形封口短边上限 mm（含）',
    sort_order INT NOT NULL DEFAULT 0,
    remark VARCHAR(500) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_pmt_model
        FOREIGN KEY (model_id) REFERENCES product_models(id) ON DELETE CASCADE,
    CONSTRAINT uk_pmt_model_tier UNIQUE (model_id, tier_code),
    INDEX idx_pmt_model_id (model_id),
    INDEX idx_pmt_deleted_at (deleted_at)
);

-- 产品规格型号关联机型档位（可空，向后兼容已有规格）
ALTER TABLE product_variants
    ADD COLUMN machine_tier_id BIGINT NULL COMMENT '关联机型档位',
    ADD INDEX idx_variant_machine_tier (machine_tier_id);
