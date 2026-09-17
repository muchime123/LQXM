-- 只读探针 3：验证新 snapshotInfo 口径（三层收敛）的数学
-- 处置表尚未由 Flyway 建表，此处无需引用：未处置时 dispositionId 恒 NULL，
-- 汇总里 pending/affected 就是全部行数，够验证口径是否正确。

SELECT '=== A. 历史口径（无费用过滤）应 = 331 ===' AS section;
SELECT COUNT(*) cnt
FROM h3_warehouse_stock ws
LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL
  AND (pc.id IS NULL OR (COALESCE(ws.quantity,0)>0 AND COALESCE(pc.purchase_price,0)<=0));

SELECT '=== B. 新口径（套费用过滤 + 分层）→ 缺产品应 307/11、缺价应 17/17 ===' AS section;
SELECT issueType, COUNT(*) pending, SUM(quantity>0) affected,
       COUNT(DISTINCT productCode) pendingCodes,
       SUM(dt_count) AS fee_excluded_here
FROM (
  SELECT TRIM(ws.product_code) productCode, COALESCE(ws.quantity,0) quantity,
    CASE WHEN pc.id IS NULL THEN 'MISSING_PRODUCT' ELSE 'MISSING_PRICE' END issueType,
    0 AS dt_count
  FROM h3_warehouse_stock ws
  LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
  LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
  LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
  WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL
    AND (pc.id IS NULL OR (COALESCE(ws.quantity,0)>0 AND COALESCE(pc.purchase_price,0)<=0))
    AND TRIM(COALESCE(COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(ws.product_category),'')),'')) NOT IN ('费用','制版费','税点','运费')
    AND TRIM(COALESCE(ws.product_code,'')) NOT IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费')
) t GROUP BY issueType;

SELECT '=== C. 真正影响金额的合计应 = 28 ===' AS section;
SELECT SUM(quantity>0) affectedTotal
FROM (
  SELECT COALESCE(ws.quantity,0) quantity
  FROM h3_warehouse_stock ws
  LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
  LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
  LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
  WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL
    AND (pc.id IS NULL OR (COALESCE(ws.quantity,0)>0 AND COALESCE(pc.purchase_price,0)<=0))
    AND TRIM(COALESCE(COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(ws.product_category),'')),'')) NOT IN ('费用','制版费','税点','运费')
    AND TRIM(COALESCE(ws.product_code,'')) NOT IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费')
) t;

SELECT '=== D. 被费用过滤挡掉的行数应 = 7（差异来源可见） ===' AS section;
SELECT COUNT(*) feeExcluded
FROM h3_warehouse_stock ws
LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)
LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)
LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL
  AND (pc.id IS NULL OR (COALESCE(ws.quantity,0)>0 AND COALESCE(pc.purchase_price,0)<=0))
  AND (TRIM(COALESCE(COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(ws.product_category),'')),'')) IN ('费用','制版费','税点','运费')
       OR TRIM(COALESCE(ws.product_code,'')) IN ('运费','加工费','制版费','税点','模具费','打样费','样品费','优惠','加急费','认证费','代发运费'));

SELECT '=== E. 处置表若存在，其行数（建表前应为 0/报错） ===' AS section;
SELECT COUNT(*) existing_dispositions FROM h3_inventory_issue_dispositions;
