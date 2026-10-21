package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 氚云导入暂存行生命周期管理（2026-10-20 建立）。
 *
 * <p><b>为什么要有这个类</b>：{@code h3_import_staging_rows} 原设计只有整库重置
 * （{@link H3ResetService}）会清，日常导入零清理。十天内同类型全量重导十余次，
 * 每次落一份完整副本，单表累积到 40 万行 / 3GB，占全库体积 45%。
 * 建表时就留了 {@code idx_h3isr_parsed_at}（注释「按解析时间清理过期暂存行」），
 * 但清理动作从未实现——本类补上这一环。
 *
 * <p><b>只清什么（口径 2026-10-20 与 LQ 确认）</b>：
 * <ul>
 *   <li>仅「已终态」批次：{@code COMPLETED} / {@code PARTIAL_FAILED}。
 *       终态批次状态机已禁止再次确认，暂存行对业务零作用；</li>
 *   <li>清前必须确认该批次 {@code preview_json} 非空——暂存行唯一残留用途是
 *       preview_json 缺失时的预览回退路径（{@link H3FactImportService#getPreviewFromStaging}），
 *       preview_json 在则回退路径永不触发，删暂存不影响「查看预览」；</li>
 *   <li>{@code UPLOADED / PARSING / PREVIEW_READY / CONFIRMING / FAILED} 一律不动。
 *       PREVIEW_READY 是「已上传未确认」的在途数据，删了用户就无法继续确认；
 *       FAILED 允许原样重试，同样保留。</li>
 * </ul>
 *
 * <p><b>为什么在提交后</b>：清理必须在业务事务成功提交之后执行。
 * 确认失败却把暂存删掉，会让这批数据彻底无法重试——这是不可接受的失效模式。
 * 因此两个调用点都是 afterCommit（B 域显式注册同步器；A 域本身就在异步线程、
 * 且是切终态之后的独立语句）。
 *
 * <p><b>失败语义</b>：清理是纯优化动作，任何异常只记日志、绝不向上抛出，
 * 不影响导入结果与批次终态。
 */
@Service
@RequiredArgsConstructor
public class H3StagingCleanupService {

    private static final Logger log = LoggerFactory.getLogger(H3StagingCleanupService.class);

    /** 单次 DELETE 行数上限；分批避免长事务把元数据锁拖死（同 loadPreviousSnapshots 的教训）。 */
    private static final int DELETE_CHUNK = 5000;

    /** 终态：可安全清理暂存行的批次状态。 */
    private static final List<String> TERMINAL_STATUSES = List.of("COMPLETED", "PARTIAL_FAILED");

    /** 全局开关：默认开启；设为 false 可整体停用（排查期或特殊运维窗口）。 */
    @Value("${h3.import.staging-cleanup.enabled:true}")
    private boolean enabled;

    private final JdbcTemplate jdbc;

    /** 清理结果（供日志与运维端点判定，不参与业务逻辑）。 */
    public enum Outcome {
        /** 已清理（可能删 0 行，批次本就无暂存）。 */
        DELETED,
        /** 开关关闭。 */
        SKIPPED_DISABLED,
        /** 批次不存在。 */
        SKIPPED_MISSING,
        /** 非终态（在途 / 可重试），按口径不动。 */
        SKIPPED_NOT_TERMINAL,
        /** preview_json 为空，删暂存会失去预览回退路径。 */
        SKIPPED_NO_PREVIEW
    }

    public record CleanupResult(Outcome outcome, int deletedRows) {}

    /**
     * 清理单个批次的暂存行（幂等：重复调用第二次删 0 行）。
     *
     * <p>调用点必须在业务事务提交之后；本方法自身不开事务，靠 JdbcTemplate 自动提交。
     */
    public CleanupResult purgeAfterConfirm(long batchId) {
        if (!enabled) {
            return new CleanupResult(Outcome.SKIPPED_DISABLED, 0);
        }
        try {
            List<Object[]> metas = jdbc.query(
                    "SELECT status, preview_json FROM h3_import_batches WHERE id=?",
                    (rs, n) -> new Object[]{rs.getString(1), rs.getString(2)}, batchId);
            if (metas.isEmpty()) {
                log.warn("[暂存清理] 批次 {} 不存在，跳过", batchId);
                return new CleanupResult(Outcome.SKIPPED_MISSING, 0);
            }
            String status = (String) metas.getFirst()[0];
            String previewJson = (String) metas.getFirst()[1];
            if (!TERMINAL_STATUSES.contains(status)) {
                return new CleanupResult(Outcome.SKIPPED_NOT_TERMINAL, 0);
            }
            if (previewJson == null || previewJson.isBlank()) {
                log.warn("[暂存清理] 批次 {}（{}）preview_json 为空，保留暂存行以免丢失预览", batchId, status);
                return new CleanupResult(Outcome.SKIPPED_NO_PREVIEW, 0);
            }
            int deleted = deleteInChunks(batchId);
            if (deleted > 0) {
                log.info("[暂存清理] 批次 {}（{}）已清理暂存行 {} 行", batchId, status, deleted);
            }
            return new CleanupResult(Outcome.DELETED, deleted);
        } catch (Exception e) {
            // 清理只是回收空间，绝不能影响导入结果
            log.warn("[暂存清理] 批次 {} 清理失败（不影响导入）：{}", batchId, e.getMessage(), e);
            return new CleanupResult(Outcome.SKIPPED_MISSING, 0);
        }
    }

    /**
     * 运维批处理：清理所有终态批次的历史暂存行（Tier 1 存量治理入口）。
     *
     * <p>与自动清理同一套判据，逐批次处理，天然幂等；
     * 在途批次（PREVIEW_READY 等）永远不在候选集内。
     *
     * @param maxBatches 单次最多处理批次数（防一次性锁过多表），≤0 表示不限
     * @param dryRun     true 时只统计不删除
     * @return 合计行数（dryRun 时为「将被删除」行数）
     */
    public int purgeAllTerminalBatches(int maxBatches, boolean dryRun) {
        String sql = """
                SELECT b.id FROM h3_import_batches b
                 WHERE b.status IN ('COMPLETED','PARTIAL_FAILED')
                   AND b.preview_json IS NOT NULL AND b.preview_json <> ''
                   AND EXISTS (SELECT 1 FROM h3_import_staging_rows s WHERE s.batch_id = b.id)
                 ORDER BY b.id
                """;
        if (maxBatches > 0) sql = sql + " LIMIT " + maxBatches;
        List<Long> ids = jdbc.query(sql, (rs, n) -> rs.getLong(1));
        int total = 0;
        for (Long id : ids) {
            if (dryRun) {
                Integer cnt = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM h3_import_staging_rows WHERE batch_id=?", Integer.class, id);
                int n = cnt == null ? 0 : cnt;
                total += n;
                log.info("[暂存清理][dryRun] 批次 {} 可清理 {} 行", id, n);
            } else {
                total += deleteInChunks(id);
            }
        }
        log.info("[暂存清理] 批处理完成：{} 个终态批次，{} {}", ids.size(), dryRun ? "预计清理" : "已清理", total);
        return total;
    }

    /** 分批删除；返回实际删除行数。 */
    private int deleteInChunks(long batchId) {
        int total = 0;
        while (true) {
            int n = jdbc.update(
                    "DELETE FROM h3_import_staging_rows WHERE batch_id=? LIMIT " + DELETE_CHUNK, batchId);
            total += n;
            if (n < DELETE_CHUNK) break;
        }
        return total;
    }
}
