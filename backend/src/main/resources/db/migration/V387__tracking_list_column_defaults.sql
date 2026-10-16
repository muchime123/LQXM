-- 生产跟单列表「默认可见列」的全站配置（负责人 2026-10-12 定：由管理员统一配置一套，员工仍可自行勾选覆盖）。
-- 背景：在此之前「默认隐藏哪几列」是前端写死的常量（DEFAULT_HIDDEN_LIST_COLUMNS），
--   改默认值要发版；本表把它变成可配置数据。
-- 语义：column_key 在表里 = 管理员已明确决定过该列；不在表里 = 未配置，回落前端代码默认。
--   ⚠️ 保存时后端写全量（每列一行），所以「全部显示」＝15 行 visible=1，不会与「未配置」混淆。
-- 列 key 白名单由后端 ProductionTrackingService.LIST_COLUMN_KEYS 维护，且与前端 LIST_COLUMN_DEFS 由契约测试钉死。
CREATE TABLE IF NOT EXISTS production_tracking_column_defaults (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    column_key VARCHAR(64) NOT NULL COMMENT '列标识，与前端 LIST_COLUMN_DEFS 的 key 同源',
    visible TINYINT(1) NOT NULL COMMENT '1=默认显示 / 0=默认隐藏',
    updated_by BIGINT NULL COMMENT '最后修改的员工 id',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tracking_column_key (column_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单列表默认可见列（全站，仅管理员可改）';

-- 播种＝改造前的前端写死值（6 个默认隐藏列），保证升级后界面与改前完全一致。
-- 其余 9 列不播种：未配置即回落代码默认（可见），以后新增列也天然可见。
INSERT INTO production_tracking_column_defaults(column_key,visible) VALUES
    ('workshop',0),('trackingStatus',0),('trackingAction',0),('dueDate',0),('salesTrace',0),('assignee',0)
ON DUPLICATE KEY UPDATE column_key=VALUES(column_key);

-- 权限：只登记「配置默认列」这一个动作。读矩阵沿用页面读权限（manufacturing.read.*），全员可读；
-- 写为独立权限码——看得到列表 ≠ 有权决定全公司看到哪些列。
INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('manufacturing.listcolumns.manage','配置生产跟单列表默认显示列');

INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,sort_order)
VALUES ('MANUFACTURING','UPDATE','manufacturing.listcolumns.manage',92);

-- 只绑系统管理员：这是「仅管理员」在本系统的表达方式（角色管理页可再调）。
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code='ADMIN' AND p.permission_code='manufacturing.listcolumns.manage';

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
SET g.selected_actions=CONCAT_WS(',',NULLIF(g.selected_actions,''),'manufacturing.listcolumns.manage')
WHERE g.feature_code='MANUFACTURING'
  AND r.role_code='ADMIN'
  AND FIND_IN_SET('manufacturing.listcolumns.manage',g.selected_actions)=0;
