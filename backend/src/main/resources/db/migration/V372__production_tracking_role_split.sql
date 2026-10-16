-- 生产跟单可见性角色拆分（2026-10-08）
--
-- 可见性唯一判据 = 角色在 MANUFACTURING 功能上的数据范围（system_role_feature_grants.data_scope）：
--   ALL  → 看全部生产跟单（生产跟单主管 / ADMIN / BOSS）
--   其余 → 只看本人名下 + 尚未分配归属的跟单（生产跟单员）
-- 执行层另按 @RequirePermission 的三档读权限（read.self/department/all，任一即可）放行接口与菜单。
--
-- 权限行要与特征授权一致：AccessControlService.expandGrant 在保存角色时会用 data_scope 重算
-- system_role_permissions（VIEW 变体 ALL→read.all / DEPARTMENT→read.department / 其余→read.self），
-- 故本脚本按同一规则物化权限行，避免日后在「角色权限」页一保存权限集就漂移。
--
-- 注：不改 system_roles.data_scope（角色全局范围）——它只作未配置功能的兜底，改动会连带影响
-- 该角色在其他功能上的可见性；此处仅收窄 MANUFACTURING 功能级范围。

-- 1) 既有「生产跟单」角色 = 生产跟单员：改名，并把 MANUFACTURING 范围由 ALL 收窄为 SELF
UPDATE system_roles SET role_name = '生产跟单员'
 WHERE role_code = 'PRODUCTION_TRACER' AND role_name = '生产跟单';

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
   SET g.data_scope = 'SELF'
 WHERE r.role_code = 'PRODUCTION_TRACER' AND g.feature_code = 'MANUFACTURING';

-- 与 SELF 展开一致：补 read.self，移除过期的 read.department / read.all
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code = 'PRODUCTION_TRACER' AND p.permission_code = 'manufacturing.read.self';

DELETE rp FROM system_role_permissions rp
  JOIN system_roles r ON r.id = rp.role_id
  JOIN system_permissions p ON p.id = rp.permission_id
 WHERE r.role_code = 'PRODUCTION_TRACER'
   AND p.permission_code IN ('manufacturing.read.department', 'manufacturing.read.all');

-- 2) 新增「生产跟单主管」角色：看全部生产跟单（成员为空，由管理员在「角色权限」页按需加人）
INSERT INTO system_roles(role_code, role_name, data_scope, built_in)
SELECT 'PRODUCTION_TRACKING_SUPERVISOR', '生产跟单主管', 'ALL', 0
 WHERE NOT EXISTS (SELECT 1 FROM system_roles WHERE role_code = 'PRODUCTION_TRACKING_SUPERVISOR');

INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT r.id, 'MANUFACTURING', 'ALL', 'VIEW,manufacturing.followup'
  FROM system_roles r
 WHERE r.role_code = 'PRODUCTION_TRACKING_SUPERVISOR'
   AND NOT EXISTS (SELECT 1 FROM system_role_feature_grants g
                    WHERE g.role_id = r.id AND g.feature_code = 'MANUFACTURING');

-- 与 ALL 展开一致：read.all + 跟单写入
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code = 'PRODUCTION_TRACKING_SUPERVISOR'
   AND p.permission_code IN ('manufacturing.read.all', 'manufacturing.followup');

-- 3) 车间报工：MANUFACTURING 范围与角色全局范围（SELF）对齐，避免非主管看到全部跟单
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
   SET g.data_scope = 'SELF'
 WHERE r.role_code = 'SHOPFLOOR_REPORTER' AND g.feature_code = 'MANUFACTURING';
