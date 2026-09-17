-- V65: 删除产品定价模块（price plans / tiers / addons）
-- 前端已移除定价 API 调用和 ProductPricingCard 组件，后端已删除 Controller/Service/Entity/Repository/DTO

-- 先删子表（有外键依赖 price_plan_id）
DROP TABLE IF EXISTS product_price_addons;
DROP TABLE IF EXISTS product_price_tiers;
DROP TABLE IF EXISTS product_price_plans;
