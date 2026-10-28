package com.liqiang.archive.service;

import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Maintains product-level image assignments in the website-owned gallery. */
@Service
@RequiredArgsConstructor
public class WebsiteProductGalleryService {
    private final WebsiteProductRepository products;
    private final WebsiteMediaAssetRepository media;
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    @Transactional
    public void add(Long productId, GalleryFields fields) {
        var product = draft(productId);
        if (fields == null || fields.mediaId() == null) badRequest();
        var asset = media.findByIdAndDeletedAtIsNull(fields.mediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网媒体不存在"));
        if (!"IMAGE".equals(asset.getMediaType()) || "ARCHIVED".equals(asset.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能选择有效的官网图片");
        }
        validate(fields);
        Integer duplicates = jdbc.queryForObject("""
                SELECT COUNT(*) FROM website_product_media
                WHERE product_id = ? AND variant_id IS NULL AND media_id = ? AND display_role = ?
                """, Integer.class, productId, fields.mediaId(), fields.role());
        if (duplicates != null && duplicates > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该图片已用于相同图库角色");
        }
        jdbc.update("""
                INSERT INTO website_product_media(product_id, variant_id, media_id, display_role, alt_text, sort_order)
                VALUES (?, NULL, ?, ?, ?, ?)
                """, productId, fields.mediaId(), fields.role(), trim(fields.altText()), fields.sortOrder());
        audit.log("WebsiteProduct", productId, product.getName(), "WEBSITE_PRODUCT_GALLERY_ADD", "添加官网产品图库图片");
    }

    @Transactional
    public void update(Long productId, Long assignmentId, GalleryFields fields) {
        var product = draft(productId);
        if (fields == null) badRequest();
        validate(fields);
        int changed = jdbc.update("""
                UPDATE website_product_media SET display_role = ?, alt_text = ?, sort_order = ?
                WHERE id = ? AND product_id = ? AND variant_id IS NULL AND media_id = ?
                """, fields.role(), trim(fields.altText()), fields.sortOrder(), assignmentId, productId, fields.mediaId());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "官网图库关联不存在或已变化");
        audit.log("WebsiteProduct", productId, product.getName(), "WEBSITE_PRODUCT_GALLERY_UPDATE", "更新官网产品图库顺序和说明");
    }

    private com.liqiang.archive.entity.WebsiteProduct draft(Long productId) {
        var product = products.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
        if (!"DRAFT".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能修改官网产品草稿的图库");
        }
        return product;
    }

    private static void validate(GalleryFields fields) {
        if (fields.mediaId() == null || fields.sortOrder() == null || fields.sortOrder() < 0
                || fields.sortOrder() > 100000 || fields.altText() != null && fields.altText().length() > 500
                || fields.role() == null
                || !java.util.Set.of("HERO", "GALLERY", "DETAIL", "SCENE", "PARAMETER").contains(fields.role())) {
            badRequest();
        }
    }

    private static void badRequest() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网图库图片、角色或排序无效");
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record GalleryFields(Long mediaId, String role, String altText, Integer sortOrder) {}
}
