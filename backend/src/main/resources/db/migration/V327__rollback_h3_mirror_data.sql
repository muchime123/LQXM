-- =====================================================================
-- V327: 撤回双轨制全量镜像数据（LQ 2026-09-22 拍板：全部撤掉，回到干净状态）
--
-- 背景：V324/V323 时期把氚云台账 18,966 张销售出库、5,691 张生产工单全量
-- 镜像进了业务表。实际效果是两个页面变成「只读的脏数据复制品」，没有任何
-- 可用业务动作（销售侧过账/编辑全部被护栏锁死；生产侧 4,613 张是已完成
-- 历史单）。经确认：数据全撤，流程重新设计后再动数据。
--
-- 原则：
-- * 只清数据，不动结构——V323~V326 加的列 / 放开的 NOT NULL / 词表注释
--   全部保留（已应用、无害；将来重建流程仍可复用源关联机制）。
-- * 台账源数据（production_orders / sales_outbounds / sales_outbound_items）
--   一行不动——它们是只读台账，与业务表无关。
-- * 审计行（erp.row_change_log）按 #20/#29 纪律保留，不删——单据可撤，
--   审计记录的是「发生过什么」，撤回本身也应留痕。
-- * ERP 测试单（external_source IS NULL，1 张）不受影响。
-- =====================================================================

-- ---------- 生产域（先子后父） ----------
DELETE FROM manufacturing_work_reports
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_order_operations
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_order_outputs
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_completion_receipts
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_inspections
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_material_requirements
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_material_movements
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_issues
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM manufacturing_reworks
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');
DELETE FROM finished_goods_lots
 WHERE manufacturing_order_id IN (SELECT id FROM manufacturing_orders WHERE external_source = 'H3YUN');

DELETE FROM manufacturing_orders WHERE external_source = 'H3YUN';

-- 清掉台账侧的回写指针（指向刚删除的工单，留着是悬空引用）
UPDATE production_orders
   SET linked_manufacturing_order_id = NULL,
       linked_manufacturing_order_no = NULL
 WHERE linked_manufacturing_order_id IS NOT NULL;

-- ---------- 销售域 ----------
DELETE FROM erp_sales_outbound_items
 WHERE outbound_id IN (SELECT id FROM erp_sales_outbounds WHERE external_source = 'H3YUN');
DELETE FROM erp_sales_outbounds WHERE external_source = 'H3YUN';
