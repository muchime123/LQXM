-- V175: 出库回款口径收口 — 正式回款与快照不再相加
-- 新增三列：received_source / received_verified / snapshot_received_amount
-- 目标：让下游消费者能区分"已验证正式回款"和"氚云快照兜底"

ALTER TABLE sales_outbounds
    ADD COLUMN received_source VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT '回款来源：DIRECT=正式分配, H3_SNAPSHOT=快照兜底, NONE=无回款',
    ADD COLUMN received_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已验证：1=正式白名单回款, 0=快照兜底或无回款',
    ADD COLUMN snapshot_received_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT '快照回款金额（max(内嵌快照, 氚云主表)），仅用于对账展示';

-- 初始化已有数据：根据当前 received_amount > 0 标记为 H3_SNAPSHOT（未验证）
-- 后续 HistoricalOutboundRecoveryService.refreshRecoveredSnapshots 会按口径重新计算
UPDATE sales_outbounds
SET received_source = CASE WHEN received_amount > 0 THEN 'H3_SNAPSHOT' ELSE 'NONE' END,
    received_verified = 0,
    snapshot_received_amount = COALESCE(source_received_amount, 0)
WHERE received_source = 'NONE';
