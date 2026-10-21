-- 首节点申请日兜底不再混入正式节点间隔候选；保留其独立分布供核实。
ALTER TABLE production_tracking_product_benchmarks
    ADD COLUMN planned_anchor_count INT NOT NULL DEFAULT 0 COMMENT '首节点：计划开工锚点有效样本',
    ADD COLUMN application_anchor_count INT NOT NULL DEFAULT 0 COMMENT '首节点：申请日兜底样本，不进入正式 P50/P75',
    ADD COLUMN application_p50_days DECIMAL(5,1) NULL,
    ADD COLUMN application_p75_days DECIMAL(5,1) NULL,
    ADD COLUMN zero_day_count INT NOT NULL DEFAULT 0 COMMENT '有效样本中零天间隔数量';
