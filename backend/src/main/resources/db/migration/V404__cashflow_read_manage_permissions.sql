-- A-0：资金数据独立读取/管理权限码（2026-10-23，P2-A 第一切片）
--
-- 背景：资金日记账（/api/cashflow-journal/**）此前凭 integration.h3（管理氚云数据）
-- 就能看全量财务数据，持有者仅 ADMIN/BOSS。P2-A 新增资金主体/账户管理后，财务数据
-- 必须有独立权限，不能继续挂在「氚云集成」名下：
--   · cashflow.read  —— 查看资金日记账/资金账户/期间报表（读）
--   · cashflow.manage —— 管理资金归属主体、账户认领、停用、年度期初（写）
-- 授予：read → ADMIN / BOSS / FINANCE（FINANCE 首次获得资金数据可见性，按 LQ 决定）；
--       manage → ADMIN / FINANCE。
-- BOSS 原本凭 integration.h3 可见资金日记账，授 read 保证不回退。
--
-- ⚠️ 两处必须同步写入：system_role_feature_grants（配置层，供「角色权限」页渲染）与
-- system_role_permissions（执行层，@RequirePermission 判据）。只改一处会被
-- AccessControlService.expandGrant 在下次保存角色时冲掉。
--
-- 回滚：
--   DELETE rp FROM system_role_permissions rp
--     JOIN system_permissions p ON p.id = rp.permission_id
--    WHERE p.permission_code IN ('cashflow.read','cashflow.manage');
--   UPDATE system_role_feature_grants SET selected_actions =
--     TRIM(BOTH ',' FROM REPLACE(REPLACE(CONCAT(',', selected_actions, ','), ',cashflow.read,', ','), ',cashflow.manage,', ','))
--    WHERE feature_code = 'CASHFLOW';
--   DELETE FROM system_feature_actions WHERE feature_code = 'CASHFLOW';
--   DELETE rp2 FROM system_role_permissions rp2
--     JOIN system_permissions p2 ON p2.id = rp2.permission_id
--    WHERE p2.permission_code IN ('cashflow.read','cashflow.manage');
--   DELETE FROM system_role_feature_grants WHERE feature_code = 'CASHFLOW';
--   DELETE FROM system_permissions WHERE permission_code IN ('cashflow.read','cashflow.manage');
--   DELETE FROM system_features WHERE feature_code = 'CASHFLOW';

-- 1) 功能目录
INSERT IGNORE INTO system_features(feature_code, feature_name, category, sort_order, enabled)
VALUES ('CASHFLOW', '资金日记账', 'FINANCE', 135, 1);

-- 2) 权限码
INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
 ('cashflow.read',  '查看资金日记账与资金账户'),
 ('cashflow.manage','管理资金归属主体与账户');

-- 3) 功能-动作归类
INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, sort_order)
VALUES ('CASHFLOW','VIEW','cashflow.read',1);

INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order)
VALUES ('CASHFLOW','EXECUTE','cashflow.manage',NULL,2);

-- 4) 配置层：角色授予（ADMIN/BOSS/FINANCE 给读；ADMIN/FINANCE 另给管理）
INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT r.id, 'CASHFLOW', 'ALL',
       CASE WHEN r.role_code IN ('ADMIN','FINANCE')
            THEN 'VIEW,cashflow.read,cashflow.manage'
            ELSE 'VIEW,cashflow.read' END
  FROM system_roles r
 WHERE r.role_code IN ('ADMIN','BOSS','FINANCE')
   AND NOT EXISTS (SELECT 1 FROM system_role_feature_grants g
                    WHERE g.role_id = r.id AND g.feature_code = 'CASHFLOW');

-- 已有行的角色补齐动作码（幂等）
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
   SET g.selected_actions = CONCAT_WS(',', NULLIF(g.selected_actions, ''), 'cashflow.read')
 WHERE r.role_code IN ('ADMIN','BOSS','FINANCE') AND g.feature_code = 'CASHFLOW'
   AND FIND_IN_SET('cashflow.read', g.selected_actions) = 0;

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id = g.role_id
   SET g.selected_actions = CONCAT_WS(',', NULLIF(g.selected_actions, ''), 'cashflow.manage')
 WHERE r.role_code IN ('ADMIN','FINANCE') AND g.feature_code = 'CASHFLOW'
   AND FIND_IN_SET('cashflow.manage', g.selected_actions) = 0;

-- 5) 执行层：物化，使 @RequirePermission 立即放行
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','FINANCE')
   AND p.permission_code = 'cashflow.read';

INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','FINANCE')
   AND p.permission_code = 'cashflow.manage';
