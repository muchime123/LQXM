-- =====================================================================
-- V386 模块「查看权限」落地（2026-10-12）
--
-- 背景：V163 的设计口径是「读操作保持全员开放（灰度放行）」，产品/包材/模具/
-- 知识产权/公司信息/自媒体/物品资产/客户档案 8 个模块只有 *.manage 写权限。
-- 本迁移按 2026-10-12 LQ 决定把口径改为「查看即权限」：
--   1) 每个模块注册 *.read 查看权限码，并在功能目录挂 VIEW 动作
--      （无范围变体，与 material_inventory.read 同型 —— expandGrant 直接授读码）；
--   2) 读接口由 @RequirePermission(xxx.read) 实拦（配合 9 个 Controller 的类级注解）；
--   3) 前端侧栏/路由按查看权限显隐，无权者不显示、直连 403。
--
-- 授权口径（LQ 2026-10-12 定）：
--   ADMIN / BOSS                → 8 个查看权限全授；
--   PRODUCTION_TRACER 生产跟单员 → 只授 6 个（产品/包材/模具/知识产权/公司/自媒体），
--                                  不含 物品资产、客户档案；
--   其余既有角色                 → 不再默认可见（原来靠灰度放行人人可读），
--                                  需要时管理员在「角色权限」页勾选；
--   已拥有 *.manage 的角色       → 自动补 VIEW（能管必然能看）。
--
-- ⚠️ 授权必须同时落 system_role_feature_grants：角色权限页每次保存会整体重建
--    system_role_permissions（AccessControlService.replaceRoleGrants），只插权限行
--    会在下次保存时被洗掉（V372 同一教训）。
-- =====================================================================

-- ---------- 1. 权限码 ----------
INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
('product.read',     '查看产品中心（含设备视频库）'),
('packaging.read',   '查看包材管理'),
('mold.read',        '查看模具资产'),
('certificate.read', '查看知识产权'),
('company.read',     '查看公司信息与资质合规'),
('media.read',       '查看自媒体账号'),
('officeAsset.read', '查看物品资产'),
('customer.read',    '查看客户档案');

-- ---------- 2. 功能目录挂 VIEW 动作（无范围变体） ----------
INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order) VALUES
('PRODUCT_CENTER','VIEW','product.read',NULL,0),
('PACKAGING','VIEW','packaging.read',NULL,0),
('MOLD','VIEW','mold.read',NULL,0),
('CERTIFICATE','VIEW','certificate.read',NULL,0),
('COMPANY','VIEW','company.read',NULL,0),
('MEDIA','VIEW','media.read',NULL,0),
('OFFICE_ASSET','VIEW','officeAsset.read',NULL,0),
('CUSTOMER','VIEW','customer.read',NULL,0);

-- ---------- 3. 授权行（grants 为准，保存时可重复展开） ----------

-- 3a) 已拥有某模块 manage 的授权行：自动补 VIEW（能管必然能看）
UPDATE system_role_feature_grants g
SET g.selected_actions = CONCAT_WS(',', 'VIEW', g.selected_actions)
WHERE FIND_IN_SET('VIEW', g.selected_actions) = 0
  AND EXISTS (SELECT 1 FROM system_permissions p
              WHERE FIND_IN_SET(p.permission_code, g.selected_actions)
                AND p.permission_code IN ('product.manage','packaging.manage','mold.manage',
                    'company.manage','certificate.manage','officeAsset.manage','media.manage','customer.manage'));

-- 3b) ADMIN / BOSS：缺授权行的功能补一行 VIEW（manage 已由历史迁移授予并物化）
INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT r.id, f.feature_code, NULL, 'VIEW'
FROM system_roles r
CROSS JOIN (
  SELECT 'PRODUCT_CENTER' AS feature_code
  UNION ALL SELECT 'PACKAGING' UNION ALL SELECT 'MOLD' UNION ALL SELECT 'CERTIFICATE'
  UNION ALL SELECT 'COMPANY' UNION ALL SELECT 'MEDIA' UNION ALL SELECT 'OFFICE_ASSET'
  UNION ALL SELECT 'CUSTOMER'
) f
WHERE r.role_code IN ('ADMIN','BOSS')
  AND NOT EXISTS (SELECT 1 FROM system_role_feature_grants g
                  WHERE g.role_id = r.id AND g.feature_code = f.feature_code);

-- 3c) 生产跟单员：6 个模块的查看授权（不含 物品资产 / 客户档案）
INSERT INTO system_role_feature_grants(role_id, feature_code, data_scope, selected_actions)
SELECT r.id, f.feature_code, NULL, 'VIEW'
FROM system_roles r
CROSS JOIN (
  SELECT 'PRODUCT_CENTER' AS feature_code
  UNION ALL SELECT 'PACKAGING' UNION ALL SELECT 'MOLD' UNION ALL SELECT 'CERTIFICATE'
  UNION ALL SELECT 'COMPANY' UNION ALL SELECT 'MEDIA'
) f
WHERE r.role_code = 'PRODUCTION_TRACER'
  AND NOT EXISTS (SELECT 1 FROM system_role_feature_grants g
                  WHERE g.role_id = r.id AND g.feature_code = f.feature_code);

-- ---------- 4. 物化 role_permissions ----------

-- 4a) 拥有 manage 的角色 → 补对应 read（与 3a 的 VIEW 展开一致）
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT rp.role_id, rd.id
FROM system_role_permissions rp
JOIN system_permissions p ON p.id = rp.permission_id
  AND p.permission_code IN ('product.manage','packaging.manage','mold.manage',
      'company.manage','certificate.manage','officeAsset.manage','media.manage','customer.manage')
JOIN system_permissions rd ON rd.permission_code = REPLACE(p.permission_code, '.manage', '.read');

-- 4b) ADMIN / BOSS：8 个查看权限全授
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code IN ('ADMIN','BOSS')
  AND p.permission_code IN ('product.read','packaging.read','mold.read','certificate.read',
      'company.read','media.read','officeAsset.read','customer.read');

-- 4c) 生产跟单员：6 个查看权限（不含 officeAsset.read / customer.read）
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code = 'PRODUCTION_TRACER'
  AND p.permission_code IN ('product.read','packaging.read','mold.read','certificate.read',
      'company.read','media.read');
