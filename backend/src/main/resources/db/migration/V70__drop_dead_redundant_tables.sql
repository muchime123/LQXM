-- V70: 清理冗余/废弃数据库表
-- 背景：数据库中存在 4 张无代码引用、无 Entity 映射的废弃表，占用空间并增加维护复杂度。
--       本次清理基于全量代码扫描（Entity/Repository/Service/Controller/前端 API 调用）确认无任何读写路径。
-- 安全措施：DROP TABLE IF EXISTS，执行前建议备份。

-- 1) products：旧版产品单表（重构前 schema），已被 product_models + product_variants 完全替代。
--    数据结构对比：products.title → product_models.name，products.sku_image → product_assets.file_name
--    通过 import_records 追溯，所有数据已迁移至新 schema，表内 268 行均为历史遗留。
DROP TABLE IF EXISTS products;

-- 2) machine_component_template_items：V12 创建的机器部件模板表，无 Entity/Repository/Service 映射。
--    产品零部件品牌/规格字段已内联到 ProductVariant 实体（solenoid_valve_brand 等）。
--    表内 29 行种子数据已无任何消费方。
DROP TABLE IF EXISTS machine_component_template_items;

-- 3) product_models_field_dedup_backup：V39 字段去重的一次性备份表。
--    V39 迁移已成功执行（11 列 DROP + 2 价格字段清理），备份目的已达成。
--    表内 45 行备份数据自 V39 后从未被任何代码读取。
DROP TABLE IF EXISTS product_models_field_dedup_backup;

-- 4) product_variant_packaging_slots：V66 创建的 slot 设计表，V67 已将数据迁移至 product_variant_packaging_links。
--    V67 迁移脚本明确声明："The old table remains only as migration evidence. Runtime code no longer reads or writes it."
--    当前表内 0 行数据，无任何 Entity/Repository 映射。
DROP TABLE IF EXISTS product_variant_packaging_slots;
