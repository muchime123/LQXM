-- V133: 迁移旧 CONTRACT → 合同档案 + sales_contracts 全局只读化
-- 幂等设计：所有 CREATE TABLE / ADD COLUMN / DROP TRIGGER 均 IF NOT EXISTS / IF EXISTS
-- 注意：当前数据库中 CONTRACT 0 条（所有 NORMAL 20 条均已由 V132 关联到 sales_outbounds）
--       脚本保留完整 CONTRACT 迁移逻辑，防未来误插入后可重跑迁移。

-- ================= Step 1: 双备份（幂等） =================
DROP PROCEDURE IF EXISTS tmp_v133_backup_once;
DELIMITER //
CREATE PROCEDURE tmp_v133_backup_once()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_sales_contracts_v133') THEN
    CREATE TABLE backup_sales_contracts_v133 AS SELECT * FROM sales_contracts;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_sales_contract_items_v133') THEN
    CREATE TABLE backup_sales_contract_items_v133 AS SELECT * FROM sales_contract_items;
  END IF;
END //
DELIMITER ;
CALL tmp_v133_backup_once();
DROP PROCEDURE IF EXISTS tmp_v133_backup_once;

-- ================= Step 2: 新建合同档案表 contract_archives 与 contract_archive_items =================
CREATE TABLE IF NOT EXISTS contract_archives (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_no VARCHAR(50) NOT NULL COMMENT '合同编号（原 order_no）',
  contract_title VARCHAR(300) NULL COMMENT '合同抬头（从 customer_name/buyer_address 聚合生成，可补）',
  external_source VARCHAR(30) NOT NULL DEFAULT 'LEGACY_CONTRACT',
  external_id VARCHAR(100) NULL,
  source_hash CHAR(64) NULL,
  last_synced_at DATETIME NULL,
  customer_id BIGINT NULL,
  customer_name VARCHAR(200) NULL,
  buyer_address VARCHAR(300) NULL,
  salesperson_id BIGINT NULL,
  salesperson_name VARCHAR(50) NULL,
  signed_date DATE NULL COMMENT '合同签订日',
  effective_date DATE NULL COMMENT '合同生效日（补字段：= signed_date）',
  expire_date DATE NULL,
  region VARCHAR(50) NULL,
  shipping_method VARCHAR(50) NULL,
  is_free_shipping TINYINT(1) NULL,
  freight DECIMAL(14,2) NULL,
  warehouse VARCHAR(50) NULL,
  payment_terms VARCHAR(200) NULL COMMENT '付款条款（payment_status 升维语义）',
  payment_status VARCHAR(20) NULL,
  contract_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '合同签约金额（原 total_amount）',
  amount_in_words VARCHAR(200) NULL,
  total_purchase_amount DECIMAL(14,2) NULL,
  profit_amount DECIMAL(14,2) NULL,
  business_date DATE NULL,
  contact_phone VARCHAR(50) NULL,
  customer_address VARCHAR(300) NULL,
  contract_status VARCHAR(20) NOT NULL DEFAULT 'EFFECTIVE' COMMENT '合同履约状态（原 status）',
  line_count INT NOT NULL DEFAULT 0,
  remark VARCHAR(1000) NULL,
  -- 溯源列（从 sales_contracts 迁移）
  migrated_from_sales_contract_id BIGINT NULL UNIQUE COMMENT 'FK → sales_contracts.id',
  migrated_from_sales_contract_no VARCHAR(50) NULL,
  migrated_at DATETIME NULL,
  migration_note VARCHAR(300) NULL,
  -- 审计列
  deleted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_contract_no (contract_no),
  KEY idx_customer (customer_id),
  KEY idx_salesperson (salesperson_id),
  KEY idx_signed_date (signed_date),
  KEY idx_status (contract_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='§3.9 合同档案：纯约定事实，不参与销售额口径（销售事实以 sales_outbounds 为准）';

CREATE TABLE IF NOT EXISTS contract_archive_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_archive_id BIGINT NOT NULL,
  external_id VARCHAR(100) NULL,
  sequence_no INT NULL,
  line_title VARCHAR(300) NULL,
  line_type VARCHAR(20) NOT NULL DEFAULT 'PRODUCT',
  category VARCHAR(50) NULL,
  product_code VARCHAR(100) NULL,
  product_name VARCHAR(300) NULL,
  specification VARCHAR(300) NULL,
  unit VARCHAR(20) NULL,
  quantity DECIMAL(16,4) NULL,
  unit_price DECIMAL(14,2) NULL,
  line_total DECIMAL(14,2) NULL,
  purchase_price DECIMAL(14,2) NULL,
  purchase_total DECIMAL(14,2) NULL,
  source_profit DECIMAL(14,2) NULL,
  stock_qty DECIMAL(16,4) NULL,
  remark VARCHAR(1000) NULL,
  migrated_from_item_id BIGINT NULL UNIQUE COMMENT 'FK → sales_contract_items.id',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_contract (contract_archive_id),
  CONSTRAINT fk_ca_items_archive FOREIGN KEY (contract_archive_id) REFERENCES contract_archives(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合同档案明细表（纯约定快照，不参与利润核算）';

-- ================= Step 3: 溯源列加回 sales_contracts =================
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_contracts'
                     AND COLUMN_NAME = 'migrated_to_contract_archive_id');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE sales_contracts ADD COLUMN migrated_to_contract_archive_id BIGINT NULL COMMENT ''FK → contract_archives.id (§7.5)'' AFTER migration_note',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col2 = (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_contracts'
               AND COLUMN_NAME = 'migrated_to_contract_archive_at');
SET @ddl2 = IF(@col2 = 0,
  'ALTER TABLE sales_contracts ADD COLUMN migrated_to_contract_archive_at DATETIME NULL AFTER migrated_to_contract_archive_id',
  'SELECT 1');
PREPARE stmt2 FROM @ddl2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

SET @col3 = (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_contracts'
               AND COLUMN_NAME = 'contract_archive_migration_note');
SET @ddl3 = IF(@col3 = 0,
  'ALTER TABLE sales_contracts ADD COLUMN contract_archive_migration_note VARCHAR(300) NULL AFTER migrated_to_contract_archive_at',
  'SELECT 1');
PREPARE stmt3 FROM @ddl3; EXECUTE stmt3; DEALLOCATE PREPARE stmt3;

-- ================= Step 4: 迁移 CONTRACT（WHERE order_type=''CONTRACT''）=============
-- 4.1 contract_archives 主表
INSERT INTO contract_archives (
  contract_no, contract_title,
  external_source, external_id, source_hash, last_synced_at,
  customer_id, customer_name, buyer_address,
  salesperson_id, salesperson_name,
  signed_date, effective_date, expire_date,
  region, shipping_method, is_free_shipping, freight, warehouse,
  payment_terms, payment_status,
  contract_amount, amount_in_words,
  total_purchase_amount, profit_amount,
  business_date, contact_phone, customer_address,
  contract_status, line_count, remark,
  migrated_from_sales_contract_id, migrated_from_sales_contract_no, migrated_at, migration_note,
  deleted_at, created_at, updated_at
)
SELECT
  sc.order_no AS contract_no,
  CONCAT_WS(' ', sc.customer_name, sc.buyer_address) AS contract_title,
  CONCAT('LEGACY_', sc.external_source) AS external_source,
  sc.external_id, sc.source_hash, sc.last_synced_at,
  sc.customer_id, sc.customer_name, sc.buyer_address,
  sc.salesperson_id, sc.salesperson_name,
  sc.signed_date, sc.signed_date AS effective_date, NULL AS expire_date,
  sc.region, sc.shipping_method, sc.is_free_shipping, sc.freight, sc.warehouse,
  sc.payment_status AS payment_terms, sc.payment_status,
  sc.total_amount AS contract_amount, sc.amount_in_words,
  sc.total_purchase_amount, sc.profit_amount,
  sc.business_date, sc.contact_phone, sc.customer_address,
  sc.status AS contract_status, sc.line_count, sc.remark,
  sc.id AS migrated_from_sales_contract_id,
  sc.order_no AS migrated_from_sales_contract_no,
  NOW() AS migrated_at,
  CONCAT('V133 LEGACY CONTRACT→contract_archives (orig_id=', sc.id, ', order_no=', sc.order_no, ')') AS migration_note,
  sc.deleted_at, sc.created_at, sc.updated_at
FROM sales_contracts sc
WHERE sc.order_type = 'CONTRACT'
  AND NOT EXISTS (
    SELECT 1 FROM contract_archives ca
     WHERE ca.migrated_from_sales_contract_id = sc.id
        OR ca.contract_no = sc.order_no
  );

-- 4.2 回填 sales_contracts 溯源列
UPDATE sales_contracts sc
  JOIN contract_archives ca ON ca.migrated_from_sales_contract_id = sc.id
   SET sc.migrated_to_contract_archive_id = ca.id,
       sc.migrated_to_contract_archive_at = ca.migrated_at,
       sc.contract_archive_migration_note = ca.migration_note
 WHERE sc.order_type = 'CONTRACT'
   AND sc.migrated_to_contract_archive_id IS NULL;

-- 4.3 contract_archive_items（明细）
INSERT INTO contract_archive_items (
  contract_archive_id,
  external_id, sequence_no, line_title, line_type, category,
  product_code, product_name, specification, unit,
  quantity, unit_price, line_total,
  purchase_price, purchase_total, source_profit,
  stock_qty, remark,
  migrated_from_item_id
)
SELECT
  ca.id AS contract_archive_id,
  CONCAT('LEGACY_CONTRACT_ITEM_', sci.id) AS external_id,
  sci.line_no AS sequence_no,
  sci.product_name AS line_title,
  CASE WHEN sci.category = '费用' THEN 'FEE' ELSE 'PRODUCT' END AS line_type,
  sci.category,
  sci.product_code, sci.product_name, sci.spec AS specification, sci.unit,
  sci.quantity, sci.unit_price, sci.line_total,
  sci.purchase_price, sci.purchase_price * sci.quantity AS purchase_total, sci.profit AS source_profit,
  sci.stock_qty, sci.remark,
  sci.id AS migrated_from_item_id
FROM sales_contract_items sci
  JOIN sales_contracts sc ON sc.id = sci.contract_id
  JOIN contract_archives ca ON ca.migrated_from_sales_contract_id = sc.id
 WHERE sc.order_type = 'CONTRACT'
   AND NOT EXISTS (
     SELECT 1 FROM contract_archive_items cai
      WHERE cai.migrated_from_item_id = sci.id
   );

-- ================= Step 5: sales_contracts 全局只读化（3 个触发器硬拦截）=================
-- 设计：任何绕过 JPA / Service 的直接 SQL 写入都被拒绝
DROP TRIGGER IF EXISTS trg_sales_contracts_ro_insert;
DROP TRIGGER IF EXISTS trg_sales_contracts_ro_update;
DROP TRIGGER IF EXISTS trg_sales_contracts_items_ro_insert;
DROP TRIGGER IF EXISTS trg_sales_contracts_items_ro_update;
DROP TRIGGER IF EXISTS trg_sales_contracts_items_ro_delete;
DROP TRIGGER IF EXISTS trg_sales_contracts_ro_delete;

DELIMITER //
CREATE TRIGGER trg_sales_contracts_ro_insert BEFORE INSERT ON sales_contracts FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sales_contracts 已只读化（§7.5）。新增业务请使用「氚云 4 类 Excel 导入」写入 sales_outbounds / production_orders / receipts，合同请写入 contract_archives。';
END //
CREATE TRIGGER trg_sales_contracts_ro_update BEFORE UPDATE ON sales_contracts FOR EACH ROW
BEGIN
  -- 允许：仅溯源列本身的回填（V132/V133 脚本内部 UPDATE 调用）
  -- 判定：新旧对比，只有 3 个 contract_archive_* 溯源列 + 3 个 outbound_* 溯源列 + last_synced_at 允许变化
  IF NOT (
    (OLD.id <=> NEW.id) AND
    (OLD.order_no <=> NEW.order_no) AND
    (OLD.order_type <=> NEW.order_type) AND
    (OLD.external_source <=> NEW.external_source) AND
    (OLD.external_id <=> NEW.external_id) AND
    (OLD.source_hash <=> NEW.source_hash) AND
    (OLD.customer_id <=> NEW.customer_id) AND
    (OLD.customer_name <=> NEW.customer_name) AND
    (OLD.buyer_address <=> NEW.buyer_address) AND
    (OLD.salesperson_id <=> NEW.salesperson_id) AND
    (OLD.salesperson_name <=> NEW.salesperson_name) AND
    (OLD.signed_date <=> NEW.signed_date) AND
    (OLD.ship_date <=> NEW.ship_date) AND
    (OLD.region <=> NEW.region) AND
    (OLD.shipping_method <=> NEW.shipping_method) AND
    (OLD.is_free_shipping <=> NEW.is_free_shipping) AND
    (OLD.freight <=> NEW.freight) AND
    (OLD.warehouse <=> NEW.warehouse) AND
    (OLD.payment_status <=> NEW.payment_status) AND
    (OLD.unreceived_amount <=> NEW.unreceived_amount) AND
    (OLD.total_purchase_amount <=> NEW.total_purchase_amount) AND
    (OLD.total_sales_amount <=> NEW.total_sales_amount) AND
    (OLD.profit_amount <=> NEW.profit_amount) AND
    (OLD.received_amount <=> NEW.received_amount) AND
    (OLD.invoiced_amount <=> NEW.invoiced_amount) AND
    (OLD.uninvoiced_amount <=> NEW.uninvoiced_amount) AND
    (OLD.invoicer <=> NEW.invoicer) AND
    (OLD.business_date <=> NEW.business_date) AND
    (OLD.contact_phone <=> NEW.contact_phone) AND
    (OLD.customer_address <=> NEW.customer_address) AND
    (OLD.total_amount <=> NEW.total_amount) AND
    (OLD.amount_in_words <=> NEW.amount_in_words) AND
    (OLD.status <=> NEW.status) AND
    (OLD.line_count <=> NEW.line_count) AND
    (OLD.remark <=> NEW.remark) AND
    (OLD.deleted_at <=> NEW.deleted_at) AND
    (OLD.created_at <=> NEW.created_at) AND
    (OLD.updated_at <=> NEW.updated_at)
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sales_contracts 已只读化（§7.5）。禁止 UPDATE 业务语义列；仅允许迁移溯源列 last_synced_at / migrated_* 的变更。';
  END IF;
END //
CREATE TRIGGER trg_sales_contracts_ro_delete BEFORE DELETE ON sales_contracts FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sales_contracts 已只读化（§7.5）。禁止 DELETE；如需软删请改 contract_archives 表。';
END //
-- sales_contract_items 同理只读
CREATE TRIGGER trg_sales_contracts_items_ro_insert BEFORE INSERT ON sales_contract_items FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sales_contract_items 已只读化（§7.5）。合同明细请写入 contract_archive_items，出库明细请写入 sales_outbound_items。';
END //
CREATE TRIGGER trg_sales_contracts_items_ro_update BEFORE UPDATE ON sales_contract_items FOR EACH ROW
BEGIN
  IF NOT (OLD.id <=> NEW.id AND OLD.contract_id <=> NEW.contract_id
    AND OLD.line_no <=> NEW.line_no AND OLD.product_code <=> NEW.product_code
    AND OLD.product_name <=> NEW.product_name AND OLD.spec <=> NEW.spec
    AND OLD.unit <=> NEW.unit AND OLD.quantity <=> NEW.quantity
    AND OLD.unit_price <=> NEW.unit_price AND OLD.purchase_price <=> NEW.purchase_price
    AND OLD.line_total <=> NEW.line_total AND OLD.stock_qty <=> NEW.stock_qty
    AND OLD.category <=> NEW.category AND OLD.profit <=> NEW.profit
    AND OLD.remark <=> NEW.remark
    AND OLD.created_at <=> NEW.created_at AND OLD.updated_at <=> NEW.updated_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sales_contract_items 已只读化（§7.5）。禁止 UPDATE 业务语义列。';
  END IF;
END //
CREATE TRIGGER trg_sales_contracts_items_ro_delete BEFORE DELETE ON sales_contract_items FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sales_contract_items 已只读化（§7.5）。禁止 DELETE。';
END //
DELIMITER ;
