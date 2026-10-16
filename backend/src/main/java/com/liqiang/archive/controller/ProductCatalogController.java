package com.liqiang.archive.controller;

import com.liqiang.archive.dto.CatalogDtos.AssetView;
import com.liqiang.archive.dto.CatalogDtos.AssetMetadataRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchAssetMetadataRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchDeleteRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchResult;
import com.liqiang.archive.dto.CatalogDtos.BatchUpdateRequest;
import com.liqiang.archive.dto.CatalogDtos.MachineTierRequest;
import com.liqiang.archive.dto.CatalogDtos.MachineTierView;
import com.liqiang.archive.dto.CatalogDtos.ModelDetail;
import com.liqiang.archive.dto.CatalogDtos.ModelSimpleRow;
import com.liqiang.archive.dto.CatalogDtos.ModelSummary;
import com.liqiang.archive.dto.CatalogDtos.ModelView;
import com.liqiang.archive.dto.CatalogDtos.MoveAssetRequest;
import com.liqiang.archive.dto.CatalogDtos.ReorderRequest;
import com.liqiang.archive.dto.CatalogDtos.VideoAssetView;
import com.liqiang.archive.dto.CatalogDtos.ProductPackagingCompatibilityMatrix;
import com.liqiang.archive.dto.CatalogDtos.ProductPackagingCompatibilityRequest;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.service.ProductCatalogService;
import com.liqiang.archive.service.ProductMachineTierService;
import com.liqiang.archive.service.ProductPackagingCompatibilityService;
import com.liqiang.archive.service.VideoLibraryMaintenanceService;
import com.liqiang.archive.service.VideoPersonalWorkspaceService;
import com.liqiang.archive.service.ProductCatalogService.AssetPayload;
import com.liqiang.archive.security.LoginOnly;
import com.liqiang.archive.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 产品目录核心 CRUD：Models / Variants / Assets
 *
 * <p>说明、统计、导入接口已拆分至：
 * {@link CatalogManualController}、{@link CatalogStatsController}、
 * {@link CatalogImportController}。
 */
@RestController
@RequestMapping("/api/catalog")
@RequirePermission("product.read")
@RequiredArgsConstructor
public class ProductCatalogController {

    private final ProductCatalogService catalogService;
    private final ProductMachineTierService machineTierService;
    private final ProductPackagingCompatibilityService packagingCompatibilityService;
    private final VideoLibraryMaintenanceService videoMaintenanceService;
    private final VideoPersonalWorkspaceService videoWorkspaceService;

    // ====== Models ======

    @GetMapping("/models")
    public Page<ModelSummary> listModels(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String category,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String listFilter,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "12") int size) {
        String sortField = "name";
        Sort.Direction sortDir = Sort.Direction.ASC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            sortField = parts[0];
            if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1])) {
                sortDir = Sort.Direction.DESC;
            }
        }
        boolean computedSort = "variantCount".equals(sortField) || "assetCount".equals(sortField);
        Sort pageSort = "sortOrder".equals(sortField)
                ? Sort.by(sortDir, "sortOrder").and(Sort.by(Sort.Direction.ASC, "id"))
                : Sort.by(sortDir, sortField);
        Pageable pageable = computedSort
                ? PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name"))
                : PageRequest.of(page, size, pageSort);

        boolean hasFilter = (category != null && !category.isBlank())
                         || (status != null && !status.isBlank())
                         || (listFilter != null && !listFilter.isBlank());
        Page<ModelSummary> result;
        if (hasFilter) {
            result = catalogService.searchFiltered(keyword, category, status, listFilter, pageable);
        } else {
            result = catalogService.search(keyword, pageable);
        }
        if (computedSort) {
            List<ModelSummary> all = new ArrayList<>(result.getContent());
            final String finalField = sortField;
            final Sort.Direction finalDir = sortDir;
            all.sort((a, b) -> {
                long va = "variantCount".equals(finalField) ? a.variantCount() : a.assetCount();
                long vb = "variantCount".equals(finalField) ? b.variantCount() : b.assetCount();
                return finalDir == Sort.Direction.DESC ? Long.compare(vb, va) : Long.compare(va, vb);
            });
            return new PageImpl<>(all, PageRequest.of(page, size), result.getTotalElements());
        }
        return result;
    }

    @GetMapping("/models/filters")
    public Map<String, List<String>> getFilterOptions() {
        return catalogService.getFilterOptions();
    }

    @GetMapping("/models/simple")
    public List<ModelSimpleRow> listModelsSimple() {
        return catalogService.listAllSimple();
    }

    @GetMapping("/models/{id}")
    public ModelDetail getModel(@PathVariable Long id) {
        return catalogService.get(id);
    }

    @RequirePermission("product.manage")
    @PostMapping("/models")
    public ModelView createModel(@Valid @RequestBody ProductModel model) {
        return catalogService.createModel(model);
    }

    @RequirePermission("product.manage")
    @PutMapping("/models/{id}")
    public ModelView updateModel(@PathVariable Long id, @Valid @RequestBody ProductModel model) {
        return catalogService.updateModel(id, model);
    }

    @RequirePermission("product.manage")
    @DeleteMapping("/models/{id}")
    public void deleteModel(@PathVariable Long id) {
        catalogService.deleteModel(id);
    }

    @RequirePermission("product.manage")
    @PutMapping("/models/batch")
    public BatchResult batchUpdateModels(@RequestBody BatchUpdateRequest request) {
        return catalogService.batchUpdateModels(request);
    }

    /**
     * 官网发布字段批量更新。专用端点，仅接受 websiteEnabled / websiteFeatured /
     * websiteSlug / websiteSummary 字段，避免与通用 /batch 端点的 String setter 冲突。
     * body: { "ids": [1,2,3], "fields": { "websiteEnabled": "true", "websiteFeatured": "false" } }
     */
    @RequirePermission("product.manage")
    @PutMapping("/models/website-batch")
    public BatchResult batchUpdateWebsiteFields(@RequestBody WebsiteBatchRequest request) {
        return catalogService.batchUpdateWebsiteFields(request.ids(), request.fields());
    }

    /** 官网字段批量更新请求体。fields 的 value 统一为字符串，Boolean 字段由 service 端解析。 */
    public record WebsiteBatchRequest(List<Long> ids, Map<String, String> fields) {}

    /** 素材官网可见性批量更新请求体。fields 支持 websiteVisible / websiteSortOrder。 */
    public record WebsiteAssetBatchRequest(List<Long> ids, Map<String, String> fields) {}

    @RequirePermission("product.manage")
    @PostMapping("/models/batch-delete")
    public BatchResult batchDeleteModels(@RequestBody BatchDeleteRequest request) {
        return catalogService.batchDeleteModels(request);
    }

    @RequirePermission("product.manage")
    @PutMapping("/models/reorder")
    public void reorderModels(@RequestBody ReorderRequest request) {
        catalogService.reorderModels(request.items());
    }

    // ====== Machine Tiers（机型档位）======

    @GetMapping("/models/{id}/machine-tiers")
    public List<MachineTierView> listMachineTiers(@PathVariable Long id) {
        return machineTierService.listByModel(id);
    }

    @RequirePermission("product.manage")
    @PostMapping("/models/{id}/machine-tiers")
    public MachineTierView createMachineTier(@PathVariable Long id, @RequestBody MachineTierRequest request) {
        return machineTierService.create(id, request);
    }

    @RequirePermission("product.manage")
    @PutMapping("/machine-tiers/{id}")
    public MachineTierView updateMachineTier(@PathVariable Long id, @RequestBody MachineTierRequest request) {
        return machineTierService.update(id, request);
    }

    @RequirePermission("product.manage")
    @DeleteMapping("/machine-tiers/{id}")
    public void deleteMachineTier(@PathVariable Long id) {
        machineTierService.delete(id);
    }

    @GetMapping("/models/{id}/packaging-compatibility")
    public ProductPackagingCompatibilityMatrix getPackagingCompatibility(@PathVariable Long id) {
        return packagingCompatibilityService.getMatrix(id);
    }

    @RequirePermission("product.manage")
    @PutMapping("/models/{id}/packaging-compatibility")
    public ProductPackagingCompatibilityMatrix replacePackagingCompatibility(
            @PathVariable Long id, @RequestBody ProductPackagingCompatibilityRequest request) {
        return packagingCompatibilityService.replace(id, request);
    }

    // ====== Assets ======

    @RequirePermission("product.manage")
    @PostMapping("/models/{id}/assets")
    public AssetView addAsset(@PathVariable Long id, @RequestBody AssetPayload payload) {
        return AssetView.from(catalogService.addAsset(id, payload));
    }

    @RequirePermission("product.manage")
    @PutMapping("/assets/{id}/move")
    public AssetView moveAsset(@PathVariable Long id, @RequestBody MoveAssetRequest request) {
        return catalogService.moveAsset(id, request);
    }

    @RequirePermission("product.manage")
    @PutMapping("/assets/{id}/metadata")
    public AssetView updateAssetMetadata(@PathVariable Long id, @RequestBody AssetMetadataRequest request) {
        return catalogService.updateAssetMetadata(id, request);
    }

    @GetMapping("/assets/videos")
    public Page<VideoAssetView> searchVideos(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long modelId,
            @RequestParam(name = "productCategory", required = false) String productCategory,
            @RequestParam(required = false) String category,
            @RequestParam(name = "application", required = false) List<String> application,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) String usageStatus,
            @RequestParam(required = false) String usageChannel,
            @RequestParam(required = false) String contentStatus,
            @RequestParam(name = "featureTag", required = false) String featureTag,
            @RequestParam(name = "demonstrationTag", required = false) String demonstrationTag,
            @RequestParam(name = "machineTierId", required = false) Long machineTierId,
            @RequestParam(name = "hasCover", required = false) Boolean hasCover,
            @RequestParam(name = "incomplete", required = false) Boolean incomplete,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) String missingItem,
            @PageableDefault(size = 24, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return catalogService.searchVideos(keyword, modelId, productCategory, category, application, contentType,
                usageStatus, usageChannel, contentStatus, featureTag, demonstrationTag, machineTierId,
                hasCover, incomplete, scope, favorite, missingItem, pageable);
    }

    @GetMapping("/assets/videos/governance-summary")
    public Map<String, Object> videoGovernanceSummary() {
        return catalogService.videoGovernanceSummary();
    }

    // 收藏/浏览计数为个人工作台操作，全员开放
    @LoginOnly
    @PutMapping("/assets/videos/{assetId}/favorite")
    public Map<String, Object> setVideoFavorite(@PathVariable Long assetId,
                                                @RequestBody Map<String, Boolean> body) {
        boolean favorite = Boolean.TRUE.equals(body.get("favorite"));
        return Map.of("favorite", videoWorkspaceService.setFavorite(assetId, favorite));
    }

    @LoginOnly
    @PostMapping("/assets/videos/{assetId}/view")
    public void recordVideoView(@PathVariable Long assetId) {
        videoWorkspaceService.recordView(assetId);
    }

    @GetMapping("/assets/videos/recent")
    public List<Map<String, Object>> recentVideoViews(@RequestParam(defaultValue = "20") int limit) {
        return videoWorkspaceService.recentViews(limit);
    }

    @GetMapping("/assets/videos/collections")
    public List<Map<String, Object>> videoCollections() {
        return videoWorkspaceService.collections();
    }

    @LoginOnly
    @PostMapping("/assets/videos/collections")
    public Map<String, Object> createVideoCollection(@RequestBody Map<String, String> body) {
        return videoWorkspaceService.createCollection(body.get("name"), body.get("description"));
    }

    @GetMapping("/assets/videos/collections/{collectionId}/items")
    public List<Map<String, Object>> videoCollectionItems(@PathVariable Long collectionId) {
        return videoWorkspaceService.collectionItems(collectionId);
    }

    @LoginOnly
    @PostMapping("/assets/videos/collections/{collectionId}/items/{assetId}")
    public void addVideoToCollection(@PathVariable Long collectionId, @PathVariable Long assetId) {
        videoWorkspaceService.addToCollection(collectionId, assetId);
    }

    @LoginOnly
    @DeleteMapping("/assets/videos/collections/{collectionId}/items/{assetId}")
    public void removeVideoFromCollection(@PathVariable Long collectionId, @PathVariable Long assetId) {
        videoWorkspaceService.removeFromCollection(collectionId, assetId);
    }

    @GetMapping("/assets/videos/filter-options")
    public Map<String, List<String>> videoFilterOptions() {
        return catalogService.videoFilterOptions();
    }

    /** 为缺失摘要的视频计算 SHA-256，返回 {total, computed, alreadyHashed, missingFiles}。 */
    @RequirePermission("product.manage")
    @PostMapping("/assets/videos/hash-backfill")
    public Map<String, Object> backfillVideoHashes() {
        return videoMaintenanceService.backfillHashes();
    }

    /** 重复检测：exactGroups=同哈希精确重复，suspectedGroups=清洗后标题一致的疑似重复；仅提示不删除。 */
    @GetMapping("/assets/videos/duplicates")
    public Map<String, Object> videoDuplicates() {
        return videoMaintenanceService.findDuplicates();
    }

    /** 为缺失时长/分辨率信息的视频执行 ffprobe 提取。 */
    @RequirePermission("product.manage")
    @PostMapping("/assets/videos/media-info-backfill")
    public Map<String, Object> backfillVideoMediaInfo(@RequestParam(name = "limit", defaultValue = "500") int limit) {
        return videoMaintenanceService.backfillMediaInfo(limit);
    }

    /** 为单个视频生成自动封面（已有自定义封面时需 force=true 才覆盖）。 */
    @RequirePermission("product.manage")
    @PostMapping("/assets/videos/{assetId}/auto-cover")
    public Map<String, Object> autoCoverVideo(@PathVariable Long assetId,
                                              @RequestParam(name = "force", required = false) boolean force) {
        return videoMaintenanceService.autoCover(assetId, force);
    }

    /** 批量为无封面的视频生成自动封面。 */
    @RequirePermission("product.manage")
    @PostMapping("/assets/videos/auto-covers")
    public Map<String, Object> backfillAutoCovers(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        return videoMaintenanceService.backfillAutoCovers(limit);
    }

    @RequirePermission("product.manage")
    @PutMapping("/assets/batch-metadata")
    public BatchResult updateAssetMetadataBatch(@RequestBody BatchAssetMetadataRequest request) {
        return catalogService.updateAssetMetadataBatch(request);
    }

    /**
     * 素材官网可见性批量更新。专用端点，仅接受 websiteVisible / websiteSortOrder。
     * body: { "ids": [1,2,3], "fields": { "websiteVisible": "false", "websiteSortOrder": "1" } }
     */
    @RequirePermission("product.manage")
    @PutMapping("/assets/website-batch")
    public BatchResult batchUpdateAssetWebsiteFields(@RequestBody WebsiteAssetBatchRequest request) {
        return catalogService.batchUpdateAssetWebsiteFields(request.ids(), request.fields());
    }

    @RequirePermission("product.manage")
    @PutMapping("/assets/reorder")
    public void reorderAssets(@RequestBody ReorderRequest request) {
        catalogService.reorderAssets(request.items());
    }

}
