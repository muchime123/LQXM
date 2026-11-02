package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCredential;
import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteCredentialRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

/** 官网资质草稿只接受明确填写的公开信息。 */
@Service
@RequiredArgsConstructor
public class WebsiteCredentialService {
    private final WebsiteCredentialRepository records;
    private final WebsiteMediaAssetRepository media;
    private final AuditLogService audit;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional(readOnly = true)
    public List<WebsiteCredential> list() {
        return records.findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public WebsiteCredential create(Fields fields) {
        validate(fields);
        WebsiteCredential item = new WebsiteCredential();
        apply(item, fields);
        WebsiteCredential saved = records.save(item);
        audit.log("WebsiteCredential", saved.getId(), saved.getName(), "WEBSITE_CREDENTIAL_CREATE", "创建官网资质草稿");
        return saved;
    }

    @Transactional
    public WebsiteCredential update(Long id, Fields fields) {
        validate(fields);
        WebsiteCredential item = records.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网资质不存在"));
        if (!"DRAFT".equals(item.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿资质可以编辑");
        apply(item, fields);
        WebsiteCredential saved = records.save(item);
        audit.log("WebsiteCredential", id, saved.getName(), "WEBSITE_CREDENTIAL_UPDATE", "更新官网资质草稿");
        return saved;
    }

    @Transactional
    public WebsiteCredential publish(Long id) {
        WebsiteCredential item = get(id);
        if ("PUBLISHED".equals(item.getStatus())) return item;
        if (!"DRAFT".equals(item.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿资质可以发布");
        if (item.getName() == null || item.getName().isBlank() || item.getMediaId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网资质须有名称和证书图片");
        if (item.getValidUntil() != null && item.getValidUntil().isBefore(LocalDate.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已过期的官网资质不能发布");
        WebsiteMediaAsset image = media.findByIdAndDeletedAtIsNull(item.getMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "证书图片不存在"));
        if (!"IMAGE".equals(image.getMediaType()) || "ARCHIVED".equals(image.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "证书图片不可发布");
        if (!readableWebsiteFile(image.getStorageKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "证书图片文件缺失或不在官网媒体目录");
        image.setStatus("PUBLISHED");
        media.save(image);
        item.setStatus("PUBLISHED");
        item.setPublishedAt(LocalDateTime.now());
        WebsiteCredential saved = records.save(item);
        audit.log("WebsiteCredential", id, saved.getName(), "WEBSITE_CREDENTIAL_PUBLISH", "发布官网资质");
        return saved;
    }

    @Transactional
    public WebsiteCredential unpublish(Long id) {
        WebsiteCredential item = get(id);
        if ("DRAFT".equals(item.getStatus())) return item;
        if (!"PUBLISHED".equals(item.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已发布资质可以下线");
        item.setStatus("DRAFT");
        item.setPublishedAt(null);
        WebsiteCredential saved = records.save(item);
        audit.log("WebsiteCredential", id, saved.getName(), "WEBSITE_CREDENTIAL_UNPUBLISH", "下线官网资质");
        return saved;
    }

    private WebsiteCredential get(Long id) {
        return records.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网资质不存在"));
    }

    private void apply(WebsiteCredential item, Fields fields) {
        if (fields.mediaId() != null) {
            WebsiteMediaAsset asset = media.findByIdAndDeletedAtIsNull(fields.mediaId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网证书图片不存在"));
            if (!"IMAGE".equals(asset.getMediaType()) || "ARCHIVED".equals(asset.getStatus()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能使用未归档的官网图片");
        }
        item.setName(fields.name().trim());
        item.setCategory(fields.category());
        item.setPublicDescription(fields.publicDescription());
        item.setIssuer(fields.issuer());
        item.setValidUntil(fields.validUntil());
        item.setMediaId(fields.mediaId());
        item.setSortOrder(fields.sortOrder() == null ? 0 : fields.sortOrder());
    }

    private static void validate(Fields fields) {
        if (fields == null || fields.name() == null || fields.name().isBlank() || fields.name().length() > 300
                || tooLong(fields.category(), 100) || tooLong(fields.issuer(), 200)
                || tooLong(fields.publicDescription(), 20_000))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网资质公开字段无效");
    }

    private static boolean tooLong(String value, int max) { return value != null && value.length() > max; }

    private boolean readableWebsiteFile(String storageKey) {
        if (fileRoot == null || storageKey == null || storageKey.isBlank()) return false;
        try {
            Path root = Path.of(fileRoot).toAbsolutePath().normalize().resolve("website");
            Path file = Path.of(fileRoot).toAbsolutePath().normalize().resolve(storageKey).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) && Files.isReadable(file)
                    && file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    public record Fields(String name, String category, String publicDescription, String issuer,
                         LocalDate validUntil, Long mediaId, Integer sortOrder) {}
}
