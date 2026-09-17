-- V82: 为 company_milestones 增加 website_enabled 字段
-- 控制哪些里程碑展示在官网"关于我们"页面
-- 现有里程碑默认 website_enabled = 1，保持官网展示不中断

ALTER TABLE company_milestones
    ADD COLUMN website_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否在官网展示';

CREATE INDEX idx_cm_website ON company_milestones (website_enabled, sort_order);
