package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 氚云导入原始快照（{@code h3_import_rows}）生命周期管理（2026-10-20 建立，与 LQ 确认 A 档）。
 *
 * <p><b>为什么要有这个类</b>：{@link H3StagingCleanupService} 修的是暂存表，但快照表同样只增不减。
 * 用户每天从氚云导出 Excel 全量重导（采购入库/生产任务单一天多次），每批都落一份完整 raw_data，
 * 单张生产任务单最多被导入 21 次。实测该表 346,694 行 / 2,956MB，是清理后全库最大单表。
 *
 * <p><b>快照有 4 个消费者，口径必须逐个对齐</b>（读代码确认，勿凭印象改）：
 * <table border="1">
 *   <caption>h3_import_rows 的消费方与保护要求</caption>
 *   <tr><th>消费者</th><th>读什么</th><th>本类如何保护</th></tr>
 *   <tr><td>{@code H3FactImportService#loadPreviousSnapshots}</td>
 *       <td>每 external_id 的 <b>MAX(id)</b> 一条 raw_data，做「新旧字段对照」</td>
 *       <td><b>保护一</b>：每个 external_id 最新一条永久保留</td></tr>
 *   <tr><td>{@code H3FactImportService#listBatchWarnings}</td>
 *       <td>某批次 <b>全部</b> raw_data 重算告警明细</td>
 *       <td><b>保护二</b>：最近 N 个终态批次整批保留（告警页只对近期批次有意义）</td></tr>
 *   <tr><td>{@code CommissionCalculationService#resolveOutboundImportBatches}</td>
 *       <td>出库单最新一条的 batch_id + business_id</td>
 *       <td>保护一已覆盖；且本类<b>不处理 OUTBOUND</b>（历史切片非重复，删了断来源批次）</td></tr>
 *   <tr><td>{@code H3LedgerRowHistoryService}（台账更新历史）</td>
 *       <td><b>不读本表</b>，只读 {@code h3_import_batches.preview_json}</td>
 *       <td>无需保护——这是 LQ 问「更新历史是否依赖快照」的答案：<b>不依赖</b></td></tr>
 * </table>
 *
 * <p><b>处理范围</b>：只处理 {@code PURCHASE_IN} / {@code PRODUCTION}——这两类是「同一份文件反复全量重导」，
 * 快照无追溯价值。{@code OUTBOUND} 的 7 个批次是 2023.1~2025.12 <b>不同时段切片</b>，不是重复，一律不动。
 *
 * <p><b>⚠️ 为什么「保每单最新一条」不能省</b>：实测生产任务单有 1 张单
 * （{@code 0e4b5337-d534-411f-9035-bffbec7a3706}）只出现在 2026-10-12 的批次 253 里，
 * 之后每天重导的文件里<b>再没出现过</b>（氚云侧已删或在当前范围外）。
 * 若只按「保留最近 N 批」删，这条唯一的历史就永久丢失。两个保护缺一不可。
 *
 * <p><b>性能口径（本机实测 24 万行，别改回嵌套子查询）</b>：
 * <pre>
 *   ❌ NOT IN (SELECT MAX(id) ... GROUP BY external_id) 嵌套子查询 → 单块 32.8s（每块重算整份名单）
 *   ❌ 物化名单时 JOIN h3_import_batches 再 GROUP BY        → 43.8s（用不上 idx_h3ir_external 覆盖索引）
 *   ✅ 物化名单时纯 GROUP BY external_id（不带 join）       →  2.2s
 *   ✅ 分批候选：LEFT JOIN 两张小表 + 判 NULL               →  单块 0.39s
 * </pre>
 * 全库 `pruneAll` 总耗时约 10 秒（含一次 6.7s 的全表计数用于对账），是运维动作、不在导入路径上；
 * 导入路径只走 {@link #pruneAfterConfirm(long)}，按批内单据收窄，几千行量级。
 *
 * <p><b>失败语义</b>：与暂存清理一致——纯优化动作，任何异常只记日志、绝不向上抛出。
 */
@Service
@RequiredArgsConstructor
public class H3SnapshotCleanupService {

    private static final Logger log = LoggerFactory.getLogger(H3SnapshotCleanupService.class);

    /** 单次 DELETE 行数上限；分批避免长事务把元数据锁拖死（同 loadPreviousSnapshots 的教训）。 */
    private static final int DELETE_CHUNK = 5000;

    /**
     * ⚠️ 临时表的字符串列必须显式声明与业务表同款 collation。
     * 业务表是 {@code utf8mb4_0900_ai_ci}（MySQL 8.0+ 默认）；若误写成 {@code utf8mb4_unicode_ci}，
     * JOIN external_id 会直接报「Illegal mix of collations」。
     */
    private static final String COLLATION = "utf8mb4_0900_ai_ci";

    /** 处理范围：只碰这两类反复全量重导的数据。⚠️ 加类型前先读完类注释的消费者表格。 */
    private static final List<String> MANAGED_TYPES = List.of("PURCHASE_IN", "PRODUCTION");

    /** 可清理的批次状态：终态。在途批次（PREVIEW_READY 等）的快照一律不动。 */
    private static final String TERMINAL_STATUS_SQL = "'COMPLETED','PARTIAL_FAILED'";

    private static final String MANAGED_IN_SQL =
            String.join(",", MANAGED_TYPES.stream().map(t -> "'" + t + "'").toList());

    /** 受管类型 + 终态批次的快照行（扫描/对账口径）。 */
    private static final String SCOPED_ROWS_SQL =
            " FROM h3_import_rows r JOIN h3_import_batches b ON b.id = r.batch_id"
                    + " WHERE b.data_type IN (" + MANAGED_IN_SQL + ")"
                    + "   AND b.status IN (" + TERMINAL_STATUS_SQL + ")";

    /** 待删口径：不在保护名单一（每单最新一条）且不在保护名单二（最近 N 批）。 */
    private static final String TARGET_SQL =
            " FROM h3_import_rows r"
                    + " JOIN h3_import_batches b ON b.id = r.batch_id"
                    + " LEFT JOIN `_h3sc_latest` m ON m.eid = r.external_id"
                    + " LEFT JOIN `_h3sc_keep`   kb ON kb.batch_id = r.batch_id"
                    + " WHERE b.data_type IN (" + MANAGED_IN_SQL + ")"
                    + "   AND b.status IN (" + TERMINAL_STATUS_SQL + ")"
                    + "   AND kb.batch_id IS NULL"
                    + "   AND (m.latest_id IS NULL OR r.id < m.latest_id)";

    /** 全局开关：默认开启。 */
    @Value("${h3.import.snapshot-cleanup.enabled:true}")
    private boolean enabled;

    /** 每类保留最近多少个终态批次（整批保留，保证批次告警明细仍可查）。 */
    @Value("${h3.import.snapshot-cleanup.keep-batches:3}")
    private int keepBatches;

    private final JdbcTemplate jdbc;

    /** 治理结果（供日志与运维对账，不参与业务逻辑）。 */
    public record CleanupResult(int deletedRows, int keptRows, int scannedRows) {}

    /**
     * 自动清理：某批次确认后，清理<b>本批涉及的单据</b>在更早批次里的多余快照。
     *
     * <p>判据：本批出现的每个 external_id，只保留它在全表的 MAX(id) 那一条；
     * 比它更早的同单快照可删。这样「下次导入的新旧字段对照」永远有值可用。
     *
     * <p>范围收窄到这里的原因是成本：全库口径要物化 12 万张单的保护名单，挂在每天十几次的
     * 导入确认后并不划算；本批只涉及几千张单，走 idx_h3ir_external 很快。
     *
     * @return 删除行数
     */
    public int pruneAfterConfirm(long batchId) {
        if (!enabled) return 0;
        try {
            List<String> types = jdbc.query(
                    "SELECT data_type FROM h3_import_batches WHERE id=?", (rs, n) -> rs.getString(1), batchId);
            if (types.isEmpty() || !MANAGED_TYPES.contains(types.getFirst())) {
                return 0; // OUTBOUND 等不在治理范围，直接跳过
            }
            // 子查询取「本批的单据 → 各自最新 id」，外层只删比它更早的行
            String sql = """
                    FROM h3_import_rows r
                    JOIN h3_import_batches b ON b.id = r.batch_id
                    JOIN (SELECT external_id AS eid, MAX(id) AS latest_id
                            FROM h3_import_rows
                           WHERE external_id IN (SELECT external_id FROM h3_import_rows WHERE batch_id = ?)
                           GROUP BY external_id) m
                      ON m.eid = r.external_id
                   WHERE b.data_type = ?
                     AND b.status IN (%s)
                     AND r.id < m.latest_id
                    """.formatted(TERMINAL_STATUS_SQL);
            int deleted = deleteByTarget(sql, List.of(batchId, types.getFirst()));
            if (deleted > 0) {
                log.info("[快照治理] 批次 {}（{}）确认后清理更早重复快照 {} 行", batchId, types.getFirst(), deleted);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("[快照治理] 批次 {} 清理失败（不影响导入）：{}", batchId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 运维批处理：按全库口径治理历史存量（Tier 1-2 入口，取代手写 SQL 脚本）。
     *
     * <p>待删 = 受管类型 ∧ 终态批次 ∧ 非该 external_id 最新一条 ∧ 不在最近 N 批之内。
     *
     * @param dryRun true 时只统计不删除
     * @return deletedRows=待删/已删行数，keptRows=保留行数，scannedRows=候选总行数
     */
    public CleanupResult pruneAll(boolean dryRun) {
        if (!enabled) return new CleanupResult(0, 0, 0);
        try {
            buildKeepTables();
            int scanned = count("SELECT COUNT(*)" + SCOPED_ROWS_SQL);
            int targets = count("SELECT COUNT(*)" + TARGET_SQL);
            int kept = Math.max(scanned - targets, 0);

            if (dryRun) {
                log.info("[快照治理][dryRun] {}：扫描 {} 行，可删 {} 行，保留 {} 行",
                        MANAGED_TYPES, scanned, targets, kept);
                return new CleanupResult(targets, kept, scanned);
            }
            int deleted = targets == 0 ? 0 : deleteByTarget(TARGET_SQL, List.of());
            log.info("[快照治理] {}：扫描 {} 行，已删 {} 行，保留 {} 行（每单最新一条 + 最近 {} 批）",
                    MANAGED_TYPES, scanned, deleted, kept, keepBatches);
            return new CleanupResult(deleted, kept, scanned);
        } catch (Exception e) {
            log.warn("[快照治理] 全量治理失败：{}", e.getMessage(), e);
            return new CleanupResult(0, 0, 0);
        } finally {
            dropKeepTables();
        }
    }

    /**
     * 物化保护名单（<b>持久表</b>，靠 {@link #dropKeepTables()} 在 finally 兜底清理）：
     * <ul>
     *   <li>{@code _h3sc_latest}：每个 external_id 在全表的最新 id；</li>
     *   <li>{@code _h3sc_keep}：每类最近 {@code keepBatches} 个终态批次。</li>
     * </ul>
     * ⚠️ latest 表刻意<b>不带</b> h3_import_batches 的 join：带了就会退化成 43.8s（实测），
     * 不带才走 idx_h3ir_external 覆盖索引（2.2s）。全表取每单最新一条在语义上也更正确。
     *
     * <p>⚠️ <b>为什么不用 TEMPORARY 表</b>：{@code pruneAll} 会被运维从任意 HTTP 线程调用，
     * 而 MySQL 临时表是<b>连接级</b>的——Hikari 连接池里建表与删表若落在不同连接，
     * {@code DROP TEMPORARY TABLE IF EXISTS} 会静默删不掉另一条连接的表（且不报错）。
     * 因此这里用真实表 + {@code DROP TABLE IF EXISTS}（跨连接可见，幂等）。
     * 代价是<b>进程中途被 kill 会留下残留表</b>（实测曾留下 {@code h3sc_latest} 16MB），
     * 所以 {@link #buildKeepTables()} 开头先无条件 DROP 一次，且保留 {@link #dropKeepTables()} 的 finally 兜底。
     */
    private void buildKeepTables() {
        dropKeepTables();
        jdbc.execute("CREATE TABLE `_h3sc_latest` (eid VARCHAR(100) COLLATE " + COLLATION
                + " NOT NULL PRIMARY KEY, latest_id BIGINT NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO `_h3sc_latest`(eid, latest_id)"
                + " SELECT external_id, MAX(id) FROM h3_import_rows"
                + " WHERE external_id IS NOT NULL GROUP BY external_id");

        jdbc.execute("CREATE TABLE `_h3sc_keep` (batch_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO `_h3sc_keep`(batch_id)"
                + " SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY data_type ORDER BY id DESC) rn"
                + "   FROM h3_import_batches WHERE data_type IN (" + MANAGED_IN_SQL + ")"
                + "    AND status IN (" + TERMINAL_STATUS_SQL + ")) t WHERE t.rn <= ?",
                Math.max(keepBatches, 1));
    }

    private void dropKeepTables() {
        for (String t : List.of("_h3sc_latest", "_h3sc_keep")) {
            try {
                jdbc.execute("DROP TABLE IF EXISTS `" + t + "`");
            } catch (Exception e) {
                log.debug("[快照治理] 临时表 {} 清理忽略：{}", t, e.getMessage());
            }
        }
    }

    /** 分批删除；先物化待删 id 再删，避开「DELETE 目标表又被子查询读」的 1093。 */
    private int deleteByTarget(String targetSql, List<Object> args) {
        int total = 0;
        while (true) {
            List<Long> ids = jdbc.query("SELECT r.id " + targetSql + " LIMIT " + DELETE_CHUNK,
                    (rs, n) -> rs.getLong(1), args.toArray());
            if (ids.isEmpty()) break;
            String in = String.join(",", ids.stream().map(String::valueOf).toList());
            total += jdbc.update("DELETE FROM h3_import_rows WHERE id IN (" + in + ")");
            if (ids.size() < DELETE_CHUNK) break;
        }
        return total;
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
