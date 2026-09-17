-- V76: 批量启用产品官网发布 + 生成 slug 和 summary
-- 目的：让官网产品中心展示全部产品，而不是只展示手动启用的少数几个
-- 策略：
--   1. website_enabled = true
--   2. 为没有 slug 的产品生成 slug：优先用 model_code，其次用 product-{id}
--   3. 为没有 summary 的产品生成简单摘要：基于 name + equipment_category
-- 注意：slug 用英文/拼音兜底，避免中文字符在 URL 中出问题

-- Step 1: 启用所有产品的官网发布
UPDATE product_models
SET website_enabled = b'1'
WHERE website_enabled = b'0' OR website_enabled IS NULL;

-- Step 2: 为没有 slug 的产品生成 slug（用 id 兜底，确保唯一）
UPDATE product_models
SET website_slug = CONCAT('product-', id)
WHERE website_slug IS NULL OR website_slug = '';

-- Step 3: 为没有 summary 的产品生成简单摘要
UPDATE product_models
SET website_summary = CASE
    WHEN equipment_category IS NOT NULL AND equipment_category <> ''
        THEN CONCAT(name, '，专业', equipment_category, '，品质保障。')
    ELSE CONCAT(name, '，品质保障，值得信赖。')
END
WHERE website_summary IS NULL OR website_summary = '';

-- Step 4: 为没有 equipment_category 的产品设置默认分类（确保官网分类筛选可用）
UPDATE product_models
SET equipment_category = '其他设备'
WHERE equipment_category IS NULL OR equipment_category = '';
