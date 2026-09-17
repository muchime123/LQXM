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

    @Async("h3ImportExecutor")
    public void run(AffectedScope scope,long batchId,boolean trackChanges) {
        try {
            OrchestrationOutcome outcome=orchestrator.orchestrateAfterImport(scope,batchId);
            String status=outcome.stepsFailed()>0?"PARTIAL_FAILED":"COMPLETED";
            jdbc.update("UPDATE h3_import_batches SET status=?,progress_stage='DONE',progress_pct=100,completed_at=NOW() WHERE id=?",status,batchId);
        } catch(Exception e) {
            jdbc.update("UPDATE h3_import_batches SET status='PARTIAL_FAILED',progress_stage='DONE',progress_pct=100,error_message=?,completed_at=NOW() WHERE id=?",
                    "自动编排异常："+safe(e),batchId);
            return;
        }
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
