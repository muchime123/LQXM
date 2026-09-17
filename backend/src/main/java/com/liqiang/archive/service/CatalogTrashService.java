package com.liqiang.archive.service;

import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CatalogTrashService {

    private final ProductModelRepository modelRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductAssetRepository assetRepository;
    private final AuditLogService auditLogService;
    private final FileStorageService fileStorageService;
    private final EntityManager entityManager;

    // ====== 查询回收站 ======

    public List<ProductModel> listTrashModels() {
        return modelRepository.findDeleted();
    }

    @Transactional(readOnly = true)
    public List<ProductVariant> listTrashVariants() {
        String sql = """
            SELECT v.id, v.variant_name, v.model_id, v.deleted_at,
                   v.sort_order,
                   m.name AS model_name
            FROM product_variants v
            LEFT JOIN product_models m ON v.model_id = m.id
            WHERE v.deleted_at IS NOT NULL
            ORDER BY v.deleted_at DESC
            """;
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        List<Long> modelIds = rows.stream()
                .map(r -> ((Number) r[2]).longValue())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ProductModel> modelMap = new HashMap<>();
        if (!modelIds.isEmpty()) {
            modelRepository.findAllById(modelIds).forEach(m -> modelMap.put(m.getId(), m));
        }

        List<ProductVariant> result = new ArrayList<>();
        for (Object[] r : rows) {
            ProductVariant v = new ProductVariant();
            v.setId(((Number) r[0]).longValue());
            v.setVariantName((String) r[1]);
            if (r[3] != null) {
                v.setDeletedAt(((Timestamp) r[3]).toLocalDateTime());
            }
            if (r[4] != null) {
                v.setSortOrder(((Number) r[4]).intValue());
            }
            Long mid = ((Number) r[2]).longValue();
            ProductModel m = modelMap.get(mid);
            if (m != null) {
                v.setModel(m);
            } else {
                ProductModel deleted = new ProductModel();
                deleted.setId(mid);
                deleted.setName("(已删除)");
                v.setModel(deleted);
            }
            result.add(v);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ProductAsset> listTrashAssets() {
        String sql = """
            SELECT a.id, a.file_name, a.category, a.model_id, a.variant_id, a.deleted_at,
                   a.source_file, a.source_sheet, a.source_row_number,
                   m.name AS model_name
            FROM product_assets a
            LEFT JOIN product_models m ON a.model_id = m.id
            WHERE a.deleted_at IS NOT NULL
            ORDER BY a.deleted_at DESC
            """;
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        List<Long> modelIds = rows.stream()
                .map(r -> ((Number) r[3]).longValue())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ProductModel> modelMap = new HashMap<>();
        if (!modelIds.isEmpty()) {
            modelRepository.findAllById(modelIds).forEach(m -> modelMap.put(m.getId(), m));
        }

        List<ProductAsset> result = new ArrayList<>();
        for (Object[] r : rows) {
            ProductAsset a = new ProductAsset();
            a.setId(((Number) r[0]).longValue());
            a.setFileName((String) r[1]);
            a.setCategory((String) r[2]);
            if (r[4] != null) {
                ProductVariant variant = new ProductVariant();
                variant.setId(((Number) r[4]).longValue());
                a.setVariant(variant);
            }
            if (r[5] != null) {
                a.setDeletedAt(((Timestamp) r[5]).toLocalDateTime());
            }
            a.setSourceFile((String) r[6]);
            a.setSourceSheet((String) r[7]);
            if (r[8] != null) {
                a.setSourceRowNumber(((Number) r[8]).intValue());
            }
            Long mid = ((Number) r[3]).longValue();
            ProductModel m = modelMap.get(mid);
            if (m != null) {
                a.setModel(m);
            } else {
                ProductModel deleted = new ProductModel();
                deleted.setId(mid);
                deleted.setName("(已删除)");
                a.setModel(deleted);
            }
            result.add(a);
        }
        return result;
    }

    // ====== 恢复操作 ======

    @Transactional
    public void restoreModel(Long id) {
        ProductModel model = modelRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("产品主档不存在: " + id));
        if (model.getDeletedAt() == null) {
            throw new IllegalStateException("该产品未被删除");
        }
        model.setDeletedAt(null);
        modelRepository.save(model);
        // 同步恢复关联的素材和配置（删除产品时会被一起软删除）
        assetRepository.findDeletedByModelId(id).forEach(a -> assetRepository.restoreById(a.getId()));
        variantRepository.findDeletedByModelId(id).forEach(v -> variantRepository.restoreById(v.getId()));
        auditLogService.log("model", id, model.getName(), "restore",
                "从回收站恢复产品：" + model.getName());
    }

    @Transactional
    public void restoreVariant(Long id) {
        variantRepository.restoreById(id);
        auditLogService.log("variant", id, "回收站", "restore", "恢复配置 ID=" + id);
    }

    @Transactional
    public void restoreAsset(Long id) {
        assetRepository.restoreById(id);
        auditLogService.log("asset", id, "回收站", "restore", "恢复素材 ID=" + id);
    }

    // ====== 永久删除 ======

    @Transactional
    public void permanentDeleteModel(Long id) {
        // 批量永久删除关联的 variants 与 assets（替代循环单条 permanentDeleteById）
        List<Long> variantIds = variantRepository.findDeletedByModelId(id).stream()
                .map(ProductVariant::getId).toList();
        if (!variantIds.isEmpty()) {
            variantRepository.permanentDeleteByIds(variantIds);
        }
        List<Long> assetIds = assetRepository.findDeletedByModelId(id).stream()
                .map(ProductAsset::getId).toList();
        if (!assetIds.isEmpty()) {
            assetRepository.permanentDeleteByIds(assetIds);
        }
        modelRepository.permanentDeleteById(id);
        auditLogService.log("model", id, "回收站", "permanent_delete",
                "永久删除产品主档 #" + id + "（含变体 " + variantIds.size() + " 条、素材 " + assetIds.size() + " 条）");
    }

    @Transactional
    public void permanentDeleteVariant(Long id) {
        List<Long> deletedAssetIds = assetRepository.findDeletedByVariantId(id)
                .stream().map(ProductAsset::getId).toList();
        if (!deletedAssetIds.isEmpty()) {
            assetRepository.permanentDeleteByIds(deletedAssetIds);
        }
        variantRepository.permanentDeleteById(id);
        auditLogService.log("variant", id, "回收站", "permanent_delete",
                "永久删除配置 #" + id + "（含素材 " + deletedAssetIds.size() + " 条）");
    }

    @Transactional
    public void permanentDeleteAsset(Long id) {
        permanentDeleteAsset(id, false);
    }

    @Transactional
    public void permanentDeleteAsset(Long id, boolean deletePhysical) {
        if (deletePhysical) {
            ProductAsset asset = assetRepository.findByIdIncludingDeleted(id)
                    .orElseThrow(() -> new EntityNotFoundException("Asset does not exist: " + id));
            fileStorageService.deleteIfExists(asset.getModel().getId(), asset.getCategory(), asset.getFileName());
        }
        assetRepository.permanentDeleteById(id);
        auditLogService.log("asset", id, "回收站", "permanent_delete",
                "永久删除素材 #" + id + (deletePhysical ? "（含物理文件）" : ""));
    }

    // ====== 清空回收站 ======

    @Transactional
    public int emptyTrashModels() {
        List<ProductModel> deleted = modelRepository.findDeleted();
        if (deleted.isEmpty()) return 0;
        List<Long> ids = deleted.stream().map(ProductModel::getId).toList();
        for (Long id : ids) {
            List<Long> assetIds = assetRepository.findDeletedByModelId(id)
                    .stream().map(ProductAsset::getId).toList();
            if (!assetIds.isEmpty()) {
                assetRepository.permanentDeleteByIds(assetIds);
            }
            List<Long> variantIds = variantRepository.findDeletedByModelId(id)
                    .stream().map(ProductVariant::getId).toList();
            if (!variantIds.isEmpty()) {
                variantRepository.permanentDeleteByIds(variantIds);
            }
        }
        ids.forEach(modelRepository::permanentDeleteById);
        auditLogService.log("model", null, "回收站", "empty_trash",
                "清空产品回收站，永久删除 " + ids.size() + " 个主档");
        return ids.size();
    }

    @Transactional
    public int emptyTrashVariants() {
        List<ProductVariant> deleted = variantRepository.findAllDeleted();
        if (deleted.isEmpty()) return 0;
        List<Long> ids = deleted.stream().map(ProductVariant::getId).toList();
        for (Long vid : ids) {
            List<Long> assetIds = assetRepository.findDeletedByVariantId(vid)
                    .stream().map(ProductAsset::getId).toList();
            if (!assetIds.isEmpty()) {
                assetRepository.permanentDeleteByIds(assetIds);
            }
        }
        variantRepository.permanentDeleteByIds(ids);
        auditLogService.log("variant", null, "回收站", "empty_trash",
                "清空配置回收站，永久删除 " + ids.size() + " 个配置");
        return ids.size();
    }

    @Transactional
    public int emptyTrashAssets() {
        List<ProductAsset> deleted = assetRepository.findAllDeleted();
        if (deleted.isEmpty()) return 0;
        List<Long> ids = deleted.stream().map(ProductAsset::getId).toList();
        assetRepository.permanentDeleteByIds(ids);
        auditLogService.log("asset", null, "回收站", "empty_trash",
                "清空素材回收站，永久删除 " + ids.size() + " 个素材");
        return ids.size();
    }

    // ====== 批量操作 ======

    @Transactional
    public void batchRestoreModels(List<Long> ids) {
        ids.forEach(id -> {
            modelRepository.restoreById(id);
            // 同步恢复关联的素材和配置
            assetRepository.findDeletedByModelId(id).forEach(a -> assetRepository.restoreById(a.getId()));
            variantRepository.findDeletedByModelId(id).forEach(v -> variantRepository.restoreById(v.getId()));
            auditLogService.log("model", id, "回收站", "restore", "批量恢复产品 ID=" + id);
        });
    }

    @Transactional
    public void batchPurgeModels(List<Long> ids) {
        for (Long id : ids) {
            permanentDeleteModel(id);
        }
        auditLogService.log("model", null, "回收站", "batch_purge",
                "批量永久删除产品主档 " + ids.size() + " 个");
    }

    @Transactional
    public void batchRestoreVariants(List<Long> ids) {
        ids.forEach(id -> {
            variantRepository.restoreById(id);
            auditLogService.log("variant", id, "回收站", "restore", "批量恢复配置 ID=" + id);
        });
    }

    @Transactional
    public void batchPurgeVariants(List<Long> ids) {
        for (Long id : ids) {
            permanentDeleteVariant(id);
        }
        auditLogService.log("variant", null, "回收站", "batch_purge",
                "批量永久删除配置 " + ids.size() + " 个");
    }

    @Transactional
    public void batchRestoreAssets(List<Long> ids) {
        ids.forEach(id -> {
            assetRepository.restoreById(id);
            auditLogService.log("asset", id, "回收站", "restore", "批量恢复素材 ID=" + id);
        });
    }

    @Transactional
    public void batchPurgeAssets(List<Long> ids) {
        assetRepository.permanentDeleteByIds(ids);
        auditLogService.log("asset", null, "回收站", "batch_purge",
                "批量永久删除素材 " + ids.size() + " 个");
    }

    // ====== 统计与自动清理 ======

    @Transactional(readOnly = true)
    public Map<String, Object> trashStats() {
        List<ProductModel> models = modelRepository.findDeleted();
        List<ProductVariant> variants = variantRepository.findAllDeleted();
        List<ProductAsset> assets = assetRepository.findAllDeleted();
        return Map.of(
                "modelCount", models.size(),
                "variantCount", variants.size(),
                "assetCount", assets.size(),
                "totalCount", models.size() + variants.size() + assets.size()
        );
    }

    @Transactional
    public int autoCleanTrash(int daysOld) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);
        int totalCleaned = 0;

        List<Long> oldAssetIds = assetRepository.findAllDeleted().stream()
                .filter(a -> a.getDeletedAt() != null && a.getDeletedAt().isBefore(cutoff))
                .map(ProductAsset::getId).toList();
        if (!oldAssetIds.isEmpty()) {
            assetRepository.permanentDeleteByIds(oldAssetIds);
            totalCleaned += oldAssetIds.size();
        }

        List<Long> oldVariantIds = variantRepository.findAllDeleted().stream()
                .filter(v -> v.getDeletedAt() != null && v.getDeletedAt().isBefore(cutoff))
                .map(ProductVariant::getId).toList();
        for (Long vid : oldVariantIds) {
            List<Long> assetIds = assetRepository.findDeletedByVariantId(vid)
                    .stream().map(ProductAsset::getId).toList();
            if (!assetIds.isEmpty()) {
                assetRepository.permanentDeleteByIds(assetIds);
            }
        }
        if (!oldVariantIds.isEmpty()) {
            variantRepository.permanentDeleteByIds(oldVariantIds);
            totalCleaned += oldVariantIds.size();
        }

        List<Long> oldModelIds = modelRepository.findDeleted().stream()
                .filter(m -> m.getDeletedAt() != null && m.getDeletedAt().isBefore(cutoff))
                .map(ProductModel::getId).toList();
        for (Long mid : oldModelIds) {
            List<Long> vids = variantRepository.findDeletedByModelId(mid)
                    .stream().map(ProductVariant::getId).toList();
            if (!vids.isEmpty()) {
                for (Long vid : vids) {
                    List<Long> aids = assetRepository.findDeletedByVariantId(vid)
                            .stream().map(ProductAsset::getId).toList();
                    if (!aids.isEmpty()) assetRepository.permanentDeleteByIds(aids);
                }
                variantRepository.permanentDeleteByIds(vids);
            }
            List<Long> aids = assetRepository.findDeletedByModelId(mid)
                    .stream().map(ProductAsset::getId).toList();
            if (!aids.isEmpty()) assetRepository.permanentDeleteByIds(aids);
            modelRepository.permanentDeleteById(mid);
            totalCleaned++;
        }

        if (totalCleaned > 0) {
            auditLogService.log("trash", null, "回收站", "auto_clean",
                    "自动清理回收站，永久删除 " + totalCleaned + " 条过期记录（>=" + daysOld + " 天）");
        }
        return totalCleaned;
    }
}
