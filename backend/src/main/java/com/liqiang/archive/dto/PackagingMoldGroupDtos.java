package com.liqiang.archive.dto;

import com.liqiang.archive.entity.PackagingMoldGroup;

import java.time.LocalDateTime;

/**
 * 模具规格族相关 DTO。
 */
public class PackagingMoldGroupDtos {

    public record MoldGroupSummary(
            Long id,
            Long modelId,
            String modelName,
            String name,
            String normalizedKey,
            String shapeType,
            String lengthMm,
            String widthMm,
            String diameterMm,
            LocalDateTime createdAt,
            long moldCount
    ) {
        public static MoldGroupSummary from(PackagingMoldGroup g, long moldCount) {
            return new MoldGroupSummary(
                    g.getId(),
                    g.getModel() != null ? g.getModel().getId() : null,
                    g.getModel() != null ? g.getModel().getName() : null,
                    g.getName(),
                    g.getNormalizedKey(),
                    g.getShapeType(),
                    g.getLengthMm(),
                    g.getWidthMm(),
                    g.getDiameterMm(),
                    g.getCreatedAt(),
                    moldCount
            );
        }
    }

    public record MoldGroupPayload(
            Long modelId,
            String name,
            String shapeType,
            String lengthMm,
            String widthMm,
            String diameterMm,
            String remark
    ) {
    }
}
