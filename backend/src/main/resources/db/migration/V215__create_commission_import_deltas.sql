-- 销售出库导入引起的提成核算差异（2026-08-31 财务确认口径）
--
-- 背景：财务每次从氚云全量导出销售出库（含收款子表、运杂费）并导入，
--       历史数据可能被修正，导致收款 / 运杂费总额变化。
--       本表记录「每次导入后 较 导入前」的变化量（按业务员 + 经销商/零售拆分），
--       前端据此直接得到本期应发提成差异，无需快照版本做差。
--
-- 计算方式（CommissionDeltaService）：
--   导入事务开始前全量聚合一次（before），事务提交后再聚合一次（after），
--   Δ = after − before（收款 / 运杂费各自做差，不是提成做差）；
--   差异提成 = Δ经销回款 × 经销比例 + Δ零售回款 × 零售比例 − Δ经销运杂费 − Δ零售运杂费（运杂费绝对扣减、不乘比例）。
--
-- 幂等：同一 batch_id 只生成一次（UNIQUE uk_cid_batch_emp）；负数为数据修正引起的应提成减少（计入下期抵扣）。
-- 本表为只读核算结果，不修改任何业务事实数据。

CREATE TABLE IF NOT EXISTS commission_import_deltas (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id              BIGINT NOT NULL COMMENT '导入批次 h3_import_batches.id',
    calculated_at         DATETIME NOT NULL COMMENT '差异记录生成时点',
    period                VARCHAR(7) NOT NULL COMMENT '导入发生月 yyyy-MM（用于按月汇总）',
    employee_id           BIGINT NOT NULL COMMENT '业务员 employees.id',
    employee_name         VARCHAR(100) NOT NULL COMMENT '业务员姓名快照（导入后最新）',
    before_receipt        DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '导入前累计回款',
    after_receipt         DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '导入后累计回款',
    delta_receipt         DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '回款变化 = after − before',
    delta_dealer_receipt  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '经销商回款变化',
    delta_retail_receipt  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '零售回款变化',
    before_freight        DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '导入前累计运杂费',
    after_freight         DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '导入后累计运杂费',
    delta_freight         DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '运杂费变化 = after − before',
    delta_dealer_freight  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '经销商运杂费变化',
    delta_retail_freight  DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '零售运杂费变化',
    dealer_rate           DECIMAL(6,4) NOT NULL COMMENT '核算时点经销商比例快照',
    retail_rate           DECIMAL(6,4) NOT NULL COMMENT '核算时点零售比例快照',
    delta_commission      DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '本期差异提成（负数=修正需抵扣）',
    remark                VARCHAR(500) NULL COMMENT '来源说明（如：导入后自动核算）',
    UNIQUE KEY uk_cid_batch_emp (batch_id, employee_id),
    KEY idx_cid_calc (calculated_at),
    KEY idx_cid_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='销售出库导入引起的提成核算差异（按导入批次+业务员，2026-08 财务口径）';
