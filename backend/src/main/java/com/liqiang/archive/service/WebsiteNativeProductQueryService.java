package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Published product reads owned exclusively by website tables. No archive services or IDs. */
@Service
@RequiredArgsConstructor
public class WebsiteNativeProductQueryService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final String ELIGIBLE = """
            p.status = 'PUBLISHED' AND p.deleted_at IS NULL
            AND (p.category_id IS NULL OR (c.status = 'PUBLISHED' AND c.deleted_at IS NULL))
            """;
    private static final String FROM = """
            FROM website_products p
            LEFT JOIN website_product_categories c ON c.id = p.category_id
            LEFT JOIN website_media_assets hm ON hm.id = p.hero_media_id
            LEFT JOIN website_media_assets cm ON cm.id = p.card_media_id
            """;
    private static final String FIELDS_TEMPLATE = """
            SELECT p.id, p.name, p.name_en, p.public_model, p.slug, p.summary,
                   p.application_tags, p.container_tags, p.process_tags,
                   p.capacity_level, p.featured, p.sort_order, p.seo_title, p.seo_description,
                   p.description_html, p.description_html_en, p.technical_parameters,
                   c.name AS category_name,
                   CASE WHEN hm.status %s AND hm.deleted_at IS NULL
                        THEN hm.id ELSE NULL END AS visible_hero_media_id,
                   CASE WHEN cm.status %s AND cm.deleted_at IS NULL
                        THEN cm.id ELSE NULL END AS visible_card_media_id
            """;
    private static final String FIELDS = FIELDS_TEMPLATE.formatted("= 'PUBLISHED'", "= 'PUBLISHED'");
    private static final String PREVIEW_FIELDS = FIELDS_TEMPLATE.formatted(
            "IN ('DRAFT', 'PUBLISHED')", "IN ('DRAFT', 'PUBLISHED')");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public PageView list(String keyword, String category, String application, String container,
                         String process, String capacity, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 100));
        List<Object> args = new ArrayList<>();
        String where = conditions(keyword, category, application, container, process, capacity, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + FROM + " WHERE " + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<ProductView> items = jdbc.query(FIELDS + FROM + " WHERE " + where
                        + " ORDER BY p.sort_order, p.id LIMIT ? OFFSET ?",
                (rs, n) -> product(rs), pageArgs.toArray());
        long count = total == null ? 0 : total;
        return new PageView(items, count, (int) ((count + size - 1) / size), page);
    }

    @Transactional(readOnly = true)
    public List<ProductView> featured(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        return jdbc.query(FIELDS + FROM + " WHERE " + ELIGIBLE
                        + " AND p.featured = TRUE ORDER BY p.sort_order, p.id LIMIT ?",
                (rs, n) -> product(rs), limit);
    }

    @Transactional(readOnly = true)
    public Optional<DetailView> detail(String slug) {
        List<DetailView> products = jdbc.query(FIELDS + FROM + " WHERE " + ELIGIBLE
                        + " AND p.slug = ? LIMIT 1",
                (rs, n) -> new DetailView(product(rs), rs.getString("description_html"),
                        rs.getString("description_html_en"), jsonMap(rs.getString("technical_parameters")),
                        List.of(), List.of()), slug);
        if (products.isEmpty()) return Optional.empty();
        DetailView detail = products.get(0);
        Long id = detail.product().id();
        List<GalleryView> gallery = jdbc.query("""
                SELECT pm.id, pm.media_id, pm.display_role, pm.alt_text, pm.sort_order
                FROM website_product_media pm
                JOIN website_media_assets m ON m.id = pm.media_id
                WHERE pm.product_id = ? AND pm.variant_id IS NULL
                  AND m.status = 'PUBLISHED' AND m.deleted_at IS NULL
                ORDER BY pm.sort_order, pm.id
                """, (rs, n) -> new GalleryView(rs.getLong("id"), rs.getLong("media_id"),
                rs.getString("display_role"), rs.getString("alt_text"), rs.getInt("sort_order")), id);
        List<VariantView> variants = jdbc.query("""
                SELECT id, name, public_sku, description, dimensions, technical_parameters, sort_order
                FROM website_product_variants
                WHERE product_id = ? AND status = 'PUBLISHED' AND deleted_at IS NULL
                ORDER BY sort_order, id
                """, (rs, n) -> new VariantView(rs.getLong("id"), rs.getString("name"),
                rs.getString("public_sku"), rs.getString("description"), rs.getString("dimensions"),
                jsonMap(rs.getString("technical_parameters")), rs.getInt("sort_order")), id);
        return Optional.of(new DetailView(detail.product(), detail.descriptionHtml(),
                detail.descriptionHtmlEn(), detail.technicalParameters(), gallery, variants));
    }

    /** Admin-only preview of the future public DTO, including draft website media IDs. */
    @Transactional(readOnly = true)
    public Optional<DetailView> previewDetail(Long id) {
        List<DetailView> products = jdbc.query(PREVIEW_FIELDS + FROM + """
                        WHERE p.id = ? AND p.status IN ('DRAFT', 'PUBLISHED') AND p.deleted_at IS NULL
                        LIMIT 1
                        """,
                (rs, n) -> new DetailView(product(rs), rs.getString("description_html"),
                        rs.getString("description_html_en"), jsonMap(rs.getString("technical_parameters")),
                        List.of(), List.of()), id);
        if (products.isEmpty()) return Optional.empty();
        DetailView detail = products.get(0);
        List<GalleryView> gallery = jdbc.query("""
                SELECT pm.id, pm.media_id, pm.display_role, pm.alt_text, pm.sort_order
                FROM website_product_media pm
                JOIN website_media_assets m ON m.id = pm.media_id
                WHERE pm.product_id = ? AND pm.variant_id IS NULL
                  AND m.status IN ('DRAFT', 'PUBLISHED') AND m.deleted_at IS NULL
                ORDER BY pm.sort_order, pm.id
                """, (rs, n) -> new GalleryView(rs.getLong("id"), rs.getLong("media_id"),
                rs.getString("display_role"), rs.getString("alt_text"), rs.getInt("sort_order")), id);
        List<VariantView> variants = jdbc.query("""
                SELECT id, name, public_sku, description, dimensions, technical_parameters, sort_order
                FROM website_product_variants
                WHERE product_id = ? AND status IN ('DRAFT', 'PUBLISHED') AND deleted_at IS NULL
                ORDER BY sort_order, id
                """, (rs, n) -> new VariantView(rs.getLong("id"), rs.getString("name"),
                rs.getString("public_sku"), rs.getString("description"), rs.getString("dimensions"),
                jsonMap(rs.getString("technical_parameters")), rs.getInt("sort_order")), id);
        return Optional.of(new DetailView(detail.product(), detail.descriptionHtml(),
                detail.descriptionHtmlEn(), detail.technicalParameters(), gallery, variants));
    }

    @Transactional(readOnly = true)
    public List<String> categories() {
        return jdbc.query("""
                SELECT c.name FROM website_product_categories c
                WHERE c.status = 'PUBLISHED' AND c.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM website_products p
                              WHERE p.category_id = c.id AND p.status = 'PUBLISHED'
                                AND p.deleted_at IS NULL)
                ORDER BY c.sort_order, c.id
                """, (rs, n) -> rs.getString(1));
    }

    /** Filter values belong to published website products, never archive dictionaries. */
    @Transactional(readOnly = true)
    public CategoryFiltersView filterOptions() {
        List<String> categoryNames = categories();
        var applications = new LinkedHashSet<String>();
        var containers = new LinkedHashSet<String>();
        var processes = new LinkedHashSet<String>();
        var capacities = new LinkedHashSet<String>();
        List<FilterTags> rows = jdbc.query("""
                SELECT p.application_tags, p.container_tags, p.process_tags, p.capacity_level
                FROM website_products p
                LEFT JOIN website_product_categories c ON c.id = p.category_id
                WHERE """ + ELIGIBLE + " ORDER BY p.sort_order, p.id",
                (rs, n) -> new FilterTags(rs.getString("application_tags"),
                        rs.getString("container_tags"), rs.getString("process_tags"),
                        rs.getString("capacity_level")));
        for (FilterTags row : rows) {
            applications.addAll(jsonList(row.applications()));
            containers.addAll(jsonList(row.containers()));
            processes.addAll(jsonList(row.processes()));
            if (hasText(row.capacity())) capacities.add(row.capacity());
        }
        return new CategoryFiltersView(categoryNames, categoryNames, List.copyOf(applications),
                List.copyOf(containers), List.copyOf(processes), List.copyOf(capacities));
    }

    private record FilterTags(String applications, String containers, String processes, String capacity) {}

    private static String conditions(String keyword, String category, String application,
                                     String container, String process, String capacity, List<Object> args) {
        StringBuilder where = new StringBuilder(ELIGIBLE);
        if (hasText(keyword)) {
            where.append(" AND (INSTR(LOWER(p.name), LOWER(?)) > 0")
                    .append(" OR INSTR(LOWER(COALESCE(p.public_model, '')), LOWER(?)) > 0")
                    .append(" OR INSTR(LOWER(COALESCE(p.summary, '')), LOWER(?)) > 0)");
            String value = keyword.trim();
            args.add(value); args.add(value); args.add(value);
        }
        if (hasText(category)) {
            where.append(" AND c.name = ?");
            args.add(category.trim());
        }
        addTagFilter(where, args, "application_tags", application);
        addTagFilter(where, args, "container_tags", container);
        addTagFilter(where, args, "process_tags", process);
        if (hasText(capacity)) {
            where.append(" AND p.capacity_level = ?");
            args.add(capacity.trim());
        }
        return where.toString();
    }

    private static void addTagFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (hasText(value)) {
            where.append(" AND JSON_CONTAINS(p.").append(column).append(", JSON_QUOTE(?))");
            args.add(value.trim());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ProductView product(ResultSet rs) throws SQLException {
        Long heroId = nullableLong(rs, "visible_hero_media_id");
        Long cardId = nullableLong(rs, "visible_card_media_id");
        return new ProductView(rs.getLong("id"), rs.getString("name"), rs.getString("name_en"),
                rs.getString("public_model"), rs.getString("category_name"), rs.getString("slug"),
                rs.getString("summary"), jsonList(rs.getString("application_tags")),
                jsonList(rs.getString("container_tags")), jsonList(rs.getString("process_tags")),
                rs.getString("capacity_level"), rs.getBoolean("featured"), rs.getInt("sort_order"),
                rs.getString("seo_title"), rs.getString("seo_description"), heroId, cardId);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("官网产品筛选标签格式错误", e);
        }
    }

    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("官网产品技术参数格式错误", e);
        }
    }

    public record PageView(List<ProductView> items, long total, int totalPages, int page) {}

    public record CategoryFiltersView(List<String> equipmentCategories, List<String> productLines,
                                      List<String> applications, List<String> containers,
                                      List<String> processes, List<String> capacityLevels) {}

    public record ProductView(Long id, String name, String nameEn, String publicModel,
                              String category, String slug, String summary,
                              List<String> applicationTags, List<String> containerTags,
                              List<String> processTags, String capacityLevel, boolean featured,
                              int sortOrder, String seoTitle, String seoDescription,
                              Long heroMediaId, Long cardMediaId) {}

    public record GalleryView(Long id, Long mediaId, String role, String altText, int sortOrder) {}

    public record VariantView(Long id, String name, String publicSku, String description,
                              String dimensions, Map<String, Object> technicalParameters, int sortOrder) {}

    public record DetailView(ProductView product, String descriptionHtml, String descriptionHtmlEn,
                             Map<String, Object> technicalParameters, List<GalleryView> gallery,
                             List<VariantView> variants) {}
}
