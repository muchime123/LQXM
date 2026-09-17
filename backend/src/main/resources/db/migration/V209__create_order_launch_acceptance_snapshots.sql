CREATE TABLE order_launch_acceptance_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    gate_snapshot_json JSON NOT NULL COMMENT '确认时七项上线门槛不可变快照',
    passed_gate_count INT NOT NULL,
    total_gate_count INT NOT NULL,
    acceptance_passed_count INT NOT NULL,
    confirmation_note VARCHAR(500) NOT NULL,
    confirmed_by_employee_id BIGINT NULL,
    confirmed_by_name VARCHAR(100) NOT NULL,
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_olas_confirmed_at (confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单利润正式上线验收快照';
