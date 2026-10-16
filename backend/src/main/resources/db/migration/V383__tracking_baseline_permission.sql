-- 生产跟单「节点参考完成基准」的可配置入口（负责人 2026-10-10 定：仅系统管理员可改天数）。
-- 基准数据本身由 V382 建表并播种中位数，本迁移只登记权限，不动任何基准值。
-- 权限码独立于 manufacturing.followup：跟单员能推节点，但不该改「一般要几天」的判据。
INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('manufacturing.baseline.manage','配置生产跟单节点参考基准天数');

INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,sort_order)
VALUES ('MANUFACTURING','UPDATE','manufacturing.baseline.manage',91);

-- 只绑系统管理员：这是「仅管理员」在本系统的表达方式（角色管理页可再调）。
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code='ADMIN' AND p.permission_code='manufacturing.baseline.manage';

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
SET g.selected_actions=CONCAT_WS(',',NULLIF(g.selected_actions,''),'manufacturing.baseline.manage')
WHERE g.feature_code='MANUFACTURING'
  AND r.role_code='ADMIN'
  AND FIND_IN_SET('manufacturing.baseline.manage',g.selected_actions)=0;
