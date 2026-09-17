-- 提成规则尚未由老板和财务正式确认，现阶段所有未结算订单提成仅作试算展示。
-- 已结算历史记录保留原状，避免改写既有财务事实。
UPDATE sales_outbound_commissions
SET available_commission = 0,
    status = CASE
        WHEN employee_id IS NULL OR rule_id IS NULL THEN 'WAITING_RULE'
        WHEN commissionable_profit <= 0 THEN 'NOT_AVAILABLE'
        ELSE 'TRIAL'
    END,
    formula_version = 'V1_TRIAL'
WHERE status <> 'SETTLED';
