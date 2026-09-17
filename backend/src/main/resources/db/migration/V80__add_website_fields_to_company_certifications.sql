-- V80: 为 company_certifications 增加官网发布字段
-- website_enabled: 是否在官网展示（默认 false，需手动开启）
-- website_featured: 是否在首页重点展示（默认 false）
-- website_sort_order: 官网排序（升序，默认 0）
-- public_description: 官网公开描述（可与内部 description 不同）

ALTER TABLE company_certifications
    ADD COLUMN website_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否在官网展示',
    ADD COLUMN website_featured TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否在官网首页重点展示',
    ADD COLUMN website_sort_order INT NOT NULL DEFAULT 0 COMMENT '官网排序（升序）',
    ADD COLUMN public_description TEXT COMMENT '官网公开描述';

-- 为官网查询加索引
CREATE INDEX idx_ccert_website ON company_certifications (website_enabled, website_sort_order);

-- 数据迁移：将现有 status='有效' 的资质默认设为官网可见，保持官网展示不中断
UPDATE company_certifications SET website_enabled = 1 WHERE status = '有效';
