package com.liqiang.archive.controller;

import com.liqiang.archive.dto.OfficeAssetDtos.AssetActionRequest;
import com.liqiang.archive.dto.OfficeAssetDtos.AssetStats;
import com.liqiang.archive.entity.OfficeAsset;
import com.liqiang.archive.entity.OfficeAssetRecord;
import com.liqiang.archive.service.OfficeAssetService;
import com.liqiang.archive.security.LoginOnly;
import com.liqiang.archive.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/office-assets")
@RequiredArgsConstructor
public class OfficeAssetController {
    private static final Set<String> SORTS = Set.of("assetCode", "name", "category", "status", "department", "updatedAt");
    private final OfficeAssetService service;

    @GetMapping public Page<OfficeAsset> list(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) String category, @RequestParam(required = false) String status,
        @RequestParam(required = false) String department, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "updatedAt") String sort,
        @RequestParam(defaultValue = "desc") String direction) {
        page = Math.max(0, page); size = Math.max(1, Math.min(100, size));
        if (!SORTS.contains(sort)) sort = "updatedAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return service.search(keyword, category, status, department, PageRequest.of(page, size, Sort.by(dir, sort)));
    }
    @GetMapping("/stats") public AssetStats stats() { return service.stats(); }
    @GetMapping("/scan") public OfficeAsset scan(@RequestParam String code) { return service.scan(code); }
    @GetMapping("/{id}/records") public List<OfficeAssetRecord> records(@PathVariable Long id) { return service.records(id); }
    @RequirePermission("officeAsset.manage")
    @PostMapping public OfficeAsset create(@Valid @RequestBody OfficeAsset asset) { return service.create(asset); }
    @RequirePermission("officeAsset.manage")
    @PutMapping("/{id}") public OfficeAsset update(@PathVariable Long id, @Valid @RequestBody OfficeAsset asset) { return service.update(id, asset); }
    // 借用/归还/流转为全员日常操作，不要求 officeAsset.manage
    @LoginOnly
    @PostMapping("/{id}/actions") public OfficeAsset action(@PathVariable Long id, @Valid @RequestBody AssetActionRequest request) { return service.action(id, request); }
    @RequirePermission("officeAsset.manage")
    @DeleteMapping("/{id}") public void delete(@PathVariable Long id) { service.delete(id); }

    /**
     * 通用批量修改字段：按白名单一次性更新选中资产的任意元数据字段。
     * body: { "ids": [...], "fields": { "status": "在库", "department": "研发部", "purchaseDate": "2026-01-01" } }
     * 未出现在 fields 中的字段不会被修改。
     */
    @RequirePermission("officeAsset.manage")
    @PutMapping("/batch")
    public Map<String, Integer> batchUpdateFields(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
                .map(Number::longValue).toList();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        int count = service.batchUpdateFields(ids, fields);
        return Map.of("affected", count);
    }
}
