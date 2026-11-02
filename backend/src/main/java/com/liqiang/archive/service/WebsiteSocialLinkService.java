package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.repository.WebsiteSocialLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

/** Website-owned official links; no implicit synchronization from MediaAccount. */
@Service
@RequiredArgsConstructor
public class WebsiteSocialLinkService {
    private final WebsiteSocialLinkRepository links;
    private final AuditLogService audit;

    @Transactional(readOnly = true)
    public List<WebsiteSocialLink> list() { return links.findByDeletedAtIsNullOrderBySortOrderAscIdAsc(); }

    @Transactional(readOnly = true)
    public List<WebsiteSocialLink> published() {
        return links.findByStatusAndDeletedAtIsNullOrderBySortOrderAscIdAsc("PUBLISHED");
    }

    @Transactional
    public WebsiteSocialLink create(Fields fields) {
        validate(fields);
        var item = new WebsiteSocialLink();
        apply(item, fields);
        WebsiteSocialLink saved = links.save(item);
        audit.log("WebsiteSocialLink", saved.getId(), saved.getAccountName(),
                "WEBSITE_SOCIAL_LINK_CREATE", "创建官网官方链接草稿");
        return saved;
    }

    @Transactional
    public WebsiteSocialLink update(Long id, Fields fields) {
        validate(fields);
        WebsiteSocialLink item = getDraft(id);
        apply(item, fields);
        WebsiteSocialLink saved = links.save(item);
        audit.log("WebsiteSocialLink", id, saved.getAccountName(),
                "WEBSITE_SOCIAL_LINK_UPDATE", "更新官网官方链接草稿");
        return saved;
    }

    @Transactional
    public WebsiteSocialLink publish(Long id) {
        WebsiteSocialLink item = links.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网官方链接不存在"));
        if ("PUBLISHED".equals(item.getStatus())) return item;
        if (!"DRAFT".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿链接可以发布");
        item.setStatus("PUBLISHED");
        item.setPublishedAt(LocalDateTime.now());
        WebsiteSocialLink saved = links.save(item);
        audit.log("WebsiteSocialLink", id, saved.getAccountName(),
                "WEBSITE_SOCIAL_LINK_PUBLISH", "发布官网官方链接");
        return saved;
    }

    @Transactional
    public void archive(Long id) {
        WebsiteSocialLink item = links.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网官方链接不存在"));
        item.setDeletedAt(LocalDateTime.now());
        links.save(item);
        audit.log("WebsiteSocialLink", id, item.getAccountName(),
                "WEBSITE_SOCIAL_LINK_ARCHIVE", "归档官网官方链接");
    }

    private WebsiteSocialLink getDraft(Long id) {
        WebsiteSocialLink item = links.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网官方链接不存在"));
        if (!"DRAFT".equals(item.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "已发布链接不能通过草稿接口修改");
        return item;
    }

    private static void validate(Fields fields) {
        if (fields == null || blank(fields.platform()) || fields.platform().length() > 50
                || blank(fields.accountName()) || fields.accountName().length() > 200
                || blank(fields.link()) || fields.link().length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台、账号名称或公开链接无效");
        }
        try {
            URI uri = URI.create(fields.link().trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException("invalid URL");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "公开链接必须是有效的 HTTP 或 HTTPS 地址");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void apply(WebsiteSocialLink item, Fields fields) {
        item.setPlatform(fields.platform().trim());
        item.setAccountName(fields.accountName().trim());
        item.setLink(fields.link().trim());
        item.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    public record Fields(String platform, String accountName, String link, Integer sortOrder) {}
}
