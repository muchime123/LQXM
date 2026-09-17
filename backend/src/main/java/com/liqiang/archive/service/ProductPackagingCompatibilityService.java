package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.MachineTierPackagingAssignment;
import com.liqiang.archive.dto.CatalogDtos.PackagingSpecOption;
import com.liqiang.archive.dto.CatalogDtos.ProductPackagingCompatibilityMatrix;
import com.liqiang.archive.dto.CatalogDtos.ProductPackagingCompatibilityRequest;
import com.liqiang.archive.entity.*;
import com.liqiang.archive.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductPackagingCompatibilityService {
    private final ProductModelRepository modelRepository;
    private final ProductMachineTierRepository machineTierRepository;
    private final PackagingVariantRepository packagingVariantRepository;
    private final ProductMachineTierPackagingLinkRepository tierLinkRepository;
    private final ProductModelPackagingLinkRepository modelLinkRepository;
    private final ProductMachineTierCapabilityValidator capabilityValidator;
    private final PackagingApertureResolver apertureResolver;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public ProductPackagingCompatibilityMatrix getMatrix(Long modelId) {
        if (!modelRepository.existsById(modelId)) throw new EntityNotFoundException("产品不存在: " + modelId);
        boolean hasMachineTiers = !machineTierRepository.findByModelIdOrderBySortOrderAscIdAsc(modelId).isEmpty();
        List<PackagingSpecOption> options = packagingVariantRepository.findAllActiveWithModel().stream()
                .map(this::toOption).filter(Objects::nonNull).toList();
        List<Long> productIds = (hasMachineTiers ? List.<ProductModelPackagingLink>of()
                : modelLinkRepository.findByProductModelId(modelId)).stream()
                .map(link -> link.getPackagingVariant().getId()).distinct().toList();
        Map<Long, List<Long>> byTier = new LinkedHashMap<>();
        tierLinkRepository.findByProductModelId(modelId).forEach(link ->
                byTier.computeIfAbsent(link.getMachineTier().getId(), ignored -> new ArrayList<>())
                        .add(link.getPackagingVariant().getId()));
        List<MachineTierPackagingAssignment> assignments = byTier.entrySet().stream()
                .map(entry -> new MachineTierPackagingAssignment(entry.getKey(), entry.getValue())).toList();
        return new ProductPackagingCompatibilityMatrix(options, productIds, assignments);
    }

    @Transactional
    public ProductPackagingCompatibilityMatrix replace(Long modelId, ProductPackagingCompatibilityRequest request) {
        ProductModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new EntityNotFoundException("产品不存在: " + modelId));
        LinkedHashSet<Long> ids = new LinkedHashSet<>(request.packagingVariantIds() == null
                ? List.of() : request.packagingVariantIds());
        Map<Long, PackagingVariant> packaging = new LinkedHashMap<>();
        packagingVariantRepository.findAllById(ids).forEach(item -> packaging.put(item.getId(), item));
        if (packaging.size() != ids.size()) throw new IllegalArgumentException("选择的包材规格不存在或已删除");

        if (request.machineTierId() == null) {
            if (!machineTierRepository.findByModelIdOrderBySortOrderAscIdAsc(modelId).isEmpty()) {
                throw new IllegalArgumentException("当前产品已维护机型档位，请分别维护各机型的适配包材");
            }
            modelLinkRepository.deleteByProductModelId(modelId);
            modelLinkRepository.saveAll(packaging.values().stream().map(item -> {
                ProductModelPackagingLink link = new ProductModelPackagingLink();
                link.setProductModel(model); link.setPackagingVariant(item); link.setMatchSource("MANUAL");
                return link;
            }).toList());
            auditLogService.log("product", modelId, model.getName(), "update-packaging-compatibility",
                    "更新产品通用适配包材，共 " + ids.size() + " 项");
        } else {
            ProductMachineTier tier = machineTierRepository.findById(request.machineTierId())
                    .orElseThrow(() -> new EntityNotFoundException("机型档位不存在: " + request.machineTierId()));
            if (!Objects.equals(tier.getModel().getId(), modelId)) throw new IllegalArgumentException("机型档位不属于当前产品");
            for (PackagingVariant item : packaging.values()) {
                var aperture = apertureResolver.fromPackaging(item);
                if (aperture == null) throw new IllegalArgumentException("包材“" + label(item) + "”缺少可识别的封口口径");
                capabilityValidator.validate(tier, aperture);
            }
            tierLinkRepository.deleteByMachineTierId(tier.getId());
            tierLinkRepository.saveAll(packaging.values().stream().map(item -> {
                ProductMachineTierPackagingLink link = new ProductMachineTierPackagingLink();
                link.setMachineTier(tier); link.setPackagingVariant(item); link.setMatchSource("MANUAL");
                return link;
            }).toList());
            auditLogService.log("machine-tier", tier.getId(), model.getName(), "update-packaging-compatibility",
                    "更新机型适配包材，共 " + ids.size() + " 项");
        }
        return getMatrix(modelId);
    }

    private PackagingSpecOption toOption(PackagingVariant variant) {
        var aperture = apertureResolver.fromPackaging(variant);
        if (aperture == null) return null;
        String height = clean(variant.getHeightMm());
        String dimensions = height == null ? aperture.label()
                : aperture.label().replace("mm", "") + "×" + (height.toLowerCase().contains("mm") ? height : height + "mm");
        return new PackagingSpecOption(variant.getId(), variant.getModel().getId(), variant.getModel().getName(),
                variant.getVariantName(), aperture.shape(), aperture.key(), aperture.label(), height,
                clean(variant.getCapacityMl()), dimensions);
    }

    private String label(PackagingVariant variant) {
        return clean(variant.getVariantName()) == null ? variant.getModel().getName() : variant.getVariantName();
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
