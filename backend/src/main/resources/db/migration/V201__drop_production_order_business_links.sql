-- 生产任务不再生成业务单 / 不再与销售出库关联
--
-- 背景：
--   1. 生产任务工单是从氚云导出的历史执行事实，生产处理逻辑（标记生产完成、入库等）
--      全部由车间人员在氚云内操作，本地不回写、也无回写通道。
--   2. 历史生产任务与销售出库强行逐单关联没有实际收益，反而制造大量人工待办与误匹配。
--   3. 业务目标是看出库、回款、利润与提成，生产过程不作为这些处理的前置关系。
--
-- 因此：
--   生产工单仅作为独立事实存在于 /production-board 只读查看，不再生成业务单、不进业务中心。

-- 1. 移除所有「生产任务 → 业务单」的关联
DELETE FROM business_order_links WHERE object_type = 'PRODUCTION_ORDER';

-- 2. 关联被移除后，不再挂载任何事实对象的业务单软标记为 ORPHAN。
--    business_orders 被业务核算子表外键引用，不能物理删除，改为软标记由查询层过滤。
UPDATE business_orders bo
LEFT JOIN business_order_links l ON l.business_order_id = bo.id
SET bo.match_status = 'ORPHAN'
WHERE l.id IS NULL
  AND bo.match_status <> 'ORPHAN';

-- 3. 清理已废弃的「生产任务 ↔ 销售出库」匹配候选。
--    确认过的候选此前已落成 business_order_links，此处删候选不影响既有关联。
DELETE FROM business_match_candidates;
