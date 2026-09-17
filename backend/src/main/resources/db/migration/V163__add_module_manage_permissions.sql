-- 内容与资产类模块的写操作权限（P0 权限目录完整化）
-- 设计口径：读操作保持全员开放（产品/包材/模具等为报价参考数据，公司资料为共享资源），
-- 写操作（增删改/发布/维护）按模块权限控制，权限码统一为 {module}.manage。
-- 默认授予：ADMIN（顶层管理员）、BOSS（老板）；其他角色由管理员在「角色与权限」中按需分配。

INSERT IGNORE INTO system_permissions(permission_code, permission_name) VALUES
('product.manage',      '管理产品中心'),
('packaging.manage',    '管理包材'),
('mold.manage',         '管理模具资产'),
('website.manage',      '管理官网运营'),
('company.manage',      '管理公司信息'),
('certificate.manage',  '管理知识产权'),
('officeAsset.manage',  '管理物品资产档案'),
('media.manage',        '管理自媒体账号'),
('file.manage',         '管理文件素材库'),
('system.config.manage', '管理平台配置');

-- 默认授予 ADMIN / BOSS（财务继续只管理核算域，不在此列）
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM system_roles r
JOIN system_permissions p
  ON p.permission_code IN ('product.manage','packaging.manage','mold.manage','website.manage','company.manage','certificate.manage','officeAsset.manage','media.manage','file.manage','system.config.manage')
WHERE r.role_code IN ('ADMIN', 'BOSS');
