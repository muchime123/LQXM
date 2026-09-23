-- 来源工单只读；本地跟单不依赖正式制造工单、SKU 或报工。
-- 外部标识而非工单号/子表行 ID 保证重导不覆盖本地记录。
CREATE TABLE IF NOT EXISTS production_followup_records (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 external_source VARCHAR(32) NOT NULL,
 external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 state_json JSON NOT NULL,
 original_required_date DATE NULL COMMENT '首次跟单时的原交期，重导不覆盖',
 completed_date DATE NULL,
 expected_finish_date DATE NULL,
 updated_by_name VARCHAR(100) NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_pfr_source (external_source,external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产工单本地跟单快照，来源重新导入不覆盖';

CREATE TABLE IF NOT EXISTS production_followup_logs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 record_id BIGINT NOT NULL,
 version BIGINT NOT NULL,
 content VARCHAR(1000) NOT NULL,
 before_json JSON NOT NULL,
 after_json JSON NOT NULL,
 created_by_employee_id BIGINT NOT NULL,
 created_by_name VARCHAR(100) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_pfl_version (record_id,version),
 CONSTRAINT fk_pfl_record FOREIGN KEY(record_id) REFERENCES production_followup_records(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟进及修订历史，只增不改';

INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,sort_order)
VALUES ('MANUFACTURING','UPDATE','manufacturing.followup',90);
-- 配置层和执行层保持一致；保留已有数据范围，不扩张可见范围。
-- selected_actions 是逗号分隔字符串（非 JSON），用 FIND_IN_SET/CONCAT 操作
UPDATE system_role_feature_grants g
JOIN system_roles r ON r.id=g.role_id
SET g.selected_actions=CONCAT(g.selected_actions, ',manufacturing.followup')
WHERE g.feature_code='MANUFACTURING'
 AND r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','PRODUCTION_TRACER')
 AND FIND_IN_SET('manufacturing.followup', g.selected_actions) = 0;
