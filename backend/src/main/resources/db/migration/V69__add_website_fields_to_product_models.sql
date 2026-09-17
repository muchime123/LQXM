-- V69: 官网发布字段。为 product_models 增加 website_enabled / website_slug / website_summary。
-- 用途：P1 阶段官网产品中心 + 产品详情页所需。website_enabled=false 的产品不出现在 /api/website/products。
-- slug 用于友好 URL（/products/{slug}），允许 NULL（未设置时官网用 id 兜底）。
ALTER TABLE product_models
    ADD COLUMN website_enabled BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否在官网发布',
    ADD COLUMN website_slug VARCHAR(128) NULL COMMENT '官网 URL 标识，如 automatic-map-machine',
    ADD COLUMN website_summary VARCHAR(500) NULL COMMENT '官网摘要（区别于内部 remark）',
    ADD INDEX idx_website_enabled (website_enabled),
    ADD UNIQUE INDEX uk_website_slug (website_slug);
