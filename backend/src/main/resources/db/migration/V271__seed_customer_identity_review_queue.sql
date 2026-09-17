-- 第一阶段真实数据审计：把可疑客户身份和未绑定销售事实纳入复核队列。
-- 保留历史事实当前 customer_id，不做破坏性解绑；仅阻止未来继续自动复用可疑映射。

-- 已有客户映射中，长文本、电话混入、单字符/纯数字名称需要人工确认。
UPDATE source_identity_aliases
SET resolution_status='REVIEW',
    issue_code=CASE
      WHEN CHAR_LENGTH(TRIM(source_name))<=1 THEN 'CUSTOMER_NAME_TOO_SHORT'
      WHEN source_name REGEXP '[0-9]{7,}' THEN 'CUSTOMER_NAME_CONTAINS_PHONE'
      WHEN CHAR_LENGTH(source_name)>40 THEN 'CUSTOMER_NAME_TOO_LONG'
      ELSE 'CUSTOMER_NAME_SUSPICIOUS'
    END,
    resolution_note='历史客户名称质量审计：保留原业务绑定，暂停后续自动复用，等待人工确认',
    confidence=NULL
WHERE entity_type='CUSTOMER' AND resolution_status IN ('AUTO','CONFIRMED')
  AND (
    CHAR_LENGTH(TRIM(source_name))<=1
    OR source_name REGEXP '[0-9]{7,}'
    OR CHAR_LENGTH(source_name)>40
  );

-- 已有销售出库带客户名但没有 customer_id：按规范化名称建立待处理映射。
INSERT INTO source_identity_aliases
  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
   resolution_status,issue_code,confidence,occurrence_count,resolution_note)
SELECT 'H3YUN','CUSTOMER',
       LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　','')),
       MIN(o.customer_name),
       LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　','')),
       NULL,'REVIEW','UNRESOLVED_OUTBOUND_CUSTOMER',NULL,COUNT(*),
       '历史销售出库尚未关联正式客户'
FROM sales_outbounds o
WHERE o.customer_id IS NULL AND o.customer_name IS NOT NULL AND TRIM(o.customer_name)<>''
GROUP BY LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　',''))
ON DUPLICATE KEY UPDATE
  occurrence_count=GREATEST(occurrence_count,VALUES(occurrence_count)),
  resolution_status=IF(resolution_status='IGNORED','IGNORED','REVIEW'),
  issue_code=IF(resolution_status='IGNORED',issue_code,'UNRESOLVED_OUTBOUND_CUSTOMER'),
  resolution_note=IF(resolution_status='IGNORED',resolution_note,'历史销售出库尚未关联正式客户');
