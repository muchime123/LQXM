-- 客户档案写操作权限：新增/编辑/删除客户、联系人、业务员关系
-- 读操作保持全员开放（客户主档是共享资源），写操作需要 customer.manage
INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('customer.manage', '管理客户档案与合同');

-- 默认授予：ADMIN（顶层管理员）、BOSS（老板）、FINANCE（财务）
INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM system_roles r
JOIN system_permissions p ON p.permission_code = 'customer.manage'
WHERE r.role_code IN ('ADMIN', 'BOSS', 'FINANCE');
