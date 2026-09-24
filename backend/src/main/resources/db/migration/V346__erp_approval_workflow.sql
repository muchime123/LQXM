-- ============================================================
-- V346__erp_approval_workflow.sql
-- ============================================================
-- 决策依据：决策登记 #35（LQ 2026-09-24 四拍板）
--   · 采购申请 = 订单确认即申请，不设独立申请单
--   · 审批基建 = 通用审批流水表（**否决**「对接钉钉审批」）
--   · 外采配置由业务员在订单里填全，随订单一起提交审批
--   · 审批人 = 老板；销售订单 / 生产任务单 / 采购订单**三单**均由 BOSS + ADMIN 审
--
-- 设计要点：
--   1) `erp_approvals` 是 **append-only 流水** —— 记录「谁在何时、依据什么意见、
--      把单子从哪个状态推到哪个状态」。各单上冗余的 `approval_status` 只是**当前态**，
--      供列表筛选与状态机判断；历史一律从流水读（驳回几次全都看得到）。
--   2) **建在 `product_archive`**（与三张单据表同库）：按 #19 纪律，幂等/审计/审批写入
--      必须与业务写入同一 DataSource + 同一事务管理器，跨 schema 会静默失效。
--      （#11 的「ERP 独立 erp schema」与 U16 尚未收口，本轮不依赖它。）
--   3) **权限码沿用既有命名空间**，不新造第二套词表（同一业务概念只允许一套词）：
--        销售订单既有前缀 `sales_order.*`（动作挂在 `SALES_OUTBOUND` 功能下）
--        制造域既有前缀 `manufacturing.*`（挂在 `MANUFACTURING`）
--        采购订单既有前缀 `purchase_order.*`（挂在 `PURCHASE_ORDER`）
--      → 审批码分别取 `sales_order.approve` / `manufacturing.approve` / `purchase_order.approve`。
--   4) **授权必须写两层**（执行层 `system_role_permissions` + 配置层 `system_role_feature_grants`）：
--      只写一层会让「角色权限页显示」与「接口实际放行」静默分叉（V337 已固化此纪律）。
--
-- 幂等：CREATE TABLE IF NOT EXISTS；权限/授权 INSERT IGNORE；列增改用**动态 SQL 先查再加**
--      （MySQL 9.7.1 实测**不支持** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` → 1064）。
--
-- 回滚（如需）：
--   DROP TABLE erp_approvals;
--   ALTER TABLE erp_sales_orders / manufacturing_orders / erp_purchase_orders
--     DROP COLUMN approval_status, approval_submitted_at, approval_submitted_by_employee_id,
--                 approval_decided_at, approval_decided_by_employee_id;
--   DELETE FROM system_feature_actions WHERE permission_code IN
--     ('sales_order.approve','manufacturing.approve','purchase_order.approve');
--   DELETE FROM system_permissions WHERE permission_code IN
--     ('sales_order.approve','manufacturing.approve','purchase_order.approve');
-- ============================================================

-- ---------- 1. 通用审批流水表 ----------
CREATE TABLE IF NOT EXISTS erp_approvals (
  id                          BIGINT       NOT NULL AUTO_INCREMENT,
  doc_type                    VARCHAR(30)  NOT NULL COMMENT '单据类型：SALES_ORDER / MFG_ORDER / PURCHASE_ORDER',
  doc_id                      BIGINT       NOT NULL COMMENT '单据主键（各单自己的 id 空间）',
  doc_no                      VARCHAR(50)  NOT NULL COMMENT '单据编号快照（XSDD* / MO* / CGDD*）',
  action                      VARCHAR(20)  NOT NULL COMMENT 'SUBMIT 提交 / APPROVE 通过 / REJECT 驳回',
  from_status                 VARCHAR(30)  NULL COMMENT '动作前的单据状态',
  to_status                   VARCHAR(30)  NULL COMMENT '动作后的单据状态',
  actor_employee_id           BIGINT       NOT NULL COMMENT '动作人',
  actor_name                  VARCHAR(100) NOT NULL COMMENT '动作人姓名快照',
  comment                     VARCHAR(500) NULL COMMENT '审批意见（驳回必填）',
  request_key                 VARCHAR(128) NULL COMMENT '请求键（幂等账对账用）',
  created_at                  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_erp_approvals_doc (doc_type, doc_id, id),
  KEY idx_erp_approvals_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批流水（append-only，三条链共用；决策 #35）';

-- ---------- 2. 三张单据表加审批列（动态 SQL，先查再加；MySQL 无 ADD COLUMN IF NOT EXISTS） ----------
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_sales_orders'
                AND COLUMN_NAME = 'approval_status');
SET @sql := IF(@has = 0,
  'ALTER TABLE erp_sales_orders
     ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT ''NONE'' COMMENT ''审批当前态：NONE/PENDING/APPROVED/REJECTED（历史见 erp_approvals）'',
     ADD COLUMN approval_submitted_at DATETIME NULL COMMENT ''提交审批时间'',
     ADD COLUMN approval_submitted_by_employee_id BIGINT NULL COMMENT ''提交审批人'',
     ADD COLUMN approval_decided_at DATETIME NULL COMMENT ''审批决定时间'',
     ADD COLUMN approval_decided_by_employee_id BIGINT NULL COMMENT ''审批决定人''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'manufacturing_orders'
                AND COLUMN_NAME = 'approval_status');
SET @sql := IF(@has = 0,
  'ALTER TABLE manufacturing_orders
     ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT ''NONE'' COMMENT ''审批当前态：NONE/PENDING/APPROVED/REJECTED（历史见 erp_approvals）'',
     ADD COLUMN approval_submitted_at DATETIME NULL COMMENT ''提交审批时间'',
     ADD COLUMN approval_submitted_by_employee_id BIGINT NULL COMMENT ''提交审批人'',
     ADD COLUMN approval_decided_at DATETIME NULL COMMENT ''审批决定时间'',
     ADD COLUMN approval_decided_by_employee_id BIGINT NULL COMMENT ''审批决定人''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_purchase_orders'
                AND COLUMN_NAME = 'approval_status');
SET @sql := IF(@has = 0,
  'ALTER TABLE erp_purchase_orders
     ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT ''NONE'' COMMENT ''审批当前态：NONE/PENDING/APPROVED/REJECTED（历史见 erp_approvals）'',
     ADD COLUMN approval_submitted_at DATETIME NULL COMMENT ''提交审批时间'',
     ADD COLUMN approval_submitted_by_employee_id BIGINT NULL COMMENT ''提交审批人'',
     ADD COLUMN approval_decided_at DATETIME NULL COMMENT ''审批决定时间'',
     ADD COLUMN approval_decided_by_employee_id BIGINT NULL COMMENT ''审批决定人''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 权限码（沿用既有命名空间） ----------
INSERT IGNORE INTO system_permissions(permission_code,permission_name,enabled) VALUES
('sales_order.approve','审批销售订单（审价 / 审交期 / 审配置是否填全）',TRUE),
('manufacturing.approve','审批生产任务单（接单可行性）',TRUE),
('purchase_order.approve','审批采购订单（供应商与采购价）',TRUE);

-- ---------- 4. 功能动作目录（决定「角色权限」页在哪个功能下渲染出这一项） ----------
INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,variant,sort_order) VALUES
('SALES_OUTBOUND','EXECUTE','sales_order.approve',NULL,95),
('MANUFACTURING','EXECUTE','manufacturing.approve',NULL,95),
('PURCHASE_ORDER','EXECUTE','purchase_order.approve',NULL,95);

-- ---------- 5. 角色授权：两层都写（ADMIN + BOSS，决策 #35「三单都由老板审」） ----------
-- 5.1 执行层：真正决定「点得动 / 接口放行」
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r JOIN system_permissions p
  ON p.enabled=TRUE AND p.permission_code IN
     ('sales_order.approve','manufacturing.approve','purchase_order.approve')
 WHERE r.role_code IN ('ADMIN','BOSS');

-- 5.2 配置层：决定「角色权限」页显示成什么（缺它会出现「页面上没勾但其实能点」）
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
   SET g.selected_actions = CONCAT(g.selected_actions,',sales_order.approve')
 WHERE r.role_code IN ('ADMIN','BOSS') AND g.feature_code='SALES_OUTBOUND'
   AND FIND_IN_SET('sales_order.approve', g.selected_actions)=0;

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
   SET g.selected_actions = CONCAT(g.selected_actions,',manufacturing.approve')
 WHERE r.role_code IN ('ADMIN','BOSS') AND g.feature_code='MANUFACTURING'
   AND FIND_IN_SET('manufacturing.approve', g.selected_actions)=0;

UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
   SET g.selected_actions = CONCAT(g.selected_actions,',purchase_order.approve')
 WHERE r.role_code IN ('ADMIN','BOSS') AND g.feature_code='PURCHASE_ORDER'
   AND FIND_IN_SET('purchase_order.approve', g.selected_actions)=0;
