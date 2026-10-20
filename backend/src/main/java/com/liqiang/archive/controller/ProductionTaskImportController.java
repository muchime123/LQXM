package com.liqiang.archive.controller;

import com.liqiang.archive.dto.H3FactImportDtos.BatchProgress;
import com.liqiang.archive.dto.H3FactImportDtos.Result;
import com.liqiang.archive.dto.H3FactImportDtos.StageAccepted;
import com.liqiang.archive.dto.H3FactImportDtos.StagingPreview;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3BusinessQueryService;
import com.liqiang.archive.service.H3FactImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 生产跟单员的生产任务单专用入口；解析、预览和写入均复用氚云导入中心的原有服务。 */
@RestController
@RequestMapping("/api/manufacturing/followups/production-task-import")
@RequirePermission("manufacturing.followup")
@RequiredArgsConstructor
public class ProductionTaskImportController {
    private final H3FactImportService imports;
    private final H3BusinessQueryService batches;
    @Autowired @Lazy private H3FactImportService importProxy;

    @PostMapping("/stage")
    public StageAccepted stage(@RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        // 全局异常处理器对「缺 file 参数」的兜底文案是销售出库口径，这里给跟单员一条对症的提示。
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择氚云导出的生产任务单 Excel 文件");
        }
        byte[] bytes = file.getBytes();
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StageAccepted accepted = imports.createBatchRecord("production", file.getOriginalFilename(), file.getSize(), hash);
        if (!accepted.hashHit()) {
            importProxy.asyncParseAndStage(accepted.batchId(), H3FactImportService.FactType.PRODUCTION,
                    bytes, file.getOriginalFilename());
        }
        return accepted;
    }

    @GetMapping("/{id}/progress")
    public BatchProgress progress(@PathVariable Long id) {
        requireProduction(id);
        return imports.getBatchProgress(id);
    }

    @GetMapping("/{id}/preview")
    public StagingPreview preview(@PathVariable Long id) {
        requireProduction(id);
        return imports.getPreviewFromStaging(id);
    }

    @PostMapping("/{id}/confirm")
    public Result confirm(@PathVariable Long id) {
        requireProduction(id);
        return imports.confirmFromStaging(id);
    }

    private void requireProduction(Long id) {
        if (!"PRODUCTION".equals(batches.importBatchDataType(id))) {
            throw new IllegalArgumentException("只能导入生产任务单批次");
        }
    }
}
