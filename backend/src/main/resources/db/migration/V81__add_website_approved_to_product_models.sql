-- V81: 为 product_models 增加 website_approved 字段
-- 用于区分"已审核发布"和"未审核即发布"的产品
-- V76 曾将所有产品默认发布到官网，但未经过人工审核
-- 本次迁移将所有现有产品标记为"未审核"，需人工审核后才能正式发布

ALTER TABLE product_models
    ADD COLUMN website_approved TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否经过人工审核后发布';

-- 为审核状态查询加索引
CREATE INDEX idx_pm_website_approved ON product_models (website_approved, website_enabled);

-- 数据迁移：
-- 1. 完整度 >= 40（已达官网列表门槛）的产品，标记为"已审核"，保持现有官网展示不中断
-- 2. 完整度 < 40 的产品，标记为"未审核"，需要人工审核
--    这些产品虽然可能已被 V76 发布，但内容不完整，不应出现在官网
UPDATE product_models
SET website_approved = 1
WHERE website_enabled = 1
  AND (
    CASE
      WHEN default_image IS NOT NULL AND default_image <> '' THEN 20 ELSE 0
    END
    + CASE WHEN website_summary IS NOT NULL AND website_summary <> '' THEN 20 ELSE 0 END
    + CASE WHEN name_en IS NOT NULL AND name_en <> '' THEN 20 ELSE 0 END
    + CASE
        WHEN EXISTS (
          SELECT 1 FROM product_assets pa
          WHERE pa.model_id = product_models.id AND pa.deleted_at IS NULL
        ) THEN 20 ELSE 0
      END
    + CASE
        WHEN EXISTS (
          SELECT 1 FROM product_variants pv
          WHERE pv.model_id = product_models.id AND pv.deleted_at IS NULL
        ) THEN 20 ELSE 0
      END
  ) >= 40;

-- 完整度 < 40 的产品保持 website_approved = 0（默认值），需要人工审核
