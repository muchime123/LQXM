-- V83: 移除 product_models.website_approved（官网发布不再需要人工审核）
-- 合并「官网发布管理」到「产品管理」后，官网是否展示仅由 website_enabled 决定

DROP INDEX idx_pm_website_approved ON product_models;

ALTER TABLE product_models
    DROP COLUMN website_approved;