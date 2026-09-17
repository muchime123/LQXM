package com.liqiang.archive.service;

import com.liqiang.archive.dto.H3FactImportDtos.AffectedScope;
import com.liqiang.archive.dto.H3FactImportDtos.OrchestrationOutcome;
import com.liqiang.archive.dto.H3FactImportDtos.OrchestrationStep;
import com.liqiang.archive.dto.H3FactImportDtos.StepOutcome;
import com.liqiang.archive.service.H3FactImportService.FactType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入后统一编排服务（P5 第5步，2026-09-07 P1 收敛）。
 *
 * <p>早期过程式 autoOrchestrateAfterImport 收口的「4 个 Step 行事历」
 * （REFRESH_OUTBOUND_SNAPSHOTS / INCREMENTAL_MATCH / INVALIDATE_LINKS / REFRESH_BUSINESS_AGG）
 * 已因链路下钻不可达而下线：V1xx 起收款/结清文件停止导入，销售出库为唯一回款事实源，
 * 业务单/匹配/聚合链路不再由导入触发。
 *
 * <p>2026-09-07 P1（docs/design/h3-received-single-authority-and-chain-consolidation-20260907.md）：
 * <ul>
 *   <li>删除 MARK_OUTBOUND_PROFITS_PENDING——该步骤把导入事务内刚重算为 CALCULATED 的订单利润
 *       整批打回 PENDING，而全库没有消费者会把 PENDING 重新算回，导致利润状态可能永久滞留；
 *       订单利润现在只由导入事务内的 recalculate 直接产出（CALCULATED）。</li>
 *   <li>保留 REBALANCE_OUTBOUND_SNAPSHOTS 作为回款「单一权威」一致性守护。</li>
 *   <li>2026-09-17 起在它之前自动执行客户、员工、仓库和库存产品的确定性关联，
 *       让重导后的机械操作内化为系统步骤，只把冲突与低置信度记录留给人工。</li>
 * </ul>
 *
 * <p>设计原则：
 * <ul>
 *   <li>单步失败不中断整体编排，异常写入 StepOutcome.errorSummary 与批次 error_message。</li>
 *   <li>处理结果只用于决定批次是否完成，内部过程不作为用户操作功能。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class H3ImportOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(H3ImportOrchestratorService.class);

    private final JdbcTemplate jdbc;
    private final OutboundBalanceService balanceService;
    private final OutboundRelationGovernanceService relationGovernanceService;
    private final PlatformTransactionManager transactionManager;

    // ======================== FactType → Step 矩阵 ========================

    /** 根据导入类型确定需要执行的步骤（严格按顺序）。包内可见供测试断言。 */
    static List<OrchestrationStep> stepsFor(FactType type) {
        return switch (type) {
            // 销售出库是唯一回款事实源：确认后执行一致性守护（补/冲差额行 + 派生状态刷新）
            case OUTBOUND -> List.of(
                    OrchestrationStep.LINK_OUTBOUND_EMPLOYEE_WAREHOUSE,
                    OrchestrationStep.LINK_OUTBOUND_CUSTOMERS,
                    OrchestrationStep.CREATE_SAFE_OUTBOUND_CUSTOMERS,
                    OrchestrationStep.RESOLVE_SAFE_MULTI_PHONE_CUSTOMERS,
                    OrchestrationStep.LINK_OUTBOUND_PRODUCTS,
                    OrchestrationStep.REBALANCE_OUTBOUND_SNAPSHOTS);
            // 收款/结清文件仅存档台账、生产为独立历史事实：不触发回款一致性编排
            case RECEIPT, SETTLEMENT, PRODUCTION -> List.of();
            // 供应商主数据 / 采购入库（2026-09-17 P0）：事实层落库，无回款/利润联动，不编排
            case SUPPLIER, PURCHASE_IN -> List.of();
            // 其他入库 / 其他出库（2026-09-17 P1）：同上，只落事实层（余额仍由台账快照合并驱动）
            case OTHER_IN, OTHER_OUT -> List.of();
            case TRANSFER -> List.of();
case INVOICE_PROFILE, INVOICE_REQUEST -> List.of();
            // 生产报工（2026-09-17 第 15 类）：工序完工事实层，无回款/利润联动，不编排
            case PRODUCTION_REPORT -> List.of();
        };
    }

    // ======================== 对外主入口 ========================

    /**
     * 导入确认后统一编排入口。
     *
     * @param scope   本次导入的受影响范围（写入的事实行 ID / 客户 / 业务员）
     * @param batchId 导入批次号，用于写回进度与编排结果
     * @return 编排完整结果，包含每一步执行统计
     */
    public OrchestrationOutcome orchestrateAfterImport(AffectedScope scope, Long batchId) {
        FactType type = FactType.from(scope.factType());
        List<OrchestrationStep> plan = stepsFor(type);
        Instant startedAt = Instant.now();
        List<StepOutcome> outcomes = new ArrayList<>(plan.size());

        int skipped = 0, succeeded = 0, failed = 0;
        TransactionTemplate stepTx = new TransactionTemplate(transactionManager);
        stepTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        for (OrchestrationStep step : plan) {
            long t0 = System.currentTimeMillis();
            try {
                StepRunResult result = stepTx.execute(status -> runStep(step, scope));
                if (result == null) throw new IllegalStateException("编排步骤未返回结果：" + step);
                long dur = System.currentTimeMillis() - t0;
                if (result.skipped()) {
                    skipped++;
                    outcomes.add(new StepOutcome(step, true, dur, result.stats(), null));
                } else {
                    succeeded++;
                    outcomes.add(new StepOutcome(step, false, dur, result.stats(), null));
                }
            } catch (Exception e) {
                failed++;
                long dur = System.currentTimeMillis() - t0;
                String err = safeMsg(e);
                outcomes.add(new StepOutcome(step, false, dur, Map.of(), err));
                log.warn("编排 Step {} 失败（批次 {}）：{}", step, batchId, err, e);
            }
            // 每步完成后推一次进度（50→97 区间，留给最后 3% 收尾）
            int base = 50;
            int span = 47;
            int idx = outcomes.size();
            int pct = base + (int) ((idx * (long) span) / Math.max(1, plan.size()));
            updateStageProgress(batchId, pct, step.name());
        }

        Instant finishedAt = Instant.now();
        OrchestrationOutcome outcome = new OrchestrationOutcome(
                scope.factType(), startedAt, finishedAt,
                plan.size(), skipped, succeeded, failed, outcomes);

        if (failed > 0) {
            jdbc.update("UPDATE h3_import_batches SET error_message=? WHERE id=? AND error_message IS NULL",
                    outcome.summary(), batchId);
        }

        log.info("批次 {} 编排完成：{}", batchId, outcome.summary());
        return outcome;
    }

    // ======================== 单步执行 ========================

    /** 单步执行结果：是否跳过 + 统计。 */
    private record StepRunResult(boolean skipped, Map<String, Object> stats) {}

    private StepRunResult runStep(OrchestrationStep step, AffectedScope scope) {
        return switch (step) {
            case LINK_OUTBOUND_EMPLOYEE_WAREHOUSE ->
                    new StepRunResult(false, relationGovernanceService.applyExact("氚云导入后自动整理"));
            case LINK_OUTBOUND_CUSTOMERS ->
                    new StepRunResult(false, relationGovernanceService.applyCustomerSafe());
            case CREATE_SAFE_OUTBOUND_CUSTOMERS ->
                    new StepRunResult(false, relationGovernanceService.applyNewCustomerClusters());
            case RESOLVE_SAFE_MULTI_PHONE_CUSTOMERS ->
                    new StepRunResult(false, relationGovernanceService.applyMultiPhone());
            case LINK_OUTBOUND_PRODUCTS ->
                    new StepRunResult(false, relationGovernanceService.applyProductExact("氚云导入后自动整理"));
            case REBALANCE_OUTBOUND_SNAPSHOTS -> {
                Set<Long> outboundIds = deriveOutboundsFromScope(scope);
                if (outboundIds.isEmpty()) {
                    yield new StepRunResult(true, Map.of("reason", "本批次没有受影响销售出库"));
                }
                Map<String, Object> stats = balanceService.reconcileAndRefresh(outboundIds);
                yield new StepRunResult(false, stats);
            }
        };
    }

    // ======================== 增量范围派生 ========================

    /** 从 scope 派生受影响出库：OUTBOUND 直接用写入 ID；其余类型暂无。 */
    private Set<Long> deriveOutboundsFromScope(AffectedScope scope) {
        FactType t = FactType.from(scope.factType());
        return switch (t) {
            case OUTBOUND -> filterOutboundIds(scope.writtenFactIds());
            default -> Set.of();
        };
    }

    /** 从 AffectedScope 提取出库 ID（仅当 factType=OUTBOUND 时 writtenFactIds 是出库）。 */
    private Set<Long> filterOutboundIds(List<Long> writtenFactIds) {
        return writtenFactIds == null ? Set.of() : new HashSet<>(writtenFactIds);
    }

    // ======================== 批次进度/结果写回 ========================

    private void updateStageProgress(Long batchId, int pct, String stage) {
        try {
            jdbc.update("UPDATE h3_import_batches SET progress_pct=?, progress_stage=? WHERE id=?",
                    Math.max(0, Math.min(99, pct)), "ORCH_" + stage, batchId);
        } catch (Exception e) {
            // 进度是可观测信息，不能因为字段长度/短暂写入异常中断已成功的业务编排。
            log.warn("批次 {} 编排进度写入失败（{} / {}%）：{}", batchId, stage, pct, safeMsg(e));
        }
    }

    // ======================== 工具 ========================

    static String safeMsg(Exception e) {
        if (e == null) return "null";
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 400 ? m.substring(0, 400) : m;
    }
}
