-- 生产跟单员开放「生产产品与工期管理」（2026-10-22，试点）
--
-- 背景：生产跟单员（role_code='PRODUCTION_TRACER'）是生产产品字典与各产品节点参考工期的
-- 实际维护人，但该页用到的两个权限码此前都没给他：
--   · manufacturing.template.manage —— V224 起只授 ADMIN / BOSS / PRODUCTION_MANAGER，
--     V249 的治理步骤又把它排除在「查看/报工/异常上报」之外。它是页面/路由的门槛，
--     没有它连页面都进不去（前端 routes.ts / navigation.ts 门槛即此码）。
--   · manufacturing.baseline.manage —— V383 起只授 ADMIN。它单独把关页面右栏的
--     「产品节点参考工期」维护、样本明细、批量重算与右上角「通用参考设置」；
--     只给 template.manage 会进得去页面却用不了「工期」那半边（右栏显示无权限提示）。
-- 两者同给，页面功能才完整（产品字典 + 产品工期 + 通用参考兜底）。
--
-- 影响面（试点阶段已知并接受）：
--   1. manufacturing.template.manage 是「生产产品与工期管理」与「制程与工序管理」两页的
--      共同门槛，两者会一起开放；
--   2. 后端 ManufacturingTaskConfigController（车间 / 任务单配置）、
--      ManufacturingController（工序库 / 制程模板 / 产品规则）与
--      ProductionTrackingController 的基准相关端点均为类级/方法级权限，
--      故本次是**读写全开**（与 ADMIN 同级），不是只读；
--   3. 只授 PRODUCTION_TRACER 一个角色，不含生产跟单主管与车间报工。
--
-- ⚠️ 两处必须同步写入：system_role_feature_grants（配置层，供「角色权限」页渲染）与
-- system_role_permissions（执行层，@RequirePermission 判据）。AccessControlService.expandGrant
-- 在保存角色时会按 selected_actions 重算 role_permissions，且只认 system_feature_actions 里
-- 已归类的动作码；只改一处会被冲掉或界面显示与实际不符。
--
-- 前置检查（授予前确认权限码没被停用，否则授了也不生效）：
--   SELECT permission_code, permission_name, enabled FROM system_permissions
--    WHERE permission_code IN ('manufacturing.template.manage','manufacturing.baseline.manage');
-- 若 enabled = 0，需管理员在「权限管理」页启用后再验证（本脚本不擅自改启停状态）。
--
-- 回滚（试点终止时执行，两个权限码都撤）：
--   UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
--      SET g.selected_actions = TRIM(BOTH ',' FROM REPLACE(CONCAT(',', g.selected_actions, ','),
--                                    ',manufacturing.template.manage,', ','))
--    WHERE r.role_code = 'PRODUCTION_TRACER' AND g.feature_code = 'MANUFACTURING';
--   UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
--      SET g.selected_actions = TRIM(BOTH ',' FROM REPLACE(CONCAT(',', g.selected_actions, ','),
--                                    ',manufacturing.baseline.manage,', ','))
--    WHERE r.role_code = 'PRODUCTION_TRACER' AND g.feature_code = 'MANUFACTURING';
--   DELETE rp FROM system_role_permissions rp
--     JOIN system_roles r ON r.id = rp.role_id
--     JOIN system_permissions p ON p.id = rp.permission_id
--    WHERE r.role_code = 'PRODUCTION_TRACER'
--      AND p.permission_code IN ('manufacturing.template.manage','manufacturing.baseline.manage');

-- 1) 权限码与功能归类（V224 / V249 / V383 已建；此处仅为自建库或缺失时兜底，幂等）
INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
 ('manufacturing.template.manage','维护生产工序与制程模板'),
 ('manufacturing.baseline.manage','配置生产跟单节点参考基准天数');

INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order)
VALUES ('MANUFACTURING','EXECUTE','manufacturing.template.manage',NULL,12);

INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, sort_order)
VALUES ('MANUFACTURING','UPDATE','manufacturing.baseline.manage',91);

-- 2) 配置层：MANUFACTURING 功能勾选两个动作码
--    data_scope 沿用 V372 收敛后的 SELF（跟单可见性判据，不受本次授权影响）。
INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT r.id, 'MANUFACTURING', 'SELF', 'VIEW,manufacturing.template.manage,manufacturing.baseline.manage'
  FROM system_roles r
 WHERE r.role_code = 'PRODUCTION_TRACER'
   AND NOT EXISTS (SELECT 1 FROM system_role_feature_grants g
                    WHERE g.role_id = r.id AND g.feature_code = 'MANUFACTURING');

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
   SET g.selected_actions = CONCAT_WS(',', NULLIF(g.selected_actions, ''), 'manufacturing.template.manage')
 WHERE r.role_code = 'PRODUCTION_TRACER' AND g.feature_code = 'MANUFACTURING'
   AND FIND_IN_SET('manufacturing.template.manage', g.selected_actions) = 0;

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
   SET g.selected_actions = CONCAT_WS(',', NULLIF(g.selected_actions, ''), 'manufacturing.baseline.manage')
 WHERE r.role_code = 'PRODUCTION_TRACER' AND g.feature_code = 'MANUFACTURING'
   AND FIND_IN_SET('manufacturing.baseline.manage', g.selected_actions) = 0;

-- 3) 执行层：物化到 system_role_permissions，使 @RequirePermission 立即放行
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code = 'PRODUCTION_TRACER'
   AND p.permission_code IN ('manufacturing.template.manage', 'manufacturing.baseline.manage');
