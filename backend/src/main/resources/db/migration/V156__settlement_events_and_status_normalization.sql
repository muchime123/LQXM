-- ====================================================================
-- V156 结清事件流水 + 出库状态归一化 + 分配引用解析标记 + 业务单结清聚合
-- 对应优化路线图：
--   P0-1 统一出库状态枚举（outbound_status 中→英归一化，利润/筛选统一引用）
--   P1-1 核销事务内联动（settlement_events 记录每次核销/分配/撤销）
--   P1-2 结清事件化（出库结清状态可由事件流水追溯）
--   P1-4 业务单级结清聚合（business_orders 汇总应收/实收/结清状态）
--   P1-3 分配引用未解析标记（REFER_UNRESOLVED 替代事后 reconcile 兜底）
-- ====================================================================

-- --------------------------------------------------------------------
-- 1. settlement_events 结清事件流水表
--    出库结清状态的"来源凭证"：记录每笔分配/核销/撤销发生前后的金额与结清状态。
-- --------------------------------------------------------------------
CREATE TABLE settlement_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(20) NOT NULL COMMENT '事件类型：ALLOCATE=手动分配/CONFIRM=核销/REVERSE=撤销/IMPORT=导入',
    receipt_id BIGINT NULL,
    receipt_no VARCHAR(64) NULL,
    receipt_allocation_id BIGINT NULL,
    sales_outbound_id BIGINT NULL,
    outbound_no VARCHAR(64) NULL,
    business_order_id BIGINT NULL,
    amount DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本次事件涉及金额',
    outbound_received_before DECIMAL(14,2) NULL COMMENT '事件前出库已收',
    outbound_received_after DECIMAL(14,2) NULL COMMENT '事件后出库已收',
    outbound_settlement_status_before VARCHAR(20) NULL COMMENT '事件前结清状态 UNSETTLED/PARTIAL/SETTLED',
    outbound_settlement_status_after VARCHAR(20) NULL COMMENT '事件后结清状态',
    operator_name VARCHAR(100) NULL COMMENT '操作人',
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500) NULL,
    KEY idx_se_outbound (sales_outbound_id),
    KEY idx_se_receipt (receipt_id),
    KEY idx_se_business (business_order_id),
    KEY idx_se_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结清事件流水：出库结清状态的历史凭证';

-- --------------------------------------------------------------------
-- 2. sales_outbounds 增加本地归一化状态列 outbound_status
--    氚云 status 为原文透传（存在中英文混用），本地统一映射为英文枚举：
--       DRAFT/PROCESSING/CONFIRMED/CANCELLED
--    利润 SQL、结清判定、前端筛选统一引用本列。
-- --------------------------------------------------------------------
ALTER TABLE sales_outbounds
    ADD COLUMN outbound_status VARCHAR(20) NULL
        COMMENT '本地归一化状态：DRAFT/PROCESSING/CONFIRMED/CANCELLED（由氚云 status 映射）'
        AFTER status;

UPDATE sales_outbounds SET outbound_status = CASE
    WHEN status IN ('已生效','生效','已完结','完结','CONFIRMED','EFFECTIVE','COMPLETED') THEN 'CONFIRMED'
    WHEN status IN ('草稿','DRAFT') THEN 'DRAFT'
    WHEN status IN ('进行中','生产中','加工中','制作中','PROCESSING','IN_PROGRESS') THEN 'PROCESSING'
    WHEN status IN ('已取消','取消','CANCELLED','CANCELED') THEN 'CANCELLED'
    WHEN status IS NULL OR status = '' THEN 'CONFIRMED'
    ELSE 'CONFIRMED'
END;

ALTER TABLE sales_outbounds
    MODIFY COLUMN outbound_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED';

CREATE INDEX idx_so_outbound_status ON sales_outbounds (outbound_status);

-- 出库单结清状态（本地派生，由 refreshOutboundSnapshot 维护；替代中文 payment_status 三态）
ALTER TABLE sales_outbounds
    ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED'
        COMMENT '结清状态：UNSETTLED=未结清/PARTIAL=部分结清/SETTLED=已结清' AFTER payment_status;

UPDATE sales_outbounds
SET settlement_status = CASE
    WHEN COALESCE(received_amount, 0) >= sales_amount AND received_amount > 0 THEN 'SETTLED'
    WHEN COALESCE(received_amount, 0) > 0 THEN 'PARTIAL'
    ELSE 'UNSETTLED'
END;

CREATE INDEX idx_so_settlement_status ON sales_outbounds (settlement_status);

-- --------------------------------------------------------------------
-- 3. receipt_allocations 增加出库引用解析状态 reference_status
--    outbound_no_snapshot 在导入时匹配不到 sales_outbounds 的行标记为
--    REFER_UNRESOLVED，由"补齐出库关联"治理收敛，避免静默脏数据。
-- --------------------------------------------------------------------
ALTER TABLE receipt_allocations
    ADD COLUMN reference_status VARCHAR(20) NOT NULL DEFAULT 'REFER_RESOLVED'
        COMMENT '出库引用解析：REFER_RESOLVED=已关联出库 / REFER_UNRESOLVED=outbound_no_snapshot 未匹配'
        AFTER sales_outbound_id;

UPDATE receipt_allocations SET reference_status = CASE
    WHEN sales_outbound_id IS NOT NULL THEN 'REFER_RESOLVED'
    ELSE 'REFER_UNRESOLVED'
END;

CREATE INDEX idx_ra_reference_status ON receipt_allocations (reference_status);

-- --------------------------------------------------------------------
-- 4. business_orders 增加结清聚合列
--    汇总关联的 CONFIRMED 出库应收（sales_amount）与 CONFIRMED 分配实收，
--    推导结清状态。由后端 refreshBusinessOrderSettlement 维护，本脚本回填存量。
-- --------------------------------------------------------------------
ALTER TABLE business_orders
    ADD COLUMN receivable_amount DECIMAL(14,2) NOT NULL DEFAULT 0
        COMMENT '应收总额：关联 CONFIRMED 出库 sales_amount 汇总' AFTER match_status,
    ADD COLUMN received_amount DECIMAL(14,2) NOT NULL DEFAULT 0
        COMMENT '实收总额：关联 CONFIRMED 分配 allocated_amount 汇总' AFTER receivable_amount,
    ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED'
        COMMENT '结清状态：UNSETTLED=未结清/PARTIAL=部分结清/SETTLED=已结清' AFTER received_amount;

-- 步骤1：回填应收（关联 CONFIRMED 出库 sales_amount 汇总）
UPDATE business_orders bo
LEFT JOIN (
    SELECT l.business_order_id AS bo_id,
           SUM(CASE WHEN o.outbound_status='CONFIRMED' THEN o.sales_amount ELSE 0 END) AS receivable
    FROM business_order_links l
    JOIN sales_outbounds o ON o.id = l.object_id AND l.object_type='SALES_OUTBOUND'
    GROUP BY l.business_order_id
) agg1 ON agg1.bo_id = bo.id
SET bo.receivable_amount = COALESCE(agg1.receivable, 0);

-- 步骤2：回填实收（关联 CONFIRMED 分配汇总）
UPDATE business_orders bo
LEFT JOIN (
    SELECT l.business_order_id AS bo_id,
           SUM(a.allocated_amount) AS received
    FROM business_order_links l
    JOIN receipt_allocations a ON a.sales_outbound_id = l.object_id AND l.object_type='SALES_OUTBOUND'
    WHERE a.allocation_status='CONFIRMED'
    GROUP BY l.business_order_id
) agg2 ON agg2.bo_id = bo.id
SET bo.received_amount = COALESCE(agg2.received, 0);

-- 步骤3：推导结清状态
UPDATE business_orders
SET settlement_status = CASE
    WHEN receivable_amount IS NULL OR receivable_amount <= 0 THEN 'UNSETTLED'
    WHEN COALESCE(received_amount, 0) >= receivable_amount THEN 'SETTLED'
    WHEN COALESCE(received_amount, 0) > 0 THEN 'PARTIAL'
    ELSE 'UNSETTLED'
END;

CREATE INDEX idx_bo_settlement_status ON business_orders (settlement_status);
