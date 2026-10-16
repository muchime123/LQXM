-- V365: 删除费用归单/分配表
-- 背景：费用归单/分配（allocation）链路已于 2026-10-07 下线。
--       报销数据仅作台账留档，不再归集到销售出库等业务单据。
--       expense_allocation_rules / expense_allocation_runs 已由 V262 清理。

DROP TABLE IF EXISTS expense_allocations;
