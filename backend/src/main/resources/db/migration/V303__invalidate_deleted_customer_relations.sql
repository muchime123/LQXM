-- P0：客户软删除后，销售出库和身份映射不能继续把已删除客户当成有效目标。
-- 仅处理已经失效的引用；有效客户关系和氚云原始快照不变。

UPDATE sales_outbounds o
LEFT JOIN customers c ON c.id=o.customer_id AND c.deleted_at IS NULL
SET o.customer_id=NULL
WHERE o.customer_id IS NOT NULL AND c.id IS NULL;

UPDATE source_identity_aliases a
LEFT JOIN customers c ON c.id=a.target_id AND c.deleted_at IS NULL
SET a.target_id=NULL,
    a.resolution_status='REVIEW',
    a.issue_code='TARGET_CUSTOMER_DELETED',
    a.resolution_note='原关联客户已删除，等待重新关联',
    a.updated_at=NOW()
WHERE a.entity_type='CUSTOMER' AND a.target_id IS NOT NULL AND c.id IS NULL;

UPDATE customer_source_cluster_mappings m
LEFT JOIN customers c ON c.id=m.customer_id AND c.deleted_at IS NULL
SET m.status='REVOKED',m.updated_at=NOW()
WHERE m.status='CONFIRMED' AND c.id IS NULL;
