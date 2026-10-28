-- 生产跟单分析周期快照：给「已过去的周期」固化一份分析结果，支持历史周报回看与趋势。
-- 背景：跟单分析的「未完工」是现状而非历史快照，交期调整后任务会在区间之间移动，
--       回看过往周期数字会变。过去周期落快照后，周复盘读快照（可审计），当前周期仍实时现算。
-- 键：period_from + period_to + workshop（NULL/'' = 全部车间）。历史周期首次固化后不再更新（INSERT 幂等，靠唯一键跳过）。

CREATE TABLE production_tracking_analytics_snapshots (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    period_from              DATE         NOT NULL COMMENT '周期起始（含）',
    period_to                DATE         NOT NULL COMMENT '周期截止（含）',
    workshop                 VARCHAR(64)  NULL     COMMENT '车间；NULL/空 = 全部车间',
    snapshot_json            JSON         NOT NULL COMMENT 'analyzeUncut 完整结果快照',
    generated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '固化时刻',
    generated_by_employee_id BIGINT       NULL     COMMENT '固化操作人（跟单场景当前为系统/前端，可空）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ptas_period_ws (period_from, period_to, workshop)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产跟单分析周期快照';
