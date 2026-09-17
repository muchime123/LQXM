-- V96: 清理产品域死字段
-- 背景：经字段级审计确认以下列无任何读取/展示链路（DTO 不读、前端无引用、批量白名单不含）。
--
-- 1. product_models.pricing_addons
--    - V42 已将历史数据备份至 product_models_pricing_addons_backup
--    - 实体 ProductModel.pricingAddons 无 getter/setter 调用
--    - ModelView DTO 不含，MODEL_UPDATEABLE_FIELDS 不含，前端 CatalogModel 无定义
--
-- 2. product_variants.source_file / source_sheet / import_batch_id
--    - 仅 CatalogImportService 导入时写入（用于溯源），但 VariantView DTO 从不读取
--    - VARIANT_UPDATEABLE_FIELDS 不含，前端 CatalogVariant 无定义
--    - SourceTrace 组件只用于 ProductAsset，不展示 Variant 溯源
--    - CatalogImportService 中对应的 3 行 setter 已同步删除

ALTER TABLE product_models DROP COLUMN pricing_addons;
ALTER TABLE product_variants DROP COLUMN source_file;
ALTER TABLE product_variants DROP COLUMN source_sheet;
ALTER TABLE product_variants DROP COLUMN import_batch_id;
