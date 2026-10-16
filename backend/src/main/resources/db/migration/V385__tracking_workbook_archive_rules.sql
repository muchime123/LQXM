-- 「表格待处理」噪声治理：车间进度表里有一部分行本来就对不上氚云工单
-- （典型：无订单号的「模具」「LQ750全自动片膜多模具」「配件」等非成品行）。
-- 这类行每次导入都重新变成待匹配，主管只能反复手工跳过。
-- 归档规则按「归一化后的表格产品名」记住这类行：归档后当前行移出待处理，
-- 后续导入的同名行在 plan 阶段直接判定为 ARCHIVED，不再进入待处理队列。
-- 软删（revoked_at）保留审计轨迹，撤销规则后可重新计入待处理。
CREATE TABLE production_tracking_workbook_archive_rules (
  id BIGINT NOT NULL AUTO_INCREMENT,
  product_key VARCHAR(255) NOT NULL COMMENT '归一化后的表格产品名（去空白/标点/大小写/「车间」字样）',
  product_label VARCHAR(300) NOT NULL COMMENT '归档时的原始产品名，仅用于展示',
  reason VARCHAR(200) NOT NULL,
  created_by_employee_id BIGINT NOT NULL,
  created_by_name VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at DATETIME NULL,
  revoked_by_employee_id BIGINT NULL,
  PRIMARY KEY (id),
  KEY idx_ptwar_active (product_key,revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表格待处理行的归档规则（按表格产品名）';
