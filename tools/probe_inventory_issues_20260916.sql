-- 只读探针：产品库存「待处理异常」两类口径的真实分布（2026-09-16）
-- 目的：判断异常是「氚云真缺」还是「写法/批次错位」，为处置动作定路径

SELECT '=== 0. 两份台账最近批次 ===' AS section;
SELECT id, data_type, status, file_name, created_at, completed_at
FROM h3_import_batches
WHERE data_type IN ('PRODUCT_INFO','WAREHOUSE_STOCK') AND status='COMPLETED'
ORDER BY completed_at DESC LIMIT 6;

SELECT '=== 1. 缺产品：按数量桶 ===' AS section;
SELECT CASE WHEN ws.quantity IS NULL THEN 'NULL'
            WHEN ws.quantity = 0 THEN '=0'
            WHEN ws.quantity < 0 THEN '<0'
            ELSE '>0' END AS qty_bucket,
       COUNT(*) cnt, COUNT(DISTINCT TRIM(ws.product_code)) codes
FROM h3_warehouse_stock ws
LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE pc.id IS NULL
GROUP BY qty_bucket;

SELECT '=== 2. 缺价：按数量桶 ===' AS section;
SELECT CASE WHEN ws.quantity = 0 THEN '=0' WHEN ws.quantity < 0 THEN '<0' ELSE '>0' END AS qty_bucket,
       COUNT(*) cnt, COUNT(DISTINCT TRIM(ws.product_code)) codes
FROM h3_warehouse_stock ws
JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE ws.quantity>0 AND COALESCE(pc.purchase_price,0)<=0
GROUP BY qty_bucket;

SELECT '=== 3. 缺产品 TOP 编号（按行数） ===' AS section;
SELECT TRIM(ws.product_code) code, MAX(ws.product_name) name, COUNT(*) cnt,
       COUNT(DISTINCT ws.warehouse) whs, SUM(ws.quantity) qty
FROM h3_warehouse_stock ws
LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE pc.id IS NULL
GROUP BY TRIM(ws.product_code)
ORDER BY cnt DESC, qty DESC LIMIT 40;

SELECT '=== 4. 缺价 TOP 编号（按行数） ===' AS section;
SELECT TRIM(ws.product_code) code, MAX(ws.product_name) name, COUNT(*) cnt,
       MAX(pc.product_name) catalog_name, MAX(pc.purchase_price) price
FROM h3_warehouse_stock ws
JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)
WHERE ws.quantity>0 AND COALESCE(pc.purchase_price,0)<=0
GROUP BY TRIM(ws.product_code)
ORDER BY cnt DESC LIMIT 40;

SELECT '=== 5. 缺产品：编号是否只是写法差异（产品信息侧存在同号但有空白/全角差） ===' AS section;
SELECT TRIM(ws.product_code) code, COUNT(*) cnt,
       MAX(pc.product_code) catalog_raw_code
FROM h3_warehouse_stock ws
JOIN h3_product_catalog pc
  ON REPLACE(REPLACE(REPLACE(pc.product_code,' ',''),CHAR(9),''),CHAR(13),'') = TRIM(ws.product_code)
LEFT JOIN h3_product_catalog pc2 ON TRIM(pc2.product_code)=TRIM(ws.product_code)
WHERE pc2.id IS NULL
GROUP BY TRIM(ws.product_code)
ORDER BY cnt DESC LIMIT 30;

SELECT '=== 6. 产品信息台账规模与采购价填充率 ===' AS section;
SELECT COUNT(*) cnt, COUNT(DISTINCT TRIM(product_code)) codes,
       SUM(COALESCE(purchase_price,0)>0) price_filled,
       SUM(COALESCE(purchase_price,0)<=0) price_empty
FROM h3_product_catalog;

SELECT '=== 7. 仓库明细规模 ===' AS section;
SELECT COUNT(*) cnt, COUNT(DISTINCT TRIM(product_code)) codes,
       SUM(COALESCE(quantity,0)>0) qty_pos, SUM(COALESCE(quantity,0)=0) qty_zero,
       SUM(quantity IS NULL OR quantity<0) qty_bad
FROM h3_warehouse_stock;

SELECT '=== 8. 快照清单实际行数（页面口径） ===' AS section;
SELECT COUNT(*) balance_rows, COUNT(DISTINCT sales_product_id) products
FROM product_inventory_balances;
