-- 品牌资产表（替代前端 localStorage 方案）
-- 执行时间：2026-07-02
-- Hibernate ddl-auto=update 会在重启时自动建表，此脚本供手动执行用

CREATE TABLE IF NOT EXISTS company_assets (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    category      VARCHAR(50)  NOT NULL,       -- 'logo' | 'seal' | 'info_image'
    file_name     VARCHAR(500) NOT NULL,        -- 对应 storage/lq-files/company/ 目录下的文件名
    display_order  INT          DEFAULT 0,       -- 展示顺序（越小越靠前）
    description   VARCHAR(500),                  -- 备注（如"横版Logo"、"方形印章"）
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_display_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始化数据：将 company_info 表中已有的文件迁移过来（如有）
-- INSERT INTO company_assets (category, file_name, display_order, description)
-- SELECT 'logo', logo_file_name, 0, '公司Logo'
--   FROM company_info WHERE logo_file_name IS NOT NULL AND logo_file_name != '';
--
-- INSERT INTO company_assets (category, file_name, display_order, description)
-- SELECT 'seal', seal_file_name, 0, '公司印章'
--   FROM company_info WHERE seal_file_name IS NOT NULL AND seal_file_name != '';
--
-- INSERT INTO company_assets (category, file_name, display_order, description)
-- SELECT 'info_image', license_file_name, 0, '营业执照'
--   FROM company_info WHERE license_file_name IS NOT NULL AND license_file_name != '';
