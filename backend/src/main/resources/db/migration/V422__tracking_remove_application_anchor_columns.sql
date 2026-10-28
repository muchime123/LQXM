-- 锚点口径收敛 A2（2026-10-28）：候选/样本锚点只认氚云排产时间。
-- V392 加的"申请日兜底"独立分布从未命中（锚点只读 schedule_time，planned_start_date/申请日均无样本），
-- 是死数据列。删除 application_anchor_count / application_p50_days / application_p75_days 三列。
ALTER TABLE production_tracking_product_benchmarks
    DROP COLUMN application_anchor_count,
    DROP COLUMN application_p50_days,
    DROP COLUMN application_p75_days;
