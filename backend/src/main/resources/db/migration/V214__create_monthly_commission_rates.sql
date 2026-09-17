-- 月度提成比例配置（现行简化口径，2026-08 财务确认）
--
-- 核算维度：业务员 + 月份。
--   经销商判定：sales_outbounds.dealer_name 非空（dealer_name IS NOT NULL AND dealer_name <> ''）
--   提成金额   = Σ(本月回款 × 按经销商/零售对应比例) − 上月运杂费
--                （运杂费为绝对扣减，不乘比例；口径待财务最终确认时仅需调整 Service 公式）
-- 比例规则：
--   默认（employee_id NULL）        ：经销商 4% / 零售 4%（未单独配置的业务员）
--   李水云（employees.name）        ：全部 1%（不分经销商/零售）
--   叶宪克（图上"叶克克"）          ：经销商 1% / 零售 4%
--   邵林（employees.name）          ：经销商 1% / 零售 3%
-- 比例可能调整：财务通过「月度提成核算 → 比例配置」维护本表，保存即生效。
-- 本模块为只读核算 + 配置，不建核算表、不写业务事实数据。

CREATE TABLE IF NOT EXISTS monthly_commission_rates (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NULL COMMENT '业务员 employees.id；NULL=默认规则（未单独配置的业务员）',
    dealer_rate DECIMAL(6,4) NOT NULL COMMENT '经销商提成比例（0.0100 = 1%）',
    retail_rate DECIMAL(6,4) NOT NULL COMMENT '零售提成比例（非经销商订单）',
    remark      VARCHAR(500) NULL COMMENT '配置说明',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mcr_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月度提成比例配置（现行简化口径，财务可维护）';

-- 默认规则（幂等）：未单独配置的业务员，经销商/零售均 4%
INSERT INTO monthly_commission_rates (employee_id, dealer_rate, retail_rate, remark)
SELECT NULL, 0.0400, 0.0400, '默认规则：未单独配置的业务员经销商/零售均 4%'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM monthly_commission_rates WHERE employee_id IS NULL);

-- 李水云：不分经销商/零售，全部 1%
INSERT INTO monthly_commission_rates (employee_id, dealer_rate, retail_rate, remark)
SELECT e.id, 0.0100, 0.0100, '不分经销商/零售，全部 1%'
FROM employees e
WHERE e.name = '李水云' COLLATE utf8mb4_unicode_ci
  AND NOT EXISTS (SELECT 1 FROM monthly_commission_rates r WHERE r.employee_id = e.id);

-- 叶宪克（财务图上"叶克克"）：经销商 1%，零售 4%
INSERT INTO monthly_commission_rates (employee_id, dealer_rate, retail_rate, remark)
SELECT e.id, 0.0100, 0.0400, '经销商 1%，零售 4%'
FROM employees e
WHERE e.name = '叶宪克' COLLATE utf8mb4_unicode_ci
  AND NOT EXISTS (SELECT 1 FROM monthly_commission_rates r WHERE r.employee_id = e.id);

-- 邵林：经销商 1%，零售 3%
INSERT INTO monthly_commission_rates (employee_id, dealer_rate, retail_rate, remark)
SELECT e.id, 0.0100, 0.0300, '经销商 1%，零售 3%'
FROM employees e
WHERE e.name = '邵林' COLLATE utf8mb4_unicode_ci
  AND NOT EXISTS (SELECT 1 FROM monthly_commission_rates r WHERE r.employee_id = e.id);

-- 权限码：管理月度提成比例配置（ADMIN / BOSS / FINANCE）
INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
 ('monthly_commission.manage', '管理月度提成比例配置');

INSERT IGNORE INTO system_role_permissions
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','FINANCE') AND p.permission_code = 'monthly_commission.manage';
