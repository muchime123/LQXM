package com.liqiang.archive.service;

import com.liqiang.archive.util.FileCategory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

@Service
public class FileStorageService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tif", ".tiff", ".avif"
    );

    private static final Set<String> DOC_EXTENSIONS = Set.of(
            ".doc", ".docx", ".pdf", ".md", ".txt", ".rtf", ".xls", ".xlsx", ".ppt", ".pptx"
    );

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mov", ".avi", ".wmv", ".mkv", ".webm", ".m4v"
    );

    private static final long MAX_IMAGE_SIZE = 500 * 1024 * 1024L;
    private static final long MAX_DOC_SIZE = 500 * 1024 * 1024L;
    private static final long MAX_VIDEO_SIZE = 2L * 1024 * 1024 * 1024;

    private static final int THUMBNAIL_WIDTH = 200;
    private static final int THUMBNAIL_HEIGHT = 200;
    private static final String THUMBNAIL_DIR = "thumbnails";

    private static final Set<String> IMAGE_AND_DOC_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tif", ".tiff", ".avif",
            ".doc", ".docx", ".pdf", ".md", ".txt", ".rtf", ".xls", ".xlsx", ".ppt", ".pptx"
    );
    private static final Set<String> ANY_EXTENSION = Set.of("*");

    private final Path fileRoot;

    public FileStorageService(@Value("${app.files.root}") String fileRoot) {
        this.fileRoot = resolveFileRoot(fileRoot);
    }

    public Path fileRoot() {
        return fileRoot;
    }

    public Path productRoot() {
        return fileRoot.resolve("products");
    }

    /**
     * 存储文件（无产品关联，如证书、文档等）
     */
    public UploadResult store(String category, MultipartFile file) throws IOException {
        return store(category, file, null, null);
    }

    /**
     * 存储文件（有产品名，无产品ID — 兼容旧调用）
     */
    public UploadResult store(String category, MultipartFile file, String modelName) throws IOException {
        return store(category, file, null, modelName);
    }

    /**
     * 存储文件到产品子目录
     * @param category 文件分类
     * @param file 文件
     * @param modelId 产品ID（null 则为非产品分类，存到根目录下）
     * @param modelName 产品名称（用于可读性）
     */
    public UploadResult store(String category, MultipartFile file, Long modelId, String modelName) throws IOException {
        String folder = FileCategory.folderOf(category);
        if (folder == null || file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件分类或文件内容不合法");
        }

        String originalName = Path.of(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename())
                .getFileName()
                .toString()
                .replaceAll("[\\\\/:*?\"<>|]", "_");

        validateExtension(originalName, category);
        validateSize(file.getSize(), category);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String storedName = appendTimestampBeforeExtension(originalName, timestamp);

        // 构建目标目录：fileRoot/[productDir or packagingDir]/[category_folder]/
        Path dir = storageBaseDir(modelId, category).resolve(folder).normalize();
        Files.createDirectories(dir);
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        file.transferTo(target);

        if (isImageCategory(category)) {
            generateThumbnail(target, dir);
        }

        return new UploadResult(storedName, category, file.getSize());
    }

    /**
     * 删除文件（无产品关联）
     */
    public boolean deleteIfExists(String category, String fileName) {
        return deleteIfExists(null, category, fileName);
    }

    /**
     * 删除文件
     * @param modelId 产品ID（null 则为非产品分类）
     * @param modelName 产品名称（用于定位产品子目录，null 时退化为纯 ID 目录）
     */
    public boolean deleteIfExists(Long modelId, String category, String fileName) {
        return deleteIfExists(modelId, null, category, fileName);
    }

    /**
     * 删除文件（带产品名，确保目录匹配上传时生成的 productDirName）
     */
    public boolean deleteIfExists(Long modelId, String modelName, String category, String fileName) {
        if (category == null || !isSafeStoredFileName(fileName)) {
            return false;
        }
        String folder = FileCategory.folderOf(category);
        if (folder == null) {
            return false;
        }
        try {
            Path dir = storageBaseDir(modelId, category);
            Path target = dir.resolve(folder).resolve(fileName).normalize();
            if (!target.startsWith(fileRoot)) {
                return false;
            }
            boolean deleted = false;
            if (Files.exists(target) && Files.isRegularFile(target)) {
                deleted = Files.deleteIfExists(target);
            }
            return deleteThumbnail(modelId, category, fileName) || deleted;
        } catch (IOException ignored) {
        }
        return false;
    }

    /**
     * 解析产品子目录路径（仅用 modelId，不依赖产品名称）。
     * 兼容旧格式 {modelId}_{name} 目录，不强制迁移。
     * 同一 modelId 存在多个旧格式目录时，优先选择 main_image 下已有文件的目录。
     */
    public Path productDir(Long modelId) {
        if (modelId == null) {
            return fileRoot;
        }
        Path canonical = productRoot().resolve(String.valueOf(modelId));
        if (Files.exists(canonical)) {
            return canonical;
        }
        // 扫描旧格式 {modelId}_* 目录，直接使用而不再尝试迁移（Windows 上 Files.move 经常失败）
        String prefix = modelId + "_";
        Path bestMatch = null;
        boolean bestHasMainImage = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileRoot)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && Files.isDirectory(entry)) {
                    if (bestMatch == null) {
                        bestMatch = entry;
                        bestHasMainImage = hasMainImageFile(entry);
                    } else if (!bestHasMainImage) {
                        // 当前最佳没有主图，看这个有没有
                        if (hasMainImageFile(entry)) {
                            bestMatch = entry;
                            bestHasMainImage = true;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        if (bestMatch != null) {
            return bestMatch;
        }
        // 完全没有匹配目录，返回不存在的规范路径（上传时会自动创建）
        return canonical;
    }

    private Path resolveFileRoot(String configuredRoot) {
        Path configured = Path.of(configuredRoot).toAbsolutePath().normalize();
        if (Files.isDirectory(configured)) {
            return configured;
        }

        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (Path dir = current; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("storage").resolve("lq-files").normalize();
            if (Files.isDirectory(candidate)) {
                log.warn("Configured file root does not exist: {}. Falling back to {}", configured, candidate);
                return candidate;
            }
        }

        Path codeSource = Path.of(FileStorageService.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .toAbsolutePath()
                .normalize();
        Path start = Files.isRegularFile(codeSource) ? codeSource.getParent() : codeSource;
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("storage").resolve("lq-files").normalize();
            if (Files.isDirectory(candidate)) {
                log.warn("Configured file root does not exist: {}. Falling back to {}", configured, candidate);
                return candidate;
            }
        }

        Path documents = Path.of(System.getProperty("user.home", ""), "Documents").toAbsolutePath().normalize();
        if (Files.isDirectory(documents)) {
            try (Stream<Path> stream = Files.find(
                    documents,
                    3,
                    (path, attrs) -> attrs.isDirectory()
                            && "lq-files".equals(path.getFileName().toString())
                            && path.getParent() != null
                            && "storage".equals(path.getParent().getFileName().toString()))) {
                Path discovered = stream.findFirst().orElse(null);
                if (discovered != null) {
                    Path candidate = discovered.toAbsolutePath().normalize();
                    log.warn("Configured file root does not exist: {}. Falling back to discovered root {}", configured, candidate);
                    return candidate;
                }
            } catch (IOException ignored) {
            }
        }

        return configured;
    }

    /**
     * 返回某个产品可能对应的全部素材目录。
     * 兼容历史目录：{modelId} 与 {modelId}_{productName} 可能同时存在。
     */
    public List<Path> productDirs(Long modelId) {
        List<Path> dirs = new ArrayList<>();
        if (modelId == null) {
            return dirs;
        }
        Path canonical = productRoot().resolve(String.valueOf(modelId));
        if (Files.isDirectory(canonical)) {
            dirs.add(canonical);
        }
        Path legacyIdDir = fileRoot.resolve(String.valueOf(modelId));
        if (Files.isDirectory(legacyIdDir) && dirs.stream().noneMatch(existing -> existing.equals(legacyIdDir))) {
            dirs.add(legacyIdDir);
        }
        String prefix = modelId + "_";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && dirs.stream().noneMatch(existing -> existing.equals(entry))) {
                    dirs.add(entry);
                }
            }
        } catch (IOException ignored) {
        }
        return dirs;
    }

    /**
     * 检查目录下的 main_image 子目录是否有文件
     */
    public boolean exists(String category, String fileName) {
        String folder = com.liqiang.archive.util.FileCategory.folderOf(category);
        if (folder == null || !isSafeStoredFileName(fileName)) {
            return false;
        }
        Path file = fileRoot.resolve(folder).resolve(fileName).normalize();
        return file.startsWith(fileRoot) && Files.exists(file) && Files.isRegularFile(file);
    }

    public long fileSize(String category, String fileName) {
        String folder = com.liqiang.archive.util.FileCategory.folderOf(category);
        if (folder == null || !isSafeStoredFileName(fileName)) {
            return 0;
        }
        Path file = fileRoot.resolve(folder).resolve(fileName).normalize();
        if (!file.startsWith(fileRoot) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return 0;
        }
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean hasMainImageFile(Path dir) {
        Path mainImageDir = dir.resolve("main_image");
        if (!Files.isDirectory(mainImageDir)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mainImageDir)) {
            for (Path ignored : stream) {
                return true; // 至少有一个文件
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /**
     * 将文件从源产品目录移动到目标产品目录（用于素材归属转移）。
     * @throws IOException 如果文件移动失败（源文件不存在、目标目录不可写等）
     */
    public void moveFile(Long fromModelId, String fromCategory, Long toModelId, String toCategory, String fileName) throws IOException {
        if (!isSafeStoredFileName(fileName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String fromFolder = FileCategory.folderOf(fromCategory);
        String toFolder = FileCategory.folderOf(toCategory);
        if (fromFolder == null || toFolder == null) {
            throw new IllegalArgumentException("不支持的文件分类：" + fromCategory + " → " + toCategory);
        }
        Path fromDir = productDir(fromModelId).resolve(fromFolder);
        Path toDir = productDir(toModelId).resolve(toFolder);
        Path source = fromDir.resolve(fileName).normalize();
        Path target = toDir.resolve(fileName).normalize();
        if (!source.startsWith(fileRoot) || !target.startsWith(fileRoot)) {
            throw new IllegalArgumentException("文件路径不合法：" + fileName);
        }
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw new java.nio.file.NoSuchFileException(source.toString(), null, "源文件不存在，无法移动");
        }
        Files.createDirectories(toDir);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * @deprecated 产品目录已改为只用 {modelId}，不再依赖名称。
     *             保留此方法仅为向后兼容，内部忽略 modelName 参数。
     */
    @Deprecated
    public String productDirName(Long modelId, String modelName) {
        return String.valueOf(modelId);
    }

    /**
     * 包材子目录路径: fileRoot/packaging/{modelId}
     */
    public Path packagingDir(Long modelId) {
        if (modelId == null) {
            return fileRoot.resolve("packaging");
        }
        return fileRoot.resolve("packaging").resolve(String.valueOf(modelId));
    }

    private void validateExtension(String fileName, String category) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("文件缺少扩展名，无法识别文件类型");
        }
        String ext = fileName.substring(dot).toLowerCase();
        Set<String> allowed = allowedExtensions(category);
        if (allowed.contains("*")) {
            return;
        }
        if (!allowed.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext + ", " + category + " 仅允许 " + allowed);
        }
    }

    private void validateSize(long size, String category) {
        long max = isImageCategory(category) ? MAX_IMAGE_SIZE : isVideoCategory(category) ? MAX_VIDEO_SIZE : MAX_DOC_SIZE;
        if (size > max) {
            throw new IllegalArgumentException("文件大小超出限制（最大 " + (max / 1024 / 1024) + " MB）");
        }
    }

    private String appendTimestampBeforeExtension(String fileName, String timestamp) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + "_" + timestamp;
        }
        return fileName.substring(0, dot) + "_" + timestamp + fileName.substring(dot);
    }

    private Set<String> allowedExtensions(String category) {
        if ("drawing".equals(category) || "pkg_drawing".equals(category)) {
            return ANY_EXTENSION;
        }
        if ("company_asset".equals(category)) {
            return ANY_EXTENSION;
        }
        if ("certificate".equals(category) || "brochure".equals(category)
                || "company".equals(category) || "company_finance".equals(category)
                || "document".equals(category)
                || "patent".equals(category) || "other".equals(category)
                || "pkg_spec_sheet".equals(category) || "pkg_package".equals(category)) {
            return IMAGE_AND_DOC_EXTENSIONS;
        }
        if (isImageCategory(category)) {
            return IMAGE_EXTENSIONS;
        }
        if (isVideoCategory(category)) {
            return VIDEO_EXTENSIONS;
        }
        return DOC_EXTENSIONS;
    }

    private boolean isImageCategory(String category) {
        return "sku_image".equals(category)
                || "main_image".equals(category)
                || "scene_image".equals(category)
                || "detail_image".equals(category)
                || "parameter_image".equals(category)
                || "pkg_main_image".equals(category)
                || "pkg_scene_image".equals(category)
                || "pkg_printing".equals(category)
                || "company_team".equals(category);
    }

    private boolean isVideoCategory(String category) {
        return "device_video".equals(category)
                || "operation_video".equals(category)
                || "pkg_usage_video".equals(category);
    }

    public record UploadResult(String fileName, String category, long size) {
    }

    /**
     * 生成缩略图
     * @param source 源图片路径
     * @param parentDir 源文件所在目录
     */
    public void generateThumbnail(Path source, Path parentDir) {
        String fileName = source.getFileName().toString();
        String thumbnailName = "thumb_" + fileName;
        Path thumbnailDir = parentDir.resolve(THUMBNAIL_DIR);
        Path thumbnailPath = thumbnailDir.resolve(thumbnailName);

        try {
            BufferedImage originalImage = ImageIO.read(source.toFile());
            if (originalImage == null) {
                return;
            }

            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            double scale = Math.min(
                    (double) THUMBNAIL_WIDTH / originalWidth,
                    (double) THUMBNAIL_HEIGHT / originalHeight
            );

            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);

            Image scaledImage = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            BufferedImage thumbnailImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);

            var graphics = thumbnailImage.createGraphics();
            graphics.drawImage(scaledImage, 0, 0, null);
            graphics.dispose();

            Files.createDirectories(thumbnailDir);
            ImageIO.write(thumbnailImage, getImageFormat(fileName), thumbnailPath.toFile());
        } catch (IOException e) {
            log.warn("生成缩略图失败: {}", fileName, e);
        }
    }

    /**
     * 获取缩略图路径
     * @param modelId 产品ID
     * @param category 文件分类
     * @param fileName 原始文件名
     */
    public Path getThumbnailPath(Long modelId, String category, String fileName) {
        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeStoredFileName(fileName)) {
            return null;
        }
        FileCategory.StorageScope scope = FileCategory.scopeOf(category);
        if (modelId == null || scope == FileCategory.StorageScope.FLAT) {
            return fileRoot.resolve(folder).resolve(THUMBNAIL_DIR).resolve("thumb_" + fileName);
        }
        if (scope == FileCategory.StorageScope.PACKAGING) {
            return packagingDir(modelId).resolve(folder).resolve(THUMBNAIL_DIR).resolve("thumb_" + fileName);
        }
        for (Path dir : productDirs(modelId)) {
            Path thumbnail = dir.resolve(folder).resolve(THUMBNAIL_DIR).resolve("thumb_" + fileName);
            if (Files.isRegularFile(thumbnail)) {
                return thumbnail;
            }
        }
        return productDir(modelId).resolve(folder).resolve(THUMBNAIL_DIR).resolve("thumb_" + fileName);
    }

    /**
     * 删除缩略图
     */
    public boolean deleteThumbnail(Long modelId, String category, String fileName) {
        Path thumbnailPath = getThumbnailPath(modelId, category, fileName);
        if (thumbnailPath == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(thumbnailPath);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isSafeStoredFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..")
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.indexOf('\0') >= 0 || fileName.indexOf(':') >= 0) {
            return false;
        }
        try {
            return Path.of(fileName).getFileName().toString().equals(fileName);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Path storageBaseDir(Long modelId, String category) {
        FileCategory.StorageScope scope = FileCategory.scopeOf(category);
        if (modelId == null || scope == FileCategory.StorageScope.FLAT) {
            return fileRoot;
        }
        if (scope == FileCategory.StorageScope.PACKAGING) {
            return packagingDir(modelId);
        }
        return productDir(modelId);
    }

    private String getImageFormat(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "jpg";
        }
        String ext = fileName.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "png" -> "png";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            default -> "jpg";
        };
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FileStorageService.class);
}
