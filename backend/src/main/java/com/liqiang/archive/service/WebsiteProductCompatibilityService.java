package com.liqiang.archive.service;

import com.liqiang.archive.entity.MoldVariantLink;
import com.liqiang.archive.entity.PackagingMold;
import com.liqiang.archive.entity.PackagingMoldGroup;
import com.liqiang.archive.entity.PackagingVariant;
import com.liqiang.archive.entity.ProductMachineTierPackagingLink;
import com.liqiang.archive.entity.ProductModelPackagingLink;
import com.liqiang.archive.repository.MoldVariantLinkRepository;
import com.liqiang.archive.repository.ProductMachineTierPackagingLinkRepository;
import com.liqiang.archive.repository.ProductModelPackagingLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 聚合产品/机型档位→包材规格→模具关系，输出官网安全字段。 */
@Service
@RequiredArgsConstructor
public class WebsiteProductCompatibilityService {
    private final ProductMachineTierPackagingLinkRepository tierPackagingRepository;
    private final ProductModelPackagingLinkRepository modelPackagingRepository;
    private final MoldVariantLinkRepository moldVariantLinkRepository;
    private final PackagingApertureResolver apertureResolver;

    public record CompatiblePackaging(Long productVariantId, String productVariantName,
                                      Long packagingModelId, String packagingModelName,
                                      String packagingKind, String material, String shapeType,
                                      Long packagingVariantId, String packagingVariantName,
                                      String apertureLabel, String capacityMl, Boolean customizable) {}

    public record CompatibleMold(Long packagingVariantId, String packagingModelName,
                                String packagingVariantName, Long moldId, String moldCategory,
                                Integer cavityCount, String relationType, Boolean defaultMold,
                                String apertureLabel) {}

    public record Snapshot(List<CompatiblePackaging> packaging, List<CompatibleMold> molds) {
        public static Snapshot empty() { return new Snapshot(List.of(), List.of()); }
    }

    @Transactional(readOnly = true)
    public Snapshot getForProduct(Long modelId) {
        List<CompatibilityEntry> links = new ArrayList<>();
        for (ProductMachineTierPackagingLink link : tierPackagingRepository.findByProductModelId(modelId)) {
            if (!"COMPATIBLE".equalsIgnoreCase(link.getRelationType())) continue;
            links.add(new CompatibilityEntry(link.getMachineTier().getId(),
                    firstNonBlank(link.getMachineTier().getTierLabel(), link.getMachineTier().getTierCode()),
                    link.getPackagingVariant()));
        }
        for (ProductModelPackagingLink link : modelPackagingRepository.findByProductModelId(modelId)) {
            if (!"COMPATIBLE".equalsIgnoreCase(link.getRelationType())) continue;
            links.add(new CompatibilityEntry(null, "产品通用", link.getPackagingVariant()));
        }
        List<CompatibilityEntry> visibleLinks = links.stream()
                .filter(link -> Boolean.TRUE.equals(link.packaging().getModel().getWebsiteEnabled()))
                .filter(link -> "在售".equals(link.packaging().getModel().getStatus())).toList();
        if (visibleLinks.isEmpty()) return Snapshot.empty();

        List<CompatiblePackaging> packaging = visibleLinks.stream().map(this::toPackaging).toList();
        List<Long> packagingVariantIds = visibleLinks.stream()
                .map(link -> link.packaging().getId())
                .distinct()
                .toList();

        List<MoldVariantLink> moldLinks = moldVariantLinkRepository
                .findWebsiteVisibleByPackagingVariantIds(packagingVariantIds);
        Map<String, CompatibleMold> uniqueMolds = new LinkedHashMap<>();
        for (MoldVariantLink moldLink : moldLinks) {
            CompatibleMold view = toMold(moldLink);
            uniqueMolds.putIfAbsent(view.packagingVariantId() + ":" + view.moldId(), view);
        }
        return new Snapshot(packaging, new ArrayList<>(uniqueMolds.values()));
    }

    private CompatiblePackaging toPackaging(CompatibilityEntry link) {
        PackagingVariant variant = link.packaging();
        var model = variant.getModel();
        var aperture = apertureResolver.fromPackaging(variant);
        return new CompatiblePackaging(link.machineTierId(), link.scopeName(),
                model.getId(), model.getName(), model.getPackagingKind(), model.getMaterial(), model.getShapeType(),
                variant.getId(), variant.getVariantName(), aperture == null ? null : aperture.label(),
                variant.getCapacityMl(), model.getCustomizable());
    }

    private record CompatibilityEntry(Long machineTierId, String scopeName, PackagingVariant packaging) {}

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private CompatibleMold toMold(MoldVariantLink moldLink) {
        PackagingVariant packaging = moldLink.getVariant();
        PackagingMold mold = moldLink.getMold();
        PackagingMoldGroup group = mold.getGroup();
        var aperture = apertureResolver.fromPackaging(packaging);
        String apertureLabel = aperture == null ? mold.getRawCaliber() : aperture.label();
        return new CompatibleMold(packaging.getId(), packaging.getModel().getName(), packaging.getVariantName(), mold.getId(),
                mold.getMoldCategory(), mold.getCavityCount(), moldLink.getRelationType(), moldLink.getIsDefault(),
                apertureLabel != null ? apertureLabel : group == null ? null : group.getName());
    }
}
