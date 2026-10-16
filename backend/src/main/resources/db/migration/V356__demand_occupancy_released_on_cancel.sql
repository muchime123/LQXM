-- 「销售需求占坑」口径统一（2026-09-28）。
--
-- 背景：同一判据此前有 6 份副本。Java 侧 5 处都写 status<>'CANCELLED'（语义：取消即释放），
-- 而 erp_purchase_orders / manufacturing_orders 的 uk_*_sales_order_item 是「无条件 UNIQUE」
-- （语义：取消也永久占坑）。两者不同义，且失败发生在数据库层：
--   ① 采购单取消后明细重新出现在「待采购需求」，点「建采购单」时应用层放行、DB 抛
--      Duplicate entry → 用户看得见报错，却看不出「按列表口径这条明明可建」；
--   ② 生产侧判重查询连状态过滤都没有，明细建过任务单就再也回不到「待下达生产需求」。
--
-- 处置：把「占坑」升格为显式列，用**生成列**表达——状态一变成 CANCELLED，占坑自动释放，
-- 不存在「某段 Java 代码忘了置 NULL」的可能。Java 侧谓词收敛到唯一来源
-- com.liqiang.archive.util.ErpDemandOccupancy.occupies(...)，与本文件同义。
--
-- ⚠️ 外键陷阱（已在临时库实测）：uk_epo_sales_order_item / uk_mo_sales_order_item 同时被外键
--    fk_epo_sales_order_item / fk_mo_sales_order_item 依赖，直接 DROP INDEX 会报
--    errno 1553（Cannot drop index needed in a foreign key constraint）。
--    故先用同列普通索引 idx_*_sales_order_item 顶替，再删掉唯一索引。

ALTER TABLE erp_purchase_orders
  ADD KEY idx_epo_sales_order_item (sales_order_item_id),
  ADD COLUMN demand_guard BIGINT
    GENERATED ALWAYS AS (IF(status='CANCELLED', NULL, sales_order_item_id)) STORED
    COMMENT '占坑列：CANCELLED 置 NULL 即释放销售明细的唯一名额（与 ErpDemandOccupancy 同义）',
  DROP INDEX uk_epo_sales_order_item,
  ADD UNIQUE KEY uk_epo_demand_guard (demand_guard);

ALTER TABLE manufacturing_orders
  ADD KEY idx_mo_sales_order_item (sales_order_item_id),
  ADD COLUMN demand_guard BIGINT
    GENERATED ALWAYS AS (IF(status='CANCELLED', NULL, sales_order_item_id)) STORED
    COMMENT '占坑列：CANCELLED 置 NULL 即释放销售明细的唯一名额（与 ErpDemandOccupancy 同义）',
  DROP INDEX uk_mo_sales_order_item,
  ADD UNIQUE KEY uk_mo_demand_guard (demand_guard);
