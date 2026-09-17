-- ============================================================
-- V18: 包材管理模块 — 3张表
-- packaging_models:    包材主表（打包装/铝箔碗/封口膜）
-- packaging_variants:  包材配置（容量/尺寸/卷膜阶梯价）
-- packaging_assets:    包材素材（7分类）
-- ============================================================

-- 1. 包材主表
CREATE TABLE packaging_models (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    name            VARCHAR(200) NOT NULL,
    name_en         VARCHAR(200),
    model_code      VARCHAR(80),

    -- 分类
    packaging_kind  VARCHAR(60),       -- container | bowl | sealing_film
    film_form       VARCHAR(60),       -- roll | sheet (仅封口膜使用)
    shape_type      VARCHAR(60),       -- round | square | rectangle | oval
    material        VARCHAR(100),      -- PP / PET / 铝箔 / 镀铝 / 纯铝 / 万能膜
    color_style     VARCHAR(100),      -- 透明 / 黑色 / 白色 / 彩印 / 磨砂
    capacity_ml     VARCHAR(60),       -- 容量（统一字段）
    applicable      VARCHAR(300),      -- 适用产品
    advantages      TEXT,              -- 产品亮点

    -- 商业字段
    moq             VARCHAR(60),       -- 起订量
    unit_price      VARCHAR(60),       -- 参考单价
    lead_time       VARCHAR(60),       -- 交期
    customizable    BOOLEAN DEFAULT FALSE,
    status          VARCHAR(60),       -- 在售 / 停产 / 预售
    tags            VARCHAR(500),

    -- 展示
    default_image   VARCHAR(500),
    sort_order      INT NOT NULL DEFAULT 0,
    remark          VARCHAR(1000),

    -- 导入追溯
    source_file     VARCHAR(500),
    source_sheet    VARCHAR(200),
    source_row_number INT,
    import_batch_id BIGINT,

    deleted_at      DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_packaging_name (name),
    INDEX idx_packaging_kind (packaging_kind),
    INDEX idx_packaging_material (material),
    INDEX idx_packaging_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 包材配置
CREATE TABLE packaging_variants (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    model_id        BIGINT NOT NULL,
    variant_name    VARCHAR(220),

    -- 规格
    capacity_ml     VARCHAR(60),
    length_mm       VARCHAR(60),
    width_mm        VARCHAR(60),
    height_mm       VARCHAR(60),
    weight_g        VARCHAR(60),
    boxes_per_roll  VARCHAR(60),       -- 封口膜专用: 5kg/卷可封1200盒
    material_detail VARCHAR(120),      -- 详细材质说明

    -- 价格（通用）
    dealer_price       VARCHAR(100),
    retail_price       VARCHAR(100),
    dealer_price_usd   VARCHAR(100),
    retail_price_usd   VARCHAR(100),

    -- 卷膜阶梯价（仅封口膜-卷膜使用）
    peelability     VARCHAR(60),       -- 易揭 / 不易揭 / 半易揭
    thickness_um    VARCHAR(60),       -- 8丝 / 10丝
    tier_50_99      DECIMAL(10,2),
    tier_100_299    DECIMAL(10,2),
    tier_300_499    DECIMAL(10,2),
    tier_500_999    DECIMAL(10,2),
    tier_1000_1999  DECIMAL(10,2),
    tier_2000_up    DECIMAL(10,2),
    plate_fee       DECIMAL(10,2),     -- 版费

    -- 展示
    sort_order      INT NOT NULL DEFAULT 0,
    remark          VARCHAR(1000),

    -- 导入追溯
    source_file     VARCHAR(500),
    source_sheet    VARCHAR(200),
    import_batch_id BIGINT,

    deleted_at      DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_pkg_variant_model_id (model_id),
    CONSTRAINT fk_pkg_variant_model FOREIGN KEY (model_id)
        REFERENCES packaging_models(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 包材素材
CREATE TABLE packaging_assets (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    model_id        BIGINT NOT NULL,
    variant_id      BIGINT,
    category        VARCHAR(60) NOT NULL,   -- main_image/scene_image/spec_sheet/drawing/printing/package/usage_video
    file_name       VARCHAR(500) NOT NULL,
    tags            VARCHAR(500),
    sort_order      INT NOT NULL DEFAULT 0,
    default_asset   BOOLEAN NOT NULL DEFAULT FALSE,

    -- 导入追溯
    source_file     VARCHAR(500),
    source_sheet    VARCHAR(200),
    import_batch_id BIGINT,

    deleted_at      DATETIME,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_pkg_asset_model_id (model_id),
    INDEX idx_pkg_asset_category (category),
    CONSTRAINT fk_pkg_asset_model FOREIGN KEY (model_id)
        REFERENCES packaging_models(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
