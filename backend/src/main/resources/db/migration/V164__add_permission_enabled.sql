-- 权限码启停用：disabled 的权限码即时从所有用户权限中移除（可再启用恢复）。
-- 语义：停用=临时下线该权限（用户立即失去），启用=恢复。停用不删除分配记录。
ALTER TABLE system_permissions ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
