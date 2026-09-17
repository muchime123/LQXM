-- ====================================================================
-- V150 销售出库氚云快照兜底列 + 索引/后端查询状态筛选优化
--
-- 背景：
--   1. refreshOutboundSnapshot() 基于 receipt_allocations 汇总重算 received_amount，
--      会覆盖 saveOutbound() 从氚云 main.已收款金额 写入的原始值。当出库内嵌
--      的"收款详细"子表尚未同步为 allocations 时，会导致已回款订单显示 0。
--      → 新增 source_received_amount 列保存氚云原始快照，refresh 时用
--        MAX(allocations 汇总, source_received_amount) 兜底。
--   2. 前端需要展示和筛选 status（数据状态：草稿/已生效/进行中/已取消），
--      查询默认排除草稿/进行中/已取消。补一个 status 索引方便快速过滤。
-- ====================================================================

ALTER TABLE sales_outbounds
    ADD COLUMN source_received_amount DECIMAL(18,2) NULL COMMENT '氚云导出 main.已收款金额 原始快照；refreshOutboundSnapshot 的兜底来源'
        AFTER received_amount;

-- 把当前 received_amount / outstanding_amount 的旧值回灌到 source_*
-- （对于 batch 19/20 H3YUN 导入后还没被 refresh 覆盖过的行，received_amount 本身就是氚云快照）
UPDATE sales_outbounds
SET source_received_amount = CASE
    WHEN source_received_amount IS NULL THEN received_amount
    ELSE source_received_amount
END
WHERE external_source IN ('H3YUN', 'H3YUN_RECOVERED');

CREATE INDEX idx_sales_outbounds_status ON sales_outbounds(status);
CREATE INDEX idx_sales_outbounds_external ON sales_outbounds(external_source, external_id);

-- 同样给 receipts 的 external_source 区分 H3YUN 独立收款单和出库内嵌补回的收款单：
-- 加索引避免 backfill 时查询 receipts 主键变慢
CREATE INDEX idx_receipts_external ON receipts(external_source, external_id);
