-- 只读探针 2：把「异常统计口径」与「页面清单口径」的差异精确拆开（2026-09-16）
-- 页面口径 = ProductInventoryService.VIEW_FROM + FEE_FILTER
-- 异常口径 = ProductInventoryService.snapshotInfo（无 FEE_FILTER、无仓库映射过滤、无数量过滤）

SELECT '=== A. 产品信息台账编号带前后空白的行数（决定页面 join 是否失配） ===' AS section;
SELECT COUNT(*) cnt_raw,
       SUM(pc.product_code <> TRIM(pc.product_code)) cnt_has_blank
FROM h3_product_catalog pc;

SELECT '=== B. 缺价：异常口径 vs 页面口径 ===' AS section;
SELECT 'snapshotInfo 口径(两侧TRIM,无费用过滤)' AS scope, COUNT(*) cnt
FROM h3_warehouse_stock ws JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE ws.quantity>0 AND COALESCE(pc.purchase_price,0)<=0
UNION ALL
SELECT '页面口径(严格join+费用过滤+仓库映射)', COUNT(*)
FROM h3_warehouse_stock ws
LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
LEFT JOIN h3_product_catalog pc ON pc.product_code=TRIM(ws.product_code)
WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL AND ws.quantity>0
  AND COALESCE(NULLIF(pc.purchase_price,0),NULLIF(ws.purchase_price,0)) IS NULL
  AND TRIM(COALESCE(COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(ws.product_category),'')),'')) NOT IN ('费用','制版费','税点','运费')
  AND TRIM(COALESCE(TRIM(ws.product_code),'')) NOT IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费');

SELECT '=== C. 缺产品 307 行的构成（有多少其实页面根本看不到） ===' AS section;
SELECT CASE WHEN fee_hit=1 THEN '费用项(页面已过滤,不该报)'
            WHEN unmapped=1 THEN '仓库映射不上(页面不显示)'
            ELSE '页面可见的真缺产品' END AS bucket,
       COUNT(*) cnt, SUM(t.quantity<>0) qty_nonzero
FROM (
  SELECT ws.quantity,
    CASE WHEN TRIM(COALESCE(TRIM(ws.product_code),'')) IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费')
              OR TRIM(COALESCE(ws.product_category,'')) IN ('费用','制版费','税点','运费') THEN 1 ELSE 0 END AS fee_hit,
    CASE WHEN COALESCE(m.warehouse_id,w.id) IS NULL THEN 1 ELSE 0 END AS unmapped
  FROM h3_warehouse_stock ws
  LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
  LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
  LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
  WHERE pc.id IS NULL
) t GROUP BY bucket;

SELECT '=== D. 缺价 24 行的构成 ===' AS section;
SELECT CASE WHEN fee_hit=1 THEN '费用项(页面已过滤,不该报)' ELSE '页面可见的真缺价' END AS bucket,
       COUNT(*) cnt, GROUP_CONCAT(code) codes
FROM (
  SELECT TRIM(ws.product_code) code,
    CASE WHEN TRIM(COALESCE(TRIM(ws.product_code),'')) IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费')
              OR TRIM(COALESCE(pc.product_category,'')) IN ('费用','制版费','税点','运费') THEN 1 ELSE 0 END AS fee_hit
  FROM h3_warehouse_stock ws JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
  WHERE ws.quantity>0 AND COALESCE(pc.purchase_price,0)<=0
) t GROUP BY bucket;

SELECT '=== E. 缺产品且数量>0 的 11 行（真正影响库存金额的） ===' AS section;
SELECT TRIM(ws.product_code) code, ws.product_name name, ws.warehouse, ws.unit, ws.quantity,
       m.warehouse_id mapped_by_name, w.id matched_warehouse
FROM h3_warehouse_stock ws
LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
WHERE pc.id IS NULL AND ws.quantity>0
ORDER BY ws.quantity DESC;

SELECT '=== F. 页面口径下采购价为空的可见行数（用户在页面上看到「—」的量） ===' AS section;
SELECT COUNT(*) cnt_null_price, SUM(ws.quantity>0) cnt_null_price_with_qty
FROM h3_warehouse_stock ws
LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
LEFT JOIN h3_product_catalog pc ON pc.product_code=TRIM(ws.product_code)
WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL
  AND COALESCE(NULLIF(pc.purchase_price,0),NULLIF(ws.purchase_price,0)) IS NULL
  AND TRIM(COALESCE(COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(ws.product_category),'')),'')) NOT IN ('费用','制版费','税点','运费')
  AND TRIM(COALESCE(TRIM(ws.product_code),'')) NOT IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费');

SELECT '=== G. 仓库明细里的仓库名分布（是否都能映射） ===' AS section;
SELECT TRIM(ws.warehouse) wh, COUNT(*) cnt,
       MAX(CASE WHEN COALESCE(m.warehouse_id,w.id) IS NOT NULL THEN 1 ELSE 0 END) mapped
FROM h3_warehouse_stock ws
LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
GROUP BY TRIM(ws.warehouse) ORDER BY cnt DESC;
