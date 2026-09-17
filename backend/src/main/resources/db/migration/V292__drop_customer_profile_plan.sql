-- 清理已下线的「客户档案整改计划」遗留表
--
-- 背景：客户档案整改计划（地区补全计划 + 重复档案合并计划）对应的
-- CustomerProfilePlanController / CustomerProfilePlanService 已无前端入口、无自动调用，
-- 其能力已被「客户地区治理（批量地址解析 + CSV 导入导出）」与
-- SourceIdentityService 的客户身份识别/拆分工作台取代，代码已删除。
-- 四张表自建立后只被该死代码读写，随代码一并下线。
--
-- 注意：customer_merge_history / customer_source_cluster_mappings / customer_split_runs
-- 仍被 SourceIdentityService 使用，不在此次清理范围。

DROP TABLE IF EXISTS customer_merge_moved_rows;
DROP TABLE IF EXISTS customer_merge_plan_undo;
DROP TABLE IF EXISTS customer_merge_plan;
DROP TABLE IF EXISTS customer_region_fill_plan;
