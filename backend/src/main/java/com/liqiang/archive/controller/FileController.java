package com.liqiang.archive.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.liqiang.archive.dto.ApiResponse;
import com.liqiang.archive.dto.OfficePreviewStatus;
import com.liqiang.archive.security.LoginOnly;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.service.FileStorageService;
import com.liqiang.archive.service.OfficePreviewService;
import com.liqiang.archive.util.FileCategory;

import jakarta.persistence.EntityNotFoundException;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final Path fileRoot;
    private final FileStorageService fileStorageService;
    private final OfficePreviewService officePreviewService;

    public FileController(FileStorageService fileStorageService, OfficePreviewService officePreviewService) {
        this.fileStorageService = fileStorageService;
        this.officePreviewService = officePreviewService;
        this.fileRoot = fileStorageService.fileRoot();
    }

    @LoginOnly
    @PostMapping("/upload")
    public FileStorageService.UploadResult upload(@RequestParam("category") String category,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "modelId", required = false) Long modelId,
            @RequestParam(value = "modelName", required = false) String modelName) throws Exception {
        return fileStorageService.store(category, file, modelId, modelName);
    }

    @PublicApi
    @GetMapping("/{modelId:\\d+}/{category}")
    public ResponseEntity<Resource> getProductFile(@PathVariable Long modelId,
            @PathVariable String category,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws Exception {
        String fileName = decodeFileName(encodedFileName);
        if (fileName == null) {
            return ResponseEntity.notFound().build();
        }
        return resolveProductFile(modelId, category, fileName);
    }

    @PublicApi
    @GetMapping("/{category:[a-zA-Z_]+}")
    public ResponseEntity<Resource> getFileByQuery(@PathVariable String category,
            @RequestParam(value = "name", required = false) String fileName,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws Exception {
        if (encodedFileName != null && !encodedFileName.isBlank()) {
            fileName = decodeFileName(encodedFileName);
        }
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return resolveFlatFile(category, fileName);
    }

    /**
     * 品牌资产子分类文件访问：/api/files/company/{subCategory}?encoded=xxx
     * 解析到 company/<subCategory>/<fileName>，找不到时回退到平铺 company/<fileName>。
     */
    @PublicApi
    @GetMapping("/company/{subCategory}")
    public ResponseEntity<Resource> getCompanyAssetFile(@PathVariable String subCategory,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws Exception {
        String fileName = decodeFileName(encodedFileName);
        if (fileName == null || !isSafeFileName(fileName) || !isSafeFileName(subCategory)) {
            return ResponseEntity.notFound().build();
        }
        Path subFile = fileRoot.resolve("company").resolve(subCategory).resolve(fileName).normalize();
        if (subFile.startsWith(fileRoot) && Files.isRegularFile(subFile)) {
            return serveFile(subFile);
        }
        // 回退到平铺路径（迁移过渡期）
        Path flatFile = fileRoot.resolve("company").resolve(fileName).normalize();
        if (flatFile.startsWith(fileRoot) && Files.isRegularFile(flatFile)) {
            return serveFile(flatFile);
        }
        return ResponseEntity.notFound().build();
    }

    /** 品牌资产子分类 Office PDF 预览。 */
    @PublicApi
    @GetMapping("/company/{subCategory}/preview-pdf")
    public ResponseEntity<Resource> previewCompanyAssetAsPdf(
            @PathVariable String subCategory,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws Exception {
        String fileName = decodeFileName(encodedFileName);
        if (fileName == null || !isSafeFileName(fileName) || !isSafeFileName(subCategory)) {
            return ResponseEntity.notFound().build();
        }
        Path sourceFile = resolveCompanyAssetSource(subCategory, fileName);
        if (sourceFile == null || !officePreviewService.supports(fileName)) {
            return ResponseEntity.notFound().build();
        }
        long documentId = hashedDocumentIdForCategory("company/" + subCategory, fileName, Files.getLastModifiedTime(sourceFile).toMillis());
        Path cacheRoot = fileRoot.resolve(".preview-cache").toAbsolutePath().normalize();
        Path pdfPath = officePreviewService.previewPdf(documentId, sourceFile, cacheRoot);
        Resource pdfResource = new FileSystemResource(pdfPath);
        String previewName = fileName.replaceAll("\\.[^.]+$", "") + ".pdf";
        String previewEncoded = URLEncoder.encode(previewName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + previewEncoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfResource);
    }

    /** 品牌资产子分类 Office 预览状态轮询。 */
    @PublicApi
    @GetMapping("/company/{subCategory}/preview-pdf/status")
    public ApiResponse<OfficePreviewStatus> previewCompanyAssetAsPdfStatus(
            @PathVariable String subCategory,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws IOException {
        String fileName = decodeFileName(encodedFileName);
        if (fileName == null || !isSafeFileName(fileName) || !isSafeFileName(subCategory)) {
            throw new EntityNotFoundException("文件名缺失");
        }
        Path sourceFile = resolveCompanyAssetSource(subCategory, fileName);
        if (sourceFile == null) {
            throw new EntityNotFoundException("源文件不存在");
        }
        if (!officePreviewService.supports(fileName)) {
            throw new EntityNotFoundException("该文件类型不支持预览");
        }
        long documentId = hashedDocumentIdForCategory("company/" + subCategory, fileName, Files.getLastModifiedTime(sourceFile).toMillis());
        Path cacheRoot = fileRoot.resolve(".preview-cache").toAbsolutePath().normalize();
        return ApiResponse.success(officePreviewService.startOrGetStatus(documentId, sourceFile, cacheRoot));
    }

    /** 解析品牌资产源文件路径：优先子分类目录，回退平铺。 */
    private Path resolveCompanyAssetSource(String subCategory, String fileName) {
        Path subFile = fileRoot.resolve("company").resolve(subCategory).resolve(fileName).normalize();
        if (subFile.startsWith(fileRoot) && Files.isRegularFile(subFile)) {
            return subFile;
        }
        Path flatFile = fileRoot.resolve("company").resolve(fileName).normalize();
        if (flatFile.startsWith(fileRoot) && Files.isRegularFile(flatFile)) {
            return flatFile;
        }
        return null;
    }

    private ResponseEntity<Resource> resolveProductFile(Long modelId, String category, String fileName)
            throws Exception {
        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeFileName(fileName)) {
            return ResponseEntity.notFound().build();
        }

        FileCategory.StorageScope scope = FileCategory.scopeOf(category);
        if (scope == FileCategory.StorageScope.EMPLOYEE) {
            Path file = fileStorageService.employeeDir(modelId).resolve(folder).resolve(fileName).normalize();
            if (file.startsWith(fileRoot) && Files.isRegularFile(file)) {
                return serveFile(file);
            }
            return ResponseEntity.notFound().build();
        }

        if (FileCategory.isPackaging(category)) {
            Path file = fileStorageService.packagingDir(modelId).resolve(folder).resolve(fileName).normalize();
            if (file.startsWith(fileRoot) && Files.isRegularFile(file)) {
                return serveFile(file);
            }
        } else {
            for (Path baseDir : fileStorageService.productDirs(modelId)) {
                Path file = baseDir.resolve(folder).resolve(fileName).normalize();
                if (file.startsWith(fileRoot) && Files.isRegularFile(file)) {
                    return serveFile(file);
                }
            }
            Path legacyFlatFile = fileRoot.resolve(folder).resolve(fileName).normalize();
            if (legacyFlatFile.startsWith(fileRoot) && Files.isRegularFile(legacyFlatFile)) {
                log.warn("Serving legacy product file from flat category folder: modelId={}, category={}, file={}",
                        modelId, category, fileName);
                return serveFile(legacyFlatFile);
            }
        }
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<Resource> resolveFlatFile(String category, String fileName) throws Exception {
        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeFileName(fileName)) {
            return ResponseEntity.notFound().build();
        }

        Path file = fileRoot.resolve(folder).resolve(fileName).normalize();
        if (!file.startsWith(fileRoot) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return serveFile(file);
    }

    private ResponseEntity<Resource> serveFile(Path file) throws Exception {
        Resource resource = new UrlResource(file.toUri());
        String contentType = contentTypeOf(file);
        String encodedName = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+",
                "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    private String contentTypeOf(Path file) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".svg"))
            return "image/svg+xml";
        if (fileName.endsWith(".webp"))
            return "image/webp";
        if (fileName.endsWith(".avif"))
            return "image/avif";
        if (fileName.endsWith(".ico"))
            return "image/x-icon";
        if (fileName.endsWith(".tif") || fileName.endsWith(".tiff"))
            return "image/tiff";
        String contentType = Files.probeContentType(file);
        return contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private String decodeFileName(String encoded) {
        if (encoded == null || encoded.isBlank())
            return null;
        try {
            return new String(Base64.getUrlDecoder().decode(addBase64Padding(encoded)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("文件名解码失败: {}", encoded);
            return null;
        }
    }

    private boolean isSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("..")) {
            return false;
        }
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('\0') >= 0
                || fileName.indexOf(':') >= 0) {
            return false;
        }
        try {
            return Path.of(fileName).getFileName().toString().equals(fileName);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String addBase64Padding(String base64) {
        int remainder = base64.length() % 4;
        if (remainder == 2)
            return base64 + "==";
        if (remainder == 3)
            return base64 + "=";
        return base64;
    }

    // ====== Office 文件 PDF 预览 ======

    /**
     * 将 Office 文件（包括 .doc）转换为 PDF 后预览。
     * 用于前端预览旧版 Word 等文件。
     */
    @PublicApi
    @GetMapping("/{category:[a-zA-Z_]+}/preview-pdf")
    public ResponseEntity<Resource> previewAsPdf(
            @PathVariable String category,
            @RequestParam(value = "name", required = false) String fileName,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws Exception {
        if (encodedFileName != null && !encodedFileName.isBlank()) {
            fileName = decodeFileName(encodedFileName);
        }
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeFileName(fileName)) {
            return ResponseEntity.notFound().build();
        }

        Path sourceFile = fileRoot.resolve(folder).resolve(fileName).normalize();
        if (!sourceFile.startsWith(fileRoot) || !Files.isRegularFile(sourceFile)) {
            return ResponseEntity.notFound().build();
        }

        if (!officePreviewService.supports(fileName)) {
            return ResponseEntity.notFound().build();
        }

        long documentId = hashedDocumentIdForCategory(folder, fileName, Files.getLastModifiedTime(sourceFile).toMillis());
        Path cacheRoot = fileRoot.resolve(".preview-cache").toAbsolutePath().normalize();
        Path pdfPath = officePreviewService.previewPdf(documentId, sourceFile, cacheRoot);

        Resource pdfResource = new FileSystemResource(pdfPath);
        String previewName = fileName.replaceAll("\\.[^.]+$", "") + ".pdf";
        String previewEncoded = URLEncoder.encode(previewName, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + previewEncoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfResource);
    }

    /**
     * 产品文件 PDF 预览（支持 .doc 等旧格式）
     */
    @PublicApi
    @GetMapping("/{modelId:\\d+}/{category}/preview-pdf")
    public ResponseEntity<Resource> previewProductFileAsPdf(
            @PathVariable Long modelId,
            @PathVariable String category,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws Exception {
        String fileName = decodeFileName(encodedFileName);
        if (fileName == null) {
            return ResponseEntity.notFound().build();
        }

        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeFileName(fileName)) {
            return ResponseEntity.notFound().build();
        }

        Path sourceFile = findProductFile(modelId, folder, fileName);
        if (sourceFile == null) {
            return ResponseEntity.notFound().build();
        }

        if (!officePreviewService.supports(fileName)) {
            return ResponseEntity.notFound().build();
        }

        long documentId = hashedDocumentIdForProduct(modelId, folder, fileName, Files.getLastModifiedTime(sourceFile).toMillis());
        Path cacheRoot = fileRoot.resolve(".preview-cache").toAbsolutePath().normalize();
        Path pdfPath = officePreviewService.previewPdf(documentId, sourceFile, cacheRoot);

        Resource pdfResource = new FileSystemResource(pdfPath);
        String previewName = fileName.replaceAll("\\.[^.]+$", "") + ".pdf";
        String previewEncoded = URLEncoder.encode(previewName, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + previewEncoded)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfResource);
    }

    // ====== Office 预览异步状态查询（前端轮询用） ======

    /** /{category}/preview-pdf 的 documentId 推导（与 previewAsPdf 一致，保证 status 命中同缓存）。 */
    private long hashedDocumentIdForCategory(String folder, String fileName, long mtime) {
        return (long) Math.abs(Objects.hash(folder, fileName, mtime));
    }

    /** /{modelId}/{category}/preview-pdf 的 documentId 推导（与 previewProductFileAsPdf 一致）。 */
    private long hashedDocumentIdForProduct(Long modelId, String folder, String fileName, long mtime) {
        return (long) Math.abs(Objects.hash(modelId, folder, fileName, mtime));
    }

    /** 状态轮询由前端原生 fetch 调用（无 Authorization header），且转换入口 {@code preview-pdf} 已为 {@link PublicApi}。 */
    @PublicApi
    @GetMapping("/{category:[a-zA-Z_]+}/preview-pdf/status")
    public ApiResponse<OfficePreviewStatus> previewAsPdfStatus(
            @PathVariable String category,
            @RequestParam(value = "name", required = false) String fileName,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws IOException {
        if (encodedFileName != null && !encodedFileName.isBlank()) {
            fileName = decodeFileName(encodedFileName);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new EntityNotFoundException("文件名缺失");
        }
        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeFileName(fileName)) {
            throw new EntityNotFoundException("文件不存在");
        }
        Path sourceFile = fileRoot.resolve(folder).resolve(fileName).normalize();
        if (!sourceFile.startsWith(fileRoot) || !Files.isRegularFile(sourceFile)) {
            throw new EntityNotFoundException("源文件不存在");
        }
        if (!officePreviewService.supports(fileName)) {
            throw new EntityNotFoundException("该文件类型不支持预览");
        }
        long documentId = hashedDocumentIdForCategory(folder, fileName, Files.getLastModifiedTime(sourceFile).toMillis());
        Path cacheRoot = fileRoot.resolve(".preview-cache").toAbsolutePath().normalize();
        return ApiResponse.success(officePreviewService.startOrGetStatus(documentId, sourceFile, cacheRoot));
    }

    /** 同 {@link #previewAsPdfStatus}：原生 fetch 轮询，标注 {@link PublicApi}。 */
    @PublicApi
    @GetMapping("/{modelId:\\d+}/{category}/preview-pdf/status")
    public ApiResponse<OfficePreviewStatus> previewProductFileAsPdfStatus(
            @PathVariable Long modelId,
            @PathVariable String category,
            @RequestParam(value = "encoded", required = false) String encodedFileName) throws IOException {
        String fileName = decodeFileName(encodedFileName);
        if (fileName == null) {
            throw new EntityNotFoundException("文件名缺失");
        }
        String folder = FileCategory.folderOf(category);
        if (folder == null || !isSafeFileName(fileName)) {
            throw new EntityNotFoundException("文件不存在");
        }
        Path sourceFile = findProductFile(modelId, folder, fileName);
        if (sourceFile == null) {
            throw new EntityNotFoundException("源文件不存在");
        }
        if (!officePreviewService.supports(fileName)) {
            throw new EntityNotFoundException("该文件类型不支持预览");
        }
        long documentId = hashedDocumentIdForProduct(modelId, folder, fileName, Files.getLastModifiedTime(sourceFile).toMillis());
        Path cacheRoot = fileRoot.resolve(".preview-cache").toAbsolutePath().normalize();
        return ApiResponse.success(officePreviewService.startOrGetStatus(documentId, sourceFile, cacheRoot));
    }

    private Path findProductFile(Long modelId, String folder, String fileName) {
        FileCategory.StorageScope scope = FileCategory.scopeOf(folder);
        if (scope == FileCategory.StorageScope.EMPLOYEE) {
            Path file = fileStorageService.employeeDir(modelId).resolve(folder).resolve(fileName).normalize();
            if (file.startsWith(fileRoot) && Files.isRegularFile(file)) {
                return file;
            }
            return null;
        }
        if (FileCategory.isPackaging(folder)) {
            Path file = fileStorageService.packagingDir(modelId).resolve(folder).resolve(fileName).normalize();
            if (file.startsWith(fileRoot) && Files.isRegularFile(file)) {
                return file;
            }
        } else {
            for (Path baseDir : fileStorageService.productDirs(modelId)) {
                Path file = baseDir.resolve(folder).resolve(fileName).normalize();
                if (file.startsWith(fileRoot) && Files.isRegularFile(file)) {
                    return file;
                }
            }
            Path legacyFlatFile = fileRoot.resolve(folder).resolve(fileName).normalize();
            if (legacyFlatFile.startsWith(fileRoot) && Files.isRegularFile(legacyFlatFile)) {
                return legacyFlatFile;
            }
        }
        return null;
    }

}
