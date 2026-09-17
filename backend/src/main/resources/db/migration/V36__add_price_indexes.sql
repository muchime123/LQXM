-- V36: 添加价格方案相关复合索引
-- 2026-07-21 | 优化常用查询性能

-- product_price_plans: 常用查询组合
CREATE INDEX idx_product_price_plans_model_status ON product_price_plans (product_model_id, status);
CREATE INDEX idx_product_price_plans_model_effective ON product_price_plans (product_model_id, effective_date DESC);

-- product_price_tiers: 档位匹配查询优化
CREATE INDEX idx_product_price_tiers_plan_shape ON product_price_tiers (price_plan_id, shape_type);
CREATE INDEX idx_product_price_tiers_plan_enabled ON product_price_tiers (price_plan_id, enabled);

-- product_price_addons: 选配查询优化
CREATE INDEX idx_product_price_addons_plan_enabled ON product_price_addons (price_plan_id, enabled);
CREATE INDEX idx_product_price_addons_plan_shape ON product_price_addons (price_plan_id, applicable_shape);