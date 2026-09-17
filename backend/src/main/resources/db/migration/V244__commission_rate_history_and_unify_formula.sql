-- 销售出库提成：比例调整历史 + 修正口径统一（2026-09-02 财务确认）
--
-- 背景：
--   1) 比例配置此前只有当前值（monthly_commission_rates 保存即生效），历史调整不可追溯。
--      「历史修正追原期间比例」要求修正变化按单据归属日期取「当时生效的比例」而非识别时点比例。
--      本次新增 monthly_commission_rate_history：每次调整（新增/修改/删除回默认）追加一条，
--      追溯查询 = 该员工 effective_date <= 归属日期 的最近一条；无专属则取默认规则行。
--   2) 统计口径统一：(回款 − 运杂费) × 比例。此前变化提成 = Δ回款 × 比例 − Δ运杂费
--      （运杂费绝对扣减、不乘比例），与期间正常提成 (回款−运杂费)×比例 不一致。
--      本次重算既有变化明细的 change_commission（按各行已存比例），此后新记录按统一公式计算。
--   3) 核算版本状态支持 VOID（作废仅限同期间最新已结账版本，作废后其并入的修正回滚待核算）。

-- ============ 1. 比例调整历史表 ============
CREATE TABLE IF NOT EXISTS monthly_commission_rate_history (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id    BIGINT NULL COMMENT '业务员 employees.id；NULL=默认规则',
    dealer_rate    DECIMAL(6,4) NOT NULL COMMENT '经销商提成比例（0.0100 = 1%）',
    retail_rate    DECIMAL(6,4) NOT NULL COMMENT '零售提成比例',
    remark         VARCHAR(500) NULL COMMENT '调整说明',
    effective_date DATE NOT NULL COMMENT '生效日期：该日（含）起按此行比例追溯',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_mcrh_emp_date (employee_id, effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月度提成比例调整历史（每次调整一条，追溯原期间比例用）';

-- 当前配置作为系统启用基线：生效日取 2026-01-01（早于全部历史单归属日期，
-- 使追溯命中当前配置；此前若从未调整过比例，历史修正即按该比例计）
INSERT INTO monthly_commission_rate_history(employee_id, dealer_rate, retail_rate, remark, effective_date)
SELECT employee_id, dealer_rate, retail_rate, COALESCE(remark, '系统启用基线'), '2026-01-01'
FROM monthly_commission_rates;

-- ============ 2. 口径统一：重算变化提成 ============
-- 新公式（与期间正常提成一致）：
--   change_commission = (Δ经销回款 − Δ经销运杂费) × 经销比例 + (Δ零售回款 − Δ零售运杂费) × 零售比例
-- 各行比例即归属期间/识别时点已存快照，按行重算即可。
UPDATE commission_outbound_changes
SET change_commission = ROUND(
      ((after_receipt_dealer  - before_receipt_dealer)  - (after_freight_dealer  - before_freight_dealer))  * dealer_rate
    + ((after_receipt_retail  - before_receipt_retail)  - (after_freight_retail  - before_freight_retail))  * retail_rate
    , 2);

-- 注意：若存在已并入核算版本（PROCESSED）的变化，其版本 items 的修正提成为旧口径快照，
-- 本次不回溯历史版本；财务可将对应最新版本作废后按新口径重新核算结账。

-- ============ 3. 核算版本状态支持作废 ============
ALTER TABLE commission_calculation_versions
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'FINAL'
    COMMENT 'FINAL=已结账 / VOID=已作废（仅最新版本可作废，其并入修正回滚待核算）';
