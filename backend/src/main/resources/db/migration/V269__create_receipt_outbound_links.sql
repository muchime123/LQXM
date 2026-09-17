-- 单订单收款「收款记录」关联销售出库表（2026-09-07 台账核对补全）
--
-- 业务形态：氚云「收款记录」主单（receipts receipt_type=SINGLE_OUTBOUND）本身通过
-- 「选择销售出库单」关联一张销售出库；氚云导出 Excel 时以收款记录主表子行展开，
-- 每张收款单每行携带「销售出库编号」「销售订单日期」。
--
-- V222 曾为「多订单结清」（MULTI_OUTBOUND）保存 settlement_outbound_links；
-- 但「收款记录」导入时只保存了 receipts 主单，其关联的销售出库信息被丢弃，
-- 导致收款台账/详情看不到「这笔收款对应哪张销售出库」。
-- 本表仿 settlement_outbound_links 保留导入原始关联，仅作台账只读参照，
-- 不写 receipt_allocations、不参与正式回款核销口径。

CREATE TABLE receipt_outbound_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'PK',
    receipt_id BIGINT NOT NULL COMMENT '收款记录主单ID（FK -> receipts.id，receipt_type=SINGLE_OUTBOUND）',
    sequence_no INT NOT NULL DEFAULT 1 COMMENT '明细顺序（按导入行序）',
    outbound_no VARCHAR(50) NOT NULL COMMENT '关联销售出库编号（销售出库编号列，规整后）',
    outbound_id BIGINT NULL COMMENT '匹配到的销售出库ID（FK -> sales_outbounds.id，可能匹配不到）',
    sales_order_date DATE NULL COMMENT '销售订单日期（导入原始值，若有）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    UNIQUE KEY uk_rol_receipt_outbound (receipt_id, outbound_no) COMMENT '同一收款记录内出库单去重',
    KEY idx_rol_outbound_no (outbound_no) COMMENT '按出库单号反查覆盖它的收款记录',
    KEY idx_rol_receipt (receipt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款记录主单↔销售出库关联（只读参照，不参与正式回款）';
