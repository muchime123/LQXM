-- 销售出库提成核算：核算期间由「整月」升级为「任意日期范围」（2026-09-01 财务反馈）
--
-- 背景：财务并非整月整月核算，需要按自定义起止日期核算（如 7/16~8/15 账期）。
-- 核算口径推广：期间 [start,end] 内实际回款 − 上一期（[start−1月, end−1月]）运杂费，
-- 当期间恰为自然月时与旧口径（本月回款 − 上月运杂费）完全一致。
--
-- 改动：commission_calculation_versions
--   1) period 列由 yyyy-MM 改为日期范围标签（如 2026-08-01~2026-08-31，兼容旧数据展示）；
--   2) 新增 start_date / end_date 结构化日期（过滤、去重计数用）；
--   3) 版本号列放宽到 40，唯一约束改由 (start_date, end_date, version_no) 保证。
-- 旧数据 period 保持原值（如 2026-08），start_date/end_date 为 NULL，不受影响。

ALTER TABLE commission_calculation_versions
    MODIFY COLUMN period VARCHAR(30) NOT NULL COMMENT '核算期间标签（如 2026-08-01~2026-08-31，兼容旧 yyyy-MM）',
    MODIFY COLUMN version_no VARCHAR(40) NOT NULL COMMENT '版本号（同期间内递增，如 2026-08-01~2026-08-31-1）',
    ADD COLUMN start_date DATE NULL COMMENT '核算开始日期（含）' AFTER period,
    ADD COLUMN end_date DATE NULL COMMENT '核算结束日期（含）' AFTER start_date,
    DROP INDEX uk_ccv_period_no,
    ADD UNIQUE KEY uk_ccv_range_no (start_date, end_date, version_no),
    ADD KEY idx_ccv_range (start_date, end_date);
