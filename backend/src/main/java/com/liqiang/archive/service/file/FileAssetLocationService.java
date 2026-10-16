package com.liqiang.archive.service.file;

import com.liqiang.archive.config.DesktopProperties;
import com.liqiang.archive.dto.FileAssetErrorCodes;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.FileStorageService;
import com.liqiang.archive.util.FileCategory;
import com.liqiang.archive.util.FileCategory.StorageScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class FileAssetLocationService {

    private static final Logger log = LoggerFactory.getLogger(FileAssetLocationService.class);

    private final FileStorageService fileStorageService;
    private final DesktopProperties desktopProperties;
    private final Path fileRoot;

    public FileAssetLocationService(FileStorageService fileStorageService,
                                    DesktopProperties desktopProperties) {
        this.fileStorageService = fileStorageService;
        this.desktopProperties = desktopProperties;
        this.fileRoot = fileStorageService.fileRoot();
    }

    public Map<String, Object> localPath(FileHandle handle) {
        Path file;
        try {
            file = resolveExistingFile(handle);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve file localPath: {}", e.getMessage());
            return localPathError(e.getMessage(), errorCode(e));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("fileRoot", fileRoot.toString());
        result.put("checkedPath", file.toString());
        if (!isExistingFile(file)) {
            result.putAll(localPathError("File does not exist", FileAssetErrorCodes.FILE_NOT_FOUND));
            return result;
        }
        result.putAll(localPathSuccess(file, handle));
        return result;
    }

    public Path fileRoot() {
        return fileRoot;
    }

    public Map<String, Object> openFolder(FileHandle handle) {
        if (!desktopProperties.isOpenFolderEnabled()) {
            log.info("Open folder is disabled by configuration (app.desktop.open-folder-enabled=false)");
            return openFolderError("Open folder is disabled", FileAssetErrorCodes.OPEN_FOLDER_DISABLED);
        }

        Path file;
        try {
            file = resolveExistingFile(handle);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve file for openFolder: {}", e.getMessage());
            return openFolderError(e.getMessage(), errorCode(e));
        }
        if (!isExistingFile(file)) {
            return openFolderError("File does not exist", FileAssetErrorCodes.FILE_NOT_FOUND);
        }

        return openFolderByPath(file);
    }

    public Map<String, Object> openFolderByPath(OwnerType ownerType, Long ownerId, String category, String fileName) {
        if (!desktopProperties.isOpenFolderEnabled()) {
            log.info("Open folder is disabled by configuration (app.desktop.open-folder-enabled=false)");
            return openFolderError("Open folder is disabled", FileAssetErrorCodes.OPEN_FOLDER_DISABLED);
        }

        if (!isSafeFileName(fileName)) {
            log.warn("Invalid fileName detected: {}", fileName);
            return openFolderError("fileName is invalid: " + fileName, FileAssetErrorCodes.INVALID_FILE);
        }

        String folder = FileCategory.folderOf(category);
        if (folder == null) {
            log.warn("Cannot resolve folder for category: {}", category);
            return openFolderError("category is invalid: " + category, FileAssetErrorCodes.INVALID_CATEGORY);
        }

        Path file = resolvePath(ownerType, ownerId, category, folder, fileName);
        if (!isExistingFile(file)) {
            return openFolderError("File does not exist", FileAssetErrorCodes.FILE_NOT_FOUND);
        }

        return openFolderByPath(file);
    }

    private Map<String, Object> openFolderByPath(Path file) {
        String fullPath = file.toAbsolutePath().normalize().toString();
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            ProcessBuilder processBuilder;
            if (os.contains("win")) {
                processBuilder = new ProcessBuilder("explorer.exe", "/select,", fullPath);
            } else if (os.contains("mac")) {
                processBuilder = new ProcessBuilder("open", "-R", fullPath);
            } else {
                Path parent = file.getParent();
                processBuilder = new ProcessBuilder("xdg-open", parent == null ? fullPath : parent.toString());
            }
            processBuilder.start();
            return openFolderSuccess(fullPath);
        } catch (IOException | RuntimeException e) {
            return openFolderError(
                    "Cannot open file explorer: " + e.getMessage(),
                    FileAssetErrorCodes.OPEN_FOLDER_FAILED,
                    fullPath
            );
        }
    }

    private Map<String, Object> localPathSuccess(Path file, FileHandle handle) {
        Map<String, Object> result = new HashMap<>();
        result.put("found", true);
        result.put("localPath", file.toAbsolutePath().normalize().toString());
        result.put("fileName", handle.fileName());
        result.put("category", handle.category());
        result.put("ownerType", handle.ownerType());
        result.put("ownerId", handle.ownerId());
        return result;
    }

    private Map<String, Object> localPathError(String error, String errorCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("found", false);
        result.put("error", error);
        result.put("errorCode", errorCode);
        return result;
    }

    private Map<String, Object> openFolderSuccess(String localPath) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("localPath", localPath);
        return result;
    }

    private Map<String, Object> openFolderError(String error, String errorCode) {
        return openFolderError(error, errorCode, null);
    }

    private Map<String, Object> openFolderError(String error, String errorCode, String localPath) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", error);
        result.put("errorCode", errorCode);
        if (localPath != null) {
            result.put("localPath", localPath);
        }
        return result;
    }

    private static String errorCode(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("fileName is invalid")) {
            return FileAssetErrorCodes.INVALID_FILE;
        }
        if (message.startsWith("category is invalid")) {
            return FileAssetErrorCodes.INVALID_CATEGORY;
        }
        return FileAssetErrorCodes.FILE_NOT_FOUND;
    }

    private Path resolveExistingFile(FileHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("file handle is required");
        }
        if (!isSafeFileName(handle.fileName())) {
            log.warn("Invalid fileName detected: {}", handle.fileName());
            throw new IllegalArgumentException("fileName is invalid: " + handle.fileName());
        }

        String category = handle.storageCategory() == null ? handle.category() : handle.storageCategory();
        String folder = FileCategory.folderOf(category);
        if (folder == null) {
            log.warn("Cannot resolve folder for category: {} (storageCategory: {})", handle.category(), handle.storageCategory());
            throw new IllegalArgumentException("category is invalid: " + category);
        }

        Long storageOwnerId = handle.storageOwnerId();
        if (storageOwnerId == null) {
            return fileRoot.resolve(folder).resolve(handle.fileName()).normalize();
        }
        return switch (FileCategory.scopeOf(category)) {
            case FLAT -> fileRoot.resolve(folder).resolve(handle.fileName()).normalize();
            case PACKAGING -> fileStorageService.packagingDir(storageOwnerId).resolve(folder).resolve(handle.fileName()).normalize();
            case EMPLOYEE -> fileStorageService.employeeDir(storageOwnerId).resolve(folder).resolve(handle.fileName()).normalize();
            case PRODUCT -> resolveProductFile(storageOwnerId, folder, handle.fileName());
        };
    }

    /**
     * 解析文件物理路径（供缩略图等场景使用）。不会因为文件不存在而抛错，
     * 由调用方自行决定是否需要存在性检查。
     */
    public Path resolvePath(FileHandle handle) {
        return resolveExistingFile(handle);
    }

    /**
     * 解析文件物理路径并在文件不存在时抛出 FileNotFoundException。
     */
    public Path resolveExistingPath(FileHandle handle) throws java.nio.file.NoSuchFileException {
        Path path = resolveExistingFile(handle);
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            throw new java.nio.file.NoSuchFileException(handle.fileName());
        }
        return path;
    }

    /**
     * 解析封面文件的物理路径。
     * 封面文件与原文件存储在同一目录下，coverFileName 为独立文件名。
     * 通过构造一个"虚拟 FileHandle"（用 coverFileName 替换 fileName）复用现有路径解析逻辑。
     */
    public Path resolveCoverPath(FileHandle handle) throws java.nio.file.NoSuchFileException {
        if (handle == null || handle.coverFileName() == null || handle.coverFileName().isBlank()) {
            throw new IllegalArgumentException("coverFileName is required");
        }
        if (!isSafeFileName(handle.coverFileName())) {
            throw new IllegalArgumentException("coverFileName is invalid: " + handle.coverFileName());
        }
        // 构造临时 FileHandle：保持 storageCategory/storageOwnerId 等目录信息不变，仅替换 fileName
        // 字段顺序：assetId, ownerType, ownerId, category, storageCategory, storageOwnerId,
        //           fileName, originalFileName, mimeType, fileSize, tags, defaultFile, sortOrder,
        //           deleted, createdAt, updatedAt, coverFileName, capabilities
        FileHandle coverHandle = new FileHandle(
                handle.assetId(),            // assetId
                handle.ownerType(),          // ownerType
                handle.ownerId(),            // ownerId
                handle.category(),           // category
                handle.storageCategory(),    // storageCategory
                handle.storageOwnerId(),     // storageOwnerId
                handle.coverFileName(),      // fileName ← 用封面名
                null, null, null, null,      // originalFileName, mimeType, fileSize, tags
                null, null, null,            // defaultFile, sortOrder, deleted
                null, null,                  // createdAt, updatedAt
                null,                        // coverFileName (cover 自身没有嵌套 cover)
                handle.capabilities()        // capabilities
        );
        Path path = resolveExistingFile(coverHandle);
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            throw new java.nio.file.NoSuchFileException(handle.coverFileName());
        }
        return path;
    }

    /**
     * 解析 asset 文件所在目录（用于上传封面时确定存储目标目录）。
     * 仅返回目录，不校验文件是否存在；不存在时会按路径规则推断目标目录。
     */
    public Path resolveAssetDirectory(FileHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("file handle is required");
        }

        String category = handle.storageCategory() == null ? handle.category() : handle.storageCategory();
        String folder = FileCategory.folderOf(category);
        if (folder == null) {
            log.warn("Cannot resolve folder for category: {} (storageCategory: {})", handle.category(), handle.storageCategory());
            throw new IllegalArgumentException("category is invalid: " + category);
        }

        Long storageOwnerId = handle.storageOwnerId();
        if (storageOwnerId == null) {
            return fileRoot.resolve(folder).normalize();
        }
        StorageScope scope = FileCategory.scopeOf(category);
        if (scope == null) {
            return fileRoot.resolve(folder).normalize();
        }
        return switch (scope) {
            case FLAT -> fileRoot.resolve(folder).normalize();
            case PACKAGING -> fileStorageService.packagingDir(storageOwnerId).resolve(folder).normalize();
            case EMPLOYEE -> fileStorageService.employeeDir(storageOwnerId).resolve(folder).normalize();
            case PRODUCT -> fileStorageService.productDir(storageOwnerId).resolve(folder).normalize();
        };
    }

    private Path resolveProductFile(Long modelId, String folder, String fileName) {
        for (Path baseDir : fileStorageService.productDirs(modelId)) {
            Path file = baseDir.resolve(folder).resolve(fileName).normalize();
            if (isExistingFile(file)) {
                return file;
            }
        }
        Path legacyFlatFile = fileRoot.resolve(folder).resolve(fileName).normalize();
        if (isExistingFile(legacyFlatFile)) {
            return legacyFlatFile;
        }
        Path discovered = discoverProductFile(modelId, folder, fileName);
        if (discovered != null) {
            return discovered;
        }
        return fileStorageService.productDir(modelId).resolve(folder).resolve(fileName).normalize();
    }

    private Path resolvePath(OwnerType ownerType, Long ownerId, String category, String folder, String fileName) {
        if (ownerId == null) {
            return fileRoot.resolve(folder).resolve(fileName).normalize();
        }
        return switch (FileCategory.scopeOf(category)) {
            case FLAT -> fileRoot.resolve(folder).resolve(fileName).normalize();
            case PACKAGING -> fileStorageService.packagingDir(ownerId).resolve(folder).resolve(fileName).normalize();
            case EMPLOYEE -> fileStorageService.employeeDir(ownerId).resolve(folder).resolve(fileName).normalize();
            case PRODUCT -> resolveProductFile(ownerId, folder, fileName);
        };
    }

    private boolean isExistingFile(Path file) {
        return file != null && Files.exists(file) && Files.isRegularFile(file);
    }

    private Path discoverProductFile(Long modelId, String folder, String fileName) {
        if (modelId == null || !isSafeFileName(fileName)) {
            return null;
        }
        Path documents = Path.of(System.getProperty("user.home", ""), "Documents").toAbsolutePath().normalize();
        if (!Files.isDirectory(documents)) {
            return null;
        }
        String modelPrefix = modelId + "_";
        try (Stream<Path> stream = Files.find(
                documents,
                7,
                (path, attrs) -> attrs.isRegularFile()
                        && fileName.equals(path.getFileName().toString())
                        && path.getParent() != null
                        && folder.equals(path.getParent().getFileName().toString())
                        && path.getParent().getParent() != null
                        && isModelDirectory(path.getParent().getParent().getFileName().toString(), modelId, modelPrefix))) {
            return stream.findFirst().map(Path::toAbsolutePath).map(Path::normalize).orElse(null);
        } catch (IOException | UncheckedIOException ignored) {
            return null;
        }
    }

    private boolean isModelDirectory(String directoryName, Long modelId, String modelPrefix) {
        return String.valueOf(modelId).equals(directoryName) || directoryName.startsWith(modelPrefix);
    }

    private boolean isSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..")) {
            return false;
        }
        // Windows 驱动器号（如 C:）中的冒号是特殊处理
        // 其他位置的冒号（如时间戳）在 Windows 上是非法字符
        // 但我们存储的文件名应该已经过滤了这些字符
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('\0') >= 0) {
            return false;
        }
        // 检查冒号：Windows 禁止文件名中包含冒号（除驱动器号外）
        // 但我们存储的文件名不应该包含驱动器号
        if (fileName.indexOf(':') >= 0) {
            log.warn("FileName contains colon (invalid on Windows): {}", fileName);
            return false;
        }
        try {
            return Path.of(fileName).getFileName().toString().equals(fileName);
        } catch (InvalidPathException e) {
            log.warn("Invalid path for fileName: {}", fileName, e);
            return false;
        }
    }
}
