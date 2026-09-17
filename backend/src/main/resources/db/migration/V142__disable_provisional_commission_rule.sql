-- 默认 10% 仅作为配置模板，未经公司确认不得参与正式提成计算。
UPDATE business_commission_rules
SET status='DISABLED', description='规则模板：请由管理员确认真实比例、回款门槛和生效日期后再启用。'
WHERE rule_name='全员默认提成规则' AND employee_id IS NULL;
