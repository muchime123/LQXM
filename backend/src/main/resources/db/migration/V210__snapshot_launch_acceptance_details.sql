ALTER TABLE order_launch_acceptance_snapshots
    ADD COLUMN acceptance_snapshot_json JSON NULL
    COMMENT '确认时已通过订单验收明细不可变快照' AFTER gate_snapshot_json;
