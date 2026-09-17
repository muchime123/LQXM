-- 月度提成正式版本冻结氚云销售出库考核快照。
-- 未收款将作为提成考核核心指标，因此历史版本必须保留结账时的销售额、已收、未收和回款率。
ALTER TABLE commission_calculation_items
    ADD COLUMN sales_amount_snapshot DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '核算期间氚云销售出库销售金额快照',
    ADD COLUMN received_amount_snapshot DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '核算期间氚云销售出库已收款金额快照',
    ADD COLUMN outstanding_amount_snapshot DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '核算期间氚云销售出库未收款金额快照',
    ADD COLUMN collection_rate_snapshot DECIMAL(8,6) NOT NULL DEFAULT 1 COMMENT '已收/(已收+未收)快照';
