-- 补充公司信息表字段（兼容已有字段）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'former_name');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN former_name VARCHAR(200) AFTER company_name', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'paid_capital');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN paid_capital VARCHAR(100) AFTER registered_capital', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'insured_count');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN insured_count INT AFTER paid_capital', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'enterprise_scale');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN enterprise_scale VARCHAR(50) AFTER insured_count', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'factory_address');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN factory_address VARCHAR(500) AFTER office_address', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'brand_slogan');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN brand_slogan VARCHAR(300) AFTER brand_name_en', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'mission');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN mission TEXT AFTER brand_slogan', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'vision');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN vision TEXT AFTER mission', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'values');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN `values` TEXT AFTER vision', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'revenue_device_pct');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN revenue_device_pct DECIMAL(5,2) AFTER advantages', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'company_info' AND column_name = 'revenue_consumable_pct');
SET @sql = IF(@exists = 0, 'ALTER TABLE company_info ADD COLUMN revenue_consumable_pct DECIMAL(5,2) AFTER revenue_device_pct', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 修正注册资本
UPDATE company_info SET registered_capital = '1000万元人民币' WHERE registered_capital = '壹佰万元整';

-- 补充公司信息数据
UPDATE company_info SET
    former_name = '温州利强包装机械有限公司',
    paid_capital = '890万元人民币',
    insured_count = 23,
    enterprise_scale = '小型企业',
    factory_address = '',
    brand_slogan = '',
    mission = '',
    vision = '',
    `values` = '',
    revenue_device_pct = 70.00,
    revenue_consumable_pct = 30.00
WHERE id = 1;

-- 公司地址管理表
CREATE TABLE IF NOT EXISTS company_addresses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    address_type VARCHAR(30) NOT NULL COMMENT '地址类型: registered/office/factory/new_plant',
    address VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'current' COMMENT '状态: current/using/pending',
    contact_person VARCHAR(100),
    contact_phone VARCHAR(50),
    is_default BOOLEAN DEFAULT FALSE,
    sort_order INT DEFAULT 0,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO company_addresses (address_type, address, status, sort_order, created_at, updated_at) VALUES
('registered', '浙江省温州市瓯海区南白象街道金洋路77号温州启迪未来产业园D号楼第二层', 'current', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('office', '浙江省温州市瓯海区新桥街道新桥创新路85号', 'using', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('new_plant', '', 'pending', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 公司发展历程表
CREATE TABLE IF NOT EXISTS company_milestones (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    year INT NOT NULL COMMENT '年份',
    title VARCHAR(200) NOT NULL,
    description TEXT,
    icon VARCHAR(50),
    file_name VARCHAR(500),
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;