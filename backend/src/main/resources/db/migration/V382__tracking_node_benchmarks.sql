-- 节点参考完成基准：每个车间类别 × 节点的「一般情况耗时」（天）。
-- 数据来源：production_tracking_milestones 真实完成日期的中位数（2026-10-10 试算，封口机 9 月起 392 单）：
--   开工→备料 0 天(n=392) / 备料→模具 3 天(n=381) / 模具→组装 2 天(n=352) / 组装→入库 0 天(n=352)。
-- 机器车间（画图/钣金/调试等）暂无跟单数据，不播种 ⇒ 参考值为空，等有数据再补。
-- 参考完成时间是派生值，不落工单表：详情接口按「上游锚点 + typical_days」链式推算。
CREATE TABLE IF NOT EXISTS production_tracking_node_benchmarks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workshop_kind VARCHAR(20) NOT NULL COMMENT 'SEALER=封口机 / MACHINE=气调机等机器车间',
    stage_code VARCHAR(30) NOT NULL,
    typical_days DECIMAL(5,1) NOT NULL COMMENT '该节点一般耗时（天）＝上游完成→本节点完成的中位间隔',
    sample_count INT NOT NULL DEFAULT 0 COMMENT '推算时的样本行数',
    baseline_note VARCHAR(100) NOT NULL COMMENT '推算方式说明',
    computed_at DATE NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_benchmark_kind_stage (workshop_kind, stage_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单节点参考完成基准';

INSERT INTO production_tracking_node_benchmarks(workshop_kind,stage_code,typical_days,sample_count,baseline_note,computed_at) VALUES
    ('SEALER','MATERIAL',0.0,392,'中位数：计划开工→备料完成（2026-10-10 试算，2026-09 起封口机跟单数据）','2026-10-10'),
    ('SEALER','MOLD',3.0,381,'中位数：备料完成→模具生产完成','2026-10-10'),
    ('SEALER','ASSEMBLY',2.0,352,'中位数：模具生产完成→组装完成（按需工序氧化不占链）','2026-10-10'),
    ('SEALER','WAREHOUSE',0.0,352,'中位数：组装完成→入库完成','2026-10-10')
ON DUPLICATE KEY UPDATE typical_days=VALUES(typical_days),sample_count=VALUES(sample_count),
    baseline_note=VALUES(baseline_note),computed_at=VALUES(computed_at);
