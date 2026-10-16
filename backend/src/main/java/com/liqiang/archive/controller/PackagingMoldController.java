package com.liqiang.archive.controller;

import com.liqiang.archive.dto.PackagingMoldDtos.BatchResult;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldAccessoryPayload;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldAccessoryView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldByVariantView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldDetail;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldHistoryView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldPayload;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldStats;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldVariantLinkPayload;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldVariantLinkView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldView;
import com.liqiang.archive.dto.PackagingMoldDtos.MoldDataQualityReport;
import com.liqiang.archive.dto.PackagingMoldDtos.VariantMoldSummary;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportConfirmRequest;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportConfirmResult;
import com.liqiang.archive.dto.PackagingMoldImportDtos.MoldImportPreview;
import com.liqiang.archive.service.PackagingMoldImportService;
import com.liqiang.archive.service.PackagingMoldService;
import com.liqiang.archive.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 包材模具资产台账 CRUD API。
 * <p>路由前缀: /api/packaging-molds
 */
@RestController
@RequestMapping("/api/packaging-molds")
@RequirePermission("mold.read")
@RequiredArgsConstructor
public class PackagingMoldController {

    private final PackagingMoldService service;
    private final PackagingMoldImportService importService;

    // ====== 模具搜索与详情 ======

    @GetMapping
    public Object list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String moldRole,
            @RequestParam(required = false) String moldTypeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String engravingType,
            @RequestParam(required = false) Boolean hasVariant,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean pageByGroup,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {

        Sort.Direction sortDir = Sort.Direction.ASC;
        String sortField = "moldCode";
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            sortField = parts[0];
            if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1])) {
                sortDir = Sort.Direction.DESC;
            }
        }
        Sort pageSort = "moldCode".equals(sortField)
                ? Sort.by(sortDir, "moldCode").and(Sort.by(Sort.Direction.ASC, "id"))
                : Sort.by(sortDir, sortField);
        PageRequest pageable = PageRequest.of(page, size, pageSort);
        return pageByGroup
                ? service.searchByGroup(keyword, modelId, moldRole, moldTypeCode, status, engravingType, hasVariant, pageable)
                : service.search(keyword, modelId, moldRole, moldTypeCode, status, engravingType, hasVariant, pageable);
    }

    @GetMapping("/by-variant/{variantId}")
    public List<MoldByVariantView> listByVariant(@PathVariable Long variantId) {
        return service.findMoldsByVariant(variantId);
    }

    /**
     * 按系列批量汇总各规格的模具信息：默认模具 + 其他模具总数，供包材详情的规格卡片展示。
     */
    @GetMapping("/summarize/by-model/{modelId}")
    public List<VariantMoldSummary> summarizeByModel(@PathVariable Long modelId) {
        return service.summarizeVariantMoldsByModel(modelId);
    }

    @GetMapping("/filters")
    public Map<String, List<String>> getFilterOptions() {
        return service.getFilterOptions();
    }

    @GetMapping("/stats")
    public MoldStats getStats() {
        return service.getStats();
    }

    /**
     * 只读数据质量审计：返回按类型分桶的问题项列表，用于数据质量页或管理端一键排查。
     * 不修改数据库，仅用于发现遗留脏数据（V55 迁移后仍残留的并发/历史问题）。
     */
    @GetMapping("/audit-quality")
    public MoldDataQualityReport auditQuality() {
        return service.auditQuality();
    }

    /**
     * 主动修复可自动解决的两类数据质量问题：
     * <ul>
     *   <li>双归属不一致：mold.model_id 回填为 group.model_id</li>
     *   <li>重复默认模具：保留最早创建的默认关联，其余置为非默认</li>
     * </ul>
     * 与 05:00 定时任务逻辑一致，改为用户主动触发，便于即时修复。
     * @return {@code [双归属修复条数, 重复默认清除条数]}
     */
    @RequirePermission("mold.manage")
    @PostMapping("/audit-quality/fix")
    public long[] fixDataQuality() {
        return service.fixDataQuality();
    }

    @GetMapping("/{id}")
    public MoldDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/history")
    public List<MoldHistoryView> getHistory(@PathVariable Long id) {
        return service.getHistory(id);
    }

    @RequirePermission("mold.manage")
    @PostMapping
    public MoldView create(@Valid @RequestBody MoldPayload payload) {
        return service.create(payload);
    }

    @RequirePermission("mold.manage")
    @PutMapping("/{id}")
    public MoldView update(@PathVariable Long id, @Valid @RequestBody MoldPayload payload) {
        return service.update(id, payload);
    }

    @RequirePermission("mold.manage")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @RequirePermission("mold.manage")
    @PostMapping("/batch-delete")
    public BatchResult batchDelete(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.getOrDefault("ids", List.of());
        return service.batchDelete(ids);
    }

    @RequirePermission("mold.manage")
    @PutMapping("/batch")
    public BatchResult batchUpdate(@RequestBody BatchFieldsRequest request) {
        return service.batchUpdateFields(request.ids(), request.fields());
    }

    public record BatchFieldsRequest(List<Long> ids, Map<String, Object> fields) {}

    // ====== 模具↔规格 关联 ======

    @RequirePermission("mold.manage")
    @PostMapping("/links")
    public MoldVariantLinkView addLink(@Valid @RequestBody MoldVariantLinkPayload payload) {
        return service.addVariantLink(payload);
    }

    @RequirePermission("mold.manage")
    @PutMapping("/links/{id}")
    public MoldVariantLinkView updateLink(@PathVariable Long id,
                                            @Valid @RequestBody MoldVariantLinkPayload payload) {
        return service.updateVariantLink(id, payload);
    }

    @RequirePermission("mold.manage")
    @DeleteMapping("/links/{id}")
    public void removeLink(@PathVariable Long id) {
        service.removeVariantLink(id);
    }

    // ====== 配件 ======

    @RequirePermission("mold.manage")
    @PostMapping("/accessories")
    public MoldAccessoryView addAccessory(@Valid @RequestBody MoldAccessoryPayload payload) {
        return service.addAccessory(payload);
    }

    @RequirePermission("mold.manage")
    @PutMapping("/accessories/{id}")
    public MoldAccessoryView updateAccessory(@PathVariable Long id,
                                                @Valid @RequestBody MoldAccessoryPayload payload) {
        return service.updateAccessory(id, payload);
    }

    @RequirePermission("mold.manage")
    @DeleteMapping("/accessories/{id}")
    public void removeAccessory(@PathVariable Long id) {
        service.removeAccessory(id);
    }

    // ====== Excel 导入 ======

    /**
     * 预览导入：上传 Excel，返回解析结果（新增/合并/冲突/待确认四分类），不落库。
     */
    @RequirePermission("mold.manage")
    @PostMapping("/import/preview")
    public MoldImportPreview previewImport(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "modelId", required = false) Long modelId) throws IOException {
        return importService.preview(file, modelId);
    }

    /**
     * 确认落库：基于预览结果，按选择行落库。
     * <p>前端回传 preview 数据 + rowIndexes；服务端无状态。
     * rowIndexes 为空则落库除 unverified/conflict 外的所有行。
     */
    @RequirePermission("mold.manage")
    @PostMapping("/import/confirm")
    public MoldImportConfirmResult confirmImport(@RequestBody MoldImportConfirmRequest request) {
        if (request.preview() == null) {
            throw new IllegalArgumentException("preview 不能为空");
        }
        return importService.confirm(request.preview(), request);
    }
}
