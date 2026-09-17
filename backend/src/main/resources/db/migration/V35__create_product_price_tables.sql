-- V35: 创建产品价格方案/档位/选配加价表
-- 2026-07-21 | 将产品定价从 product_models.pricing_addons JSON 迁移到关系型结构

-- ============================================================
-- 1. 产品价格方案表
-- ============================================================
CREATE TABLE IF NOT EXISTS product_price_plans (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_model_id      BIGINT         NOT NULL COMMENT '关联产品/机型',
  plan_name             VARCHAR(200)   NOT NULL COMMENT '方案名称，如"半自动标准款定价表"',
  version               VARCHAR(50)    NOT NULL COMMENT '版本，如"2026-V1"',
  currency              VARCHAR(10)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
  price_unit            VARCHAR(50)    NOT NULL DEFAULT '元/台' COMMENT '价格单位',
  includes_mold_quantity INT           NOT NULL DEFAULT 1 COMMENT '基础价格包含模具套数',
  effective_date        DATE           NOT NULL COMMENT '生效日期',
  expiry_date           DATE           NULL COMMENT '失效日期',
  status                VARCHAR(20)    NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/ACTIVE/EXPIRED/DISABLED',
  remark                VARCHAR(1000)  NULL COMMENT '说明',
  created_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  CONSTRAINT uk_product_price_plans_model_version UNIQUE (product_model_id, version),
  CONSTRAINT fk_product_price_plans_model FOREIGN KEY (product_model_id) REFERENCES product_models(id) ON DELETE CASCADE,
  INDEX idx_product_price_plans_status (status),
  INDEX idx_product_price_plans_effective_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品价格方案';

-- ============================================================
-- 2. 产品基础价格档位表
-- ============================================================
CREATE TABLE IF NOT EXISTS product_price_tiers (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  price_plan_id         BIGINT         NOT NULL COMMENT '所属价格方案',
  tier_name             VARCHAR(200)   NOT NULL COMMENT '档位名称',
  shape_type            VARCHAR(20)    NOT NULL COMMENT '形状: SQUARE/ROUND/IRREGULAR',
  long_side_min_mm      INT            NULL COMMENT '长边最小值(mm)',
  long_side_max_mm      INT            NULL COMMENT '长边最大值(mm)',
  short_side_min_mm     INT            NULL COMMENT '短边最小值(mm)',
  short_side_max_mm     INT            NULL COMMENT '短边最大值(mm)',
  diameter_min_mm       INT            NULL COMMENT '直径最小值(mm)',
  diameter_max_mm       INT            NULL COMMENT '直径最大值(mm)',
  dealer_price          DECIMAL(10,2)  NOT NULL COMMENT '经销参考价',
  retail_price          DECIMAL(10,2)  NOT NULL COMMENT '零售参考价',
  priority              INT            NOT NULL DEFAULT 0 COMMENT '匹配优先级，数值越大越优先',
  display_text          VARCHAR(500)   NULL COMMENT '前端展示文字',
  enabled               TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '是否启用',
  remark                VARCHAR(1000)  NULL COMMENT '备注',
  created_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  CONSTRAINT fk_product_price_tiers_plan FOREIGN KEY (price_plan_id) REFERENCES product_price_plans(id) ON DELETE CASCADE,
  INDEX idx_product_price_tiers_shape (shape_type),
  INDEX idx_product_price_tiers_plan (price_plan_id),
  INDEX idx_product_price_tiers_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品基础价格档位';

-- ============================================================
-- 3. 产品选配加价表
-- ============================================================
CREATE TABLE IF NOT EXISTS product_price_addons (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  price_plan_id         BIGINT         NOT NULL COMMENT '所属价格方案',
  addon_code            VARCHAR(50)    NOT NULL COMMENT '唯一编码',
  addon_name            VARCHAR(200)   NOT NULL COMMENT '选配名称',
  addon_group           VARCHAR(100)   NULL COMMENT '选配分类',
  applicable_shape      VARCHAR(20)    NOT NULL DEFAULT 'ALL' COMMENT '适用形状: ALL/SQUARE/ROUND/IRREGULAR',
  dealer_add_price      DECIMAL(10,2)  NOT NULL DEFAULT 0 COMMENT '经销加价',
  retail_add_price      DECIMAL(10,2)  NOT NULL DEFAULT 0 COMMENT '零售加价',
  calculation_type      VARCHAR(20)    NOT NULL DEFAULT 'FIXED' COMMENT '计算类型: FIXED/PERCENTAGE/QUANTITY',
  exclusive_group       VARCHAR(50)    NULL COMMENT '互斥组编码',
  requires_manual_confirm TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否需要人工确认',
  enabled               TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '是否启用',
  sort_order            INT            NOT NULL DEFAULT 0 COMMENT '排序',
  remark                VARCHAR(1000)  NULL COMMENT '备注',
  created_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  CONSTRAINT uk_product_price_addons_plan_code UNIQUE (price_plan_id, addon_code),
  CONSTRAINT fk_product_price_addons_plan FOREIGN KEY (price_plan_id) REFERENCES product_price_plans(id) ON DELETE CASCADE,
  INDEX idx_product_price_addons_shape (applicable_shape),
  INDEX idx_product_price_addons_group (exclusive_group),
  INDEX idx_product_price_addons_enabled (enabled),
  INDEX idx_product_price_addons_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品选配加价';
