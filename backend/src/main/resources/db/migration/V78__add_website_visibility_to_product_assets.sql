-- V78: 为 product_assets 增加官网可见性字段
-- website_visible: 控制单张素材是否出现在官网详情图库（默认 true，保持向后兼容）
-- website_sort_order: 官网图库独立排序（默认 0，与通用 sort_order 解耦）

ALTER TABLE product_assets
    ADD COLUMN website_visible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否在官网图库可见',
    ADD COLUMN website_sort_order INT NOT NULL DEFAULT 0 COMMENT '官网图库排序（升序）';

-- 为官网查询加索引：按 model_id + website_visible 过滤，按 website_sort_order 排序
CREATE INDEX idx_pa_website ON product_assets (model_id, website_visible, website_sort_order);
