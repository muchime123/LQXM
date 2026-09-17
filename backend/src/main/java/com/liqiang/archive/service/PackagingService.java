package com.liqiang.archive.service;

import com.liqiang.archive.dto.PackagingDtos.AssetTagsRequest;
import com.liqiang.archive.dto.PackagingDtos.AssetView;
import com.liqiang.archive.dto.PackagingDtos.BatchDeleteRequest;
import com.liqiang.archive.dto.PackagingDtos.BatchResult;
import com.liqiang.archive.dto.PackagingDtos.BatchUpdateRequest;
import com.liqiang.archive.dto.PackagingDtos.ModelDetail;
import com.liqiang.archive.dto.PackagingDtos.ModelSummary;
import com.liqiang.archive.dto.PackagingDtos.ModelView;
import com.liqiang.archive.dto.PackagingDtos.MoveVariantRequest;
import com.liqiang.archive.dto.PackagingDtos.ReorderItem;
import com.liqiang.archive.dto.PackagingDtos.VariantHistoryView;
import com.liqiang.archive.dto.PackagingDtos.VariantView;
import com.liqiang.archive.entity.PackagingAsset;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.entity.PackagingVariantHistory;
import com.liqiang.archive.repository.PackagingAssetRepository;
import com.liqiang.archive.repository.PackagingModelRepository;
import com.liqiang.archive.repository.PackagingVariantHistoryRepository;
import com.liqiang.archive.repository.PackagingVariantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PackagingService {

    private static final Set<String> MODEL_BATCH_EDITABLE_FIELDS = Set.of(
            "nameEn", "modelCode", "packagingKind", "filmForm", "shapeType", "material", "colorStyle",
            "capacityMl", "applicable", "advantages", "moq", "unitPrice", "leadTime", "customizable",
            "status", "tags", "sortOrder", "websiteEnabled", "websiteSortOrder", "remark");

    private final PackagingModelRepository modelRepo;
    private final PackagingVariantRepository variantRepo;
    private final PackagingAssetRepository assetRepo;
    private final PackagingVariantHistoryRepository variantHistoryRepo;
    private final FileStorageService fileStorageService;
    private final PackagingSpecResolver packagingSpecResolver;
    private final AuditLogService auditLogService;

    // ====== Models ======

    @Transactional(readOnly = true)
    public Page<ModelSummary> search(String keyword, String packagingKind, String material,
                                      String status, Pageable pageable) {
        Page<PackagingModel> page = modelRepo.searchFiltered(keyword, packagingKind, material, status, pageable);
        List<PackagingModel> models = page.getContent();
        if (models.isEmpty()) {
            return page.map(m -> ModelSummary.from(m, 0L, 0L, null));
        }
        List<Long> modelIds = models.stream().map(PackagingModel::getId).toList();

        // 批量预取 variant/asset count，避免循环内 N+1 查询
        Map<Long, Long> variantCountMap = variantRepo.countByModelIds(modelIds).stream()
                .collect(Collectors.toMap(arr -> (Long) arr[0], arr -> (Long) arr[1]));
        Map<Long, Long> assetCountMap = assetRepo.countByModelIds(modelIds).stream()
                .collect(Collectors.toMap(arr -> (Long) arr[0], arr -> (Long) arr[1]));

        // 批量预取 defaultImage 对应的素材 ID，避免循环内 N+1 查询
        Map<Long, Map<String, Long>> defaultImageAssetMap = new HashMap<>();
        List<PackagingAsset> mainImageAssets = assetRepo.findAllByModelIdsAndCategory(modelIds, "pkg_main_image");
        for (PackagingAsset asset : mainImageAssets) {
            defaultImageAssetMap.computeIfAbsent(asset.getModel().getId(), k -> new HashMap<>())
                    .put(asset.getFileName(), asset.getId());
        }

        return page.map(m -> {
            Long mid = m.getId();
            long vc = variantCountMap.getOrDefault(mid, 0L);
            long ac = assetCountMap.getOrDefault(mid, 0L);
            Long defaultImageAssetId = null;
            if (m.getDefaultImage() != null && !m.getDefaultImage().isBlank()) {
                Map<String, Long> modelImageMap = defaultImageAssetMap.get(mid);
                if (modelImageMap != null) {
                    defaultImageAssetId = modelImageMap.get(m.getDefaultImage());
                }
            }
            return ModelSummary.from(m, vc, ac, defaultImageAssetId);
        });
    }

    @Transactional(readOnly = true)
    public ModelDetail get(Long id) {
        PackagingModel model = modelRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("包材不存在: " + id));
        List<PackagingVariant> variants = variantRepo.findByModelIdOrderBySortOrderAscIdAsc(id);
        List<PackagingAsset> assets = assetRepo.findByModelIdOrderBySortOrderAscIdAsc(id);
        return new ModelDetail(ModelView.from(model),
                variants.stream().map(VariantView::from).toList(),
                assets.stream().map(AssetView::from).toList(),
                variants.size(), assets.size());
    }

    @Transactional
    public ModelView createModel(PackagingModel model) {
        if (modelRepo.findByName(model.getName()).isPresent()) {
            throw new IllegalArgumentException("包材名称已存在: " + model.getName());
        }
        if (model.getSortOrder() == null) {
            model.setSortOrder(0);
        }
        if (model.getCustomizable() == null) {
            model.setCustomizable(false);
        }
        PackagingModel saved = modelRepo.save(model);
        auditLogService.log("PackagingModel", saved.getId(), saved.getName(),
                "PKG_MODEL_CREATE", "创建包材系列 " + saved.getName());
        return ModelView.from(saved);
    }

    @Transactional
    public ModelView updateModel(Long id, PackagingModel dto) {
        PackagingModel existing = modelRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("包材不存在: " + id));
        // Check name uniqueness if changed
        if (!existing.getName().equals(dto.getName())) {
            modelRepo.findByName(dto.getName()).ifPresent(m -> {
                throw new IllegalArgumentException("包材名称已存在: " + dto.getName());
            });
        }
        existing.setName(dto.getName());
        existing.setNameEn(dto.getNameEn());
        existing.setModelCode(dto.getModelCode());
        existing.setPackagingKind(dto.getPackagingKind());
        existing.setFilmForm(dto.getFilmForm());
        existing.setShapeType(dto.getShapeType());
        existing.setMaterial(dto.getMaterial());
        existing.setColorStyle(dto.getColorStyle());
        existing.setCapacityMl(dto.getCapacityMl());
        existing.setApplicable(dto.getApplicable());
        existing.setAdvantages(dto.getAdvantages());
        existing.setMoq(dto.getMoq());
        existing.setUnitPrice(dto.getUnitPrice());
        existing.setLeadTime(dto.getLeadTime());
        existing.setCustomizable(dto.getCustomizable());
        existing.setStatus(dto.getStatus());
        existing.setTags(dto.getTags());
        existing.setDefaultImage(dto.getDefaultImage());
        existing.setSortOrder(dto.getSortOrder());
        existing.setWebsiteEnabled(dto.getWebsiteEnabled());
        existing.setWebsiteSortOrder(dto.getWebsiteSortOrder());
        existing.setRemark(dto.getRemark());
        PackagingModel saved = modelRepo.save(existing);
        auditLogService.log("PackagingModel", saved.getId(), saved.getName(),
                "PKG_MODEL_UPDATE", "更新包材系列 " + saved.getName());
        return ModelView.from(saved);
    }

    @Transactional
    public void deleteModel(Long id) {
        PackagingModel model = modelRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("包材不存在: " + id));
        model.setDeletedAt(LocalDateTime.now());
        modelRepo.save(model);
        auditLogService.log("PackagingModel", id, model.getName(),
                "PKG_MODEL_DELETE", "移入回收站：包材系列 " + model.getName());
    }

    @Transactional
    public BatchResult batchDeleteModels(BatchDeleteRequest request) {
        List<PackagingModel> models = modelRepo.findAllById(request.ids());
        LocalDateTime now = LocalDateTime.now();
        for (PackagingModel m : models) {
            m.setDeletedAt(now);
        }
        if (!models.isEmpty()) {
            modelRepo.saveAll(models);
        }
        int count = models.size();
        if (count > 0) {
            auditLogService.log("PackagingModel", null, "包材系列",
                    "PKG_MODEL_BATCH_DELETE", "批量移入回收站：" + count + " 个包材系列");
        }
        return new BatchResult(count);
    }

    @Transactional
    public BatchResult batchUpdateModels(List<Long> ids, Map<String, Object> fields) {
        if (ids == null || ids.isEmpty()) return new BatchResult(0);
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("未指定要修改的字段");
        for (String key : fields.keySet()) {
            if (!MODEL_BATCH_EDITABLE_FIELDS.contains(key)) throw new IllegalArgumentException("不支持批量修改字段: " + key);
        }
        List<PackagingModel> models = modelRepo.findAllById(ids);
        for (PackagingModel model : models) {
            applyBatchModelFields(model, fields);
            validateWebsitePublication(model);
        }
        if (!models.isEmpty()) modelRepo.saveAll(models);
        if (!models.isEmpty()) auditLogService.log("PackagingModel", null, "包材系列", "PKG_MODEL_BATCH_EDIT",
                "批量修改 " + models.size() + " 个包材系列，字段：" + fields.keySet());
        return new BatchResult(models.size());
    }

    private void applyBatchModelFields(PackagingModel model, Map<String, Object> fields) {
        fields.forEach((key, value) -> {
            String text = value == null ? null : String.valueOf(value).trim();
            switch (key) {
                case "nameEn" -> model.setNameEn(text);
                case "modelCode" -> model.setModelCode(text);
                case "packagingKind" -> model.setPackagingKind(text);
                case "filmForm" -> model.setFilmForm(text);
                case "shapeType" -> model.setShapeType(text);
                case "material" -> model.setMaterial(text);
                case "colorStyle" -> model.setColorStyle(text);
                case "capacityMl" -> model.setCapacityMl(text);
                case "applicable" -> model.setApplicable(text);
                case "advantages" -> model.setAdvantages(text);
                case "moq" -> model.setMoq(text);
                case "unitPrice" -> model.setUnitPrice(text);
                case "leadTime" -> model.setLeadTime(text);
                case "customizable" -> model.setCustomizable(value instanceof Boolean b ? b : Boolean.parseBoolean(text));
                case "status" -> model.setStatus(text);
                case "tags" -> model.setTags(value instanceof List<?> items ? items.stream().map(String::valueOf).collect(Collectors.joining(",")) : text);
                case "sortOrder" -> model.setSortOrder(text == null || text.isBlank() ? 0 : Integer.valueOf(text));
                case "websiteEnabled" -> model.setWebsiteEnabled(value instanceof Boolean b ? b : Boolean.parseBoolean(text));
                case "websiteSortOrder" -> model.setWebsiteSortOrder(text == null || text.isBlank() ? 0 : Integer.valueOf(text));
                case "remark" -> model.setRemark(text);
                default -> throw new IllegalArgumentException("不支持批量修改字段: " + key);
            }
        });
    }

    private void validateWebsitePublication(PackagingModel model) {
        if (!Boolean.TRUE.equals(model.getWebsiteEnabled())) return;
        if (!"在售".equals(model.getStatus())) {
            throw new IllegalArgumentException("包材“" + model.getName() + "”当前状态不是“在售”，无法发布到官网");
        }
    }

    // ====== Variants ======

    @Transactional
    public VariantView createVariant(Long modelId, PackagingVariant variant) {
        PackagingModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("包材不存在: " + modelId));
        variant.setModel(model);
        if (variant.getSortOrder() == null) {
            variant.setSortOrder(0);
        }
        PackagingVariant saved = variantRepo.save(variant);
        saveVariantHistory(saved, collectVariantChanges(new PackagingVariant(), saved), "create", "manual", "创建规格初始值");
        auditLogService.log("PackagingVariant", saved.getId(), saved.getVariantName(),
                "PKG_VARIANT_CREATE", "创建包材规格 " + model.getName() + " / " + saved.getVariantName());
        return VariantView.from(saved);
    }

    @Transactional
    public VariantView updateVariant(Long id, PackagingVariant dto) {
        PackagingVariant existing = variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        List<FieldChange> changes = collectVariantChanges(existing, dto);
        existing.setVariantName(dto.getVariantName());
        existing.setCapacityMl(dto.getCapacityMl());
        existing.setShapeType(dto.getShapeType());
        existing.setLengthMm(dto.getLengthMm());
        existing.setWidthMm(dto.getWidthMm());
        existing.setHeightMm(dto.getHeightMm());
        existing.setTopDiameterMm(dto.getTopDiameterMm());
        existing.setBottomDiameterMm(dto.getBottomDiameterMm());
        existing.setWeightG(dto.getWeightG());
        existing.setBoxesPerRoll(dto.getBoxesPerRoll());
        existing.setCartonSize(dto.getCartonSize());
        existing.setCartonWeight(dto.getCartonWeight());
        existing.setCartonVolume(dto.getCartonVolume());
        existing.setVariantMoq(dto.getVariantMoq());
        existing.setMaterialDetail(dto.getMaterialDetail());
        existing.setFilmMaterial(dto.getFilmMaterial());
        existing.setFilmSupplyType(dto.getFilmSupplyType());
        existing.setFilmFeatures(dto.getFilmFeatures());
        existing.setPriceUnit(dto.getPriceUnit());
        existing.setPricingNotes(dto.getPricingNotes());
        existing.setDealerPrice(dto.getDealerPrice());
        existing.setRetailPrice(dto.getRetailPrice());
        existing.setDealerPriceUsd(dto.getDealerPriceUsd());
        existing.setRetailPriceUsd(dto.getRetailPriceUsd());
        existing.setPeelability(dto.getPeelability());
        existing.setThicknessUm(dto.getThicknessUm());
        existing.setTier5099(dto.getTier5099());
        existing.setTier100299(dto.getTier100299());
        existing.setTier300499(dto.getTier300499());
        existing.setTier500999(dto.getTier500999());
        existing.setTier10001999(dto.getTier10001999());
        existing.setTier2000Up(dto.getTier2000Up());
        existing.setPlateFee(dto.getPlateFee());
        existing.setSortOrder(dto.getSortOrder());
        existing.setRemark(dto.getRemark());
        // 先记录变更前的 variantName，用于后续同步素材 tags
        String oldVariantName = changes.stream()
                .filter(c -> "variantName".equals(c.key()))
                .map(FieldChange::oldValue)
                .findFirst().orElse(null);
        PackagingVariant saved = variantRepo.save(existing);
        // 规格名称变更时，同步该规格下所有素材的 tags
        if (oldVariantName != null && saved.getVariantName() != null
                && !saved.getVariantName().equals(oldVariantName)) {
            String newTags = normalizeTags(saved.getVariantName());
            List<PackagingAsset> linkedAssets = assetRepo.findByVariantIdOrderBySortOrderAscIdAsc(saved.getId()).stream()
                    .filter(a -> a.getDeletedAt() == null)
                    .collect(Collectors.toList());
            List<PackagingAsset> toSave = new ArrayList<>();
            for (PackagingAsset asset : linkedAssets) {
                asset.setTags(newTags);
                toSave.add(asset);
            }
            if (!toSave.isEmpty()) {
                assetRepo.saveAll(toSave);
                for (PackagingAsset asset : toSave) {
                    auditLogService.log("PackagingAsset", asset.getId(), asset.getFileName(),
                            "PKG_ASSET_TAGS_SYNC_FROM_VARIANT",
                            "规格名称变更同步素材标签: " + oldVariantName + " → " + saved.getVariantName());
                }
            }
        }
        saveVariantHistory(saved, changes, "update", "manual", null);
        auditLogService.log("PackagingVariant", saved.getId(), saved.getVariantName(),
                "PKG_VARIANT_UPDATE", "更新包材规格 " + saved.getVariantName());
        return VariantView.from(saved);
    }

    @Transactional
    public void deleteVariant(Long id) {
        PackagingVariant v = variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        v.setDeletedAt(LocalDateTime.now());
        variantRepo.save(v);
        auditLogService.log("PackagingVariant", id, v.getVariantName(),
                "PKG_VARIANT_DELETE", "移入回收站：包材规格 " + v.getVariantName());
    }

    @Transactional
    public BatchResult batchDeleteVariants(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BatchResult(0);
        }
        List<PackagingVariant> variants = variantRepo.findAllById(ids);
        LocalDateTime now = LocalDateTime.now();
        for (PackagingVariant v : variants) {
            v.setDeletedAt(now);
        }
        if (!variants.isEmpty()) {
            variantRepo.saveAll(variants);
        }
        int count = variants.size();
        if (count > 0) {
            auditLogService.log("PackagingVariant", null, "包材规格",
                    "PKG_VARIANT_BATCH_DELETE", "批量移入回收站：" + count + " 个包材规格");
        }
        return new BatchResult(count);
    }

    @Transactional
    public BatchResult batchUpdateVariants(BatchUpdateRequest request) {
        if (request.ids() == null || request.ids().isEmpty()) {
            return new BatchResult(0);
        }
        List<PackagingVariant> variants = variantRepo.findAllById(request.ids());
        for (PackagingVariant existing : variants) {
            applyVariantPayload(existing, request.payload());
        }
        if (!variants.isEmpty()) {
            variantRepo.saveAll(variants);
        }
        int count = variants.size();
        if (count > 0) {
            auditLogService.log("PackagingVariant", null, "包材规格",
                    "PKG_VARIANT_BATCH_UPDATE", "批量更新 " + count + " 个包材规格");
        }
        return new BatchResult(count);
    }

    private void applyVariantPayload(PackagingVariant existing, Map<String, Object> payload) {
        if (payload == null) return;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "variantName" -> existing.setVariantName(asString(value));
                case "capacityMl" -> existing.setCapacityMl(asString(value));
                case "shapeType" -> existing.setShapeType(asString(value));
                case "lengthMm" -> existing.setLengthMm(asString(value));
                case "widthMm" -> existing.setWidthMm(asString(value));
                case "heightMm" -> existing.setHeightMm(asString(value));
                case "topDiameterMm" -> existing.setTopDiameterMm(asString(value));
                case "bottomDiameterMm" -> existing.setBottomDiameterMm(asString(value));
                case "weightG" -> existing.setWeightG(asString(value));
                case "boxesPerRoll" -> existing.setBoxesPerRoll(asString(value));
                case "cartonSize" -> existing.setCartonSize(asString(value));
                case "cartonWeight" -> existing.setCartonWeight(asString(value));
                case "cartonVolume" -> existing.setCartonVolume(asString(value));
                case "variantMoq" -> existing.setVariantMoq(asString(value));
                case "materialDetail" -> existing.setMaterialDetail(asString(value));
                case "filmMaterial" -> existing.setFilmMaterial(asString(value));
                case "filmSupplyType" -> existing.setFilmSupplyType(asString(value));
                case "filmFeatures" -> existing.setFilmFeatures(asString(value));
                case "priceUnit" -> existing.setPriceUnit(asString(value));
                case "pricingNotes" -> existing.setPricingNotes(asString(value));
                case "dealerPrice" -> existing.setDealerPrice(asString(value));
                case "retailPrice" -> existing.setRetailPrice(asString(value));
                case "dealerPriceUsd" -> existing.setDealerPriceUsd(asString(value));
                case "retailPriceUsd" -> existing.setRetailPriceUsd(asString(value));
                case "peelability" -> existing.setPeelability(asString(value));
                case "thicknessUm" -> existing.setThicknessUm(asString(value));
                case "tier5099" -> existing.setTier5099(asBigDecimal(value));
                case "tier100299" -> existing.setTier100299(asBigDecimal(value));
                case "tier300499" -> existing.setTier300499(asBigDecimal(value));
                case "tier500999" -> existing.setTier500999(asBigDecimal(value));
                case "tier10001999" -> existing.setTier10001999(asBigDecimal(value));
                case "tier2000Up" -> existing.setTier2000Up(asBigDecimal(value));
                case "plateFee" -> existing.setPlateFee(asBigDecimal(value));
                case "remark" -> existing.setRemark(asString(value));
            }
        }
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value).trim() : null;
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        String str = String.valueOf(value).trim();
        return str.isEmpty() ? null : new BigDecimal(str);
    }

    @Transactional
    public void reorderVariants(List<ReorderItem> items) {
        for (ReorderItem item : items) {
            variantRepo.findById(item.id()).ifPresent(v -> {
                v.setSortOrder(item.sortOrder());
                variantRepo.save(v);
            });
        }
        if (items != null && !items.isEmpty()) {
            auditLogService.log("PackagingVariant", null, "包材规格",
                    "PKG_VARIANT_REORDER", "重排序 " + items.size() + " 个包材规格");
        }
    }

    @Transactional
    public VariantView duplicateVariant(Long id) {
        PackagingVariant source = variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规格不存在: " + id));
        PackagingVariant variant = new PackagingVariant();
        variant.setModel(source.getModel());
        variant.setVariantName((source.getVariantName() == null || source.getVariantName().isBlank()
                ? "规格"
                : source.getVariantName()) + " - 副本");
        variant.setCapacityMl(source.getCapacityMl());
        variant.setShapeType(source.getShapeType());
        variant.setLengthMm(source.getLengthMm());
        variant.setWidthMm(source.getWidthMm());
        variant.setHeightMm(source.getHeightMm());
        variant.setTopDiameterMm(source.getTopDiameterMm());
        variant.setBottomDiameterMm(source.getBottomDiameterMm());
        variant.setWeightG(source.getWeightG());
        variant.setBoxesPerRoll(source.getBoxesPerRoll());
        variant.setCartonSize(source.getCartonSize());
        variant.setCartonWeight(source.getCartonWeight());
        variant.setCartonVolume(source.getCartonVolume());
        variant.setVariantMoq(source.getVariantMoq());
        variant.setMaterialDetail(source.getMaterialDetail());
        variant.setFilmMaterial(source.getFilmMaterial());
        variant.setFilmSupplyType(source.getFilmSupplyType());
        variant.setFilmFeatures(source.getFilmFeatures());
        variant.setPriceUnit(source.getPriceUnit());
        variant.setPricingNotes(source.getPricingNotes());
        variant.setDealerPrice(source.getDealerPrice());
        variant.setRetailPrice(source.getRetailPrice());
        variant.setDealerPriceUsd(source.getDealerPriceUsd());
        variant.setRetailPriceUsd(source.getRetailPriceUsd());
        variant.setPeelability(source.getPeelability());
        variant.setThicknessUm(source.getThicknessUm());
        variant.setTier5099(source.getTier5099());
        variant.setTier100299(source.getTier100299());
        variant.setTier300499(source.getTier300499());
        variant.setTier500999(source.getTier500999());
        variant.setTier10001999(source.getTier10001999());
        variant.setTier2000Up(source.getTier2000Up());
        variant.setPlateFee(source.getPlateFee());
        variant.setSortOrder(source.getSortOrder());
        variant.setRemark(source.getRemark());
        variant.setSourceFile(source.getSourceFile());
        variant.setSourceSheet(source.getSourceSheet());
        variant.setImportBatchId(source.getImportBatchId());
        PackagingVariant saved = variantRepo.save(variant);
        auditLogService.log("PackagingVariant", saved.getId(), saved.getVariantName(),
                "PKG_VARIANT_DUPLICATE", "复制规格 #" + id + " 为 " + saved.getVariantName());
        return VariantView.from(saved);
    }

    @Transactional
    public VariantView moveVariant(Long id, MoveVariantRequest request) {
        PackagingVariant variant = variantRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("规格不存在: " + id));
        PackagingModel sourceModel = variant.getModel();
        PackagingModel targetModel = modelRepo.findById(request.targetModelId())
                .orElseThrow(() -> new IllegalArgumentException("目标包材不存在: " + request.targetModelId()));
        if (sourceModel.getId().equals(targetModel.getId())) {
            return VariantView.from(variant);
        }

        Integer maxSort = variantRepo.findByModelIdOrderBySortOrderAscIdAsc(targetModel.getId()).stream()
                .map(PackagingVariant::getSortOrder)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .orElse(0);
        variant.setModel(targetModel);
        variant.setSortOrder(maxSort + 1);

        assetRepo.findByModelIdOrderBySortOrderAscIdAsc(sourceModel.getId()).stream()
                .filter(asset -> asset.getVariant() != null && asset.getVariant().getId().equals(id))
                .forEach(asset -> {
                    asset.setModel(targetModel);
                    assetRepo.save(asset);
                });
        PackagingVariant saved = variantRepo.save(variant);
        auditLogService.log("PackagingVariant", saved.getId(), saved.getVariantName(),
                "PKG_VARIANT_MOVE", "移动规格 " + saved.getVariantName()
                        + " 从系列 #" + sourceModel.getId() + " 到 #" + targetModel.getId());
        return VariantView.from(saved);
    }

    // ====== Variant Trash ======

    @Transactional(readOnly = true)
    public List<VariantView> listDeletedVariants(Long modelId) {
        List<PackagingVariant> deleted;
        if (modelId != null) {
            deleted = variantRepo.findDeletedByModelId(modelId);
        } else {
            deleted = variantRepo.findAllDeleted();
        }
        return deleted.stream().map(VariantView::from).toList();
    }

    @Transactional
    public void restoreVariant(Long id) {
        variantRepo.restoreById(id);
        auditLogService.log("PackagingVariant", id, "包材规格",
                "PKG_VARIANT_RESTORE", "从回收站恢复规格 #" + id);
    }

    @Transactional
    public void permanentDeleteVariant(Long id) {
        variantRepo.permanentDeleteById(id);
        auditLogService.log("PackagingVariant", id, "包材规格",
                "PKG_VARIANT_PERMANENT_DELETE", "永久删除规格 #" + id);
    }

    // ====== Assets ======

    @Transactional
    public AssetView addAsset(Long modelId, String category, String fileName, String tags, Long variantId) {
        PackagingModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("包材不存在: " + modelId));
        PackagingVariant variant = findVariantForModel(modelId, variantId);
        PackagingAsset asset = new PackagingAsset();
        asset.setModel(model);
        asset.setVariant(variant);
        asset.setCategory(category);
        asset.setFileName(fileName);
        asset.setTags(tags);
        asset.setSortOrder(0);
        asset.setDefaultAsset(false);
        // Auto-set first main_image as default and sync model.defaultImage
        if ("pkg_main_image".equals(category)
                && variant == null
                && (model.getDefaultImage() == null || model.getDefaultImage().isBlank())) {
            asset.setDefaultAsset(true);
            model.setDefaultImage(fileName);
            modelRepo.save(model);
        }
        PackagingAsset saved = assetRepo.save(asset);
        auditLogService.log("PackagingAsset", saved.getId(), fileName,
                "PKG_ASSET_ADD", "添加包材素材 " + category + " / " + fileName);
        return AssetView.from(saved);
    }

    @Transactional
    public void deleteAsset(Long id) {
        PackagingAsset a = assetRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在: " + id));
        // If deleting the default main image, clear model.defaultImage
        if (a.getDefaultAsset() && "pkg_main_image".equals(a.getCategory()) && a.getVariant() == null) {
            PackagingModel model = a.getModel();
            model.setDefaultImage(null);
            modelRepo.save(model);
        }
        a.setDeletedAt(LocalDateTime.now());
        assetRepo.save(a);
        auditLogService.log("PackagingAsset", id, a.getFileName(),
                "PKG_ASSET_DELETE", "移入回收站：包材素材 " + a.getCategory() + " / " + a.getFileName());
    }

    @Transactional
    public void permanentDeleteAsset(Long id) {
        permanentDeleteAsset(id, false);
    }

    @Transactional
    public void permanentDeleteAsset(Long id, boolean deletePhysical) {
        if (deletePhysical) {
            PackagingAsset asset = assetRepo.findDeletedById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Deleted packaging asset does not exist: " + id));
            fileStorageService.deleteIfExists(asset.getModel().getId(), asset.getCategory(), asset.getFileName());
        }
        assetRepo.permanentDeleteById(id);
        auditLogService.log("PackagingAsset", id, "包材素材",
                "PKG_ASSET_PERMANENT_DELETE", "永久删除素材 #" + id + (deletePhysical ? "（含物理文件）" : ""));
    }

    @Transactional
    public AssetView setDefaultAsset(Long id) {
        PackagingAsset a = assetRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在: " + id));
        Long variantId = a.getVariant() != null ? a.getVariant().getId() : null;
        // Unset other defaults in the same scope: series-level or the same variant.
        assetRepo.findByModelIdOrderBySortOrderAscIdAsc(a.getModel().getId()).forEach(other -> {
            Long otherVariantId = other.getVariant() != null ? other.getVariant().getId() : null;
            boolean sameScope = (variantId == null && otherVariantId == null)
                    || (variantId != null && variantId.equals(otherVariantId));
            if (other.getDefaultAsset()
                    && sameScope
                    && other.getCategory().equals(a.getCategory())
                    && !other.getId().equals(id)) {
                other.setDefaultAsset(false);
                assetRepo.save(other);
            }
        });
        a.setDefaultAsset(true);
        PackagingAsset saved = assetRepo.save(a);
        // Sync model.defaultImage if it's a main image
        if ("pkg_main_image".equals(a.getCategory()) && a.getVariant() == null) {
            PackagingModel model = a.getModel();
            model.setDefaultImage(a.getFileName());
            modelRepo.save(model);
        }
        auditLogService.log("PackagingAsset", saved.getId(), saved.getFileName(),
                "PKG_ASSET_SET_DEFAULT", "设置默认素材 " + saved.getCategory() + " / " + saved.getFileName());
        return AssetView.from(saved);
    }

    @Transactional
    public AssetView updateAssetTags(Long id, String tags) {
        PackagingAsset a = assetRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在: " + id));
        a.setTags(tags);
        // 根据 tags 自动匹配/创建 variant（只针对主图类型且 tags 非空）
        String normalizedTags = normalizeTags(tags);
        if (normalizedTags != null && !normalizedTags.isBlank()
                && "pkg_main_image".equals(a.getCategory())) {
            PackagingVariant matched = findOrCreateVariantByTags(a.getModel().getId(), normalizedTags);
            a.setVariant(matched);
        } else if (normalizedTags == null || normalizedTags.isBlank()) {
            // 清空 tags 时解除 variant 绑定（避免空标签误占用规格）
            a.setVariant(null);
        }
        PackagingAsset saved = assetRepo.save(a);
        String extra = a.getVariant() != null ? (" → 关联规格 #" + a.getVariant().getId()) : "";
        auditLogService.log("PackagingAsset", saved.getId(), saved.getFileName(),
                "PKG_ASSET_UPDATE_TAGS", "更新素材标签 " + saved.getFileName() + extra);
        return AssetView.from(saved);
    }

    /**
     * 按规格标签匹配现有 variant，找不到则创建新的 variant。
     * tags 格式例如：「一出一，方模具」或「一出一,方模具」，使用中英文逗号分割。
     */
    private PackagingVariant findOrCreateVariantByTags(Long modelId, String tags) {
        String normalized = normalizeTags(tags);
        if (normalized == null || normalized.isBlank()) return null;
        PackagingModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("包材不存在: " + modelId));
        // 在现有 variant 中按 variantName 精确匹配（排除已删除）
        List<PackagingVariant> variants = variantRepo.findByModelIdOrderBySortOrderAscIdAsc(modelId).stream()
                .filter(v -> v.getDeletedAt() == null)
                .collect(Collectors.toList());
        Optional<PackagingVariant> existing = variants.stream()
                .filter(v -> tagEquals(v.getVariantName(), normalized))
                .findFirst();
        if (existing.isPresent()) return existing.get();
        // 创建新 variant：variantName 与 specLabel 统一设为标签组合
        PackagingVariant nv = new PackagingVariant();
        nv.setModel(model);
        nv.setVariantName(normalized);
        nv.setSortOrder(variants.size());
        PackagingVariant saved = variantRepo.save(nv);
        saveVariantHistory(saved, collectVariantChanges(new PackagingVariant(), saved),
                "create", "asset_tag_link", "由素材标签关联自动创建规格");
        auditLogService.log("PackagingVariant", saved.getId(), saved.getVariantName(),
                "PKG_VARIANT_CREATE_BY_TAG",
                "素材标签关联自动创建规格 " + model.getName() + " / " + saved.getVariantName());
        return saved;
    }

    /** 标签归一化：统一分隔符为中文逗号、去除首尾空白 */
    private String normalizeTags(String tags) {
        if (tags == null) return null;
        String t = tags.trim();
        if (t.isEmpty()) return "";
        // 英文逗号/空格 统一替换为中文逗号，再去除重复连续逗号
        t = t.replace(",", "，").replace(" ", "，");
        while (t.contains("，，")) t = t.replace("，，", "，");
        if (t.startsWith("，")) t = t.substring(1);
        if (t.endsWith("，")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** 比较两个标签串在归一化后是否相等 */
    private boolean tagEquals(String a, String b) {
        String na = normalizeTags(a);
        String nb = normalizeTags(b);
        if (na == null && nb == null) return true;
        if (na == null || nb == null) return false;
        return na.equals(nb);
    }

    @Transactional
    public void reorderAssets(List<ReorderItem> items) {
        for (ReorderItem item : items) {
            assetRepo.findById(item.id()).ifPresent(a -> {
                a.setSortOrder(item.sortOrder());
                assetRepo.save(a);
            });
        }
        if (items != null && !items.isEmpty()) {
            auditLogService.log("PackagingAsset", null, "包材素材",
                    "PKG_ASSET_REORDER", "重排序 " + items.size() + " 个包材素材");
        }
    }

    @Transactional(readOnly = true)
    public List<VariantHistoryView> listVariantHistory(Long variantId) {
        if (!variantRepo.existsById(variantId)) {
            throw new IllegalArgumentException("规格不存在: " + variantId);
        }
        try {
            return variantHistoryRepo.findByVariantIdOrderByCreatedAtDescIdDesc(variantId)
                    .stream()
                    .map(VariantHistoryView::from)
                    .toList();
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private List<FieldChange> collectVariantChanges(PackagingVariant oldValue, PackagingVariant newValue) {
        List<FieldChange> changes = new ArrayList<>();
        addChange(changes, "variantName", "规格名称", oldValue.getVariantName(), newValue.getVariantName());
        addChange(changes, "capacityMl", "容量", oldValue.getCapacityMl(), newValue.getCapacityMl());
        addChange(changes, "shapeType", "盒型形状", oldValue.getShapeType(), newValue.getShapeType());
        addChange(changes, "lengthMm", "长", oldValue.getLengthMm(), newValue.getLengthMm());
        addChange(changes, "widthMm", "宽", oldValue.getWidthMm(), newValue.getWidthMm());
        addChange(changes, "heightMm", "高", oldValue.getHeightMm(), newValue.getHeightMm());
        addChange(changes, "topDiameterMm", "上直径", oldValue.getTopDiameterMm(), newValue.getTopDiameterMm());
        addChange(changes, "bottomDiameterMm", "下直径", oldValue.getBottomDiameterMm(), newValue.getBottomDiameterMm());
        addChange(changes, "weightG", "克重", oldValue.getWeightG(), newValue.getWeightG());
        addChange(changes, "boxesPerRoll", "每卷盒数", oldValue.getBoxesPerRoll(), newValue.getBoxesPerRoll());
        addChange(changes, "cartonSize", "纸箱尺寸", oldValue.getCartonSize(), newValue.getCartonSize());
        addChange(changes, "cartonWeight", "整箱重量", oldValue.getCartonWeight(), newValue.getCartonWeight());
        addChange(changes, "cartonVolume", "整箱体积", oldValue.getCartonVolume(), newValue.getCartonVolume());
        addChange(changes, "variantMoq", "单规格起订量", oldValue.getVariantMoq(), newValue.getVariantMoq());
        addChange(changes, "materialDetail", "材质明细", oldValue.getMaterialDetail(), newValue.getMaterialDetail());
        addChange(changes, "filmMaterial", "膜材质", oldValue.getFilmMaterial(), newValue.getFilmMaterial());
        addChange(changes, "filmSupplyType", "供货类型", oldValue.getFilmSupplyType(), newValue.getFilmSupplyType());
        addChange(changes, "filmFeatures", "功能/工艺", oldValue.getFilmFeatures(), newValue.getFilmFeatures());
        addChange(changes, "priceUnit", "计价单位", oldValue.getPriceUnit(), newValue.getPriceUnit());
        addChange(changes, "pricingNotes", "报价备注", oldValue.getPricingNotes(), newValue.getPricingNotes());
        addChange(changes, "dealerPrice", "经销价", oldValue.getDealerPrice(), newValue.getDealerPrice());
        addChange(changes, "retailPrice", "零售价", oldValue.getRetailPrice(), newValue.getRetailPrice());
        addChange(changes, "dealerPriceUsd", "经销价USD", oldValue.getDealerPriceUsd(), newValue.getDealerPriceUsd());
        addChange(changes, "retailPriceUsd", "零售价USD", oldValue.getRetailPriceUsd(), newValue.getRetailPriceUsd());
        addChange(changes, "peelability", "易揭性", oldValue.getPeelability(), newValue.getPeelability());
        addChange(changes, "thicknessUm", "厚度", oldValue.getThicknessUm(), newValue.getThicknessUm());
        addChange(changes, "tier5099", "50-99kg", oldValue.getTier5099(), newValue.getTier5099());
        addChange(changes, "tier100299", "100-299kg", oldValue.getTier100299(), newValue.getTier100299());
        addChange(changes, "tier300499", "300-499kg", oldValue.getTier300499(), newValue.getTier300499());
        addChange(changes, "tier500999", "500-999kg", oldValue.getTier500999(), newValue.getTier500999());
        addChange(changes, "tier10001999", "1000-1999kg", oldValue.getTier10001999(), newValue.getTier10001999());
        addChange(changes, "tier2000Up", "2000kg+", oldValue.getTier2000Up(), newValue.getTier2000Up());
        addChange(changes, "plateFee", "版费", oldValue.getPlateFee(), newValue.getPlateFee());
        addChange(changes, "sortOrder", "排序", oldValue.getSortOrder(), newValue.getSortOrder());
        addChange(changes, "remark", "备注", oldValue.getRemark(), newValue.getRemark());
        return changes;
    }

    private void addChange(List<FieldChange> changes, String key, String label, Object oldValue, Object newValue) {
        String oldText = normalizeHistoryValue(oldValue);
        String newText = normalizeHistoryValue(newValue);
        if (!Objects.equals(oldText, newText)) {
            changes.add(new FieldChange(key, label, oldText, newText));
        }
    }

    private String normalizeHistoryValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString().trim();
        return text;
    }

    private void saveVariantHistory(PackagingVariant variant, List<FieldChange> changes,
                                    String changeType, String changeSource, String changeReason) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            for (FieldChange change : changes) {
                PackagingVariantHistory history = new PackagingVariantHistory();
                history.setVariant(variant);
                history.setModel(variant.getModel());
                history.setFieldKey(change.key());
                history.setFieldLabel(change.label());
                history.setOldValue(change.oldValue());
                history.setNewValue(change.newValue());
                history.setChangeType(changeType);
                history.setChangeSource(changeSource);
                history.setChangeReason(changeReason);
                history.setSourceFile(variant.getSourceFile());
                history.setSourceSheet(variant.getSourceSheet());
                variantHistoryRepo.save(history);
            }
        } catch (DataAccessException ignored) {
            // History is a supporting audit trail and should not block packaging edits.
        }
    }

    private record FieldChange(String key, String label, String oldValue, String newValue) {}

    private PackagingVariant findVariantForModel(Long modelId, Long variantId) {
        if (variantId == null) {
            return null;
        }
        PackagingVariant variant = variantRepo.findById(variantId)
                .orElseThrow(() -> new IllegalArgumentException("閰嶇疆涓嶅瓨鍦? " + variantId));
        if (variant.getModel() == null || !modelId.equals(variant.getModel().getId())) {
            throw new IllegalArgumentException("瑙勬牸涓嶅睘浜庤鍖呮潗绯诲垪: " + variantId);
        }
        return variant;
    }

    // ====== Stats ======

    @Transactional(readOnly = true)
    public Map<String, List<String>> getFilterOptions() {
        Map<String, List<String>> filters = new HashMap<>();
        filters.put("packagingKind", modelRepo.findDistinctKinds());
        filters.put("material", modelRepo.findDistinctMaterials());
        filters.put("status", modelRepo.findDistinctStatuses());
        return filters;
    }

}
