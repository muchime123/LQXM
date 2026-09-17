-- V176 员工统一第一桥：氚云业务事实 employee_id 化
-- 目标：把氚云侧「salesperson_id = company_team_members.id」桥接到统一员工主键 employees.id，
--      让生产/出库/收款/业务单/客户业务员关系落到同一套 employee_id 上。
-- 纯结构 + 幂等回填，不触碰任何业务查询逻辑。
-- 幂等设计：列/索引均通过 information_schema 判断后创建，可安全重复执行。
-- 加列统一追加在表末尾（INSTANT 算法），不指定 AFTER 以免触发全表重建。

-- 1. company_team_members 增加 employee_id（统一员工桥）
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='company_team_members' AND COLUMN_NAME='employee_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE company_team_members ADD COLUMN employee_id BIGINT NULL COMMENT ''统一员工ID(employees.id)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='company_team_members' AND INDEX_NAME='idx_ctm_employee');
SET @ddl = IF(@has_idx=0, 'ALTER TABLE company_team_members ADD INDEX idx_ctm_employee (employee_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 回填 company_team_members.employee_id（姓名唯一匹配 employees）
UPDATE company_team_members m
JOIN employees e ON e.name = m.name
SET m.employee_id = e.id
WHERE m.employee_id IS NULL;

-- 3. 五张业务事实表增加 employee_id + 索引
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='production_orders' AND COLUMN_NAME='employee_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE production_orders ADD COLUMN employee_id BIGINT NULL COMMENT ''统一业务员ID(employees.id)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='production_orders' AND INDEX_NAME='idx_po_employee');
SET @ddl = IF(@has_idx=0, 'ALTER TABLE production_orders ADD INDEX idx_po_employee (employee_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='sales_outbounds' AND COLUMN_NAME='employee_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE sales_outbounds ADD COLUMN employee_id BIGINT NULL COMMENT ''统一业务员ID(employees.id)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='sales_outbounds' AND INDEX_NAME='idx_so_employee');
SET @ddl = IF(@has_idx=0, 'ALTER TABLE sales_outbounds ADD INDEX idx_so_employee (employee_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='receipts' AND COLUMN_NAME='employee_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE receipts ADD COLUMN employee_id BIGINT NULL COMMENT ''统一业务员ID(employees.id)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='receipts' AND INDEX_NAME='idx_receipts_employee');
SET @ddl = IF(@has_idx=0, 'ALTER TABLE receipts ADD INDEX idx_receipts_employee (employee_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='business_orders' AND COLUMN_NAME='employee_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE business_orders ADD COLUMN employee_id BIGINT NULL COMMENT ''统一业务员ID(employees.id)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='business_orders' AND INDEX_NAME='idx_bo_employee');
SET @ddl = IF(@has_idx=0, 'ALTER TABLE business_orders ADD INDEX idx_bo_employee (employee_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='customer_salesperson_relations' AND COLUMN_NAME='employee_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE customer_salesperson_relations ADD COLUMN employee_id BIGINT NULL COMMENT ''统一业务员ID(employees.id)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='customer_salesperson_relations' AND INDEX_NAME='idx_csr_employee');
SET @ddl = IF(@has_idx=0, 'ALTER TABLE customer_salesperson_relations ADD INDEX idx_csr_employee (employee_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. 回填五张事实表 employee_id（salesperson_id → company_team_members.employee_id）
UPDATE production_orders po
JOIN company_team_members m ON m.id = po.salesperson_id AND m.employee_id IS NOT NULL
SET po.employee_id = m.employee_id
WHERE po.employee_id IS NULL;

UPDATE sales_outbounds so
JOIN company_team_members m ON m.id = so.salesperson_id AND m.employee_id IS NOT NULL
SET so.employee_id = m.employee_id
WHERE so.employee_id IS NULL;

UPDATE receipts r
JOIN company_team_members m ON m.id = r.salesperson_id AND m.employee_id IS NOT NULL
SET r.employee_id = m.employee_id
WHERE r.employee_id IS NULL;

UPDATE business_orders bo
JOIN company_team_members m ON m.id = bo.salesperson_id AND m.employee_id IS NOT NULL
SET bo.employee_id = m.employee_id
WHERE bo.employee_id IS NULL;

UPDATE customer_salesperson_relations csr
JOIN company_team_members m ON m.id = csr.salesperson_id AND m.employee_id IS NOT NULL
SET csr.employee_id = m.employee_id
WHERE csr.employee_id IS NULL;

-- 5. 建立 H3YUN 外部身份映射（幂等，external_user_id = company_team_members.id，为未来 OwnerId 对齐留位）
INSERT INTO employee_external_mappings(employee_id, source_system, external_user_id, external_name, external_department, is_primary, remark, created_at, updated_at)
SELECT m.employee_id, 'H3YUN', CAST(m.id AS CHAR), m.name, m.department, 0,
       'V176 员工统一桥接：按姓名唯一匹配自动建立', NOW(), NOW()
FROM company_team_members m
WHERE m.employee_id IS NOT NULL
ON DUPLICATE KEY UPDATE
    external_name = VALUES(external_name),
    external_department = VALUES(external_department),
    updated_at = NOW();
