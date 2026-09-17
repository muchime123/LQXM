-- 修复 V171 之后由实时销售出库导入重新写入、但仍被默认标成 DIRECT 的内嵌收款。
-- 先补快照，再对镜像 receipt/allocation 打标；脚本可重入且不覆盖已存在快照。
INSERT IGNORE INTO sales_outbound_payment_snapshots (
    sales_outbound_id, installment_index,
    legacy_receipt_id, legacy_external_source, legacy_external_id, legacy_receipt_no, legacy_status,
    receipt_date, received_amount, payment_method, source_created_by, source_created_at, remark, migration_batch
)
SELECT ra.sales_outbound_id,
       COALESCE(CAST(REGEXP_SUBSTR(r.receipt_no, '[0-9]+$') AS UNSIGNED), 1),
       r.id, r.external_source, r.external_id, r.receipt_no, r.status,
       r.receipt_date, COALESCE(r.receipt_amount, 0), r.payment_method,
       r.source_created_by, r.source_created_at, r.remark, 'V193'
FROM receipts r
JOIN receipt_allocations ra ON ra.receipt_id = r.id
WHERE r.receipt_type = 'OUTBOUND_EMBEDDED'
  AND ra.sales_outbound_id IS NOT NULL;

UPDATE receipt_allocations ra
JOIN receipts r ON r.id = ra.receipt_id
SET ra.allocation_origin = 'OUTBOUND_EMBEDDED'
WHERE r.receipt_type = 'OUTBOUND_EMBEDDED';

UPDATE receipts
SET legacy_migrated_tag = 'MIGRATED_OUT_TO_SNAPSHOT'
WHERE receipt_type = 'OUTBOUND_EMBEDDED';
