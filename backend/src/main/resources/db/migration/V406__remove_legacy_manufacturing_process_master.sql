-- =====================================================================
-- 2026-10-23：移除废弃制造主数据（工序库 / 制程模板 / 产品制程推荐 + 本地 MES 执行域）
-- ---------------------------------------------------------------------
-- 背景：生产跟单已整体迁移到氚云生产任务单（production_orders），本地的
-- manufacturing_orders 及其工序快照 / 报工 / 检验均无任何代码引用（孤儿表）。
-- 本次下线「本地 MES 式制程管理」，只保留 manufacturing_workshops（车间基础数据，
-- 生产任务单配置与参考工期仍在用），并删除其制程关联列 default_process_template_id。
--
-- 原则：历史 Flyway 不回删（V224/V231/V266 等保留），只用本迁移做清理。
--
-- 依赖关系（FK 阻断单删，故整域一起下）：
--   manufacturing_inspections          → manufacturing_orders
--   manufacturing_work_reports         → manufacturing_orders / manufacturing_order_operations
--   manufacturing_order_operations     → manufacturing_orders / manufacturing_operations
--   manufacturing_process_template_rules        → manufacturing_process_templates / product_models
--   manufacturing_process_template_operations   → manufacturing_process_templates / manufacturing_operations
--   manufacturing_workshops.default_process_template_id (fk_workshop_default_template) → manufacturing_process_templates
--
-- 关闭外键检查一次性删除（这些表已确认无代码引用，属整域下线，非增量删行）。
-- =====================================================================
SET FOREIGN_KEY_CHECKS = 0;

-- 1) 车间：先显式删除外键，再删除制程关联列（MySQL 不允许直接删除被外键引用的列）
ALTER TABLE manufacturing_workshops DROP FOREIGN KEY fk_workshop_default_template;
ALTER TABLE manufacturing_workshops DROP COLUMN default_process_template_id;

-- 2) 本地 MES 执行域（按子表先删）
DROP TABLE IF EXISTS manufacturing_inspections;
DROP TABLE IF EXISTS manufacturing_work_reports;
DROP TABLE IF EXISTS manufacturing_order_operations;
DROP TABLE IF EXISTS manufacturing_orders;

-- 3) 制造主数据：产品制程推荐规则 / 制程模板工序 / 制程模板 / 工序字典
DROP TABLE IF EXISTS manufacturing_process_template_rules;
DROP TABLE IF EXISTS manufacturing_process_template_operations;
DROP TABLE IF EXISTS manufacturing_process_templates;
DROP TABLE IF EXISTS manufacturing_operations;

SET FOREIGN_KEY_CHECKS = 1;
