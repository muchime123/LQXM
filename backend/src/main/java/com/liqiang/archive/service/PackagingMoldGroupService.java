package com.liqiang.archive.service;

import com.liqiang.archive.dto.PackagingMoldGroupDtos.MoldGroupPayload;
import com.liqiang.archive.dto.PackagingMoldGroupDtos.MoldGroupSummary;
import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingMoldGroup;
import com.liqiang.archive.repository.PackagingModelRepository;
import com.liqiang.archive.repository.PackagingMoldGroupRepository;
import com.liqiang.archive.repository.PackagingMoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PackagingMoldGroupService {

    private final PackagingMoldGroupRepository groupRepo;
    private final PackagingModelRepository modelRepo;
    private final PackagingMoldRepository moldRepo;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<MoldGroupSummary> listByModel(Long modelId) {
        List<PackagingMoldGroup> groups = groupRepo.findByModelIdOrderByNameAsc(modelId);
        return groups.stream()
                .map(g -> MoldGroupSummary.from(g, moldRepo.countByGroupId(g.getId())))
                .toList();
    }

    @Transactional
    public MoldGroupSummary create(MoldGroupPayload payload) {
        PackagingModel model = modelRepo.findById(payload.modelId())
                .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + payload.modelId()));
        PackagingMoldGroup group = new PackagingMoldGroup();
        group.setModel(model);
        group.setName(payload.name());
        group.setShapeType(payload.shapeType());
        group.setLengthMm(payload.lengthMm());
        group.setWidthMm(payload.widthMm());
        group.setDiameterMm(payload.diameterMm());
        group.setRemark(payload.remark());
        applyNormalizedKey(group);
        groupRepo.save(group);
        auditLogService.log("PackagingMoldGroup", group.getId(), group.getName(),
                "PKG_MOLD_GROUP_CREATE", "创建模具规格族 " + group.getName());
        return MoldGroupSummary.from(group, 0);
    }

    /**
     * 按 modelId 和 name 查找或创建规格族。
     */
    @Transactional
    public PackagingMoldGroup findOrCreate(Long modelId, String name) {
        return groupRepo.findByModelIdAndName(modelId, name)
                .orElseGet(() -> {
                    PackagingModel model = modelRepo.findById(modelId)
                            .orElseThrow(() -> new IllegalArgumentException("包材系列不存在: " + modelId));
                    PackagingMoldGroup group = new PackagingMoldGroup();
                    group.setModel(model);
                    group.setName(name);
                    applyNormalizedKey(group);
                    return groupRepo.save(group);
                });
    }

    private void applyNormalizedKey(PackagingMoldGroup group) {
        String key = buildNormalizedKey(group);
        groupRepo.findByModelIdAndNormalizedKey(group.getModel().getId(), key)
                .filter(existing -> !existing.getId().equals(group.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("同系列已存在相同标准口径的规格族："
                            + existing.getName() + "（" + key + "）");
                });
        group.setNormalizedKey(key);
    }

    private String buildNormalizedKey(PackagingMoldGroup group) {
        Double length = parseDimension(group.getLengthMm());
        Double width = parseDimension(group.getWidthMm());
        Double diameter = parseDimension(group.getDiameterMm());
        if (length != null && width != null) {
            return "RECT:" + dimension(Math.min(length, width)) + "x" + dimension(Math.max(length, width));
        }
        if (diameter != null) return "ROUND:" + dimension(diameter);
        String name = group.getName() == null ? "" : group.getName().trim().replaceAll("\\s+", "")
                .replace('x', '×').replace('X', '×').replace('*', '×').replace('＊', '×');
        return "NAME:" + name.toLowerCase(Locale.ROOT);
    }

    private Double parseDimension(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String dimension(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
