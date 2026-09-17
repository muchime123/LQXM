package com.liqiang.archive.dto;

import com.liqiang.archive.entity.PlatformConfig;

import java.time.LocalDateTime;

public class PlatformConfigDtos {

    public record PlatformConfigView(
            Long id,
            String name,
            String icon,
            String color,
            Integer sortOrder,
            Boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static PlatformConfigView from(PlatformConfig config) {
            return new PlatformConfigView(
                    config.getId(),
                    config.getName(),
                    config.getIcon(),
                    config.getColor(),
                    config.getSortOrder(),
                    config.getIsActive(),
                    config.getCreatedAt(),
                    config.getUpdatedAt()
            );
        }
    }
}
