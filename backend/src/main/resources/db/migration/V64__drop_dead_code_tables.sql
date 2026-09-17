-- 清理完整死链表（前后端 0 调用、无 Java 代码引用）
-- 按先子表后主表顺序 DROP，避免外键约束报错

-- 报价单快照模块（完整死链，前后端全删）
DROP TABLE IF EXISTS quote_snapshot_addons;
DROP TABLE IF EXISTS quote_snapshots;

-- 产品营销辅助模块：卖点、FAQ、耗材关联、产品关联（完整死链）
DROP TABLE IF EXISTS product_consumable_relations;
DROP TABLE IF EXISTS product_relations;
DROP TABLE IF EXISTS product_faq;
DROP TABLE IF EXISTS product_selling_points;

-- 产品机器配置表（V11 创建，无 Java 代码引用）
DROP TABLE IF EXISTS product_machine_components;

-- 密码管理模块（已整体删除，3 张表）
DROP TABLE IF EXISTS password_history;
DROP TABLE IF EXISTS password_entries;
DROP TABLE IF EXISTS password_categories;

-- 产品导入 staging 临时表（设计未接入，无 Java 代码引用）
DROP TABLE IF EXISTS product_import_staging;

-- V42 遗留的备份表（无任何代码引用）
DROP TABLE IF EXISTS product_models_pricing_addons_backup;
