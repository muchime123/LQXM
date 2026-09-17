-- V73: 删除 product_variants 的 7 个幽灵 *Brand 列
--
-- 背景：
--   product_variants 表有 7 个零部件品牌字段，全部为 varchar(100)：
--     solenoid_valve_brand, cylinder_brand, motor_brand, touch_screen_brand,
--     air_switch_brand, pressure_gauge_brand, oil_water_separator_brand
--
--   这些字段在代码中：
--   - Entity 有定义，DTO 有 getter，但全后端无任何 setXxxBrand() 调用
--   - 不在 VARIANT_UPDATEABLE_FIELDS 白名单中
--   - 不在 applyVariantPayload 方法中
--   - 不在 CatalogImportService Excel 导入中
--   - 前端完全无引用
--
--   数据库中：93 行全部为 NULL（0 行非空），确认是从未被写入的幽灵列。
--
--   product_variants_archive 表也有这 7 列（归档表结构镜像主表），一并删除。

ALTER TABLE product_variants
  DROP COLUMN solenoid_valve_brand,
  DROP COLUMN cylinder_brand,
  DROP COLUMN motor_brand,
  DROP COLUMN touch_screen_brand,
  DROP COLUMN air_switch_brand,
  DROP COLUMN pressure_gauge_brand,
  DROP COLUMN oil_water_separator_brand;

ALTER TABLE product_variants_archive
  DROP COLUMN solenoid_valve_brand,
  DROP COLUMN cylinder_brand,
  DROP COLUMN motor_brand,
  DROP COLUMN touch_screen_brand,
  DROP COLUMN air_switch_brand,
  DROP COLUMN pressure_gauge_brand,
  DROP COLUMN oil_water_separator_brand;
