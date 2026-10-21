-- ============================================================================
-- product_archive 数据清理脚本（2026-10-20 · 修订版 v4）
--
-- 修订依据：LQ 2026-10-20 审核意见
--   ① 报告与脚本范围不一致（原 ROW_NUMBER 覆盖了所有非 OUTBOUND 类型）→ 已收窄
--   ② 原始快照不能只按存储空间删（有历史告警/来源追溯依赖）→ 改为三步确认 + 逐 external_id 保护
--   ③ 清单数量与报告不一致（Tier2 标 19 实列 27、Tier3 标 25 实列 19）→ 全部改为查询生成，不再硬编码
--   ④ 不应把 h3_import_staging_rows 排除在日常备份之外 → 备份章节已改
--
-- v3 修订（2026-10-20 14:30，Part 1/2 已在真库执行完毕）：
--   ⑤ Part 1 暂存行清理已完成：378,722 行 / 51 批，h3_import_staging_rows → 0（3,070 MB → 0.2 MB）
--   ⑥ Part 2 快照治理已完成：删 192,133 行 / 1,130 MB，h3_import_rows 2,956 → 1,384 MB
--      —— 且已从手写 SQL 抽成服务代码（H3SnapshotCleanupService），本节改为只读核对脚本
--   ⑦ 体积口径纠正：TABLES.DATA_LENGTH 删行后不刷新，唯一可信源是 INNODB_TABLESPACES.FILE_SIZE
--
-- v4 修订（2026-10-20 15:10，Part 3 盘点完成 + 零风险段已执行）：
--   ⑧ Part 3 全量重写：遗留表 32 张逐张精确计数 + 代码引用核查，识别出 2 张「假遗留表」
--      （production_tracking_workbook_archive_rules / product_models_archive）必须剔除
--   ⑨ 纠正错误归类：receipts(156MB)/receipt_allocations(80MB) 不是「空表」，是清空待重导的
--      核心事实表，占的是空洞 → 改用 ALGORITHM=COPY 回收，不是 DROP
--   ⑩ 新增 3-6 探针残留检测（H3SnapshotCleanupService 的持久物化表会残留）
--   ⑪ Part 3 零风险段已执行：删 4 张残留(64MB) + 9 张空洞 COPY 重建(348MB) = 回收 412 MB
--      全库 3,145 → 2,733 MB，9 张表行数 0→0 全部一致
--   ⑫ 顺手修正 Part 0 / Part 5 的表名错误：h3_production_orders 不存在，实际是 production_orders
--
-- 配套文档：
--   docs/db-slimming-audit-20261020.md      （总体审计报告 v2）
--   docs/db-part3-legacy-tables-20261020.md （Part 3 逐表判定 + 可执行语句 + 执行实录）
--
-- 真库体积轨迹（全部 INNODB_TABLESPACES.FILE_SIZE 口径）：
--   11:40 基线 6,777 MB（210 张表）
--   → Part 1 暂存清理 3,070 → 0.2 MB
--   → Part 2 快照治理 2,956 → 1,384 MB
--   14:30 全库 3,145 MB（215 张表）
--   → Part 3 零风险段（删 4 张残留 64MB + 9 张空洞 COPY 重建 348MB）
--   15:05 全库 2,733 MB
--
-- ⚠️ 执行纪律
--   · Part 1 已完成，保留作口径留档；代码已实现同样动作（H3StagingCleanupService）
--   · Part 2 已完成；现为只读核对脚本 + 服务调用说明，不含可执行 DELETE
--   · Part 3 只生成清单 + 已执行零风险段；31 张遗留表的 DROP 语句在配套文档里，
--     不在本脚本内 —— DROP 决策权留给 LQ
--   · Part 4 只生成清单
-- ============================================================================


-- ============================================================================
-- Part 0｜执行前基线（先跑，记下数字，后面每一步都要对照）
-- ============================================================================
SELECT 'base' AS phase,
       (SELECT COUNT(*) FROM h3_import_batches)                                   AS 批次数,
       (SELECT COUNT(*) FROM h3_import_staging_rows)                              AS 暂存行,
       (SELECT COUNT(*) FROM h3_import_rows)                                       AS 快照行,
       (SELECT COUNT(*) FROM h3_import_staging_rows s JOIN h3_import_batches b
          ON b.id = s.batch_id WHERE b.status = 'PREVIEW_READY')                   AS 在途暂存行_必须保留,
       (SELECT COUNT(*) FROM sales_outbounds)                                      AS 出库单,
       (SELECT COUNT(*) FROM purchase_receipts)                                    AS 采购入库单,
       (SELECT COUNT(*) FROM production_orders)                                    AS 生产任务单,
       (SELECT COUNT(*) FROM customers)                                            AS 客户,
       (SELECT ROUND(SUM(FILE_SIZE) / 1024 / 1024, 1)
          FROM INFORMATION_SCHEMA.INNODB_TABLESPACES
         WHERE NAME LIKE 'product_archive/%')                                      AS 总MB_真实;


-- ============================================================================
-- Part 0-B｜备份（⚠️ 不要排除 h3_import_staging_rows）
--
-- 为什么不能排除：这张表里有 PREVIEW_READY（已上传、未确认）的批次数据。
-- 排除后一旦库故障恢复，这些批次无法继续确认，用户当天的工作白做。
-- 正确做法 = 全量备份 + 在途批次单独留一份小备份。
-- ----------------------------------------------------------------------------
-- ① 全量备份（含三张导入表，约 6GB；--single-transaction 不锁表）
-- mysqldump -h127.0.0.1 -uroot -p --single-transaction --default-character-set=utf8mb4 \
--           --routines=false --triggers=false product_archive \
--           > _archive_export/20261020/product_archive_full.sql
--
-- ② 在途批次单独备份（恢复后可直接续做，几十 MB）
--    先取出在途 batch_id 列表：
--      SELECT GROUP_CONCAT(id) FROM h3_import_batches WHERE status IN ('PREVIEW_READY','PARSING','UPLOADED','CONFIRMING');
--    再按 batch_id 条件导出：
-- mysqldump -h127.0.0.1 -uroot -p --single-transaction --no-create-info \
--           --where="batch_id IN (此处填上一步的列表)" product_archive h3_import_staging_rows \
--           > _archive_export/20261020/staging_inflight.sql
--
-- ③ Part 2 执行前，快照表单独留档（这一份才是「删了还能查回来」的凭据）
-- mysqldump -h127.0.0.1 -uroot -p --single-transaction product_archive h3_import_rows \
--           > _archive_export/20261020/h3_import_rows_before_purge.sql
-- ============================================================================


-- ============================================================================
-- Part 1｜Tier 1-1：清理「已终态」批次的暂存行   ✅ 可直接执行
--
-- 判据（与后端 H3StagingCleanupService 完全一致）：
--   1. 批次状态 ∈ (COMPLETED, PARTIAL_FAILED) —— 状态机已禁止再确认，暂存对业务无作用
--   2. preview_json 非空 —— 暂存唯一的残留用途是预览缓存缺失时的回退，preview_json 在则不回退
--   3. PREVIEW_READY / PARSING / UPLOADED / CONFIRMING / FAILED 一律不动
-- 预期：删除约 378,722 行（回收约 2.9 GB，需配合 Part 4 OPTIMIZE 才真正释放磁盘）
-- ============================================================================

-- 1-1-a 先看会删多少（dry run，不写库）
SELECT b.status, COUNT(DISTINCT b.id) AS 批次数, COUNT(s.id) AS 暂存行
  FROM h3_import_batches b
  JOIN h3_import_staging_rows s ON s.batch_id = b.id
 WHERE b.status IN ('COMPLETED', 'PARTIAL_FAILED')
   AND b.preview_json IS NOT NULL AND b.preview_json <> ''
 GROUP BY b.status;

-- 1-1-b 确认「不会被删」的在途批次清单（这几批必须保持原样）
SELECT b.id, b.data_type, b.status, b.total_count, b.created_at, COUNT(s.id) AS 暂存行
  FROM h3_import_batches b
  LEFT JOIN h3_import_staging_rows s ON s.batch_id = b.id
 WHERE b.status NOT IN ('COMPLETED', 'PARTIAL_FAILED')
 GROUP BY b.id, b.data_type, b.status, b.total_count, b.created_at
 ORDER BY b.id;

-- 1-1-c 分批删除（存储过程，每批 5000 行，避免长事务锁表）
--       想先试算就把 CALL 的第二个参数设为 1
DROP PROCEDURE IF EXISTS sp_purge_staging_terminal;
DELIMITER $$
CREATE PROCEDURE sp_purge_staging_terminal(IN p_chunk INT, IN p_dry_run TINYINT)
BEGIN
    DECLARE v_id BIGINT;
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_rows INT DEFAULT 0;
    DECLARE v_total INT DEFAULT 0;
    DECLARE v_batches INT DEFAULT 0;
    DECLARE cur CURSOR FOR
        SELECT b.id
          FROM h3_import_batches b
         WHERE b.status IN ('COMPLETED', 'PARTIAL_FAILED')
           AND b.preview_json IS NOT NULL AND b.preview_json <> ''
           AND EXISTS (SELECT 1 FROM h3_import_staging_rows s WHERE s.batch_id = b.id)
         ORDER BY b.id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_id;
        IF v_done = 1 THEN LEAVE read_loop; END IF;
        SET v_batches = v_batches + 1;
        IF p_dry_run = 1 THEN
            SELECT COUNT(*) INTO v_rows FROM h3_import_staging_rows WHERE batch_id = v_id;
            SET v_total = v_total + v_rows;
        ELSE
            del_loop: LOOP
                DELETE FROM h3_import_staging_rows WHERE batch_id = v_id LIMIT p_chunk;
                SET v_rows = ROW_COUNT();
                SET v_total = v_total + v_rows;
                IF v_rows < p_chunk THEN LEAVE del_loop; END IF;
            END LOOP;
        END IF;
    END LOOP;
    CLOSE cur;

    SELECT v_batches AS 处理批次数, v_total AS 影响行数, p_dry_run AS 是否试算;
END$$
DELIMITER ;

-- 先试算（不删）
CALL sp_purge_staging_terminal(5000, 1);

-- 确认无误后正式执行
-- CALL sp_purge_staging_terminal(5000, 0);

-- 用完清理
-- DROP PROCEDURE IF EXISTS sp_purge_staging_terminal;

-- 1-1-d 执行后核对：暂存行应 = 在途批次行数（本机基线 28,794）
SELECT (SELECT COUNT(*) FROM h3_import_staging_rows) AS 剩余暂存行,
       (SELECT COUNT(*) FROM h3_import_staging_rows s JOIN h3_import_batches b
          ON b.id = s.batch_id WHERE b.status = 'PREVIEW_READY') AS 在途暂存行,
       (SELECT COUNT(*) FROM h3_import_rows) AS 快照行_本次不应变;

-- 1-1-e 验证「预览仍可打开」：任取一个 COMPLETED 批次，preview_json 必须还在
--       A 域（业务事实）与 B 域（产品信息/仓库明细/经销商台账）两边的预览端点
--       都只读 h3_import_batches.preview_json，不读暂存表，所以本判据同时保护两边。
SELECT id, data_type, status, total_count, CHAR_LENGTH(preview_json) AS 预览JSON长度
  FROM h3_import_batches
 WHERE status = 'COMPLETED'
 ORDER BY id DESC LIMIT 5;

-- 1-1-f B 域专项：确认三类台账批次的 preview_json 都在
SELECT data_type, COUNT(*) AS 批次数,
       SUM(CHAR_LENGTH(preview_json) IS NULL OR preview_json = '') AS 缺预览批次数
  FROM h3_import_batches
 WHERE status = 'COMPLETED' AND data_type IN ('PRODUCT_INFO', 'WAREHOUSE_STOCK', 'DEALER')
 GROUP BY data_type;


-- ============================================================================
-- Part 2｜Tier 1-2：原始快照治理   ✅ 已于 2026-10-20 落地为服务代码
--
-- ⚠️ 本节不再提供可手工执行的 DELETE 脚本。原因：本节 v1 的写法（NOT IN 嵌套子查询）
--    在真库实测单块 32.8s，24 万行需跑 20 分钟；且「手工拼接批次 id」极易漏掉保护条件。
--    现已抽成 H3SnapshotCleanupService（backend/.../service/H3SnapshotCleanupService.java），
--    由导入确认后自动调用 pruneAfterConfirm(batchId)，运维全量治理调 pruneAll(dryRun)。
--
-- 最终口径（LQ 2026-10-20 定 A 档）：
--   · 处理范围：只 PURCHASE_IN / PRODUCTION；OUTBOUND 一律不动
--     （OUTBOUND 的 7 个批次是 2023.1~2025.12 不同时段切片，不是重复，删了断来源批次）
--   · 两条保护缺一不可：
--       保护一：每个 external_id 在全表的 MAX(id) 那一条，永久保留
--               —— H3FactImportService#loadPreviousSnapshots 靠它做新旧字段对照
--               反例：生产任务单 0e4b5337-d534-411f-9035-bffbec7a3706 的最新快照只存在于
--                     2026-10-12 的批 253，此后每天重导的文件里再没出现（氚云侧已删）。
--                     只按「保留最近 N 批」删，这条唯一历史会永久丢失。
--       保护二：每类最近 N 个终态批次整批保留（N=h3.import.snapshot-cleanup.keep-batches，默认 3）
--               —— H3FactImportService#listBatchWarnings 要读整批 raw_data 重算告警明细
--   · 可清理状态：仅 COMPLETED / PARTIAL_FAILED（终态）。在途批次（PREVIEW_READY 等）一律不动。
--   · 台账「更新历史」不受影响：H3LedgerRowHistoryService 只读 h3_import_batches.preview_json，
--     changes[{path,oldValue,newValue}] 是导入当时算好的，不依赖 h3_import_rows。
--
-- 配置开关（application.yml → h3.import.snapshot-cleanup）：
--   enabled:       ${H3_SNAPSHOT_CLEANUP_ENABLED:true}
--   keep-batches:  ${H3_SNAPSHOT_KEEP_BATCHES:3}
--
-- 2026-10-20 实际执行结果（A 档，全库治理一次）：
--   扫描 244,371 行 → 删 192,133 行（raw_data 1,130 MB）→ 保留 52,238 行
--   保护一救下 1 行（即上面那张 0e4b5337）；删除后「一条不剩」的单据数 = 0
--   h3_import_rows 物理体积 2,956 MB → 1,384 MB（ALGORITHM=COPY 重建）
--   业务数据零变动：出库 109,379 / 采购入库 11,399 / 生产任务单 6,030 / 客户 48,871
--   待删 id 清单留档：_archive_export/20261020/snapshot_prune_ids_*.csv
--
-- 运维入口（推荐）：调用服务
--   H3SnapshotCleanupService#pruneAll(true)   // dryRun：只报告 扫描/待删/保留
--   H3SnapshotCleanupService#pruneAll(false)  // 真删，分批 5000，全程异常吞掉不影响业务
--
-- ⚠️ 执行前必看的三项安全断言（任一不为 0 禁止删除）：
--   [A] 待删集 ∩ 每单最新一条          必须 = 0
--   [B] 待删集 ∩ 保护名单缺失（join 失效）必须 = 0
--   [C] 待删集 ∩ 最近 N 批              必须 = 0
--   另需 [D] 删除后「一条不剩」的单据数  必须 = 0
--
-- ---------------------------------------------------------------------------
-- Step A｜核对：这两类到底是不是「同一份文件反复全量导」
-- ---------------------------------------------------------------------------
SELECT b.id, b.data_type, b.file_name, b.file_size, b.total_count,
       b.created_count, b.updated_count, b.unchanged_count,
       b.created_at, b.completed_at
  FROM h3_import_batches b
 WHERE b.data_type IN ('PURCHASE_IN', 'PRODUCTION')
 ORDER BY b.data_type, b.id;

-- Step A2｜确认这两类快照确实没被生产跟单消费（本机实测：9 个 ProductionTracking* 服务零引用 h3_import_rows）
--   如果将来跟单要基于「原始导入快照」做工序变化追踪，本 Part 必须整体作废。
--   验证方法：在 backend/src/main/java 下搜 h3_import_rows，只应命中导入/台账/提成 4 类服务。

-- ---------------------------------------------------------------------------
-- Step B｜只读核对（口径与服务完全一致，可直接粘进 mysql 比对 pruneAll(true) 的数字）
-- 用会话级临时表物化两张保护名单，避免重复引用触发
-- ERROR 1137 Can't reopen table（同一临时表在一次查询里只能引用一次）
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS _keep_latest;
DROP TEMPORARY TABLE IF EXISTS _keep_batch;

-- 保护一：每单最新一条。⚠️ 刻意不带 h3_import_batches 的 join
--   （带上会退化成 43.8s，用不上 idx_h3ir_external 覆盖索引；纯 GROUP BY 仅 2.2s）
CREATE TEMPORARY TABLE _keep_latest (
  eid VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NOT NULL PRIMARY KEY,
  latest_id BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO _keep_latest(eid, latest_id)
SELECT external_id, MAX(id) FROM h3_import_rows
 WHERE external_id IS NOT NULL GROUP BY external_id;

-- 保护二：每类最近 N 个终态批次（N=3，与 application.yml 的 keep-batches 保持一致）
CREATE TEMPORARY TABLE _keep_batch (batch_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO _keep_batch(batch_id)
SELECT id FROM (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY data_type ORDER BY id DESC) rn
    FROM h3_import_batches
   WHERE data_type IN ('PURCHASE_IN','PRODUCTION')
     AND status IN ('COMPLETED','PARTIAL_FAILED')
) t WHERE t.rn <= 3;

-- B1 扫描口径：受管类型 + 终态批次的快照总行
SELECT COUNT(*) AS 扫描行数
  FROM h3_import_rows r JOIN h3_import_batches b ON b.id = r.batch_id
 WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION')
   AND b.status IN ('COMPLETED','PARTIAL_FAILED');

-- B2 待删统计（与服务 TARGET_SQL 一字不差）
SELECT b.data_type,
       COUNT(*)                                       AS 待删行数,
       ROUND(SUM(LENGTH(r.raw_data))/1024/1024, 1)    AS 待删MB
  FROM h3_import_rows r
  JOIN h3_import_batches b ON b.id = r.batch_id
  LEFT JOIN _keep_latest m ON m.eid = r.external_id
  LEFT JOIN _keep_batch  kb ON kb.batch_id = r.batch_id
 WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION')
   AND b.status IN ('COMPLETED','PARTIAL_FAILED')
   AND kb.batch_id IS NULL
   AND (m.latest_id IS NULL OR r.id < m.latest_id)
 GROUP BY b.data_type;

-- B3 安全断言（三条全部必须为 0）
SELECT
  (SELECT COUNT(*) FROM h3_import_rows r
     JOIN h3_import_batches b ON b.id = r.batch_id
     JOIN _keep_latest m ON m.eid = r.external_id
    WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION')
      AND b.status IN ('COMPLETED','PARTIAL_FAILED')
      AND m.latest_id = r.id
      AND r.batch_id NOT IN (SELECT batch_id FROM _keep_batch)
      AND r.id < m.latest_id)                        AS A_待删含最新_必须0,
  (SELECT COUNT(*) FROM h3_import_rows r
     JOIN h3_import_batches b ON b.id = r.batch_id
     JOIN _keep_batch k ON k.batch_id = r.batch_id
    WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION')
      AND b.status IN ('COMPLETED','PARTIAL_FAILED')
      AND r.id < (SELECT latest_id FROM _keep_latest x WHERE x.eid = r.external_id)
   )                                                  AS C_待删含保留批_必须0;

-- 2026-10-20 真库实跑：扫描 244,371 / 待删 192,133（1,130 MB）/ 保留 52,238；三项断言全 0

-- ---------------------------------------------------------------------------
-- Step C｜执行 —— 已改为调用服务，不再手写 DELETE
--   H3SnapshotCleanupService#pruneAll(false)  分批 5000，异常自吞，不阻塞业务
--   若必须纯 SQL（如服务不可用），照抄 B2 的 FROM/WHERE 做分批：
-- ---------------------------------------------------------------------------
-- 逐批删（重复执行直到 影响行数 < 5000）：
-- INSERT INTO `_h3sc_prune_ids`(id)
-- SELECT r.id FROM h3_import_rows r
--   JOIN h3_import_batches b ON b.id = r.batch_id
--   LEFT JOIN _keep_latest m ON m.eid = r.external_id
--   LEFT JOIN _keep_batch  kb ON kb.batch_id = r.batch_id
--  WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION')
--    AND b.status IN ('COMPLETED','PARTIAL_FAILED')
--    AND kb.batch_id IS NULL
--    AND (m.latest_id IS NULL OR r.id < m.latest_id)
--  LIMIT 5000;
-- DELETE FROM h3_import_rows WHERE id IN (SELECT id FROM `_h3sc_prune_ids`);
-- DELETE FROM `_h3sc_prune_ids`;
--   ⚠️ 必须先物化 id 再删，直接 DELETE ... WHERE id IN (子查询读同表) 会报 ERROR 1093。

-- Step D｜执行后核对
-- SELECT b.data_type, COUNT(DISTINCT b.id) AS 批次数, COUNT(r.id) AS 剩余快照行
--   FROM h3_import_batches b LEFT JOIN h3_import_rows r ON r.batch_id = b.id
--  WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION')
--  GROUP BY b.data_type ORDER BY b.data_type;
--
-- ⚠️ 体积要看 INNODB_TABLESPACES.FILE_SIZE，不要看 TABLES.DATA_LENGTH
--    （后者在删行后不刷新，2026-10-20 实测删完 37 万行仍显示 3,069 MB，差点误判清理失败）
-- SELECT NAME, ROUND(FILE_SIZE/1048576,1) AS mb
--   FROM INFORMATION_SCHEMA.INNODB_TABLESPACES
--  WHERE NAME = 'product_archive/h3_import_rows';

-- Step E｜收尾（临时表；持久化的 id 清单表留作审计，人工确认后再 DROP）
DROP TEMPORARY TABLE IF EXISTS _keep_latest;
DROP TEMPORARY TABLE IF EXISTS _keep_batch;

-- Step F｜回收物理空间
-- ⚠️ OPTIMIZE TABLE / ALTER TABLE ... ENGINE=InnoDB 在已是 InnoDB 的表上是空转（实测 0.3s 无变化）
--    真正收缩必须走 COPY 重建：
-- ALTER TABLE h3_import_rows ROW_FORMAT=DYNAMIC, ALGORITHM=COPY;
--    2026-10-20 实测：17s，2,956 MB → 1,384 MB


-- ============================================================================
-- Part 3｜遗留表 / 空表 / 空洞表治理清单（只生成，不含任何 DROP —— 决策权留给 LQ）
--
-- 详细判定见 docs/db-part3-legacy-tables-20261020.md，本节只放可执行核对 SQL。
--
-- 真库实测（2026-10-20 逐张 COUNT(*) 精确计数，不是 TABLE_ROWS 估算）：
--   命名可疑的遗留表   32 张 / 213 MB —— 其中 2 张是「假遗留」，必须剔除（见 3-1）
--   精确 0 行的空表    81 张         —— 但一张都不建议 DROP（多数是有代码引用的活表）
--   空洞表             11 张 / 340 MB —— 表里没数据，但文件仍占地方，值得 COPY 重建
--
-- ⚠️ 口径纠正（原报告写错了）：
--   receipts(156MB) / receipt_allocations(80MB) 被归进「83 张空表」，暗示可清理。
--   实际上它们是结算台账的**核心事实表**（SettlementLedgerService 等 14+ 处 SQL），
--   0 行只是「已清空、待重导」。占的 236 MB 是空洞，正确处置是 COPY 重建，不是 DROP。
--   同类的还有 h3_reset_identity_snapshots(25MB) / h3_production_reports(21MB)。
--
-- ⚠️ 两张名字像垃圾、实际是活表的（任何按 %_archive 批量匹配都会误杀）：
--   production_tracking_workbook_archive_rules —— ProductionTrackingService 的 INSERT/UPDATE/SELECT + 审计
--   product_models_archive                    —— ArchiveScheduler 每晚 02:00 写入的归档目标表
-- ============================================================================

-- 3-1 遗留表全清单（精确行数需另外 COUNT，这里先给体积与建表时间）
SELECT t.TABLE_NAME AS 表名,
       t.TABLE_ROWS AS 估算行数_不可信,
       ROUND(IFNULL(s.FILE_SIZE,0) / 1048576, 2) AS 真实MB,
       t.CREATE_TIME AS 建表时间
  FROM information_schema.TABLES t
  LEFT JOIN INFORMATION_SCHEMA.INNODB_TABLESPACES s
         ON s.NAME = CONCAT('product_archive/', t.TABLE_NAME)
 WHERE t.TABLE_SCHEMA = 'product_archive'
   AND t.TABLE_TYPE = 'BASE TABLE'
   AND (t.TABLE_NAME REGEXP '^(bak|backup|old|legacy|tmp|temp)_'
     OR t.TABLE_NAME REGEXP '_(bak|backup|old|legacy|tmp|temp|before)$'
     OR t.TABLE_NAME REGEXP '_(bak|backup|old|legacy|before|archive|copy)_'
     OR t.TABLE_NAME REGEXP '^archive_')
   -- ⛔ 假遗留：剔除后 30 张（含 product_models_archive）
   AND t.TABLE_NAME NOT IN ('production_tracking_workbook_archive_rules')
 ORDER BY IFNULL(s.FILE_SIZE,0) DESC;

-- 3-2 外键检查：待删表若被其它表引用，DROP 会失败（先解外键 / 先删子表）
SELECT TABLE_NAME AS 子表, COLUMN_NAME AS 列, CONSTRAINT_NAME AS 外键名,
       REFERENCED_TABLE_NAME AS 被引用表
  FROM information_schema.KEY_COLUMN_USAGE
 WHERE TABLE_SCHEMA = 'product_archive'
   AND REFERENCED_TABLE_NAME IS NOT NULL
   AND REFERENCED_TABLE_NAME REGEXP '(archive_|_before_|_legacy_|^bak_)'
 ORDER BY REFERENCED_TABLE_NAME, TABLE_NAME;
-- 2026-10-20 实测只有一条：production_followup_logs_archive_20261007.fk_pfl_record
--   → 必须先 DROP 子表 production_followup_logs_archive_20261007，再 DROP 父表 production_followup_records_archive_20261007

-- 3-3 空表清单（TABLE_ROWS=0 的 88 张，精确复核后真空表 81 张）
--     ⚠️ 空 ≠ 可删。本节 78 张全部不建议 DROP，理由见报告第三节。
--     只有第一节列出的「遗留表」才是候选。
SELECT t.TABLE_NAME AS 表名, ROUND(IFNULL(s.FILE_SIZE,0) / 1048576, 2) AS 真实MB
  FROM information_schema.TABLES t
  LEFT JOIN INFORMATION_SCHEMA.INNODB_TABLESPACES s
         ON s.NAME = CONCAT('product_archive/', t.TABLE_NAME)
 WHERE t.TABLE_SCHEMA = 'product_archive'
   AND t.TABLE_TYPE = 'BASE TABLE'
   AND t.TABLE_ROWS = 0
 ORDER BY IFNULL(s.FILE_SIZE,0) DESC;

-- 3-4 空洞表（精确 0 行但文件仍占地方）—— 这批才是「白占空间」，用 COPY 重建回收
--     2026-10-20 实测合计约 340 MB，全部零业务风险（不动数据、不删表）
SELECT t.TABLE_NAME AS 表名,
       ROUND(IFNULL(s.FILE_SIZE,0) / 1048576, 2) AS 真实MB,
       ROUND(t.DATA_LENGTH / 1048576, 2) AS 有效数据MB,
       ROUND((IFNULL(s.FILE_SIZE,0) - t.DATA_LENGTH - t.INDEX_LENGTH) / 1048576, 2) AS 空洞MB
  FROM information_schema.TABLES t
  JOIN INFORMATION_SCHEMA.INNODB_TABLESPACES s
    ON s.NAME = CONCAT('product_archive/', t.TABLE_NAME)
 WHERE t.TABLE_SCHEMA = 'product_archive'
   AND t.TABLE_TYPE = 'BASE TABLE'
   AND s.FILE_SIZE > 10 * 1048576
   AND (IFNULL(s.FILE_SIZE,0) - t.DATA_LENGTH - t.INDEX_LENGTH) > 5 * 1048576
 ORDER BY (IFNULL(s.FILE_SIZE,0) - t.DATA_LENGTH - t.INDEX_LENGTH) DESC;

-- 3-5 本地制造域（LQ 明确：等新的生产管理结构稳定后再决定，本轮不动）
--        manufacturing_orders / manufacturing_order_outputs / manufacturing_completion_receipt_items
--        finished_goods_lots / finished_goods_movements
--        material_items / material_inventory_balances / material_inventory_transactions
SELECT TABLE_NAME AS 本地制造域表, TABLE_ROWS AS 行数,
       ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS MB
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'product_archive'
   AND TABLE_NAME IN ('manufacturing_orders', 'manufacturing_order_outputs',
                      'manufacturing_completion_receipt_items', 'finished_goods_lots',
                      'finished_goods_movements', 'material_items',
                      'material_inventory_balances', 'material_inventory_transactions');

-- 3-6 探针残留表（H3SnapshotCleanupService 的持久物化表 / 我历次跑探针留下的）
--     ⚠️ 根因：buildKeepTables() 建的是**持久表**（不是 TEMPORARY，因为 Hikari 跨连接删不掉），
--        靠 finally 里的 dropKeepTables() 兜底；进程被 kill 时会留下。
--     实测残留：_t_latest(16MB) / _t_keepbatch(0.11MB) / _h3sc_prune_20261020_ids(32MB)
--     可直接 DROP（CSV 已留档 _archive_export/20261020/snapshot_prune_ids_20261020-143037.csv）
SELECT SUBSTRING_INDEX(s.NAME,'/',-1) AS 表名,
       ROUND(s.FILE_SIZE / 1048576, 2) AS 真实MB,
       t.CREATE_TIME AS 建表时间
  FROM INFORMATION_SCHEMA.INNODB_TABLESPACES s
  LEFT JOIN information_schema.TABLES t
         ON t.TABLE_SCHEMA = 'product_archive'
        AND t.TABLE_NAME = SUBSTRING_INDEX(s.NAME,'/',-1)
 WHERE s.NAME LIKE 'product_archive/\_%'
 ORDER BY s.FILE_SIZE DESC;


-- ============================================================================
-- Part 4｜空间回收（维护窗口执行；结构不动，只重建表释放空洞）
--
-- ⚠️ 只有「确实删过行 / 清空过」的表才有回收价值。OPTIMIZE 会重建表并锁表，
--    单表几十 MB 也要数秒，receipt_allocations 这种 69MB 的尤其明显。
--    先跑 4-1 看真实空洞，再决定 4-2 跑哪些。
-- ============================================================================

-- 4-1 空洞检测：DATA_FREE > 10MB 的表才值得 OPTIMIZE
SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 行数,
       ROUND(DATA_LENGTH / 1024 / 1024, 2) AS 数据MB,
       ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS 索引MB,
       ROUND(DATA_FREE / 1024 / 1024, 2) AS 空洞MB
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'product_archive'
   AND DATA_FREE > 10 * 1024 * 1024
 ORDER BY DATA_FREE DESC;

-- 4-2 生成 OPTIMIZE 语句（复制结果逐条执行，不要一次性跑一串）
SELECT CONCAT('OPTIMIZE TABLE `', TABLE_NAME, '`;') AS 建议语句,
       ROUND(DATA_FREE / 1024 / 1024, 2) AS 预期回收MB
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = 'product_archive'
   AND DATA_FREE > 10 * 1024 * 1024
 ORDER BY DATA_FREE DESC;

-- 4-3 三张导入表（Part 1 删完后必须 OPTIMIZE 才能真正释放磁盘）
-- OPTIMIZE TABLE h3_import_staging_rows;
-- OPTIMIZE TABLE h3_import_rows;


-- ============================================================================
-- Part 5｜执行后总核对
-- ============================================================================
SELECT 'after' AS phase,
       (SELECT COUNT(*) FROM h3_import_batches)                    AS 批次数_不应变,
       (SELECT COUNT(*) FROM h3_import_staging_rows)               AS 暂存行_应≈在途数,
       (SELECT COUNT(*) FROM h3_import_rows)                       AS 快照行,
       (SELECT COUNT(*) FROM sales_outbounds)                      AS 出库单_不应变,
       (SELECT COUNT(*) FROM purchase_receipts)                    AS 采购入库单_不应变,
       (SELECT COUNT(*) FROM production_orders)                    AS 生产任务单_不应变,
       (SELECT COUNT(*) FROM customers)                            AS 客户_不应变,
       (SELECT ROUND(SUM(FILE_SIZE) / 1024 / 1024, 1)
          FROM INFORMATION_SCHEMA.INNODB_TABLESPACES
         WHERE NAME LIKE 'product_archive/%')                       AS 总MB_真实;
