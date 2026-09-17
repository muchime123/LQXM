package com.liqiang.archive.dto;

import com.liqiang.archive.entity.WebsiteSolution.SolutionSection;

import java.time.LocalDateTime;
import java.util.List;

public final class SolutionDtos {

    public record SolutionView(
            Long id,
            SolutionSection section,
            String itemKey,
            String slug,
            String title,
            String category,
            String summary,
            String coverImageUrl,
            String audience,
            String takeaway,
            String intro,
            String icon,
            List<String> points,
            List<ContentSection> contentSections,
            String contentHtml,
            List<String> relatedSceneKeys,
            String seoTitle,
            String seoDescription,
            Integer sortOrder,
            Boolean websiteEnabled,
            Boolean websiteFeatured,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record SolutionPayload(
            SolutionSection section,
            String itemKey,
            String slug,
            String title,
            String category,
            String summary,
            String coverImageUrl,
            String audience,
            String takeaway,
            String intro,
            String icon,
            List<String> points,
            List<ContentSection> contentSections,
            String contentHtml,
            List<String> relatedSceneKeys,
            String seoTitle,
            String seoDescription,
            Integer sortOrder,
            Boolean websiteEnabled,
            Boolean websiteFeatured
    ) {
    }

    public record ContentSection(String title, String body, List<String> points, String imageUrl) {
    }

    public record SolutionSectionGroup(
            SolutionSection section,
            List<SolutionView> items
    ) {
    }

    private SolutionDtos() {
    }
}
