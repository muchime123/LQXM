-- 建设期全量重导可选择重新评估身份规则；执行前完整保存各类旧映射原行。
ALTER TABLE h3_reset_runs
  ADD COLUMN rule_snapshot_count BIGINT NOT NULL DEFAULT 0 AFTER outbound_nature_count;

CREATE TABLE h3_reset_rule_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reset_run_id BIGINT NOT NULL,
  source_table VARCHAR(80) NOT NULL,
  source_id BIGINT NOT NULL,
  row_json LONGTEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3_reset_rule_row(reset_run_id,source_table,source_id),
  KEY idx_h3_reset_rule_run(reset_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云重导前客户身份簇及员工产品仓库映射的完整原行快照';
