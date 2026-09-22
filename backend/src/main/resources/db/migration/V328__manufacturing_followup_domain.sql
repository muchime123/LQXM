-- 生产跟单域（跟单工作台）：
--   1) 订单跟单快照列：跟单员修订的预计完成日期 / 延期原因 / 跟单员
--   2) 跟进日志表：跟单员每次跟进一条，只增不改（当前有效值 = 订单上的快照列）
--   3) 工序补录列：车间忘报工但实际已完成时，跟单员补录完成日期
--      （不动 status / completed_at / reported_quantity —— 不虚构报工数量、不触发下游解锁，
--       报工仍是工序完成的唯一权威来源；进度视图取 COALESCE(completed_at, manual_completed_date)）
--   4) 跟单写权限：manufacturing.followup（跟进日志 + 补录），授予跟单/生产管理/Admin/BOSS

ALTER TABLE manufacturing_orders
  ADD COLUMN expected_finish_date DATE NULL COMMENT '跟单员修订的预计完成日期（达不到原交期时更新，快照=最新一条跟进）' AFTER due_date,
  ADD COLUMN delay_reason VARCHAR(300) NULL COMMENT '延期原因（快照=最新一条跟进）' AFTER expected_finish_date,
  ADD COLUMN merchandiser_employee_id BIGINT NULL COMMENT '跟单员' AFTER delay_reason,
  ADD COLUMN merchandiser_name VARCHAR(100) NULL COMMENT '跟单员姓名快照' AFTER merchandiser_employee_id;

CREATE TABLE manufacturing_order_follow_ups (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  manufacturing_order_id BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL COMMENT '跟进内容',
  expected_finish_date DATE NULL COMMENT '本次跟进修订的预计完成日期',
  delay_reason VARCHAR(300) NULL COMMENT '本次跟进填写的延期原因',
  created_by_employee_id BIGINT NOT NULL,
  created_by_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_mofu_order (manufacturing_order_id),
  CONSTRAINT fk_mofu_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务跟进日志（跟单员每次跟进一条，只增不改）';

ALTER TABLE manufacturing_order_operations
  ADD COLUMN manual_completed_date DATE NULL COMMENT '跟单员补录的工序完成日期（未报工但实际完成；报工完成则本列为空）' AFTER completed_at,
  ADD COLUMN manual_completed_by_employee_id BIGINT NULL AFTER manual_completed_date,
  ADD COLUMN manual_completed_by_name VARCHAR(100) NULL AFTER manual_completed_by_employee_id,
  ADD COLUMN manual_completed_at DATETIME NULL COMMENT '补录动作时间' AFTER manual_completed_by_name;

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('manufacturing.followup','维护生产跟单记录（跟进日志与工序完成日期补录）');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','PRODUCTION_TRACER') AND p.permission_code='manufacturing.followup';
