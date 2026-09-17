-- V77: 恢复"审核后发布"原则。
-- V76 把所有产品批量启用了官网发布，并为没有 slug/summary/category 的产品自动生成了兜底值。
-- 正式发布前需要把以下类型的产品打回未发布，由员工审核后再手动开启：
--   1. website_summary 是 V76 自动生成的两种固定模板（「…，品质保障，值得信赖。」 / 「…，专业…，品质保障。」）
--   2. website_slug 是 V76 自动生成的 'product-{id}' 格式，说明从未手动维护过
--   3. 缺主图 default_image（defaultImageAssetId 在 Service 层从文件名反推，DB 列是 default_image）
-- 以上条件满足任意一个，website_enabled 重置为 0。
-- 员工通过后续「官网工作台 / 产品发布管理」页面批量补齐、重新发布。

UPDATE product_models
SET website_enabled = b'0'
WHERE website_enabled = b'1'
  AND (
    -- 条件 1：V76 生成的两种默认摘要格式
    website_summary LIKE '%品质保障，值得信赖。'
    OR website_summary LIKE '%品质保障。'
    -- 条件 2：自动生成 slug = 'product-' + id
    OR website_slug = CONCAT('product-', id)
    -- 条件 3：缺主图（default_image 字段为空）
    OR default_image IS NULL
    OR default_image = ''
  );
