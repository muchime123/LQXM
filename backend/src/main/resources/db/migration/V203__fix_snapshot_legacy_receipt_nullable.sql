-- 业务模型修正：销售出库「收款详细」子表 = 财务审核后的权威回款事实（2026-08-29 财务确认）。
-- 快照表从「迁出镜像」转正为「权威事实表」。
--
-- 修复：saveOutboundPaymentSnapshots（实时导入路径，migration_batch='LIVE_IMPORT'）
-- 直接以 Excel「收款详细」子表写入本表，不再经过 receipts.OUTBOUND_EMBEDDED 中转，
-- 因此 legacy_receipt_id 允许为 NULL（实时来源没有对应 receipts 行）。
-- 历史存量 113,618 条（V171 92,625 + V193 20,993）的 legacy_receipt_id 保持非空，
-- 仍可追溯原 receipts 行。
ALTER TABLE sales_outbound_payment_snapshots
    MODIFY COLUMN legacy_receipt_id BIGINT NULL
        COMMENT '追溯：原 receipts.id（V171/V193 迁移来源非空；LIVE_IMPORT 实时来源为 NULL）',
    COMMENT = '销售出库收款子表（权威回款事实）：实时导入写 LIVE_IMPORT，历史由 V171/V193 迁移';
