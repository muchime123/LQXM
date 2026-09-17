package com.liqiang.archive.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class H3FactImportDtos {
    private H3FactImportDtos() {}

    public record Preview(String dataType, String fileName, int totalCount,
                          int createCount, int updateCount, int unchangedCount,
                          List<RowPreview> rows) {}
    /**
     * P0-C：预览行扩展关键字段（金额/客户/业务员），便于用户在确认前判断业务含义。
     * amount 统一格式化为"数字字符串"（前端按 dataType 加 ¥ 前缀）；客户/业务员直接透传原始字段。
     * warnings：导入校验告警（Σ明细≠合计、缺价、供应商未解析等），多条以「；」连接；无告警为 null。
     */
    public record RowPreview(String businessKey, String externalId, String title,
                             String action, int childCount,
                             String amount, String customerName, String salespersonName,
                             String warnings, List<FieldChange> changes) {}
    /** UPDATE 行的字段级变化；path 保留氚云原始字段路径，oldValue/newValue 用于可读对照。 */
    public record FieldChange(String path, String oldValue, String newValue) {}
    public record Result(Long batchId, String dataType, int totalCount,
                         int createdCount, int updatedCount, int unchangedCount,
                         int childCount) {}
    public record MatchResult(int linkedExplicitly, int linkedStrictly,
                              int createdProductionBusinesses,
                              int createdOutboundBusinesses, int refreshedBusinesses) {}

    // V160/V161 异步导入新 DTO
    /** stageUpload 返回：批次基本信息 + 是否已命中幂等缓存。 */
    public record StageAccepted(Long batchId, String dataType, String fileName,
                                boolean hashHit, String status, String message) {}
    /** 进度查询返回：前端轮询数据。 */
    public record BatchProgress(Long batchId, String status, int progressPct,
                                String progressStage, String errorMessage,
                                Integer totalCount, Integer createCount,
                                Integer updateCount, Integer unchangedCount,
                                String completedAt) {}
    /** 暂存 → 事实表写入前的预览（同旧 Preview，只是数据来自暂存表而非 Excel 解析）。 */
    public record StagingPreview(Long batchId, Preview preview) {}

    /** 台账批次（产品信息/仓库明细）比对预览（2026-09 强类型化；键名与历史 Map 一致，前端零改动）。 */
    public record LedgerImportPreview(Long batchId, String dataType, String fileName,
                                      int totalCount, int createCount, int updateCount,
                                      int unchangedCount, List<Map<String, Object>> rows) {}

    // V17x 统一导入后编排（P5 第5步）：Step 矩阵 + 结果结构
    /** 导入后统一编排步骤（严格按执行顺序）。
     *  V1xx 起收款分配相关步骤（BACKFILL_ALLOC_OUTBOUND_IDS / FIX_RECEIPT_MAPPING / REBUILD_SETTLEMENT_RECONCILIATION）
     *  与 RELATION_AUDIT（关系审计服务已下线）一并移除。
     *  2026-09-07（P1，见 docs/design/h3-received-single-authority-and-chain-consolidation-20260907.md）：
     *  旧 4 步（REFRESH_OUTBOUND_SNAPSHOTS / INCREMENTAL_MATCH / INVALIDATE_LINKS / REFRESH_BUSINESS_AGG）
     *  与 MARK_OUTBOUND_PROFITS_PENDING（把刚算好的利润整批打回 PENDING，全库无消费者）均已下线，
     *  收敛为回款单一权威一致性守护一步。 */
    public enum OrchestrationStep {
        /** 唯一姓名员工、唯一名称仓库的确定性关联。 */
        LINK_OUTBOUND_EMPLOYEE_WAREHOUSE,
        /** 复用已确认身份簇、唯一电话和无冲突历史映射。 */
        LINK_OUTBOUND_CUSTOMERS,
        /** 恰好一个未占用有效电话时，安全建立客户档案。 */
        CREATE_SAFE_OUTBOUND_CUSTOMERS,
        /** 名称、地址及完整电话集合一致的多电话身份簇。 */
        RESOLVE_SAFE_MULTI_PHONE_CUSTOMERS,
        /** 仅对唯一命中的库存产品候选自动归类。 */
        LINK_OUTBOUND_PRODUCTS,
        /** 出库回款单一权威守护：删除旧 AUTO_BALANCE 行 → 按主表补/冲差额行 → 刷新派生结算状态。 */
        REBALANCE_OUTBOUND_SNAPSHOTS,
    }

    /** 单个 Step 的执行结果。 */
    public record StepOutcome(
            OrchestrationStep step,
            boolean skipped,
            long durationMs,
            Map<String, Object> stats,     // 从原 Service 方法的 Map 返回值透传
            String errorSummary            // null=正常；非 null=异常摘要，编排整体不中断（仅单步失败）
    ) {}

    /** 本次导入后编排的完整结果（会序列化进 h3_import_batches.preview_json 的 orchestration 字段）。 */
    public record OrchestrationOutcome(
            String factType,
            Instant startedAt,
            Instant finishedAt,
            int totalSteps,
            int stepsSkipped,
            int stepsSucceeded,
            int stepsFailed,
            List<StepOutcome> steps
    ) {
        public String summary() {
            long totalMs = Duration.between(startedAt, finishedAt).toMillis();
            return "编排%s完成：%d步成功/%d步跳过/%d步失败，总耗时%dms".formatted(
                    stepsFailed == 0 ? "" : "（含异常）",
                    stepsSucceeded, stepsSkipped, stepsFailed, totalMs);
        }
    }

    /** 本次导入的"受影响范围"——编排阶段用它做增量刷新，避免全量扫表。 */
    public record AffectedScope(
            String factType,
            /** P1 候选批次血缘：本次导入批次 ID，传给候选生成器写入 source_batch_id；手动触发时为 null。 */
            Long batchId,
            /** 本次写入的事实行 ID：PRODUCTION→production_orders.id；OUTBOUND→sales_outbounds.id；RECEIPT/SETTLEMENT→receipts.id。 */
            List<Long> writtenFactIds,
            /** 本次涉及的客户名集合（用于匹配时的候选缩小）。 */
            List<String> customerNames,
            /** 本次涉及的业务员名集合。 */
            List<String> salespersonNames
    ) {}
}
