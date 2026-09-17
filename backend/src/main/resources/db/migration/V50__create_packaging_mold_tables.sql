-- ============================================================
-- V50: 包材模具资产台账 — 3张表
-- packaging_molds:        模具主表（模号/盒型/出模数/格数/结构/刻字）
-- mold_variant_links:     模具↔规格 N:N 关联（一个模具可生产多规格，一个规格可有多模具）
-- mold_accessories:       模具配件清单（杯胆/杯底/拉伸头/压料环）
-- ============================================================

-- 1. 模具主表
CREATE TABLE packaging_molds (
    id                   BIGINT NOT NULL AUTO_INCREMENT,
    model_id             BIGINT NOT NULL,
    mold_code            VARCHAR(80) NOT NULL,           -- 完整模号 M20910-3F
    base_mold_code       VARCHAR(60),                    -- 基础号 M20910（解析尾部后）
    mold_type_code       VARCHAR(20),                    -- 盒型代码 F/B/W/G/WJ/FB（存原始值，不强译）
    cavity_count         INT,                            -- 出模数（从 -3F 解析数字部分）
    mold_role            VARCHAR(20) NOT NULL DEFAULT 'unknown', -- main | accessory | unknown
    mold_category        VARCHAR(40),                    -- 方盒/圆杯/碗/桶/盖（业务分类）
    compartment_count    VARCHAR(20),                    -- 单格/双格/数字
    surface_structure    VARCHAR(60),                    -- 光面/圆筋/方筋/瓦楞/腐蚀
    bottom_engraving     VARCHAR(300),                   -- 杯底刻字原文
    engraving_type       VARCHAR(20),                    -- blank | generic | brand_custom | functional
    raw_accessories_text VARCHAR(500),                   -- 配件原始整格文本（追溯用）
    status               VARCHAR(20) NOT NULL DEFAULT 'in_use', -- in_use | stopped | repair | unverified
    remark               VARCHAR(1000),

    -- 导入追溯
    source_file          VARCHAR(500),
    source_sheet         VARCHAR(200),
    source_row_number    INT,
    import_batch_id      BIGINT,

    -- 软删除
    deleted_at           DATETIME,
    created_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_packaging_mold_code (mold_code),
    INDEX idx_pkg_mold_model_id (model_id),
    INDEX idx_pkg_mold_base_code (base_mold_code),
    INDEX idx_pkg_mold_status (status),
    INDEX idx_pkg_mold_role (mold_role),
    INDEX idx_pkg_mold_category (mold_category),
    CONSTRAINT fk_pkg_mold_model FOREIGN KEY (model_id)
        REFERENCES packaging_models(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 模具↔规格 关联表（N:N）
CREATE TABLE mold_variant_links (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    mold_id         BIGINT NOT NULL,
    variant_id      BIGINT NOT NULL,
    relation_type   VARCHAR(20) NOT NULL DEFAULT 'main', -- main | backup | paired
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    remark          VARCHAR(500),

    -- 导入追溯
    source_file     VARCHAR(500),
    source_sheet    VARCHAR(200),
    source_row_number INT,
    import_batch_id BIGINT,

    -- 软删除
    deleted_at      DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_mold_variant (mold_id, variant_id),
    INDEX idx_mvl_mold_id (mold_id),
    INDEX idx_mvl_variant_id (variant_id),
    CONSTRAINT fk_mvl_mold FOREIGN KEY (mold_id)
        REFERENCES packaging_molds(id),
    CONSTRAINT fk_mvl_variant FOREIGN KEY (variant_id)
        REFERENCES packaging_variants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 模具配件表
CREATE TABLE mold_accessories (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    mold_id         BIGINT NOT NULL,
    accessory_type  VARCHAR(40) NOT NULL,               -- cup_liner | cup_bottom | drawing_head | press_ring | other
    accessory_code  VARCHAR(80),                        -- 配件编号（如有）
    quantity        INT NOT NULL DEFAULT 1,
    shared_scope    VARCHAR(20) NOT NULL DEFAULT 'dedicated', -- dedicated | series_shared | global_shared
    remark          VARCHAR(500),

    -- 导入追溯
    source_file     VARCHAR(500),
    source_sheet    VARCHAR(200),
    source_row_number INT,
    import_batch_id BIGINT,

    -- 软删除
    deleted_at      DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_mold_acc_mold_id (mold_id),
    INDEX idx_mold_acc_type (accessory_type),
    CONSTRAINT fk_mold_acc_mold FOREIGN KEY (mold_id)
        REFERENCES packaging_molds(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
