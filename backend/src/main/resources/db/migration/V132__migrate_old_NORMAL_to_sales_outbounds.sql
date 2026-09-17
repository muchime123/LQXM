-- =========================================================================
-- V132: §7.4 旧 sales_contracts.order_type='NORMAL' → sales_outbounds 迁移
-- -------------------------------------------------------------------------
-- 设计文档要求：将旧 sales_contracts（order_type=NORMAL）的普通订单记录
-- 正式迁移/关联到 sales_outbounds（销售出库事实表），同时打通与
-- business_orders、receipt_allocations 的关联链，确保后续氚云页面下线后
-- 数据不丢失、可追溯、可回滚。
--
-- 本次真实数据环境：20 条 NORMAL ↔ 20 条 sales_outbounds 已 100% 匹配
-- （按 order_no = outbound_no 配对）。因此脚本以"关联标记 + 溯源列 +
-- 双备份"为主，同时提供"防御性补齐 INSERT/UPDATE"以兼容未来增量场景。
--
-- 回滚策略：迁移前将 sales_contracts / sales_contract_items 全量备份到
-- backup_sales_contracts_v132 / backup_sales_contract_items_v132，
-- 迁移结果可通过 migrated_to_outbound_id 列反向追踪。
-- =========================================================================

-- =========================================================================
-- Step 1: 双备份（强幂等：仅当备份表不存在时才 CREATE + 全量 INSERT）
--         用存储过程 + IF 条件判断，避免"先建空表 → 源表后续加列 → 后续重跑时
--         SELECT * 列数不匹配 → 1136 错误"的坑。
-- =========================================================================
DROP PROCEDURE IF EXISTS tmp_v132_backup_once;
DELIMITER //
CREATE PROCEDURE tmp_v132_backup_once()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'backup_sales_contracts_v132'
  ) THEN
    CREATE TABLE backup_sales_contracts_v132 AS SELECT * FROM sales_contracts;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'backup_sales_contract_items_v132'
  ) THEN
    CREATE TABLE backup_sales_contract_items_v132 AS SELECT * FROM sales_contract_items;
  END IF;
END //
DELIMITER ;
CALL tmp_v132_backup_once();
DROP PROCEDURE IF EXISTS tmp_v132_backup_once;

-- =========================================================================
-- Step 2: 为 sales_contracts 源表新增三列可溯源迁移标记（幂等：检查后再加）
--         migrated_to_outbound_id  = 新表 sales_outbounds.id FK
--         migrated_to_outbound_at = 执行迁移时间
--         migration_note           = 人类可读说明（避免未来人工困惑）
-- =========================================================================
DROP PROCEDURE IF EXISTS tmp_v132_add_cols;
DELIMITER //
CREATE PROCEDURE tmp_v132_add_cols()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'sales_contracts'
       AND COLUMN_NAME  = 'migrated_to_outbound_id'
  ) THEN
    ALTER TABLE sales_contracts
      ADD COLUMN migrated_to_outbound_id BIGINT NULL
        COMMENT 'V132 迁移：对应新 sales_outbounds.id（FK）',
      ADD COLUMN migrated_to_outbound_at DATETIME NULL
        COMMENT 'V132 迁移：实际执行迁移时间',
      ADD COLUMN migration_note VARCHAR(300) NULL
        COMMENT 'V132 迁移：人工可读迁移说明';
  END IF;
END //
DELIMITER ;
CALL tmp_v132_add_cols();
DROP PROCEDURE IF EXISTS tmp_v132_add_cols;

-- =========================================================================
-- Step 3: 核心关联（100% 命中：旧订单号 = 新出库编号）
--         对 order_type='NORMAL' 且尚未标记的源行，按 order_no 绑定
-- =========================================================================
UPDATE sales_contracts sc
  JOIN sales_outbounds so
    ON so.outbound_no = sc.order_no
   SET sc.migrated_to_outbound_id = so.id,
       sc.migrated_to_outbound_at = NOW(),
       sc.migration_note = CONCAT(
         'V132 旧 NORMAL→sales_outbounds 自动关联 (order_no=outbound_no, so_id=',
         so.id, ', so_source=', so.external_source, ')'
       )
 WHERE sc.order_type = 'NORMAL'
   AND sc.migrated_to_outbound_id IS NULL;

-- =========================================================================
-- Step 4: 防御性补齐——理论上本次 0 行生效（所有 20 条 100% 匹配）
--         若未来 sales_contracts 新增了 NORMAL 但新导入服务未将其入新表，
--         这里做一条可靠的兜底 INSERT，使用与 H3 导入相同的字段映射。
-- =========================================================================
INSERT INTO sales_outbounds (
  external_source, external_id, outbound_no, status,
  ship_date, business_date, customer_id, customer_name,
  salesperson_id, salesperson_name, region, contact_phone,
  customer_address, shipping_method, free_shipping, freight,
  warehouse, payment_status, order_remark,
  purchase_amount, sales_amount, source_gross_profit,
  received_amount, outstanding_amount, invoiced_amount, uninvoiced_amount,
  department_name, source_created_by, source_created_at, source_updated_at,
  source_hash, last_synced_at, external_title
)
SELECT
  'LEGACY_NORMAL',                     -- external_source：明确是旧普通订单迁移
  sc.external_id,                      -- external_id：保留氚云/原外部 ObjectId
  sc.order_no,                         -- outbound_no：订单编号（UNIQUE）
  CASE sc.status
    WHEN 'EFFECTIVE' THEN '已生效'
    WHEN 'DRAFT'     THEN '草稿'
    ELSE COALESCE(sc.status, '进行中')
  END,                                 -- status：枚举映射（中文状态兼容前端）
  sc.ship_date,                        -- ship_date
  COALESCE(sc.business_date, sc.ship_date, sc.signed_date),
  sc.customer_id, sc.customer_name,
  sc.salesperson_id, sc.salesperson_name,
  sc.region, sc.contact_phone,
  COALESCE(sc.customer_address, sc.buyer_address),
  sc.shipping_method, sc.is_free_shipping, sc.freight,
  sc.warehouse, sc.payment_status, sc.remark,
  sc.total_purchase_amount,            -- purchase_amount
  COALESCE(sc.total_sales_amount, sc.total_amount),
  sc.profit_amount,                    -- source_gross_profit
  sc.received_amount,
  sc.unreceived_amount,                -- outstanding_amount
  sc.invoiced_amount, sc.uninvoiced_amount,
  NULL AS department_name,
  'V132_migration_from_LEGACY_NORMAL', -- source_created_by：迁移标记
  sc.created_at,                       -- source_created_at：保留原始创建时间
  sc.updated_at,                       -- source_updated_at：保留最后修改时间
  sc.source_hash,
  NOW(),                               -- last_synced_at
  CONCAT_WS('   ', sc.order_no, COALESCE(sc.customer_name,''),
            FORMAT(COALESCE(sc.total_sales_amount, sc.total_amount), 2))
FROM sales_contracts sc
 WHERE sc.order_type = 'NORMAL'
   AND NOT EXISTS (
     SELECT 1 FROM sales_outbounds so2 WHERE so2.outbound_no = sc.order_no
   );

-- 若步骤 4 真有 INSERT（新补齐了订单），回写溯源列
UPDATE sales_contracts sc
  JOIN sales_outbounds so ON so.outbound_no = sc.order_no
   SET sc.migrated_to_outbound_id = so.id,
       sc.migrated_to_outbound_at = NOW(),
       sc.migration_note = CONCAT(
         'V132 旧 NORMAL→sales_outbounds 防御性兜底 INSERT (so_id=', so.id, ')'
       )
 WHERE sc.order_type = 'NORMAL'
   AND sc.migrated_to_outbound_id IS NULL;

-- =========================================================================
-- Step 5: receipt_allocations FK 补齐
--         现实中 70 条 allocation 的 snapshot 为历史 CK 编号（非 8/21 批次），
--         本次 0 行命中；但该 UPDATE 为通用防御——只要 snapshot 是新出库编号
--         就将 sales_outbound_id FK 填上，彻底摆脱对 snapshot 依赖。
-- =========================================================================
UPDATE receipt_allocations ra
  JOIN sales_outbounds so
    ON so.outbound_no = ra.outbound_no_snapshot
   SET ra.sales_outbound_id = so.id
 WHERE ra.sales_outbound_id IS NULL;

-- =========================================================================
-- Step 6: 防御性补齐明细（sales_contract_items → sales_outbound_items）
--         现有 20 张 NORMAL → 39 条 items 已全部就位，步骤 6 理论 0 行。
-- =========================================================================
INSERT INTO sales_outbound_items (
  sales_outbound_id, external_id, sequence_no,
  external_title, line_type, category,
  product_code, product_name, specification, unit,
  quantity, sales_unit_price, sales_total,
  purchase_unit_price, purchase_total, source_profit,
  stock_quantity, remark
)
SELECT
  sc.migrated_to_outbound_id AS sales_outbound_id,
  CONCAT('LEGACY_SC_ITEM_', sci.id) AS external_id,
  sci.line_no AS sequence_no,
  sci.product_name,
  CASE WHEN sci.category = '费用' THEN 'FEE' ELSE 'PRODUCT' END AS line_type,
  sci.category,
  sci.product_code, sci.product_name, sci.spec AS specification, sci.unit,
  sci.quantity,
  sci.unit_price AS sales_unit_price,
  sci.line_total AS sales_total,
  sci.purchase_price AS purchase_unit_price,
  sci.purchase_price * sci.quantity AS purchase_total,
  sci.profit AS source_profit,
  sci.stock_qty AS stock_quantity,
  sci.remark
FROM sales_contract_items sci
  JOIN sales_contracts sc ON sc.id = sci.contract_id
 WHERE sc.order_type = 'NORMAL'
   AND sc.migrated_to_outbound_id IS NOT NULL
   AND NOT EXISTS (
     SELECT 1 FROM sales_outbound_items soi
      WHERE soi.sales_outbound_id = sc.migrated_to_outbound_id
        AND soi.sequence_no = sci.line_no
   );

-- =========================================================================
-- Step 7: 防御性补齐业务单关联（business_orders + SALES_OUTBOUND links）
--         现状：20/20 均已挂 INVENTORY_DIRECT 业务单并 CONFIRMED。
--         若步骤 4 触发了防御性 INSERT，则在此补业务单。
-- =========================================================================
DROP PROCEDURE IF EXISTS tmp_v132_ensure_bo_link;
DELIMITER //
CREATE PROCEDURE tmp_v132_ensure_bo_link()
BEGIN
  DECLARE v_done INT DEFAULT 0;
  DECLARE v_so_id BIGINT;
  DECLARE v_cust_name VARCHAR(300);
  DECLARE v_sales_name VARCHAR(100);
  DECLARE v_ship_date DATE;
  DECLARE v_business_date DATE;
  DECLARE v_cur CURSOR FOR
    SELECT so.id, so.customer_name, so.salesperson_name, so.ship_date, so.business_date
      FROM sales_outbounds so
      JOIN sales_contracts sc ON sc.migrated_to_outbound_id = so.id
     WHERE sc.order_type = 'NORMAL'
       AND NOT EXISTS (
         SELECT 1 FROM business_order_links bol
           WHERE bol.object_type = 'SALES_OUTBOUND'
             AND bol.object_id   = so.id
       );
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  OPEN v_cur;
  v_loop: LOOP
    FETCH v_cur INTO v_so_id, v_cust_name, v_sales_name, v_ship_date, v_business_date;
    IF v_done = 1 THEN LEAVE v_loop; END IF;

    -- (a) 生成独立业务单（库存直销类型，OUTBOUNDED 生命周期）
    INSERT INTO business_orders (
      business_no, customer_name_snapshot, salesperson_name_snapshot,
      business_type, lifecycle_status, contract_status, match_status,
      first_activity_date, last_activity_date
    ) VALUES (
      CONCAT('BO', UPPER(LEFT(REPLACE(UUID(),'-',''), 18))),
      v_cust_name, v_sales_name,
      'INVENTORY_DIRECT', 'OUTBOUNDED', 'UNKNOWN', 'CONFIRMED',
      COALESCE(v_business_date, v_ship_date, CURDATE()),
      COALESCE(v_business_date, v_ship_date, CURDATE())
    );
    SET @v_new_bo_id = LAST_INSERT_ID();

    -- (b) 通过 links 关联 SALES_OUTBOUND（100 置信 AUTO_RULE）
    INSERT INTO business_order_links (
      business_order_id, object_type, object_id,
      link_method, confidence, match_reason, confirmed_by, confirmed_at
    ) VALUES (
      @v_new_bo_id, 'SALES_OUTBOUND', v_so_id,
      'AUTO_RULE', 100,
      CONCAT('V132 迁移：LEGACY NORMAL 销售出库自动形成库存直销业务单 (so_id=', v_so_id, ')'),
      'V132_migration_script', NOW()
    );
  END LOOP v_loop;
  CLOSE v_cur;
END //
DELIMITER ;
CALL tmp_v132_ensure_bo_link();
DROP PROCEDURE IF EXISTS tmp_v132_ensure_bo_link;
