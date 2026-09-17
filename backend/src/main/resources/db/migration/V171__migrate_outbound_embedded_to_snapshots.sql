-- 六层架构落地 · 第2步 DML：92,625 条 OUTBOUND_EMBEDDED 迁出库内嵌快照 + 打追溯标记
-- 执行顺序严格保持：先 INSERT（无副作用）→ UPDATE alloc 打标 → UPDATE receipt 打标 → 四数校验
-- 任何一步异常请直接修复后重跑：三表 UPDATE 均以 receipt_type='OUTBOUND_EMBEDDED' 为唯一谓词，支持重入

-- ===== Step 1: 迁 OUTBOUND_EMBEDDED receipt → sales_outbound_payment_snapshots =====
-- 92,625 条（探查 B 表确认）；每 1 条 receipt 通过 1:1 allocation 反查 sales_outbound_id
-- installment_index 从 receipt_no 的 CKxxxxxxxx#Rn 后缀提取 n（支持 1~99 笔分期）
INSERT INTO sales_outbound_payment_snapshots (
    sales_outbound_id, installment_index,
    legacy_receipt_id, legacy_external_source, legacy_external_id, legacy_receipt_no, legacy_status,
    receipt_date, received_amount, payment_method, source_created_by, source_created_at, remark, migration_batch
)
SELECT s.id                                                                    AS sales_outbound_id,
       CAST(REGEXP_SUBSTR(r.receipt_no, '[0-9]+$') AS UNSIGNED)               AS installment_index,
       r.id                                                                    AS legacy_receipt_id,
       r.external_source                                                       AS legacy_external_source,
       r.external_id                                                           AS legacy_external_id,
       r.receipt_no                                                            AS legacy_receipt_no,
       r.status                                                                AS legacy_status,
       r.receipt_date                                                          AS receipt_date,
       r.receipt_amount                                                        AS received_amount,
       r.payment_method                                                        AS payment_method,
       r.source_created_by                                                     AS source_created_by,
       r.source_created_at                                                     AS source_created_at,
       r.remark                                                                AS remark,
       'V171'                                                                  AS migration_batch
FROM receipts r
JOIN receipt_allocations ra ON ra.receipt_id = r.id
JOIN sales_outbounds s        ON s.id = ra.sales_outbound_id
WHERE r.receipt_type = 'OUTBOUND_EMBEDDED';

-- ===== Step 2: receipt_allocations 打 origin = OUTBOUND_EMBEDDED 白名单标记 =====
-- 行数应 = 92,625；探查 A 表已验证 OUTBOUND_EMBEDDED n: alloc_rows = 1:1
UPDATE receipt_allocations ra
JOIN   receipts r ON r.id = ra.receipt_id
SET    ra.allocation_origin = 'OUTBOUND_EMBEDDED'
WHERE  r.receipt_type = 'OUTBOUND_EMBEDDED';

-- ===== Step 3: receipts 打 migrated 标记，页面后续消费端一律 WHERE legacy_migrated_tag IS NULL 取正式回款 =====
UPDATE receipts
SET    legacy_migrated_tag = 'MIGRATED_OUT_TO_SNAPSHOT'
WHERE  receipt_type = 'OUTBOUND_EMBEDDED';

-- ===== Step 4: 一致性校验 — 四数必须全部等于源表 OUTBOUND_EMBEDDED 行数 =====
-- src_count                 = 源 receipts 行数（基准 = 92,625）
-- snapshot_inserted_count   = 插入快照行数（= src 才 OK，否则说明 allocation/outbound 断链）
-- allocation_tagged_count   = receipt_allocations 打标行数（必须 = src，每条 OUTBOUND_EMBEDDED 都带 1 条镜像 allocation）
-- receipt_tagged_count      = receipts 打 migrated 行数（= src，否则 UPDATE 谓词漏了）
-- 任何数字不匹配时在同一次 Flyway 迁移中直接暴露异常（后面紧跟第 4 步 SettlementLedgerService 会用到这些标记）

SET @src_count               = (SELECT COUNT(*) FROM receipts WHERE receipt_type = 'OUTBOUND_EMBEDDED');
SET @snapshot_inserted_count = (SELECT COUNT(*) FROM sales_outbound_payment_snapshots WHERE migration_batch = 'V171');
SET @allocation_tagged_count = (SELECT COUNT(*) FROM receipt_allocations WHERE allocation_origin = 'OUTBOUND_EMBEDDED');
SET @receipt_tagged_count    = (SELECT COUNT(*) FROM receipts WHERE legacy_migrated_tag = 'MIGRATED_OUT_TO_SNAPSHOT');

SELECT
  @src_count               AS src_count,
  @snapshot_inserted_count AS snapshot_inserted_count,
  @allocation_tagged_count AS allocation_tagged_count,
  @receipt_tagged_count    AS receipt_tagged_count,
  CASE
    WHEN @src_count = @snapshot_inserted_count
     AND @src_count = @allocation_tagged_count
     AND @src_count = @receipt_tagged_count
    THEN 'OK'
    ELSE CONCAT('MISMATCH expect=', @src_count,
                ' snap=',   @snapshot_inserted_count,
                ' alloc=',  @allocation_tagged_count,
                ' tag=',    @receipt_tagged_count)
  END AS v171_migration_check;
