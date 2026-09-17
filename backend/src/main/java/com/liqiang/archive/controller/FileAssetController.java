package com.liqiang.archive.controller;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileAssetErrorCodes;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.file.FileAssetAdapter;
import com.liqiang.archive.service.file.FileAssetBehaviorService;
import com.liqiang.archive.service.file.FileAssetLocationService;
import com.liqiang.archive.service.file.ThumbnailService;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/file-assets")
@RequiredArgsConstructor
public class FileAssetController {

    private final FileAssetBehaviorService behaviorService;
    private final FileAssetLocationService locationService;
    private final ThumbnailService thumbnailService;

    @GetMapping("/{ownerType}/{ownerId}")
    public List<FileHandle> list(@PathVariable String ownerType, @PathVariable Long ownerId) {
        return behaviorService.list(OwnerType.from(ownerType), ownerId);
    }

    /** 无需 ownerId 的单例 owner（如 COMPANY）使用的 list 接口，避免与 /{ownerType}/{ownerId} 路径冲突。 */
    @GetMapping("/singleton/{ownerType}")
    public List<FileHandle> listSingleton(@PathVariable String ownerType) {
        return behaviorService.list(OwnerType.from(ownerType), null);
    }

    @GetMapping("/{ownerType}/assets/{assetId}")
    public FileHandle get(@PathVariable String ownerType, @PathVariable Long assetId) {
        return behaviorService.get(OwnerType.from(ownerType), assetId);
    }

    /**
     * 文件预览入口：302 重定向到公开文件接口 {@code /api/files/**}（该接口为 {@link PublicApi}）。
     * 预览由前端以 iframe/img 原生加载，无法携带 Authorization header，故与目标接口保持一致标注 {@link PublicApi}。
     */
    @PublicApi
    @GetMapping("/{ownerType}/assets/{assetId}/preview")
    public ResponseEntity<Void> preview(@PathVariable String ownerType, @PathVariable Long assetId) {
        return redirectToFileUrl(
                behaviorService.getForCapability(OwnerType.from(ownerType), assetId, Capability.PREVIEW),
                false
        );
    }

    /**
     * 文件缩略图（用于卡片/网格小图）。
     *
     * <p>自定义封面优先：当 asset 设置了 coverFileName 时，直接返回封面图片原图（无需再压缩缓存）。
     * 无封面时按原文件生成缩略图：支持图片（jpg/png/gif/bmp/webp/tiff）和 PDF（首页渲染），其他类型返回 415。
     * 缩略图生成后按文件内容 hash 缓存到 {@code fileRoot/.thumbnails/}，
     * 文件修改/替换后自动失效，无需主动清理。
     *
     * @param size 可选，目标最大边长（px）。不传时使用默认值 {@link ThumbnailService#DEFAULT_THUMBNAIL_SIZE}；
     *             后端会 clamp 到 [{@value ThumbnailService#DEFAULT_THUMBNAIL_SIZE},
     *             {@value ThumbnailService#MAX_THUMBNAIL_SIZE}]，前端无需自己限位。
     *             推荐用法：列表卡片不传 size，详情大图传 size=640/800/1024 等。
     */
    @PublicApi
    @GetMapping("/{ownerType}/assets/{assetId}/thumbnail")
    public ResponseEntity<Resource> thumbnail(
            @PathVariable String ownerType,
            @PathVariable Long assetId,
            @RequestParam(required = false) Integer size
    ) {
        FileHandle handle;
        try {
            handle = behaviorService.getForCapability(
                    OwnerType.from(ownerType), assetId, Capability.PREVIEW);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(null);
        }
        // 自定义封面优先：直接返回原图（封面已经是用户上传的图片，无需再生成缩略图）
        if (handle.coverFileName() != null && !handle.coverFileName().isBlank()) {
            try {
                Path coverFile = locationService.resolveCoverPath(handle);
                String encoded = URLEncoder.encode(handle.coverFileName(), StandardCharsets.UTF_8).replace("+", "%20");
                String contentType = probeContentType(coverFile);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''cover_" + encoded)
                        .body(new FileSystemResource(coverFile));
            } catch (java.nio.file.NoSuchFileException e) {
                // 封面文件丢失，回退到原文件缩略图逻辑
            } catch (Exception e) {
                // 其他错误也回退
            }
        }
        // 无封面或封面读取失败 → 按原文件生成缩略图
        try {
            Path file = thumbnailService.getOrCreateThumbnail(handle, size);
            String encoded = URLEncoder.encode(handle.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''thumb_" + encoded + ".jpg")
                    .body(new FileSystemResource(file));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(415)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(null);
        } catch (java.nio.file.NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(null);
        }
    }

    /**
     * 上传自定义封面图片。
     *
     * <p>封面图片保存到 asset 原文件所在目录，文件名自动加时间戳避免冲突。
     * 会自动删除旧封面（如存在）。
     *
     * @return 更新后的 FileHandle（含新的 coverFileName）
     */
    @RequirePermission("file.manage")
    @PostMapping(value = "/{ownerType}/assets/{assetId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileHandle uploadCover(@PathVariable String ownerType,
                                  @PathVariable Long assetId,
                                  @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws IOException {
        OwnerType parsedOwner = OwnerType.from(ownerType);
        FileHandle handle = behaviorService.get(parsedOwner, assetId);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("封面文件不能为空");
        }
        if (isImageAssetCategory(handle.category())) {
            throw new IllegalArgumentException("图片素材已直接显示缩略图，无需上传自定义封面");
        }
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "cover";
        String safeName = Path.of(originalName).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
        int dot = safeName.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("封面文件缺少扩展名");
        }
        String ext = safeName.substring(dot).toLowerCase();
        // 仅允许图片类型作为封面
        Set<String> allowedExts = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tif", ".tiff");
        if (!allowedExts.contains(ext)) {
            throw new IllegalArgumentException("封面仅支持图片格式：jpg/png/gif/bmp/webp/tiff");
        }

        // 1. 存储新封面文件到 asset 目录
        Path dir = locationService.resolveAssetDirectory(handle);
        Files.createDirectories(dir);
        String prefix = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String storedName = appendTimestampBeforeExtension(safeName, prefix);
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        file.transferTo(target);

        // 2. 读取旧封面文件名（用于后续删除物理文件）
        String oldCover = handle.coverFileName();

        // 3. 写入数据库
        FileHandle updated = behaviorService.setCover(parsedOwner, assetId, storedName);

        // 4. 删除旧封面物理文件
        if (oldCover != null && !oldCover.isBlank()) {
            deleteCoverFile(dir, oldCover);
        }

        return updated;
    }

    /**
     * 清除自定义封面图片（删除数据库字段 + 删除物理文件）。
     *
     * @return 更新后的 FileHandle（coverFileName = null）
     */
    @RequirePermission("file.manage")
    @DeleteMapping("/{ownerType}/assets/{assetId}/cover")
    public FileHandle clearCover(@PathVariable String ownerType,
                                 @PathVariable Long assetId) throws IOException {
        OwnerType parsedOwner = OwnerType.from(ownerType);
        FileHandle handle = behaviorService.get(parsedOwner, assetId);
        String oldCover = handle.coverFileName();
        Path dir = locationService.resolveAssetDirectory(handle);

        // 1. 清数据库字段
        FileHandle updated = behaviorService.setCover(parsedOwner, assetId, null);

        // 2. 删除旧封面物理文件
        if (oldCover != null && !oldCover.isBlank()) {
            deleteCoverFile(dir, oldCover);
        }

        return updated;
    }

    /** 同 {@link #preview}：302 到公开的 {@code /api/files}/<b>xx</b>{@code /preview-pdf}，iframe 原生加载，标注 {@link PublicApi}。 */
    @PublicApi
    @GetMapping("/{ownerType}/assets/{assetId}/preview-pdf")
    public ResponseEntity<Void> previewPdf(@PathVariable String ownerType, @PathVariable Long assetId) {
        FileHandle handle = behaviorService.getForCapability(OwnerType.from(ownerType), assetId, Capability.PREVIEW);
        if (handle.fileName() == null || handle.fileName().isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        String url = storagePdfPreviewUrl(handle);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    /**
     * Office 预览异步状态查询入口（前端轮询用）。
     *
     * <p>镜像 {@link #previewPdf} 的 302 重定向逻辑，仅将目标 URL 的后缀
     * 由 {@code /preview-pdf} 改写为 {@code /preview-pdf/status}。
     * 前端通过统一的 URL 改写规则（{@code /preview-pdf → /preview-pdf/status}）拿到状态接口。
     * 轮询为原生 fetch（无 Authorization header），且 302 目标 {@code /api/files/.../preview-pdf/status} 已标注
     * {@link PublicApi}，故此处保持一致。
     */
    @PublicApi
    @GetMapping("/{ownerType}/assets/{assetId}/preview-pdf/status")
    public ResponseEntity<Void> previewPdfStatus(@PathVariable String ownerType, @PathVariable Long assetId) {
        FileHandle handle = behaviorService.getForCapability(OwnerType.from(ownerType), assetId, Capability.PREVIEW);
        if (handle.fileName() == null || handle.fileName().isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        String url = storagePdfPreviewStatusUrl(handle);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    /**
     * 文件下载入口：302 重定向到公开文件接口 {@code /api/files/**}。
     * 前端下载（fetch / a 标签）无法携带 Authorization header，故标注 {@link PublicApi}，与目标接口保持一致。
     */
    @PublicApi
    @GetMapping("/{ownerType}/assets/{assetId}/download")
    public ResponseEntity<Void> download(@PathVariable String ownerType, @PathVariable Long assetId) {
        return redirectToFileUrl(
                behaviorService.getForCapability(OwnerType.from(ownerType), assetId, Capability.DOWNLOAD),
                true
        );
    }

    @GetMapping("/{ownerType}/assets/{assetId}/local-path")
    public Map<String, Object> localPath(@PathVariable String ownerType, @PathVariable Long assetId) {
        try {
            return locationService.localPath(
                    behaviorService.getForCapability(OwnerType.from(ownerType), assetId, Capability.OPEN_FOLDER)
            );
        } catch (RuntimeException e) {
            return fileBehaviorUnsupportedLocalPath(e);
        }
    }

    @GetMapping("/{ownerType}/assets/{assetId}/open-folder")
    public Map<String, Object> openFolder(@PathVariable String ownerType, @PathVariable Long assetId) {
        try {
            return locationService.openFolder(
                    behaviorService.getForCapability(OwnerType.from(ownerType), assetId, Capability.OPEN_FOLDER)
            );
        } catch (RuntimeException e) {
            return fileBehaviorUnsupportedOpenFolder(e);
        }
    }

    @GetMapping("/open-folder-by-path")
    public Map<String, Object> openFolderByPath(
            @RequestParam String ownerType,
            @RequestParam(required = false) Long ownerId,
            @RequestParam String category,
            @RequestParam String fileName) {
        try {
            return locationService.openFolderByPath(OwnerType.from(ownerType), ownerId, category, fileName);
        } catch (RuntimeException e) {
            return fileBehaviorUnsupportedOpenFolder(e);
        }
    }

    @GetMapping("/{ownerType}/capabilities")
    public Set<Capability> capabilities(@PathVariable String ownerType) {
        return behaviorService.capabilities(OwnerType.from(ownerType));
    }

    @GetMapping("/capabilities")
    public Map<OwnerType, Set<Capability>> allCapabilities() {
        return behaviorService.allCapabilities();
    }

    @RequirePermission("file.manage")
    @PutMapping("/{ownerType}/assets/{assetId}/tags")
    public FileHandle updateTags(@PathVariable String ownerType,
                                 @PathVariable Long assetId,
                                 @RequestBody TagsRequest request) {
        return behaviorService.updateTags(
                OwnerType.from(ownerType),
                assetId,
                requireNotNull(requireNotNull(request, "request").tags(), "tags"),
                request.replace() == null || request.replace()
        );
    }

    @RequirePermission("file.manage")
    @PostMapping("/{ownerType}/assets/{assetId}/default")
    public FileHandle setDefault(@PathVariable String ownerType, @PathVariable Long assetId) {
        return behaviorService.setDefault(OwnerType.from(ownerType), assetId);
    }

    @RequirePermission("file.manage")
    @PutMapping("/{ownerType}/reorder")
    public void reorder(@PathVariable String ownerType, @RequestBody List<ReorderRequest> items) {
        behaviorService.reorder(
                OwnerType.from(ownerType),
                requireNotNull(items, "items").stream()
                        .map(item -> {
                            ReorderRequest request = requireNotNull(item, "item");
                            return new FileAssetAdapter.ReorderItem(request.id(), request.sortOrder());
                        })
                        .toList()
        );
    }

    @RequirePermission("file.manage")
    @PutMapping("/{ownerType}/assets/{assetId}/category")
    public void moveCategory(@PathVariable String ownerType,
                             @PathVariable Long assetId,
                             @RequestBody CategoryRequest request) {
        behaviorService.moveCategory(
                OwnerType.from(ownerType),
                assetId,
                requireNotBlank(requireNotNull(request, "request").category(), "category")
        );
    }

    @RequirePermission("file.manage")
    @PutMapping("/{ownerType}/assets/{assetId}/owner")
    public void moveOwner(@PathVariable String ownerType,
                          @PathVariable Long assetId,
                          @RequestBody OwnerMoveRequest request) {
        behaviorService.moveOwner(
                OwnerType.from(ownerType),
                assetId,
                OwnerType.from(requireNotBlank(requireNotNull(request, "request").targetOwnerType(), "targetOwnerType")),
                requireNotNull(request.targetOwnerId(), "targetOwnerId")
        );
    }

    @RequirePermission("file.manage")
    @DeleteMapping("/{ownerType}/assets/{assetId}")
    public void delete(@PathVariable String ownerType,
                       @PathVariable Long assetId,
                       @RequestParam(defaultValue = "false") boolean permanent,
                       @RequestParam(defaultValue = "false") boolean deletePhysical) {
        OwnerType parsed = OwnerType.from(ownerType);
        if (permanent) {
            behaviorService.permanentDelete(parsed, assetId, deletePhysical);
        } else {
            behaviorService.softDelete(parsed, assetId);
        }
    }

    @RequirePermission("file.manage")
    @PostMapping("/{ownerType}/assets/{assetId}/restore")
    public void restore(@PathVariable String ownerType, @PathVariable Long assetId) {
        behaviorService.restore(OwnerType.from(ownerType), assetId);
    }

    public record TagsRequest(String tags, Boolean replace) {
    }

    public record ReorderRequest(Long id, Integer sortOrder) {
    }

    public record CategoryRequest(String category) {
    }

    public record OwnerMoveRequest(String targetOwnerType, Long targetOwnerId) {
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static <T> T requireNotNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Map<String, Object> fileBehaviorUnsupportedLocalPath(RuntimeException e) {
        return Map.of(
                "found", false,
                "error", errorMessage(e),
                "errorCode", FileAssetErrorCodes.FILE_BEHAVIOR_UNSUPPORTED
        );
    }

    private static Map<String, Object> fileBehaviorUnsupportedOpenFolder(RuntimeException e) {
        return Map.of(
                "success", false,
                "error", errorMessage(e),
                "errorCode", FileAssetErrorCodes.FILE_BEHAVIOR_UNSUPPORTED
        );
    }

    private static String errorMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static ResponseEntity<Void> redirectToFileUrl(FileHandle handle, boolean download) {
        if (handle.fileName() == null || handle.fileName().isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        String url = storageFileUrl(handle);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .build();
    }

    private static String storageFileUrl(FileHandle handle) {
        String category = requireNotBlank(
                handle.storageCategory() == null ? handle.category() : handle.storageCategory(),
                "storageCategory"
        );
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(handle.fileName().getBytes(StandardCharsets.UTF_8));
        if (handle.storageOwnerId() != null) {
            return "/api/files/" + handle.storageOwnerId() + "/" + category + "?encoded=" + encoded;
        }
        return "/api/files/" + category + "?encoded=" + encoded;
    }

    private static String storagePdfPreviewUrl(FileHandle handle) {
        String category = requireNotBlank(
                handle.storageCategory() == null ? handle.category() : handle.storageCategory(),
                "storageCategory"
        );
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(handle.fileName().getBytes(StandardCharsets.UTF_8));
        if (handle.storageOwnerId() != null) {
            return "/api/files/" + handle.storageOwnerId() + "/" + category + "/preview-pdf?encoded=" + encoded;
        }
        return "/api/files/" + category + "/preview-pdf?encoded=" + encoded;
    }

    /** 与 {@link #storagePdfPreviewUrl} 对称，仅将路径后缀改为 /preview-pdf/status。 */
    private static String storagePdfPreviewStatusUrl(FileHandle handle) {
        String category = requireNotBlank(
                handle.storageCategory() == null ? handle.category() : handle.storageCategory(),
                "storageCategory"
        );
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(handle.fileName().getBytes(StandardCharsets.UTF_8));
        if (handle.storageOwnerId() != null) {
            return "/api/files/" + handle.storageOwnerId() + "/" + category + "/preview-pdf/status?encoded=" + encoded;
        }
        return "/api/files/" + category + "/preview-pdf/status?encoded=" + encoded;
    }

    // ====== 封面辅助方法 ======

    /** 图片类素材目录（main_image / scene_image / sku_image / detail_image / parameter_image）。 */
    private static final Set<String> IMAGE_ASSET_CATEGORIES = Set.of(
            "main_image", "scene_image", "sku_image", "detail_image", "parameter_image"
    );

    private static boolean isImageAssetCategory(String category) {
        return category != null && IMAGE_ASSET_CATEGORIES.contains(category);
    }

    /** 探测文件 Content-Type，无法探测时回退到 application/octet-stream。 */
    private static String probeContentType(Path file) {
        try {
            String type = Files.probeContentType(file);
            return type != null && !type.isBlank() ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        } catch (IOException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    /** 在文件扩展名之前插入时间戳前缀。如 abc.jpg → abc_20260101120000000.jpg。 */
    private static String appendTimestampBeforeExtension(String fileName, String timestamp) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + "_" + timestamp;
        }
        return fileName.substring(0, dot) + "_" + timestamp + fileName.substring(dot);
    }

    /** 在指定目录内删除封面文件（带安全校验：必须在 dir 内）。 */
    private static void deleteCoverFile(Path dir, String coverFileName) {
        if (coverFileName == null || coverFileName.isBlank()) return;
        try {
            Path target = dir.resolve(coverFileName).normalize();
            if (target.startsWith(dir) && Files.exists(target) && Files.isRegularFile(target)) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
