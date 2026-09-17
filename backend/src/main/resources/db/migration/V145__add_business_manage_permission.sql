INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES ('business.manage','管理业务执行与回款核销');
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r JOIN system_permissions p ON p.permission_code='business.manage'
WHERE r.role_code IN ('ADMIN','BOSS','FINANCE');
