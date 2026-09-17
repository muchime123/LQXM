package com.liqiang.archive.dto;

import com.liqiang.archive.entity.WebsitePageSection;

import java.time.LocalDateTime;

/** 官网页面区块内容 DTO。 */
public final class PageSectionDtos {

    /** 管理端视图（含内部字段）。 */
    public record PageSectionView(
            Long id,
            String pageKey,
            String sectionKey,
            String title,
            String subtitle,
            String content,
            String imageUrl,
            Integer sortOrder,
            Boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static PageSectionView from(WebsitePageSection section) {
            return new PageSectionView(
                    section.getId(),
                    section.getPageKey(),
                    section.getSectionKey(),
                    section.getTitle(),
                    section.getSubtitle(),
                    section.getContent(),
                    section.getImageUrl(),
                    section.getSortOrder(),
                    section.getEnabled(),
                    section.getCreatedAt(),
                    section.getUpdatedAt());
        }
    }

    /** 管理端写入载荷。 */
    public record PageSectionPayload(
            String pageKey,
            String sectionKey,
            String title,
            String subtitle,
            String content,
            String imageUrl,
            Integer sortOrder,
            Boolean enabled
    ) {
    }

    /** 官网公开视图（只读展示字段）。 */
    public record PublicPageSectionView(
            String pageKey,
            String sectionKey,
            String title,
            String subtitle,
            String content,
            String imageUrl,
            Integer sortOrder
    ) {
        public static PublicPageSectionView from(WebsitePageSection section) {
            return new PublicPageSectionView(
                    section.getPageKey(),
                    section.getSectionKey(),
                    section.getTitle(),
                    section.getSubtitle(),
                    section.getContent(),
                    section.getImageUrl(),
                    section.getSortOrder());
        }
    }

    private PageSectionDtos() {
    }
}