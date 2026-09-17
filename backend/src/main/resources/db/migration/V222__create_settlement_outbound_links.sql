-- 多订单结清「出库单明细」关联表（2026-09-01 财务确认业务模型）
--
-- 业务形态：多订单结清 = 客户一笔多付的钱，按订单时间顺序滚动结算（FIFO），
-- 多付部分自动结清下一张订单，不是按销售额比例拆分，也不是记在某一张单上。
-- 因此多订单结清主单（receipts MULTI_OUTBOUND）必须保留「覆盖哪些出库单」的明细，
-- 才能支撑台账展开核对、以及后续「结清总款 vs 业务员登记」的交叉校验。
--
-- 仅作只读参照，不参与正式回款核销（与 receipt_allocations 的 SETTLEMENT_SNAPSHOT 语义区分开：
-- 本表保留导入原始明细，不写 allocation，避免重新污染正式核销口径）。

CREATE TABLE settlement_outbound_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'PK',
    receipt_id BIGINT NOT NULL COMMENT '多订单结清主单ID（FK -> receipts.id，receipt_type=MULTI_OUTBOUND）',
    sequence_no INT NOT NULL DEFAULT 1 COMMENT '明细顺序（按导入行序）',
    outbound_no VARCHAR(50) NOT NULL COMMENT '出库单明细.销售出库编号（导入原始值，规整后）',
    outbound_id BIGINT NULL COMMENT '匹配到的销售出库ID（FK -> sales_outbounds.id，可能匹配不到）',
    settled_amount DECIMAL(14,2) NULL COMMENT '明细结清金额（出库单明细.已收款金额，若有）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    UNIQUE KEY uk_sol_receipt_outbound (receipt_id, outbound_no) COMMENT '同一结清主单内出库单去重',
    KEY idx_sol_outbound_no (outbound_no) COMMENT '按出库单号反查覆盖它的结清主单',
    KEY idx_sol_receipt (receipt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多订单结清主单↔出库单明细关联（滚动核销参照，不参与正式回款）';
