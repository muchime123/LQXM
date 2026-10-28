package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteProduct;
import com.liqiang.archive.repository.WebsiteProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Plain-text editorial path. Escapes input before storing website-owned HTML. */
@Service
@RequiredArgsConstructor
public class WebsiteProductDescriptionService {
    private final WebsiteProductRepository products;
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    @Transactional
    public DescriptionView update(Long id, DescriptionFields fields) {
        WebsiteProduct product = products.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网产品不存在"));
        if (!"DRAFT".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能修改官网产品草稿的详情正文");
        }
        if (fields == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交详情正文");
        String html = paragraphs(fields.descriptionText());
        String htmlEn = paragraphs(fields.descriptionTextEn());
        int changed = jdbc.update("""
                UPDATE website_products SET description_html = ?, description_html_en = ?
                WHERE id = ? AND status = 'DRAFT' AND deleted_at IS NULL
                """, html, htmlEn, id);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "官网产品状态已变化，请刷新后重试");
        audit.log("WebsiteProduct", id, product.getName(), "WEBSITE_PRODUCT_DESCRIPTION_UPDATE", "更新官网产品详情正文");
        return new DescriptionView(id, html, htmlEn);
    }

    private static String paragraphs(String text) {
        if (text == null || text.isBlank()) return null;
        if (text.length() > 30_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "详情正文不能超过 30000 字");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder html = new StringBuilder();
        for (String paragraph : normalized.split("\\n{2,}")) {
            if (!paragraph.isBlank()) {
                html.append("<p>").append(escape(paragraph.trim()).replace("\n", "<br>"))
                        .append("</p>");
            }
        }
        return html.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record DescriptionFields(String descriptionText, String descriptionTextEn) {}
    public record DescriptionView(Long productId, String descriptionHtml, String descriptionHtmlEn) {}
}
