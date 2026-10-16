-- V357: 释放被空串占住的官网 URL 标识槽位（修复 Duplicate entry '' for key 'product_models.uk_website_slug'）
--
-- 背景：
--   product_models.website_slug 上有唯一索引 uk_website_slug。MySQL 的唯一索引把多个 NULL
--   视为互不相同、允许并存；但 '' 是真实值，全表只能有一行。
--   编辑器表单（useCatalogEditors.blankModel()）的 slug 默认值是 ''，历史上被原样入库，
--   于是第一行空 slug 记录占住了 '' 槽位，之后任何「空 slug 新建 / 清空 slug 保存」的请求
--   都会失败并报 Duplicate entry ''。
--   代码侧已改为空白一律落 NULL（ProductCatalogService.normalizeWebsiteText），
--   本迁移负责把历史遗留的 '' 记录清掉。
--
-- 处置：
--   1. 优先补成与同批产品一致的 product-{id} 形式（V76 的既有约定），保住官网可访问性；
--   2. 兜底把仍然为 '' 的记录置 NULL，确保 '' 槽位一定被释放（幂等，无匹配行则零影响）。

UPDATE product_models pm
SET pm.website_slug = CONCAT('product-', pm.id)
WHERE pm.website_slug = ''
  AND NOT EXISTS (
      SELECT 1 FROM (SELECT website_slug FROM product_models) AS others
      WHERE others.website_slug = CONCAT('product-', pm.id)
  );

UPDATE product_models
SET website_slug = NULL
WHERE website_slug = '';
