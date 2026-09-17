-- ========================================================================
-- 六层架构落地 · 第7步 DML：从氚云原始 h3_import_rows.raw_data.rows[] 补建 5,309 张缺失的历史出库单
-- 前置依赖：V173 已执行；覆盖率探查已确认 100% 可从 rows[] 提取到 Tier1 客户+日期+金额
-- 执行顺序：Step1 缺失单号基准表 → Step2 JSON_TABLE 自包含提取 + 客户名解析 + 单号最优去重
--         → Step3 INSERT 补档出库单到 sales_outbounds（打标 H3_HISTORICAL_RECOVERED）
--         → Step3.5 合并同一张 receipt 内重复引用相同 outbound 的 allocation 行（唯一键前置清理）
--         → Step4 回填 receipt_allocations.sales_outbound_id 外键 + reference_status=REFER_RESOLVED
--         → Step5 补齐 receipts.customer_name / salesperson_name 为空的收款单
--         → Step6 六数一致性校验（基准缺失数 / 插入数 / 回填数 / 孤儿剩余数 / 金额总计）
-- 幂等：所有 INSERT/UPDATE 都显式判断"尚未插入/尚未回填"，支持 Flyway 失败重跑
-- ========================================================================

-- ========= Step0: collation 统一，避免 0900 vs unicode 冲突 =========
SET collation_connection = 'utf8mb4_unicode_ci';
SET collation_database   = 'utf8mb4_unicode_ci';

DROP TEMPORARY TABLE IF EXISTS v174_related_receipts;
DROP TEMPORARY TABLE IF EXISTS v174_missing_nos;
DROP TEMPORARY TABLE IF EXISTS v174_extracted_rows;
DROP TEMPORARY TABLE IF EXISTS v174_distinct;
DROP TEMPORARY TABLE IF EXISTS v174_dup_plan;

-- ========= Step1: 缺失单号基准（真实探查询 orphan allocation 聚合） =========
CREATE TEMPORARY TABLE v174_related_receipts (
  receipt_id BIGINT PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO v174_related_receipts (receipt_id)
SELECT DISTINCT r.id
FROM receipt_allocations ra
JOIN receipts r ON r.id = ra.receipt_id
WHERE ra.sales_outbound_id IS NULL
  AND ra.outbound_no_snapshot IS NOT NULL AND TRIM(ra.outbound_no_snapshot) <> ''
  AND ra.allocation_origin = 'DIRECT' AND r.legacy_migrated_tag IS NULL
  AND NOT EXISTS (SELECT 1 FROM sales_outbounds so WHERE so.outbound_no COLLATE utf8mb4_unicode_ci = TRIM(ra.outbound_no_snapshot));

CREATE TEMPORARY TABLE v174_missing_nos (
  missing_no  VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY,
  alloc_rows  INT DEFAULT 0,
  alloc_amount DECIMAL(18,2) DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO v174_missing_nos (missing_no, alloc_rows, alloc_amount)
SELECT
  TRIM(ra.outbound_no_snapshot) COLLATE utf8mb4_unicode_ci,
  COUNT(*),
  COALESCE(SUM(ra.allocated_amount), 0)
FROM receipt_allocations ra
JOIN receipts r ON r.id = ra.receipt_id
WHERE ra.sales_outbound_id IS NULL
  AND ra.outbound_no_snapshot IS NOT NULL AND TRIM(ra.outbound_no_snapshot) <> ''
  AND ra.allocation_origin = 'DIRECT' AND r.legacy_migrated_tag IS NULL
  AND NOT EXISTS (SELECT 1 FROM sales_outbounds so WHERE so.outbound_no COLLATE utf8mb4_unicode_ci = TRIM(ra.outbound_no_snapshot))
GROUP BY TRIM(ra.outbound_no_snapshot) COLLATE utf8mb4_unicode_ci;

SET @v174_exp_missing = (SELECT COUNT(*) FROM v174_missing_nos);
SET @v174_exp_alloc   = (SELECT COALESCE(SUM(alloc_rows),0) FROM v174_missing_nos);
SET @v174_exp_amt     = (SELECT COALESCE(SUM(alloc_amount),0) FROM v174_missing_nos);

-- ========= Step2: 真正自包含的 JSON_TABLE 提取 rows[] + 客户名解析 =========
-- 2.1 先把所有相关 receipt_id 对应的 h3_import_rows.rows[] 展开抽出来
CREATE TEMPORARY TABLE v174_extracted_rows (
  outbound_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  choose_label VARCHAR(500),
  sales_date DATE,
  sales_amount DECIMAL(18,2),
  received_amount DECIMAL(18,2),
  h3_id BIGINT,
  batch_id BIGINT,
  h3_receipt_id BIGINT,
  owner_name VARCHAR(100),
  seq_no INT,
  row_idx INT,
  INDEX idx_v174_ono (outbound_no)
) ENGINE=InnoDB;

INSERT INTO v174_extracted_rows
SELECT
  TRIM(jt.outbound_no) COLLATE utf8mb4_unicode_ci,
  jt.choose_label,
  CASE WHEN jt.sales_date REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
       THEN STR_TO_DATE(jt.sales_date, '%Y-%m-%d') ELSE NULL END,
  CASE WHEN jt.sales_amount_str REGEXP '^[0-9,]+\\.?[0-9]*$'
       THEN REPLACE(jt.sales_amount_str, ',', '') + 0.0 ELSE NULL END,
  CASE WHEN jt.received_amount_str REGEXP '^[0-9,]+\\.?[0-9]*$'
       THEN REPLACE(jt.received_amount_str, ',', '') + 0.0 ELSE NULL END,
  h.id,
  h.batch_id,
  h.business_id,
  NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(h.raw_data, '$.main."拥有者(必填)"'))), ''),
  CASE WHEN jt.seq_no REGEXP '^[0-9]+$' THEN CAST(jt.seq_no AS UNSIGNED) ELSE NULL END,
  jt.row_idx
FROM h3_import_rows h,
JSON_TABLE(
  h.raw_data,
  '$.rows[*]' COLUMNS (
    outbound_no          VARCHAR(100) PATH '$."出库单明细.销售出库编号"',
    choose_label         VARCHAR(500) PATH '$."出库单明细.选择销售出库单"',
    sales_date           VARCHAR(50)  PATH '$."出库单明细.销售日期"',
    sales_amount_str     VARCHAR(50)  PATH '$."出库单明细.销售金额合计"',
    received_amount_str  VARCHAR(50)  PATH '$."出库单明细.已收款金额"',
    seq_no               VARCHAR(20)  PATH '$."出库单明细.序号"',
    row_idx              FOR ORDINALITY
  )
) jt
WHERE h.business_id IN (SELECT receipt_id FROM v174_related_receipts)
  AND jt.outbound_no IS NOT NULL AND TRIM(jt.outbound_no) <> '';

-- 2.2 去重：每个缺失单号保留质量分最高的 1 条（客户+日期+金额齐全=Tier1优先）
CREATE TEMPORARY TABLE v174_distinct (
  outbound_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY,
  choose_label VARCHAR(500),
  parsed_customer_name VARCHAR(300),
  sales_date DATE,
  sales_amount DECIMAL(18,2),
  received_amount DECIMAL(18,2),
  h3_id BIGINT,
  h3_receipt_id BIGINT,
  owner_name VARCHAR(100),
  quality_score INT
) ENGINE=InnoDB;

INSERT INTO v174_distinct
SELECT
  q.outbound_no,
  q.choose_label,
  -- 三段式客户名解析：choose_label="YYYY-MM-DD     客户名     190,800"
  -- 1) 前后空白 → 2) 连续空白缩为单空格 → 3) 截第一个空格后 + 最后一个空格前
  TRIM(
    SUBSTRING(
      REGEXP_REPLACE(TRIM(q.choose_label), '[[:space:]]+', ' '),
      LOCATE(' ', REGEXP_REPLACE(TRIM(q.choose_label), '[[:space:]]+', ' ')) + 1,
      CHAR_LENGTH(REGEXP_REPLACE(TRIM(q.choose_label), '[[:space:]]+', ' '))
      - LOCATE(' ', REGEXP_REPLACE(TRIM(q.choose_label), '[[:space:]]+', ' '))
      - LOCATE(' ', REVERSE(REGEXP_REPLACE(TRIM(q.choose_label), '[[:space:]]+', ' ')))
    )
  ),
  q.sales_date,
  q.sales_amount,
  q.received_amount,
  q.h3_id,
  q.h3_receipt_id,
  q.owner_name,
  q.quality_score
FROM (
  SELECT
    e.outbound_no,
    e.choose_label,
    e.sales_date,
    e.sales_amount,
    e.received_amount,
    e.h3_id,
    e.h3_receipt_id,
    e.owner_name,
    (CASE WHEN e.choose_label IS NOT NULL AND TRIM(e.choose_label) <> '' THEN 4 ELSE 0 END
     + CASE WHEN e.sales_date IS NOT NULL THEN 2 ELSE 0 END
     + CASE WHEN e.sales_amount IS NOT NULL THEN 1 ELSE 0 END) AS quality_score,
    ROW_NUMBER() OVER (
      PARTITION BY e.outbound_no
      ORDER BY
        (CASE WHEN e.choose_label IS NOT NULL AND TRIM(e.choose_label) <> '' THEN 4 ELSE 0 END
         + CASE WHEN e.sales_date IS NOT NULL THEN 2 ELSE 0 END
         + CASE WHEN e.sales_amount IS NOT NULL THEN 1 ELSE 0 END) DESC,
        e.sales_amount DESC,
        e.row_idx ASC
    ) AS pick_rank
  FROM v174_extracted_rows e
  INNER JOIN v174_missing_nos m ON m.missing_no = e.outbound_no
) q
WHERE q.pick_rank = 1;

SET @v174_distinct_cnt = (SELECT COUNT(*) FROM v174_distinct);

-- ========= Step3: INSERT 补档出库单 =========
INSERT INTO sales_outbounds (
  external_source, external_id, outbound_no, status, outbound_status,
  ship_date, business_date, customer_name, salesperson_name, business_owner,
  sales_amount, received_amount, source_received_amount, received_diff_amount, outstanding_amount,
  settlement_status, payment_status, source_created_at, source_created_by,
  department_name, order_remark, created_at, updated_at
)
SELECT
  'H3_HISTORICAL_RECOVERED',
  CONCAT('V174-RECOVER-', d.outbound_no),
  d.outbound_no,
  'CONFIRMED',
  'CONFIRMED',
  d.sales_date,
  d.sales_date,
  NULLIF(d.parsed_customer_name, ''),
  NULLIF(TRIM(d.owner_name), ''),
  NULLIF(TRIM(d.owner_name), ''),
  COALESCE(d.sales_amount, m.alloc_amount, 0),
  COALESCE(d.received_amount, m.alloc_amount, 0),
  COALESCE(d.received_amount, m.alloc_amount, 0),
  0.00,
  GREATEST(COALESCE(d.sales_amount, m.alloc_amount, 0) - COALESCE(d.received_amount, m.alloc_amount, 0), 0),
  CASE
    WHEN COALESCE(d.sales_amount, m.alloc_amount, 0) <= 0 THEN 'UNSETTLED'
    WHEN GREATEST(COALESCE(d.sales_amount, m.alloc_amount, 0) - COALESCE(d.received_amount, m.alloc_amount, 0), 0) = 0 THEN 'SETTLED'
    ELSE 'PARTIAL'
  END,
  CASE
    WHEN COALESCE(d.sales_amount, m.alloc_amount, 0) <= 0 THEN NULL
    WHEN GREATEST(COALESCE(d.sales_amount, m.alloc_amount, 0) - COALESCE(d.received_amount, m.alloc_amount, 0), 0) = 0 THEN '已付清'
    ELSE '部分付款'
  END,
  NOW(),
  'V174 Flyway Recovery',
  '浙江利强包装科技有限公司',
  CONCAT('[V174补档] 从 h3_import_rows id=', d.h3_id, ' rows[] 明细反建，h3_receipt_id=', d.h3_receipt_id),
  NOW(),
  NOW()
FROM v174_distinct d
JOIN v174_missing_nos m ON m.missing_no = d.outbound_no
WHERE NOT EXISTS (SELECT 1 FROM sales_outbounds so WHERE so.outbound_no COLLATE utf8mb4_unicode_ci = d.outbound_no);

SET @v174_insert_cnt = ROW_COUNT();

-- ========= Step3.5: 合并同 receipt 内相同 outbound_no_snapshot 的重复 allocation 行 =========
-- 唯一键 idx_receipt_alloc_unique=(receipt_id,sales_outbound_id) 的前置清理
-- 策略：每组保留最小id=主行；把重复行金额加到主行；然后 DELETE 重复行
CREATE TEMPORARY TABLE v174_dup_plan (
  receipt_id BIGINT,
  outbound_no VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  primary_id BIGINT,
  dup_ids TEXT,
  dup_cnt INT,
  dup_sum_amount DECIMAL(18,2),
  PRIMARY KEY (receipt_id, outbound_no)
) ENGINE=InnoDB;

INSERT INTO v174_dup_plan
SELECT
  p.receipt_id,
  p.outbound_no,
  p.primary_id,
  p.dup_ids,
  p.dup_cnt,
  COALESCE((
    SELECT SUM(ra.allocated_amount)
    FROM receipt_allocations ra
    WHERE FIND_IN_SET(ra.id, p.dup_ids) > 0
  ), 0)
FROM (
  SELECT
    x.receipt_id,
    x.outbound_no,
    MIN(x.id) AS primary_id,
    SUBSTRING(
      GROUP_CONCAT(x.id ORDER BY x.id SEPARATOR ','),
      LOCATE(',', GROUP_CONCAT(x.id ORDER BY x.id SEPARATOR ',')) + 1
    ) AS dup_ids,
    COUNT(*) - 1 AS dup_cnt
  FROM (
    SELECT ra.id, ra.receipt_id,
           TRIM(ra.outbound_no_snapshot) COLLATE utf8mb4_unicode_ci AS outbound_no
    FROM receipt_allocations ra
    JOIN receipts r ON r.id = ra.receipt_id
    INNER JOIN v174_missing_nos m2
      ON m2.missing_no = TRIM(ra.outbound_no_snapshot) COLLATE utf8mb4_unicode_ci
    WHERE ra.outbound_no_snapshot IS NOT NULL AND TRIM(ra.outbound_no_snapshot) <> ''
      AND ra.sales_outbound_id IS NULL
      AND ra.allocation_origin = 'DIRECT' AND r.legacy_migrated_tag IS NULL
  ) x
  GROUP BY x.receipt_id, x.outbound_no
  HAVING COUNT(*) >= 2
) p;

SET @v174_dup_groups  = (SELECT COUNT(*) FROM v174_dup_plan);
SET @v174_dup_deletes = (SELECT COALESCE(SUM(dup_cnt),0) FROM v174_dup_plan);

-- 3.5.1 主行追加重复行金额
UPDATE receipt_allocations ra
JOIN   v174_dup_plan dp ON dp.primary_id = ra.id
SET    ra.allocated_amount = COALESCE(ra.allocated_amount, 0) + dp.dup_sum_amount;

-- 3.5.2 DELETE 重复行
DELETE ra
FROM receipt_allocations ra
JOIN v174_dup_plan dp ON FIND_IN_SET(ra.id, dp.dup_ids) > 0;

SET @v174_dup_deleted = ROW_COUNT();

-- ========= Step4: 回填 receipt_allocations.sales_outbound_id + REFER_RESOLVED =========
UPDATE receipt_allocations ra
JOIN   sales_outbounds so ON so.outbound_no COLLATE utf8mb4_unicode_ci = TRIM(ra.outbound_no_snapshot)
SET    ra.sales_outbound_id = so.id,
       ra.reference_status  = 'REFER_RESOLVED'
WHERE  ra.sales_outbound_id IS NULL
  AND  ra.outbound_no_snapshot IS NOT NULL AND TRIM(ra.outbound_no_snapshot) <> ''
  AND  ra.allocation_origin = 'DIRECT'
  AND  so.external_source = 'H3_HISTORICAL_RECOVERED';

SET @v174_backfill_cnt = ROW_COUNT();

-- ========= Step5: 补齐 receipts 中 customer_name / salesperson_name 为空的 =========
UPDATE receipts r
JOIN (
  SELECT ra.receipt_id,
         MAX(so.customer_name)    AS inferred_customer_name,
         MAX(so.salesperson_name) AS inferred_salesperson_name
  FROM receipt_allocations ra
  JOIN sales_outbounds so ON so.id = ra.sales_outbound_id
  WHERE so.external_source = 'H3_HISTORICAL_RECOVERED'
  GROUP BY ra.receipt_id
) fix ON fix.receipt_id = r.id
SET
  r.customer_name    = COALESCE(NULLIF(TRIM(r.customer_name), ''),    fix.inferred_customer_name),
  r.salesperson_name = COALESCE(NULLIF(TRIM(r.salesperson_name), ''), fix.inferred_salesperson_name)
WHERE NULLIF(TRIM(r.customer_name), '') IS NULL
   OR NULLIF(TRIM(r.salesperson_name), '') IS NULL;

SET @v174_fix_rcpt_cnt = ROW_COUNT();

-- ========= Step6: 六数一致性校验（和 DryRun v6 完全一致的判断逻辑） =========
SET @v174_recovered_nos = (
  SELECT COUNT(DISTINCT so.outbound_no)
  FROM sales_outbounds so WHERE so.external_source = 'H3_HISTORICAL_RECOVERED'
);
SET @v174_linked_allocs = (
  SELECT COUNT(*)
  FROM receipt_allocations ra
  JOIN sales_outbounds so ON so.id = ra.sales_outbound_id
  WHERE so.external_source = 'H3_HISTORICAL_RECOVERED' AND ra.allocation_origin = 'DIRECT'
);
SET @v174_orphan_allocs = (
  SELECT COUNT(*)
  FROM receipt_allocations ra
  JOIN receipts r ON r.id = ra.receipt_id
  WHERE ra.sales_outbound_id IS NULL
    AND ra.outbound_no_snapshot IS NOT NULL AND TRIM(ra.outbound_no_snapshot) <> ''
    AND ra.allocation_origin = 'DIRECT' AND r.legacy_migrated_tag IS NULL
    AND NOT EXISTS (SELECT 1 FROM sales_outbounds so WHERE so.outbound_no COLLATE utf8mb4_unicode_ci = TRIM(ra.outbound_no_snapshot))
);
SET @v174_sales_sum  = (SELECT ROUND(COALESCE(SUM(sales_amount),0),2)   FROM sales_outbounds WHERE external_source='H3_HISTORICAL_RECOVERED');
SET @v174_rcv_sum    = (SELECT ROUND(COALESCE(SUM(received_amount),0),2) FROM sales_outbounds WHERE external_source='H3_HISTORICAL_RECOVERED');
SET @v174_linked_amt = (
  SELECT ROUND(COALESCE(SUM(ra.allocated_amount),0),2)
  FROM receipt_allocations ra
  JOIN sales_outbounds so ON so.id = ra.sales_outbound_id
  WHERE so.external_source = 'H3_HISTORICAL_RECOVERED' AND ra.allocation_origin = 'DIRECT'
);
SET @v174_after_merge_expected = @v174_exp_alloc - @v174_dup_deletes;

SELECT
  @v174_exp_missing           AS expected_missing_nos,
  @v174_exp_alloc             AS expected_alloc_rows_before_merge,
  @v174_dup_deletes           AS dup_rows_merged_and_deleted,
  @v174_after_merge_expected  AS expected_alloc_rows_after_merge,
  @v174_insert_cnt            AS inserted_outbounds,
  @v174_backfill_cnt          AS backfilled_allocs,
  @v174_recovered_nos         AS recovered_nos,
  @v174_linked_allocs         AS linked_allocs,
  @v174_orphan_allocs         AS remaining_orphans__应为0,
  @v174_fix_rcpt_cnt          AS fixed_receipt_names,
  @v174_sales_sum             AS outbound_sales_total,
  @v174_rcv_sum               AS outbound_received_total,
  @v174_linked_amt            AS linked_allocated_total,
  CASE
    WHEN @v174_orphan_allocs = 0
     AND @v174_recovered_nos = @v174_exp_missing
     AND @v174_linked_allocs = @v174_after_merge_expected
    THEN 'OK'
    ELSE CONCAT('MISMATCH: orphans=', @v174_orphan_allocs,
                ' rec_nos=', @v174_recovered_nos, '/', @v174_exp_missing,
                ' linked=', @v174_linked_allocs, '/', @v174_after_merge_expected)
  END AS v174_recovery_check;

-- ========= 清理临时表 =========
DROP TEMPORARY TABLE IF EXISTS v174_related_receipts;
DROP TEMPORARY TABLE IF EXISTS v174_missing_nos;
DROP TEMPORARY TABLE IF EXISTS v174_extracted_rows;
DROP TEMPORARY TABLE IF EXISTS v174_distinct;
DROP TEMPORARY TABLE IF EXISTS v174_dup_plan;
