-- 异常结案权限：允许在结案时确认节点「本单不适用 / 完成但查不到日期 / 适用但尚未登记」。
--
-- 为什么要有独立权限：这三类结论在系统里都查不到依据，「适用性」和「完成日」全靠人工核实，
-- 挂在普通 manufacturing.followup 上等于谁都能把节点跳过去，节点完成率会立刻失真。
-- 「已完成且能给出日期」不需要本权限 —— 日期是操作人填的，本身有据可查。
--
-- 默认只给系统管理员：跟单主管由管理员在角色管理页按需开通（与 V383 的 baseline.manage 同款表达）。
INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('manufacturing.followup.abnormal','异常结案：确认节点不适用或已完成但无日期');

INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,sort_order)
VALUES ('MANUFACTURING','UPDATE','manufacturing.followup.abnormal',92);

INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code='ADMIN' AND p.permission_code='manufacturing.followup.abnormal';

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
SET g.selected_actions=CONCAT_WS(',',NULLIF(g.selected_actions,''),'manufacturing.followup.abnormal')
WHERE g.feature_code='MANUFACTURING'
  AND r.role_code='ADMIN'
  AND FIND_IN_SET('manufacturing.followup.abnormal',g.selected_actions)=0;
