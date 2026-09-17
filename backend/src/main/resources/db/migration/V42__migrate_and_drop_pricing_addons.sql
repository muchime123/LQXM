-- V42: 标记 pricing_addons 为内部存储字段（不删列）
-- 背景：pricing_addons LONGTEXT 存储 PricingStructure JSON。
--       读写路径已收口到 ProductPricingService 单一入口：
--       - PricingUpdatePayload 移除 pricingAddons 字段，只接受 pricingStructure 结构体
--       - ModelView DTO 移除 pricingAddons 字段，不再对外暴露原始 JSON
--       - 历史说明书模板功能曾通过 ProductPricingService.getPricingStructure() 解析
--       - ProductCatalogService.applyModelPayload 移除 pricingAddons 透传
--       列保留作为 PricingStructure 的 JSON 序列化存储，只被 ProductPricingService 内部读写。

-- 备份当前有值的数据（安全快照）
CREATE TABLE IF NOT EXISTS product_models_pricing_addons_backup AS
SELECT id, name, pricing_addons
FROM product_models
WHERE pricing_addons IS NOT NULL
  AND pricing_addons != ''
  AND deleted_at IS NULL;

-- 添加列注释标记为内部存储
ALTER TABLE product_models
    MODIFY COLUMN pricing_addons LONGTEXT
    COMMENT '@INTERNAL PricingStructure JSON 序列化存储，仅 ProductPricingService 读写';
