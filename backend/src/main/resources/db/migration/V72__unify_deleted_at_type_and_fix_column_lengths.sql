-- V72: 统一 deleted_at 数据类型为 datetime(6) + 修跨表同名字段列长度不一致
--
-- 背景：
--   1) 17 张软删除表的 deleted_at 列混用 3 种类型：
--      - datetime(6): 7 张（product_models/variants/assets, documents, *_archive）— 已是目标类型
--      - datetime:    9 张（packaging_*, company_assets, mold_*, office_assets）— 需升级
--      - timestamp:   1 张（certificates）— 需改为 datetime(6)（消除 2038 年问题和时区转换开销）
--   2) product_models 与 product_variants 的 3 个同名继承字段列长度不一致，
--      Variant 写入较长值后回写 Model 基线时会被截断：
--      - air_pressure_requirement: Model(100) vs Variant(150)
--      - gas_replacement_rate:     Model(100) vs Variant(120)
--      - customs_code:             Model(50)  vs Variant(100)
--
-- 安全性说明：
--   - datetime → datetime(6) 只是增加微秒精度，现有值补零，不丢数据
--   - timestamp → datetime(6) 在同一 MySQL 实例内转换，时间值保持一致
--     （certificates.deleted_at 全部为 NULL，无实际值转换风险）
--   - varchar 扩大长度是 INPLACE 操作，不锁表

-- ============================================================
-- 第一部分：统一 deleted_at 为 datetime(6)
-- ============================================================

-- 1. certificates: timestamp → datetime(6)
ALTER TABLE certificates MODIFY COLUMN deleted_at datetime(6) NULL;

-- 2. company_assets: datetime → datetime(6)
ALTER TABLE company_assets MODIFY COLUMN deleted_at datetime(6) NULL;

-- 3. mold_accessories: datetime → datetime(6)
ALTER TABLE mold_accessories MODIFY COLUMN deleted_at datetime(6) NULL;

-- 4. mold_variant_links: datetime → datetime(6)
ALTER TABLE mold_variant_links MODIFY COLUMN deleted_at datetime(6) NULL;

-- 5. office_assets: datetime → datetime(6)
ALTER TABLE office_assets MODIFY COLUMN deleted_at datetime(6) NULL;

-- 6. packaging_assets: datetime → datetime(6)
ALTER TABLE packaging_assets MODIFY COLUMN deleted_at datetime(6) NULL;

-- 7. packaging_models: datetime → datetime(6)
ALTER TABLE packaging_models MODIFY COLUMN deleted_at datetime(6) NULL;

-- 8. packaging_mold_groups: datetime → datetime(6)
ALTER TABLE packaging_mold_groups MODIFY COLUMN deleted_at datetime(6) NULL;

-- 9. packaging_molds: datetime → datetime(6)
ALTER TABLE packaging_molds MODIFY COLUMN deleted_at datetime(6) NULL;

-- 10. packaging_variants: datetime → datetime(6)
ALTER TABLE packaging_variants MODIFY COLUMN deleted_at datetime(6) NULL;

-- ============================================================
-- 第二部分：统一 product_models 跨表同名继承字段列长度
-- 扩大到与 product_variants 一致，防止回写截断
-- ============================================================

-- 11. air_pressure_requirement: varchar(100) → varchar(150)
ALTER TABLE product_models MODIFY COLUMN air_pressure_requirement varchar(150) NULL;

-- 12. gas_replacement_rate: varchar(100) → varchar(120)
ALTER TABLE product_models MODIFY COLUMN gas_replacement_rate varchar(120) NULL;

-- 13. customs_code: varchar(50) → varchar(100)
ALTER TABLE product_models MODIFY COLUMN customs_code varchar(100) NULL;
