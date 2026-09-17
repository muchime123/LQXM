-- 第二阶段（修正版）：销售出库产品行复用“产品信息 + 仓库明细”已建立的交易产品身份。
-- 不建立生产任务、成品批次与销售出库之间的推测关系。

-- 1. 优先复用已确认的氚云产品编码别名。
UPDATE sales_outbound_items i
JOIN sales_product_source_aliases a
  ON a.source_system='H3YUN'
 AND a.resolution_status='RESOLVED'
 AND a.sales_product_id IS NOT NULL
 AND a.source_product_code=TRIM(i.product_code)
SET i.sales_product_id=a.sales_product_id
WHERE i.line_type='PRODUCT' AND i.sales_product_id IS NULL
  AND i.product_code IS NOT NULL AND TRIM(i.product_code)<>'';

-- 2. 没有编码别名时，仅在“名称 + 规格 + 单位”唯一命中时自动回填。
UPDATE sales_outbound_items i
JOIN (
  SELECT MIN(id) id,product_name,COALESCE(specification,'') specification,COALESCE(unit,'') unit
  FROM sales_products WHERE deleted_at IS NULL
  GROUP BY product_name,COALESCE(specification,''),COALESCE(unit,'')
  HAVING COUNT(*)=1
) p ON p.product_name=i.product_name
   AND p.specification=COALESCE(i.specification,'')
   AND p.unit=COALESCE(i.unit,'')
SET i.sales_product_id=p.id
WHERE i.line_type='PRODUCT' AND i.sales_product_id IS NULL;

-- 3. 已回填关系沉淀到统一身份表，后续导入可直接复用。
INSERT IGNORE INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
   resolution_status,confidence,resolution_note,resolved_at,occurrence_count)
SELECT 'H3YUN','PRODUCT',
       COALESCE(NULLIF(TRIM(i.product_code),''),CONCAT('NAME|',SHA2(CONCAT_WS('|',
         LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.product_name,'')),' ',''),'　','')),
         LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.specification,'')),' ',''),'　','')),
         LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.unit,'')),' ',''),'　',''))),256))),
       MIN(i.product_name),LOWER(REPLACE(REPLACE(TRIM(MIN(i.product_name)),' ',''),'　','')),
       MIN(i.sales_product_id),'CONFIRMED',1.0000,'由历史销售出库唯一交易产品关系迁移',NOW(),COUNT(*)
FROM sales_outbound_items i
WHERE i.line_type='PRODUCT' AND i.sales_product_id IS NOT NULL
GROUP BY COALESCE(NULLIF(TRIM(i.product_code),''),CONCAT('NAME|',SHA2(CONCAT_WS('|',
  LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.product_name,'')),' ',''),'　','')),
  LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.specification,'')),' ',''),'　','')),
  LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.unit,'')),' ',''),'　',''))),256)))
HAVING COUNT(DISTINCT i.sales_product_id)=1;

-- 4. 仍无法解析的产品只进入人工复核队列，不自动新建、不模糊绑定。
INSERT INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,resolution_status,
   issue_code,resolution_note,occurrence_count)
SELECT 'H3YUN','PRODUCT',
       COALESCE(NULLIF(TRIM(i.product_code),''),CONCAT('NAME|',SHA2(CONCAT_WS('|',
         LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.product_name,'')),' ',''),'　','')),
         LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.specification,'')),' ',''),'　','')),
         LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.unit,'')),' ',''),'　',''))),256))),
       MIN(i.product_name),LOWER(REPLACE(REPLACE(TRIM(MIN(i.product_name)),' ',''),'　','')),
       'REVIEW','PRODUCT_NOT_FOUND','销售出库产品尚未关联交易产品',COUNT(*)
FROM sales_outbound_items i
WHERE i.line_type='PRODUCT' AND i.sales_product_id IS NULL
  AND (NULLIF(TRIM(i.product_code),'') IS NOT NULL OR NULLIF(TRIM(i.product_name),'') IS NOT NULL)
GROUP BY COALESCE(NULLIF(TRIM(i.product_code),''),CONCAT('NAME|',SHA2(CONCAT_WS('|',
  LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.product_name,'')),' ',''),'　','')),
  LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.specification,'')),' ',''),'　','')),
  LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.unit,'')),' ',''),'　',''))),256)))
ON DUPLICATE KEY UPDATE occurrence_count=VALUES(occurrence_count),updated_at=NOW();
