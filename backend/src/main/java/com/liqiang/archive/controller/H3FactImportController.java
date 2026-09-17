package com.liqiang.archive.controller;

import com.liqiang.archive.dto.H3FactImportDtos.Result;
import com.liqiang.archive.dto.H3FactImportDtos.StageAccepted;
import com.liqiang.archive.service.H3FactImportService;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
@RestController
@RequestMapping("/api/h3-facts")
@RequiredArgsConstructor
@RequirePermission("integration.h3")
public class H3FactImportController {
    private final H3FactImportService service;

    /** 代理引用：用于在本类方法内部触发 @Async（避免 self-invocation）。 */
    @Autowired @Lazy
    private H3FactImportService importServiceProxy;

    // ====================================================================
    // V160/V161 新管线：Excel 只解析一次，stage → progress 轮询 → preview → confirm
    // ====================================================================

    /**
     * 新端点 Step 1：上传文件 → 登记批次 → 后台异步解析。
     * 与旧 preview/confirm 的本质差异：Excel 只解析一次，落暂存表后预览&确认都从 DB 读。
     */
    @PostMapping("/{type}/stage")
    public StageAccepted stageUpload(@PathVariable String type,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String fileHash = sha256(bytes);
        StageAccepted accepted = service.createBatchRecord(
                type, file.getOriginalFilename(), file.getSize(), fileHash);
        // 幂等命中（hashHit=true）不重复解析
        if (!accepted.hashHit()) {
            H3FactImportService.FactType factType = H3FactImportService.FactType.from(type);
            // 走 Spring 代理调用 → @Async 生效
            importServiceProxy.asyncParseAndStage(accepted.batchId(), factType, bytes, file.getOriginalFilename());
        }
        return accepted;
    }

    /**
     * 新端点 Step 2/3（批次进度 / 暂存预览）已统一收口到 {@code /api/h3-imports/batches/**}
     * （H3ImportBatchController），旧前缀读端点于阶段 4 下线；此处仅保留写与专有读端点。
     */
    /** 新端点 Step 4：确认导入 — 从暂存写入事实表 + 自动编排。 */
    @PostMapping("/import-batches/{id}/confirm")
    public Result stagingConfirm(@PathVariable Long id) {
        return service.confirmFromStaging(id);
    }

    // ========== 控制器内工具方法（仅为新管线使用，不对外暴露） ==========
    private static String sha256(byte[] bytes) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
