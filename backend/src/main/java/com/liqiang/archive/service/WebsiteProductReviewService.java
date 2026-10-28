package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Read-only review of website-owned draft content before any public API cutover. */
@Service
@RequiredArgsConstructor
public class WebsiteProductReviewService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public ReviewView get(Long id) {
        List<ReviewView> rows = jdbc.query("""
                SELECT p.id, p.category_id, p.name, p.name_en, p.public_model, p.slug, p.summary, p.summary_en,
                       p.description_html, p.description_html_en, p.application_tags,
                       p.container_tags, p.process_tags, p.capacity_level, p.seo_title,
                       p.seo_description, p.hero_media_id, p.card_media_id, p.sort_order,
                       p.featured, p.status, c.name AS category_name, c.slug AS category_slug
                FROM website_products p
                LEFT JOIN website_product_categories c ON c.id = p.category_id
                WHERE p.id = ? AND p.deleted_at IS NULL
                """, (rs, n) -> new ReviewView(
                rs.getLong("id"), (Long) rs.getObject("category_id"), rs.getString("name"), rs.getString("name_en"),
                rs.getString("public_model"), rs.getString("slug"), rs.getString("summary"),
                rs.getString("summary_en"), rs.getString("description_html"),
                rs.getString("description_html_en"), parseTags(rs.getString("application_tags")),
                parseTags(rs.getString("container_tags")), parseTags(rs.getString("process_tags")),
                rs.getString("capacity_level"), rs.getString("seo_title"),
                rs.getString("seo_description"), (Long) rs.getObject("hero_media_id"),
                (Long) rs.getObject("card_media_id"), rs.getInt("sort_order"),
                rs.getBoolean("featured"), rs.getString("status"), rs.getString("category_name"),
                rs.getString("category_slug"), List.of()), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在");
        }
        ReviewView product = rows.get(0);
        List<GalleryView> gallery = jdbc.query("""
                SELECT pm.id, pm.media_id, pm.display_role, pm.sort_order,
                       pm.alt_text, m.display_name, m.original_filename, m.status
                FROM website_product_media pm
                JOIN website_media_assets m ON m.id = pm.media_id
                WHERE pm.product_id = ? AND pm.variant_id IS NULL AND m.deleted_at IS NULL
                ORDER BY pm.sort_order, pm.id
                """, (rs, n) -> new GalleryView(rs.getLong("id"), rs.getLong("media_id"),
                rs.getString("display_role"), rs.getInt("sort_order"), rs.getString("alt_text"),
                rs.getString("display_name"), rs.getString("original_filename"),
                rs.getString("status")), id);
        return new ReviewView(product.id(), product.categoryId(), product.name(), product.nameEn(), product.publicModel(),
                product.slug(), product.summary(), product.summaryEn(), product.descriptionHtml(),
                product.descriptionHtmlEn(), product.applicationTags(), product.containerTags(),
                product.processTags(), product.capacityLevel(), product.seoTitle(),
                product.seoDescription(), product.heroMediaId(), product.cardMediaId(),
                product.sortOrder(), product.featured(), product.status(), product.categoryName(),
                product.categorySlug(), gallery);
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("官网产品筛选标签不是字符串数组", e);
        }
    }

    public record GalleryView(Long id, Long mediaId, String role, Integer sortOrder, String altText,
                              String displayName, String originalFilename, String mediaStatus) {}

    public record ReviewView(Long id, Long categoryId, String name, String nameEn, String publicModel, String slug,
                             String summary, String summaryEn, String descriptionHtml,
                             String descriptionHtmlEn, List<String> applicationTags,
                             List<String> containerTags, List<String> processTags,
                             String capacityLevel, String seoTitle, String seoDescription,
                             Long heroMediaId, Long cardMediaId, Integer sortOrder,
                             boolean featured, String status, String categoryName,
                             String categorySlug, List<GalleryView> gallery) {}
}
