DROP TABLE IF EXISTS _diag_unm;
CREATE TABLE _diag_unm (nm VARCHAR(500) PRIMARY KEY) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- 与 address-sync 一致的口径：有地址出库、按 Java 精确名（含大小写）比对档案无同名
INSERT IGNORE INTO _diag_unm (nm)
SELECT DISTINCT TRIM(o.customer_name)
FROM sales_outbounds o
WHERE o.customer_name IS NOT NULL AND TRIM(o.customer_name)<>''
  AND (o.customer_address IS NOT NULL AND o.customer_address<>'' OR o.region IS NOT NULL AND o.region<>'')
  AND NOT EXISTS (
    SELECT 1 FROM customers c WHERE c.deleted_at IS NULL AND c.customer_name = BINARY TRIM(o.customer_name));

SELECT 'T1_unmatched_total' k, COUNT(*) v FROM _diag_unm;
SELECT 'T2_have_archive_after_ws_norm' k, COUNT(*) v FROM _diag_unm t WHERE EXISTS (
    SELECT 1 FROM customers c WHERE c.deleted_at IS NULL
      AND LOWER(REPLACE(REPLACE(TRIM(c.customer_name),' ',''),'　',''))
        = LOWER(REPLACE(REPLACE(TRIM(t.nm),' ',''),'　','')));
SELECT 'T3_have_any_alias' k, COUNT(DISTINCT a.normalized_name) v
FROM source_identity_aliases a JOIN _diag_unm t
  ON a.normalized_name = LOWER(REPLACE(REPLACE(TRIM(t.nm),' ',''),'　',''))
WHERE a.entity_type='CUSTOMER';
SELECT 'T4_names_with_null_customer_id_fact' k, COUNT(DISTINCT t.nm) v
FROM _diag_unm t JOIN sales_outbounds o ON TRIM(o.customer_name)=t.nm
WHERE o.customer_id IS NULL;
SELECT 'T5_names_every_fact_bound' k, COUNT(*) v FROM _diag_unm t WHERE NOT EXISTS (
    SELECT 1 FROM sales_outbounds o WHERE TRIM(o.customer_name)=t.nm AND o.customer_id IS NULL);

SELECT 'T6_no_alias_nor_null_fact' k, COUNT(DISTINCT t.nm) v
FROM _diag_unm t
WHERE NOT EXISTS (SELECT 1 FROM source_identity_aliases a
    WHERE a.entity_type='CUSTOMER'
      AND a.normalized_name=LOWER(REPLACE(REPLACE(TRIM(t.nm),' ',''),'　','')))
  AND NOT EXISTS (SELECT 1 FROM sales_outbounds o WHERE TRIM(o.customer_name)=t.nm AND o.customer_id IS NULL);

-- 样本：名字对不上档案、没有别名、但事实全部已绑客户ID（占大多数）
SELECT t.nm, COUNT(*) facts, MIN(customer_id) cid1, MAX(customer_id) cid2
FROM _diag_unm t JOIN sales_outbounds o ON TRIM(o.customer_name)=t.nm
WHERE NOT EXISTS (SELECT 1 FROM source_identity_aliases a
      WHERE a.entity_type='CUSTOMER' AND a.normalized_name=LOWER(REPLACE(REPLACE(TRIM(t.nm),' ',''),'　','')))
GROUP BY t.nm HAVING MIN(o.customer_id) IS NOT NULL
ORDER BY facts DESC LIMIT 10;
DROP TABLE _diag_unm;
