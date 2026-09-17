-- V97: 清理产品域 C 类死字段，保留官网发布/国际站多语言/SEO/发货字段
--
-- 删除 5 列（均不在官网 PublicProductModelView 展示，且前端表单无入口）：
--   1. order_process_cn / order_process_en   订单流程文案
--   2. source_file / source_sheet / import_batch_id   ProductModel 导入溯源
--      （ProductVariant 层同名字段已在 V96 清理；ProductAsset 仍保留用于 SourceTrace 展示）
-- 保留并重申后端有写入链路但前端缺表单/类型的字段（本轮前端同步补齐）：
--   nameEn / productLineNameEn / equipmentCategoryEn / applicableIndustryEn / applicableProductEn
--   / applicableScopeEn / equipmentAdvantagesEn / coreConfigDescriptionEn / introductionEn
--   / packagingMethodEn / introductionCn / searchKeywordsCn / searchKeywordsEn
--   / packagingMethodCn / shippingMethods / leadTimeStock / leadTimeCustom
--   / applicationTags / containerTags / packagingProcessTags / capacityLevel
--   / referenceDistributorPrice / referenceRetailPrice / pricingNotes / sortOrder

ALTER TABLE product_models DROP COLUMN order_process_cn;
ALTER TABLE product_models DROP COLUMN order_process_en;
ALTER TABLE product_models DROP COLUMN source_file;
ALTER TABLE product_models DROP COLUMN source_sheet;
ALTER TABLE product_models DROP COLUMN import_batch_id;
