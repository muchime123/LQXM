-- V75: 官网首页精选产品字段。
-- 用途：product_models 增加 website_featured，标记首页「核心产品」区域展示的产品。
-- true 的产品出现在 /api/website/home 的精选产品列表；false 仅作为普通产品出现在产品中心。
-- 实体 ProductModel.websiteFeatured 已声明，此迁移补齐数据库列。
ALTER TABLE product_models
    ADD COLUMN website_featured BIT(1) NOT NULL DEFAULT b'0' COMMENT '是否作为官网首页精选产品',
    ADD INDEX idx_website_featured (website_featured);
