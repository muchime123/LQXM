package com.liqiang.archive.service;

import com.liqiang.archive.dto.H3FactImportDtos.AffectedScope;
import com.liqiang.archive.dto.H3FactImportDtos.OrchestrationOutcome;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** P2 post-commit worker: the HTTP/import transaction no longer executes reconciliation synchronously. */
@Service
@RequiredArgsConstructor
public class H3PostImportAsyncService {
    private static final Logger log=LoggerFactory.getLogger(H3PostImportAsyncService.class);
    private final JdbcTemplate jdbc;
    private final H3ImportOrchestratorService orchestrator;
    private final CommissionCalculationService commissions;
    private final H3StagingCleanupService stagingCleanup;
    private final H3SnapshotCleanupService snapshotCleanup;

    @Async("h3ImportExecutor")
    public void run(AffectedScope scope,long batchId,boolean trackChanges) {
        try {
            OrchestrationOutcome outcome=orchestrator.orchestrateAfterImport(scope,batchId);
            String status=outcome.stepsFailed()>0?"PARTIAL_FAILED":"COMPLETED";
            jdbc.update("UPDATE h3_import_batches SET status=?,progress_stage='DONE',progress_pct=100,completed_at=NOW() WHERE id=?",status,batchId);
        } catch(Exception e) {
            jdbc.update("UPDATE h3_import_batches SET status='PARTIAL_FAILED',progress_stage='DONE',progress_pct=100,error_message=?,completed_at=NOW() WHERE id=?",
                    "自动编排异常："+safe(e),batchId);
            // 业务数据已在主事务中提交，批次已终态；暂存行对重试无意义，仍按口径清
            stagingCleanup.purgeAfterConfirm(batchId);
            snapshotCleanup.pruneAfterConfirm(batchId);
            return;
        }
        // 终态写入之后才清暂存：本方法是 @Async 且无事务，两个语句各自提交，顺序天然安全。
        // 清理内部自带失败兜底与 preview_json 守卫，不影响上面已落定的终态。
        stagingCleanup.purgeAfterConfirm(batchId);
        // 快照治理：只清「本批涉及的单据在更早批次里的重复快照」，每单最新一条永久保留，
        // 因此下次导入的新旧字段对照、台账更新历史（读 preview_json）均不受影响。
        snapshotCleanup.pruneAfterConfirm(batchId);
        if(trackChanges) {
            try {
                int rows=commissions.trackOutboundChanges(batchId,new ArrayList<>(scope.writtenFactIds()==null?List.of():scope.writtenFactIds()),"导入后自动识别");
                if(rows>0) log.info("批次 {} 已生效出库单变化跟踪：{} 笔",batchId,rows);
            } catch(Exception e) {
                log.warn("批次 {} 出库变化跟踪失败（不影响导入）：{}",batchId,safe(e));
            }
        }
    }
    private static String safe(Exception e){String s=e.getMessage();if(s==null||s.isBlank())s=e.getClass().getSimpleName();return s.length()>400?s.substring(0,400):s;}
}
