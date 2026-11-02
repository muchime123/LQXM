package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteIpItem;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteIpItemRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** 独立官网知识产权内容；内部证书改动不会触发同步。 */
@Service
@RequiredArgsConstructor
public class WebsiteIpItemService {
    private final WebsiteIpItemRepository records;
    private final WebsiteMediaAssetRepository media;
    private final AuditLogService audit;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional(readOnly = true)
    public List<WebsiteIpItem> list() {
        return records.findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<WebsiteIpItem> published() {
        return records.findByStatusAndDeletedAtIsNullOrderBySortOrderAscIdAsc("PUBLISHED");
    }

    @Transactional
    public WebsiteIpItem create(Fields fields) {
        validate(fields);
        WebsiteIpItem item = new WebsiteIpItem();
        apply(item, fields);
        WebsiteIpItem saved = records.save(item);
        audit.log("WebsiteIpItem", saved.getId(), saved.getName(), "WEBSITE_IP_CREATE", "创建官网知识产权草稿");
        return saved;
    }

    @Transactional
    public WebsiteIpItem update(Long id, Fields fields) {
        validate(fields);
        WebsiteIpItem item = get(id);
        if (!"DRAFT".equals(item.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿可以编辑");
        apply(item, fields);
        WebsiteIpItem saved = records.save(item);
        audit.log("WebsiteIpItem", id, saved.getName(), "WEBSITE_IP_UPDATE", "更新官网知识产权草稿");
        return saved;
    }

    @Transactional
    public WebsiteIpItem publish(Long id) {
        WebsiteIpItem item = get(id);
        if ("PUBLISHED".equals(item.getStatus())) return item;
        if (!"DRAFT".equals(item.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿可以发布");
        if (blank(item.getPublicNumber()) || blank(item.getApplicant())
                || blank(item.getLegalStatus()) || item.getMediaId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发布前须核实公开编号、申请人、法律状态和证书图片");
        if (!"有效".equals(item.getLegalStatus()) && !"已授权有效".equals(item.getLegalStatus())
                && !"正常有效".equals(item.getLegalStatus()) && !"专利权有效".equals(item.getLegalStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已核实有效的知识产权可以作为现行成果发布");
        WebsiteMediaAsset image = media.findByIdAndDeletedAtIsNull(item.getMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "官网证书图片不存在"));
        if (!"IMAGE".equals(image.getMediaType()) || "ARCHIVED".equals(image.getStatus())
                || !readableWebsiteFile(image.getStorageKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "证书图片不可发布或文件缺失");
        image.setStatus("PUBLISHED");
        media.save(image);
        item.setStatus("PUBLISHED");
        item.setPublishedAt(LocalDateTime.now());
        WebsiteIpItem saved = records.save(item);
        audit.log("WebsiteIpItem", id, saved.getName(), "WEBSITE_IP_PUBLISH", "发布官网知识产权");
        return saved;
    }

    @Transactional
    public WebsiteIpItem unpublish(Long id) {
        WebsiteIpItem item = get(id);
        if ("DRAFT".equals(item.getStatus())) return item;
        if (!"PUBLISHED".equals(item.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已发布内容可以下线");
        item.setStatus("DRAFT");
        item.setPublishedAt(null);
        WebsiteIpItem saved = records.save(item);
        audit.log("WebsiteIpItem", id, saved.getName(), "WEBSITE_IP_UNPUBLISH", "下线官网知识产权");
        return saved;
    }

    private WebsiteIpItem get(Long id) {
        return records.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网知识产权不存在"));
    }

    private void apply(WebsiteIpItem item, Fields fields) {
        if (fields.mediaId() != null) {
            WebsiteMediaAsset image = media.findByIdAndDeletedAtIsNull(fields.mediaId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网图片不存在"));
            if (!"IMAGE".equals(image.getMediaType()) || "ARCHIVED".equals(image.getStatus()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能选择有效官网图片");
        }
        item.setName(fields.name().trim());
        item.setCategory(fields.category().trim());
        item.setPatentType(trim(fields.patentType()));
        item.setPublicNumber(trim(fields.publicNumber()));
        item.setApplicant(trim(fields.applicant()));
        item.setLegalStatus(trim(fields.legalStatus()));
        item.setMediaId(fields.mediaId());
        item.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    private static void validate(Fields f) {
        if (f == null || blank(f.name()) || blank(f.category()) || tooLong(f.name(), 300)
                || tooLong(f.category(), 50) || tooLong(f.patentType(), 100)
                || tooLong(f.publicNumber(), 150) || tooLong(f.applicant(), 300)
                || tooLong(f.legalStatus(), 100))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网知识产权公开字段无效");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean tooLong(String value, int max) { return value != null && value.length() > max; }
    private static String trim(String value) { return blank(value) ? null : value.trim(); }

    private boolean readableWebsiteFile(String storageKey) {
        if (blank(fileRoot) || blank(storageKey)) return false;
        try {
            Path root = Path.of(fileRoot).toAbsolutePath().normalize().resolve("website");
            Path file = Path.of(fileRoot).toAbsolutePath().normalize().resolve(storageKey).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) && Files.isReadable(file)
                    && file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    public record Fields(String name, String category, String patentType, String publicNumber,
                         String applicant, String legalStatus, Long mediaId, Integer sortOrder) {}
}
