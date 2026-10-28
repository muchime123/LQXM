package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductCategoryRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks a website-owned draft against the current public read rules without changing any status. */
@Service
@RequiredArgsConstructor
public class WebsiteProductPublicationReadinessService {
    private final WebsiteProductRepository products;
    private final WebsiteProductCategoryRepository categories;
    private final WebsiteMediaAssetRepository media;
    private final JdbcTemplate jdbc;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional(readOnly = true)
    public ReadinessView check(Long id) {
        WebsiteProduct product = products.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if ("ARCHIVED".equals(product.getStatus())) blockers.add("已归档产品不能发布");
        if (blank(product.getName())) blockers.add("产品名称为空");
        if (blank(product.getSlug())) blockers.add("产品 URL 标识为空");
        if (blank(product.getSummary())) blockers.add("产品摘要为空");
        if (product.getCategoryId() == null) {
            blockers.add("未设置官网分类");
        } else {
            WebsiteProductCategory category = categories.findByIdAndDeletedAtIsNull(product.getCategoryId()).orElse(null);
            if (category == null || !"PUBLISHED".equals(category.getStatus())) {
                blockers.add("官网分类未发布或不存在");
            }
        }
        checkImage(product.getHeroMediaId(), "主图", blockers);
        checkImage(product.getCardMediaId(), "卡片图", blockers);
        List<Long> galleryIds = jdbc.query("""
                SELECT media_id FROM website_product_media
                WHERE product_id = ? AND variant_id IS NULL ORDER BY sort_order, id
                """, (rs, row) -> rs.getLong(1), id);
        if (galleryIds.isEmpty()) blockers.add("详情图库为空");
        for (Long mediaId : galleryIds) checkImage(mediaId, "图库媒体 " + mediaId, blockers);
        String description = jdbc.queryForObject("""
                SELECT CONCAT(COALESCE(description_html, ''), '\n', COALESCE(description_html_en, ''))
                FROM website_products WHERE id = ? AND deleted_at IS NULL
                """, String.class, id);
        if (description != null) {
            String lower = description.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("<img") || lower.contains("<video") || lower.contains("<source")) {
                blockers.add("详情正文含内嵌媒体，须改用官网媒体库并完成审核");
            }
            if (lower.contains("/api/files/") || lower.contains("/api/file-assets/")
                    || lower.contains("product_model/assets/")) {
                blockers.add("详情正文仍引用内部档案文件");
            }
        }
        if (blank(product.getSeoTitle())) warnings.add("SEO 标题待审阅");
        if (blank(product.getSeoDescription())) warnings.add("SEO 描述待审阅");
        return new ReadinessView(id, blockers.isEmpty(), List.copyOf(blockers), List.copyOf(warnings));
    }

    private void checkImage(Long id, String label, List<String> blockers) {
        if (id == null) {
            blockers.add(label + "未设置");
            return;
        }
        WebsiteMediaAsset asset = media.findByIdAndDeletedAtIsNull(id).orElse(null);
        if (asset == null || !"IMAGE".equals(asset.getMediaType()) || !"PUBLISHED".equals(asset.getStatus())) {
            blockers.add(label + "不是已发布的官网图片");
            return;
        }
        if (blank(asset.getStorageKey())) {
            blockers.add(label + "文件路径为空");
            return;
        }
        Path root = Path.of(fileRoot).toAbsolutePath().normalize();
        Path websiteRoot = root.resolve("website");
        Path file = root.resolve(asset.getStorageKey()).normalize();
        try {
            if (!file.startsWith(websiteRoot) || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(websiteRoot.toRealPath())) {
                blockers.add(label + "文件不可读取或不在官网目录");
            }
        } catch (IOException | RuntimeException e) {
            blockers.add(label + "文件不可读取或不在官网目录");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ReadinessView(Long productId, boolean ready, List<String> blockers, List<String> warnings) {}
}
