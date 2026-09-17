-- 提成结算版本扩展 + 渠道来源 + 比例精度（2026-09-04 P1/P2 落地，与方案文档配套）
--
-- 变更点：
--   1) commission_calculation_versions 增加结算类型 / 运费窗口快照 / 作废原因 / 发放状态；
--   2) commission_calculation_source_lines 增加 channel_source（渠道判定来源），ADJUSTMENT 行为空；
--   3) 比例列统一 DECIMAL(8,6)（monthly_commission_rates、rate_history、版本 items、变化明细），
--      设计口径：比例 6 位小数，如 0.000100 = 0.01%。

-- ============ 1. 核算版本：结算类型 / 运费窗口 / 作废 / 发放 ============
-- 新列统一追加在表尾（不使用 AFTER 引用同语句新增列，兼容不同 MySQL 版本）。
ALTER TABLE commission_calculation_versions
    ADD COLUMN settlement_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'NORMAL=正常核算 / SUPPLEMENT=补充核算 / RECALCULATION=作废后重新核算',
    ADD COLUMN freight_start_date DATE NULL COMMENT '本次实际采用的运费窗口开始日期（含）',
    ADD COLUMN freight_end_date DATE NULL COMMENT '本次实际采用的运费窗口结束日期（含）',
    ADD COLUMN freight_period_mode VARCHAR(30) NOT NULL DEFAULT 'PREVIOUS_CALENDAR_MONTH'
        COMMENT 'PREVIOUS_CALENDAR_MONTH=结束日所在月上一完整自然月 / PREVIOUS_EQUAL_PERIOD=前一等长周期 / CUSTOM=自定义',
    ADD COLUMN void_reason VARCHAR(500) NULL COMMENT '作废原因（必填）',
    ADD COLUMN void_by BIGINT NULL COMMENT '作废人 employees.id',
    ADD COLUMN void_at DATETIME NULL COMMENT '作废时间',
    ADD COLUMN paid_flag VARCHAR(20) NOT NULL DEFAULT 'UNPAID'
        COMMENT 'UNPAID=未发放 / PAID=已发放（已发放不可作废）',
    ADD COLUMN paid_by BIGINT NULL COMMENT '标记发放的操作人 employees.id',
    ADD COLUMN paid_at DATETIME NULL COMMENT '标记发放时间';

-- 存量 FINAL 版本回填运费窗口：当时固定口径 = 结束日所在月的上一完整自然月
UPDATE commission_calculation_versions v
   SET v.freight_period_mode='PREVIOUS_CALENDAR_MONTH',
       v.freight_start_date=DATE_FORMAT(v.end_date - INTERVAL 1 MONTH, '%Y-%m-01'),
       v.freight_end_date = DATE_SUB(DATE_FORMAT(v.end_date, '%Y-%m-01'), INTERVAL 1 DAY)
 WHERE v.start_date IS NOT NULL AND v.end_date IS NOT NULL;

-- ============ 2. 来源明细：渠道判定来源 ============
-- DEALER_FIELD=出库单填了经销商 / MARGIN_FALLBACK=毛利率兜底 / DEFAULT_RETAIL=默认终端
-- ADJUSTMENT（调整）行不适用渠道判定，该列为空。
ALTER TABLE commission_calculation_source_lines
    ADD COLUMN channel_source VARCHAR(30) NULL
        COMMENT 'DEALER_FIELD=填了经销商 / MARGIN_FALLBACK=毛利兜底 / DEFAULT_RETAIL=默认终端；ADJUSTMENT 行为 NULL' AFTER channel_type;

UPDATE commission_calculation_source_lines l
JOIN sales_outbounds o ON o.id = l.outbound_id
   SET l.channel_source = CASE
        WHEN o.dealer_name IS NOT NULL AND o.dealer_name<>'' THEN 'DEALER_FIELD'
        WHEN o.sales_amount IS NOT NULL AND o.sales_amount>0
             AND COALESCE(o.source_gross_profit,0)/o.sales_amount < 0.15 THEN 'MARGIN_FALLBACK'
        ELSE 'DEFAULT_RETAIL' END
 WHERE l.line_type IN ('RECEIPT','FREIGHT');

-- ============ 3. 比例精度统一 DECIMAL(8,6) ============
ALTER TABLE monthly_commission_rates
    MODIFY COLUMN dealer_rate DECIMAL(8,6) NOT NULL COMMENT '经销商提成比例（0.010000 = 1%）',
    MODIFY COLUMN retail_rate DECIMAL(8,6) NOT NULL COMMENT '零售提成比例';

ALTER TABLE monthly_commission_rate_history
    MODIFY COLUMN dealer_rate DECIMAL(8,6) NOT NULL COMMENT '经销商提成比例（0.010000 = 1%）',
    MODIFY COLUMN retail_rate DECIMAL(8,6) NOT NULL COMMENT '零售提成比例';

ALTER TABLE commission_calculation_items
    MODIFY COLUMN dealer_rate DECIMAL(8,6) NOT NULL COMMENT '核算时点经销商比例快照',
    MODIFY COLUMN retail_rate DECIMAL(8,6) NOT NULL COMMENT '核算时点零售比例快照';

ALTER TABLE commission_outbound_changes
    MODIFY COLUMN dealer_rate DECIMAL(8,6) NOT NULL COMMENT '变化识别/归属时点经销商比例',
    MODIFY COLUMN retail_rate DECIMAL(8,6) NOT NULL COMMENT '变化识别/归属时点零售比例';
