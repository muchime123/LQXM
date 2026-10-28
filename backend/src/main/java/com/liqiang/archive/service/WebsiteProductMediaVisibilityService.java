package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Allows public media only when a published website product actually references it. */
@Service
@RequiredArgsConstructor
public class WebsiteProductMediaVisibilityService {
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public boolean isReferencedByPublishedProduct(Long mediaId) {
        Integer visible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM website_products p
                    LEFT JOIN website_product_categories c ON c.id = p.category_id
                    WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
                      AND (p.category_id IS NULL OR
                           (c.status = 'PUBLISHED' AND c.deleted_at IS NULL))
                      AND (p.hero_media_id = ? OR p.card_media_id = ?
                           OR EXISTS (
                               SELECT 1 FROM website_product_media pm
                               WHERE pm.product_id = p.id AND pm.media_id = ?
                                 AND (pm.variant_id IS NULL OR EXISTS (
                                     SELECT 1 FROM website_product_variants v
                                     WHERE v.id = pm.variant_id AND v.product_id = p.id
                                       AND v.status = 'PUBLISHED' AND v.deleted_at IS NULL
                                 ))
                           ))
                    LIMIT 1
                )
                """, Integer.class, mediaId, mediaId, mediaId);
        return Integer.valueOf(1).equals(visible);
    }
}
