-- 通用参考（车间级兜底）恢复封口机 4 个节点（2026-10-22）。
--
-- 背景：V382 播种过这 4 行，但 production_tracking_node_benchmarks 现在是 0 行——
-- 「通用参考设置」是全量提交、清空即删行，一次空值保存就把整表清掉了。
-- 后果不只是通用参考没了：50 个产品里 43 个没有任何产品级发布值，两级都空 ⇒
-- 这些产品的节点参考日期与「生产工期」全部推不出来。
--
-- 数值来源：docs/跟单节点间隔实测-20261021.md §1（样本＝593 条跟单记录，
-- 锚点口径为氚云排产时间起算）。与 V382 播的值一致，sample_count 换成新口径实测段数。
--
-- ⚠️ 已知取舍（沿用 V382 判断，未改）：备料与入库 P50＝0 且零天占比约 90%，
-- 发布 0 天等于「排产当天必须备完 / 组装当天必须入库」，没有缓冲。
-- 留 0 是让兜底值与车间真实节奏一致；需要缓冲时应在产品级单独发布，不在此处加安全系数。
-- 机器车间（画图/钣金/调试）样本不足，不播种，未配置即不推算。
INSERT INTO production_tracking_node_benchmarks(workshop_kind,stage_code,typical_days,sample_count,baseline_note,computed_at) VALUES
    ('SEALER','MATERIAL',0.0,313,'中位数：排产时间→备料完成（2026-10-21 新口径实测，零天占 90%）','2026-10-22'),
    ('SEALER','MOLD',3.0,507,'中位数：备料完成→模具生产完成（2026-10-21 新口径实测）','2026-10-22'),
    ('SEALER','ASSEMBLY',2.0,460,'中位数：模具生产完成→组装完成（2026-10-21 新口径实测，按需工序氧化不占链）','2026-10-22'),
    ('SEALER','WAREHOUSE',0.0,472,'中位数：组装完成→入库完成（2026-10-21 新口径实测，零天占 90%）','2026-10-22')
ON DUPLICATE KEY UPDATE typical_days=VALUES(typical_days),sample_count=VALUES(sample_count),
    baseline_note=VALUES(baseline_note),computed_at=VALUES(computed_at);
