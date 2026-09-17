package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.MachineTierRequest;
import com.liqiang.archive.dto.CatalogDtos.MachineTierView;
import com.liqiang.archive.entity.ProductMachineTier;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.repository.ProductMachineTierRepository;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 机型档位 CRUD 服务。
 *
 * 描述同一产品按物理尺寸划分的销售机型（如 330 / 390 / 500）及其适配能力。
 */
@Service
@RequiredArgsConstructor
public class ProductMachineTierService {
    private final ProductMachineTierRepository tierRepository;
    private final ProductAssetRepository assetRepository;
    private final ProductModelRepository modelRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<MachineTierView> listByModel(Long modelId) {
        if (!modelRepository.existsById(modelId)) {
            throw new EntityNotFoundException("产品不存在: " + modelId);
        }
        return tierRepository.findByModelIdOrderBySortOrderAscIdAsc(modelId).stream()
                .map(MachineTierView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductMachineTier requireById(Long id) {
        return tierRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("机型档位不存在: " + id));
    }

    @Transactional
    public MachineTierView create(Long modelId, MachineTierRequest request) {
        ProductModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException("产品不存在: " + modelId));
        validate(request);
        tierRepository.findFirstByModelIdAndTierCodeIgnoreCase(modelId, request.tierCode())
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("该产品已存在相同机型档位: " + existing.getTierCode());
                });
        ProductMachineTier tier = new ProductMachineTier();
        tier.setModel(model);
        applyPayload(tier, request);
        tierRepository.save(tier);
        auditLogService.log("machine-tier", tier.getId(), model.getName(), "create",
                "新建机型档位: " + tierLabelForAudit(tier));
        return MachineTierView.from(tier, 0L);
    }

    @Transactional
    public MachineTierView update(Long id, MachineTierRequest request) {
        ProductMachineTier tier = requireById(id);
        validate(request);
        tierRepository.findFirstByModelIdAndTierCodeIgnoreCase(tier.getModel().getId(), request.tierCode())
                .filter(existing -> !Objects.equals(existing.getId(), id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("该产品已存在相同机型档位: " + existing.getTierCode());
                });
        applyPayload(tier, request);
        tierRepository.save(tier);
        auditLogService.log("machine-tier", tier.getId(), tier.getModel().getName(), "update",
                "更新机型档位: " + tierLabelForAudit(tier));
        return MachineTierView.from(tier);
    }

    @Transactional
    public void delete(Long id) {
        ProductMachineTier tier = requireById(id);
        long assetCount = assetRepository.countByMachineTiersId(id);
        if (assetCount > 0) {
            throw new IllegalStateException("该机型仍被 " + assetCount + " 个素材使用，请先将素材改为产品通用或迁移到其他机型");
        }
        tier.setDeletedAt(LocalDateTime.now());
        tierRepository.save(tier);
        auditLogService.log("machine-tier", tier.getId(), tier.getModel().getName(), "delete",
                "删除机型档位: " + tierLabelForAudit(tier));
    }

    private void validate(MachineTierRequest request) {
        if (request.tierCode() == null || request.tierCode().isBlank()) {
            throw new IllegalArgumentException("机型档位编码不能为空");
        }
        validateRange("圆形口径", request.roundDiameterMin(), request.roundDiameterMax());
        validateRange("方形长边", request.rectLengthMin(), request.rectLengthMax());
        validateRange("方形短边", request.rectWidthMin(), request.rectWidthMax());
        boolean supportsRound = request.roundDiameterMin() != null || request.roundDiameterMax() != null;
        boolean hasRectLength = request.rectLengthMin() != null || request.rectLengthMax() != null;
        boolean hasRectWidth = request.rectWidthMin() != null || request.rectWidthMax() != null;
        if (hasRectLength != hasRectWidth) {
            throw new IllegalArgumentException("方形口径必须同时配置长边和短边范围");
        }
        if (!supportsRound && !hasRectLength) {
            throw new IllegalArgumentException("请至少配置一种圆形或方形口径范围");
        }
    }

    private void validateRange(String label, BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException(label + "下限不能大于上限");
        }
    }

    private void applyPayload(ProductMachineTier tier, MachineTierRequest request) {
        tier.setTierCode(request.tierCode().trim());
        tier.setTierLabel(clean(request.tierLabel()));
        tier.setRoundDiameterMin(request.roundDiameterMin());
        tier.setRoundDiameterMax(request.roundDiameterMax());
        tier.setRectLengthMin(request.rectLengthMin());
        tier.setRectLengthMax(request.rectLengthMax());
        tier.setRectWidthMin(request.rectWidthMin());
        tier.setRectWidthMax(request.rectWidthMax());
        tier.setMachineSize(clean(request.machineSize()));
        tier.setNetWeightKg(clean(request.netWeightKg()));
        tier.setCartonSize(clean(request.cartonSize()));
        tier.setWoodBoxSize(clean(request.woodBoxSize()));
        tier.setGrossWeightKg(clean(request.grossWeightKg()));
        if (request.sortOrder() != null) {
            tier.setSortOrder(request.sortOrder());
        }
        tier.setRemark(clean(request.remark()));
    }

    private String tierLabelForAudit(ProductMachineTier tier) {
        String label = tier.getTierLabel();
        return (label == null || label.isBlank()) ? tier.getTierCode() : label;
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
