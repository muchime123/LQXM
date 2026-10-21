package com.liqiang.archive.service;

import com.liqiang.archive.dto.H3FactImportDtos.BatchProgress;
import com.liqiang.archive.dto.H3FactImportDtos.DuplicateImportHint;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 氚云导入「批次管线骨架」（阶段 3：管线服务合并）。
 *
 * <p>两域上传管线（A 域四类业务事实、B 域产品/仓库台账）在 V160/V17x 各自实现时，
 * 把同一套「登记批次 → 哈希幂等 → 异步解析进度 → 暂存落库 → 预览缓存 → 失败打标」
 * 骨架按不同方言重复写了两份（INSERT / UPDATE / FAILED / preview_json 缓存等）。
 * 本服务将骨架统一收口，两域 Service 只保留数据源差异（解析表头/分组/比对/写业务表），
 * 骨架一律委托本服务执行，保证状态机与批次表契约只有一份实现。
 *
 * <p>语义与重构前逐字节保持一致：
 * <ul>
 *   <li>哈希幂等：相同 data_type + file_hash 且状态为 PREVIEW_READY（未确认）时复用旧批次；
 *       COMPLETED 批次不复用（数据已落库，旧预览计数过期）；</li>
 *   <li>失败打标：status=FAILED + progress_stage + error_message(≤1000) +
 *       error_detail(堆栈≤50 行、≤65535 字符)，并记录错误日志；</li>
 *   <li>预览就绪：一次 UPDATE 置 PREVIEW_READY / PREVIEW / progress_pct=100，
 *       同时写 preview_json 与 total_count（field_version 仅 A 域按需传值，B 域传 null）。</li>
 * </ul>
 *
 * <p>confirm 语义（写业务表/编排/终态）不属于本骨架：A/B 域各自的确认逻辑保持原样，
 * 仅它们内部可能复用 {@link #updateProgress(long, String, int, String)} 等通用步进。
 */
@Service
@RequiredArgsConstructor
public class BatchImportPipelineService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BatchImportPipelineService.class);

    /** 错误摘要上限（error_message，前端列表展示用）。 */
    private static final int ERROR_MESSAGE_CAP = 1000;
    /** 错误详情上限（error_detail，含堆栈）。 */
    private static final int ERROR_DETAIL_CAP = 65535;
    /** 失败打标时保留的堆栈行数上限。 */
    private static final int STACK_LINE_CAP = 50;

    private final JdbcTemplate jdbc;

    /**
     * 登记新批次：仅写批次头（UPLOADED/UPLOAD/0），不落文件字节。
     *
     * @param fieldVersion 字段版本快照（A 域暂不维护字段指纹时传 null；B 域恒传 null）
     * @return 新批次 id
     */
    public long createBatch(String fileName, long fileSize, String fileHash, String dbType, String fieldVersion) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO h3_import_batches(file_name,file_size,file_hash,data_type,field_version,status,progress_stage,total_count)"
                            + " VALUES(?,?,?,?,?,'UPLOADED','UPLOAD',0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fileName);
            ps.setLong(2, fileSize);
            ps.setString(3, fileHash);
            ps.setString(4, dbType);
            ps.setString(5, fieldVersion);
            return ps;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /**
     * 幂等命中：同 data_type + file_hash 且仍处于 PREVIEW_READY（未确认）的批次。
     * 找不到返回 null；COMPLETED 批次不复用，由调用方正常建新批次。
     */
    public Long findPreviewReadyBatchId(String dbType, String fileHash) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM h3_import_batches WHERE data_type=? AND file_hash=? AND status='PREVIEW_READY' LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), dbType, fileHash);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * 「同内容文件此前已成功导入过」提示源（<b>只提示、不拦截</b>）。
     *
     * <p>与 {@link #findPreviewReadyBatchId} 的区别：那个是「未确认批次复用解析结果」
     * （省一次解析，hashHit=true）；本方法查的是<b>已经确认落库</b>的历史批次，
     * 用于让操作员知道「这份文件上午已经导过一次」，但依然正常建新批次、正常比对写入。
     *
     * <p>按内容哈希而非文件名判断——氚云导出的文件名常带日期或固定不变，不可靠。
     *
     * @return 最近一次已确认批次；从未成功导入过则返回 null
     */
    public DuplicateImportHint findLastConfirmedSameFile(String dbType, String fileHash) {
        if (!StringUtils.hasText(fileHash)) return null;
        List<DuplicateImportHint> hits = jdbc.query("""
                SELECT id, file_name, completed_at
                  FROM h3_import_batches
                 WHERE data_type=? AND file_hash=? AND status IN ('COMPLETED','PARTIAL_FAILED')
                 ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> {
                    java.sql.Timestamp ts = rs.getTimestamp("completed_at");
                    return new DuplicateImportHint(
                            rs.getLong("id"),
                            rs.getString("file_name"),
                            ts == null ? null : ts.toLocalDateTime().toString());
                }, dbType, fileHash);
        return hits.isEmpty() ? null : hits.getFirst();
    }

    /** 批次进度（轻量，只读批次表，供前端每 500ms 轮询）；批次不存在返回 null。 */
    public BatchProgress batchProgress(Long batchId) {
        List<BatchProgress> list = jdbc.query("""
                SELECT id,status,progress_pct,progress_stage,error_message,
                       total_count,created_count,updated_count,unchanged_count,completed_at
                  FROM h3_import_batches WHERE id=?
                """, (rs, rowNum) -> {
                    java.time.LocalDateTime cat = rs.getTimestamp("completed_at") == null ? null
                            : rs.getTimestamp("completed_at").toLocalDateTime();
                    return new BatchProgress(
                            rs.getLong("id"),
                            rs.getString("status"),
                            rs.getInt("progress_pct"),
                            rs.getString("progress_stage"),
                            rs.getString("error_message"),
                            (Integer) rs.getObject("total_count"),
                            (Integer) rs.getObject("created_count"),
                            (Integer) rs.getObject("updated_count"),
                            (Integer) rs.getObject("unchanged_count"),
                            cat == null ? null : cat.toString());
                }, batchId);
        return list.isEmpty() ? null : list.getFirst();
    }

    /** 轻量进度步进：pct 统一收敛到 [0,100]。 */
    public void updateProgress(long batchId, String status, int pct, String stage) {
        jdbc.update("UPDATE h3_import_batches SET status=?, progress_pct=?, progress_stage=? WHERE id=?",
                status, Math.max(0, Math.min(100, pct)), stage, batchId);
    }

    /**
     * 失败打标：status=FAILED + 阶段 + 摘要(≤1000) + 堆栈详情(≤65535)。
     * 打标自身异常仅告警、不二次抛出；错误日志统一在本方法记录。
     */
    public void markFailed(long batchId, String stage, String msg, Exception ex) {
        String errShort = msg.length() > ERROR_MESSAGE_CAP ? msg.substring(0, ERROR_MESSAGE_CAP) : msg;
        String detail = ex == null ? msg : (msg + "\n" + StringUtils.arrayToDelimitedString(
                Arrays.stream(ex.getStackTrace()).limit(STACK_LINE_CAP).toArray(), "\n  at "));
        try {
            jdbc.update("""
                    UPDATE h3_import_batches SET status='FAILED', progress_stage=?, error_message=?, error_detail=?
                     WHERE id=?
                    """, stage, errShort,
                    detail.length() > ERROR_DETAIL_CAP ? detail.substring(0, ERROR_DETAIL_CAP) : detail,
                    batchId);
        } catch (Exception e2) {
            log.warn("标记批次失败失败：", e2);
        }
        log.error("[H3导入] 批次{}失败：{}", batchId, msg, ex);
    }

    /** 一次置 PREVIEW_READY（preview_json 缓存 + total_count；field_version 由 A 域按需传值，B 域传 null）。 */
    public void markPreviewReady(long batchId, String previewJson, int totalCount, String fieldVersion) {
        jdbc.update("""
                UPDATE h3_import_batches
                   SET status='PREVIEW_READY', progress_stage='PREVIEW', progress_pct=100,
                       field_version=?, preview_json=?, total_count=?
                 WHERE id=?
                """, fieldVersion, previewJson, totalCount, batchId);
    }

    /**
     * 落一条暂存行（与 h3_import_staging_rows 结构对齐）。调用方按业务键/外部 id/哈希
     * 预计算好值后逐条写入（单条 INSERT + ON DUPLICATE KEY UPDATE，避免拼多值方言）。
     *
     * <p>骨架统一用「已规范化 JSON 字符串」接收，A 域把分组主表与明细拆成 main_json/rows_json，
     * B 域把整行放 main_json、rows_json 恒为 []——差异由调用方决定，本服务只负责落库契约。
     */
    public void insertStagingRow(long batchId, int rowSeq, String groupKey, String externalId,
                                 String sourceHash, String mainJson, String rowsJson) {
        jdbc.update("""
                INSERT INTO h3_import_staging_rows(batch_id,row_seq,group_key,external_id,source_hash,main_json,rows_json)
                VALUES(?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE main_json=VALUES(main_json), rows_json=VALUES(rows_json),
                                        source_hash=VALUES(source_hash), parsed_at=CURRENT_TIMESTAMP
                """, batchId, rowSeq, groupKey, externalId, sourceHash, mainJson, rowsJson);
    }
}
