package com.liqiang.archive.controller;

import com.liqiang.archive.dto.PackagingDtos.AssetView;
import com.liqiang.archive.dto.PackagingDtos.BatchDeleteRequest;
import com.liqiang.archive.dto.PackagingDtos.BatchResult;
import com.liqiang.archive.dto.PackagingDtos.BatchUpdateRequest;
import com.liqiang.archive.dto.PackagingDtos.ModelDetail;
import com.liqiang.archive.dto.PackagingDtos.ModelSummary;
import com.liqiang.archive.dto.PackagingDtos.ModelView;
import com.liqiang.archive.dto.PackagingDtos.MoveVariantRequest;
import com.liqiang.archive.dto.PackagingDtos.ReorderRequest;
import com.liqiang.archive.dto.PackagingDtos.VariantHistoryView;
import com.liqiang.archive.dto.PackagingDtos.VariantView;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.service.PackagingService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 包材管理 CRUD API
 * <p>路由前缀: /api/packaging
 */
@RestController
@RequestMapping("/api/packaging")
@RequirePermission("packaging.read")
@RequiredArgsConstructor
public class PackagingController {

    private final PackagingService service;

    // ====== Models ======

    @GetMapping("/models")
    public Page<ModelSummary> listModels(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String packagingKind,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {

        Sort.Direction sortDir = Sort.Direction.ASC;
        String sortField = "sortOrder";
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            sortField = parts[0];
            if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1])) {
                sortDir = Sort.Direction.DESC;
            }
        }
        Sort pageSort = "sortOrder".equals(sortField)
                ? Sort.by(sortDir, "sortOrder").and(Sort.by(Sort.Direction.ASC, "id"))
                : Sort.by(sortDir, sortField);
        return service.search(keyword, packagingKind, material, status,
                PageRequest.of(page, size, pageSort));
    }

    @GetMapping("/models/filters")
    public Map<String, List<String>> getFilterOptions() {
        return service.getFilterOptions();
    }

    @GetMapping("/models/{id}")
    public ModelDetail getModel(@PathVariable Long id) {
        return service.get(id);
    }

    @RequirePermission("packaging.manage")
    @PostMapping("/models")
    public ModelView createModel(@Valid @RequestBody PackagingModel model) {
        return service.createModel(model);
    }

    @RequirePermission("packaging.manage")
    @PutMapping("/models/{id}")
    public ModelView updateModel(@PathVariable Long id, @Valid @RequestBody PackagingModel model) {
        return service.updateModel(id, model);
    }

    @RequirePermission("packaging.manage")
    @DeleteMapping("/models/{id}")
    public void deleteModel(@PathVariable Long id) {
        service.deleteModel(id);
    }

    @RequirePermission("packaging.manage")
    @PostMapping("/models/batch-delete")
    public BatchResult batchDeleteModels(@RequestBody BatchDeleteRequest request) {
        return service.batchDeleteModels(request);
    }

    @RequirePermission("packaging.manage")
    @PutMapping("/models/batch")
    public BatchResult batchUpdateModels(@RequestBody BatchFieldsRequest request) {
        return service.batchUpdateModels(request.ids(), request.fields());
    }

    public record BatchFieldsRequest(List<Long> ids, Map<String, Object> fields) {}

    // ====== Variants ======

    @RequirePermission("packaging.manage")
    @PostMapping("/models/{id}/variants")
    public VariantView createVariant(@PathVariable Long id, @Valid @RequestBody PackagingVariant variant) {
        return service.createVariant(id, variant);
    }

    @RequirePermission("packaging.manage")
    @PutMapping("/variants/{id}")
    public VariantView updateVariant(@PathVariable Long id, @Valid @RequestBody PackagingVariant variant) {
        return service.updateVariant(id, variant);
    }

    @RequirePermission("packaging.manage")
    @DeleteMapping("/variants/{id}")
    public void deleteVariant(@PathVariable Long id) {
        service.deleteVariant(id);
    }

    @RequirePermission("packaging.manage")
    @PostMapping("/variants/batch-delete")
    public BatchResult batchDeleteVariants(@RequestBody BatchDeleteRequest request) {
        return service.batchDeleteVariants(request.ids());
    }

    @RequirePermission("packaging.manage")
    @PostMapping("/variants/batch-update")
    public BatchResult batchUpdateVariants(@RequestBody BatchUpdateRequest request) {
        return service.batchUpdateVariants(request);
    }

    @RequirePermission("packaging.manage")
    @PutMapping("/variants/reorder")
    public void reorderVariants(@RequestBody ReorderRequest request) {
        service.reorderVariants(request.items());
    }

    @RequirePermission("packaging.manage")
    @PostMapping("/variants/{id}/duplicate")
    public VariantView duplicateVariant(@PathVariable Long id) {
        return service.duplicateVariant(id);
    }

    @RequirePermission("packaging.manage")
    @PostMapping("/variants/{id}/move")
    public VariantView moveVariant(@PathVariable Long id, @RequestBody MoveVariantRequest request) {
        return service.moveVariant(id, request);
    }

    @GetMapping("/variants/{id}/history")
    public List<VariantHistoryView> listVariantHistory(@PathVariable Long id) {
        return service.listVariantHistory(id);
    }

    // ====== Variant Trash ======

    @GetMapping("/variants/trash")
    public List<VariantView> listVariantTrash(@RequestParam(required = false) Long modelId) {
        return service.listDeletedVariants(modelId);
    }

    @RequirePermission("packaging.manage")
    @PutMapping("/variants/trash/{id}/restore")
    public void restoreVariant(@PathVariable Long id) {
        service.restoreVariant(id);
    }

    @RequirePermission("packaging.manage")
    @DeleteMapping("/variants/trash/{id}")
    public void permanentDeleteVariant(@PathVariable Long id) {
        service.permanentDeleteVariant(id);
    }

    // ====== Assets ======

    @RequirePermission("packaging.manage")
    @PostMapping("/models/{id}/assets")
    public AssetView addAsset(@PathVariable Long id,
                              @RequestParam String category,
                              @RequestParam String fileName,
                              @RequestParam(required = false) String tags,
                              @RequestParam(required = false) Long variantId) {
        return service.addAsset(id, category, fileName, tags, variantId);
    }

    @RequirePermission("packaging.manage")
    @PutMapping("/assets/reorder")
    public void reorderAssets(@RequestBody ReorderRequest request) {
        service.reorderAssets(request.items());
    }
}
