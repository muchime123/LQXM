-- 月度提成核算（现行简化口径）权限码注册
--
-- 核算口径（2026-08 财务确认，与 V204 权威回款公式一致）：
--   本月实际回款 = SUM(sales_outbound_payment_snapshots.received_amount)
--                 WHERE receipt_date 属于本月 AND legacy_status='CONFIRMED'
--   上月运杂费   = SUM(sales_outbounds.freight)
--                 WHERE COALESCE(ship_date,business_date) 属于上月
--   提成核算基数 = 本月实际回款 - 上月运杂费
--
-- 本模块为只读核算视图，不建核算表、不写业务数据；旧订单提成体系（V205/V206）不受影响。

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('monthly_commission.read.self','查看本人月度提成核算'),
 ('monthly_commission.read.department','查看本部门月度提成核算'),
 ('monthly_commission.read.all','查看全部月度提成核算');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS')
   AND p.permission_code IN ('monthly_commission.read.self','monthly_commission.read.department','monthly_commission.read.all');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
 ON p.permission_code='monthly_commission.read.department'
 WHERE r.role_code='SALES_MANAGER';

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
 ON p.permission_code='monthly_commission.read.self'
 WHERE r.role_code='SALESPERSON';

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
 ON p.permission_code='monthly_commission.read.all'
 WHERE r.role_code='FINANCE';
