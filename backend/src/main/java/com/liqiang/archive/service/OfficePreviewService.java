package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.OfficePreviewStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Service
public class OfficePreviewService {

    private static final Set<String> OFFICE_EXTENSIONS = Set.of(
            "doc", "docx", "wps", "xls", "xlsx", "xlsm", "et", "ppt", "pptx", "dps", "rtf"
    );

    static final String STATUS_PENDING = "pending";
    static final String STATUS_DONE = "done";
    static final String STATUS_ERROR = "error";
    static final String STATUS_NONE = "none";

    private final String converterPath;
    private final long timeoutSeconds;
    private final Executor executor;
    private final ObjectMapper objectMapper;

    /** 单实例 per-documentId 锁，保证同一文件并发请求只启动一次转换。 */
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    public OfficePreviewService(
            @Value("${app.preview.office-converter.path:${OFFICE_CONVERTER_PATH:soffice}}") String converterPath,
            @Value("${app.preview.office-converter.timeout-seconds:${OFFICE_CONVERTER_TIMEOUT_SECONDS:60}}") long timeoutSeconds,
            @Qualifier("officePreviewExecutor") Executor executor,
            ObjectMapper objectMapper) {
        this.converterPath = converterPath;
        this.timeoutSeconds = timeoutSeconds;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public boolean supports(String fileName) {
        return OFFICE_EXTENSIONS.contains(extension(fileName));
    }

    // ====== 同步路径（保留：热缓存命中或同步 preview-pdf 端点用） ======

    public Path previewPdf(Long documentId, Path sourceFile, Path cacheRoot) throws IOException {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IOException("源文件不存在");
        }
        if (!supports(sourceFile.getFileName().toString())) {
            throw new IOException("不是可转换的 Office 文件");
        }

        Path outputDir = outputDir(cacheRoot, documentId);
        Files.createDirectories(outputDir);
        Path outputFile = pdfPath(outputDir, sourceFile);
        if (isCacheFresh(sourceFile, outputFile)) {
            return outputFile;
        }

        convertToPdf(sourceFile, outputDir, outputFile);
        return outputFile;
    }

    // ====== 异步状态机 ======

    /**
     * GET 即触发：读状态决定是否启动异步转换，返回当前状态。
     * <ul>
     *   <li>DONE 且缓存新鲜 → 返回 done（pdfReady=true）</li>
     *   <li>PENDING 且未 stale 且指纹匹配 → 返回 pending（去重，他人正在转）</li>
     *   <li>ERROR 且指纹匹配 → 返回 error（不自动重试）</li>
     *   <li>其余（无状态 / stale-pending / 指纹不匹配 / done 缓存过期）→ 写 PENDING + 异步转换</li>
     * </ul>
     */
    public OfficePreviewStatus startOrGetStatus(Long documentId, Path sourceFile, Path cacheRoot) throws IOException {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IOException("源文件不存在");
        }
        if (!supports(sourceFile.getFileName().toString())) {
            throw new IOException("不是可转换的 Office 文件");
        }

        long sourceMtime = Files.getLastModifiedTime(sourceFile).toMillis();
        long sourceSize = Files.size(sourceFile);

        Object lock = locks.computeIfAbsent(documentId, k -> new Object());
        synchronized (lock) {
            Path outputDir = outputDir(cacheRoot, documentId);
            Files.createDirectories(outputDir);
            StatusFile status = readStatus(outputDir);
            Path outputFile = pdfPath(outputDir, sourceFile);

            if (status != null && STATUS_DONE.equals(status.status()) && isCacheFresh(sourceFile, outputFile)) {
                return toDto(status, documentId, true);
            }
            if (status != null && STATUS_PENDING.equals(status.status())
                    && !isStale(status) && fingerprintMatches(status, sourceMtime, sourceSize)) {
                return toDto(status, documentId, false);
            }
            if (status != null && STATUS_ERROR.equals(status.status())
                    && fingerprintMatches(status, sourceMtime, sourceSize)) {
                return toDto(status, documentId, false);
            }

            LocalDateTime now = LocalDateTime.now();
            StatusFile pending = new StatusFile(STATUS_PENDING, sourceMtime, sourceSize, now, now, null);
            writeStatus(outputDir, pending);
            CompletableFuture.runAsync(
                    () -> doConvert(documentId, sourceFile, outputDir, outputFile, sourceMtime, sourceSize, now),
                    executor);
            return toDto(pending, documentId, false);
        }
    }

    /** 只读状态，不触发转换。 */
    public OfficePreviewStatus getStatus(Long documentId, Path sourceFile, Path cacheRoot) throws IOException {
        Path outputDir = outputDir(cacheRoot, documentId);
        StatusFile status = readStatus(outputDir);
        if (status == null) {
            return new OfficePreviewStatus(STATUS_NONE, documentId, null, null, null, false);
        }
        boolean pdfReady = STATUS_DONE.equals(status.status())
                && sourceFile != null && Files.isRegularFile(sourceFile)
                && isCacheFresh(sourceFile, pdfPath(outputDir, sourceFile));
        return toDto(status, documentId, pdfReady);
    }

    /**
     * 清理过期缓存。只清 {@code .preview-cache/documents/} 下，校验路径归属防越界。
     * 按 status.updatedAt（无状态文件按目录 mtime）判断是否过期。
     */
    public void cleanStaleCache(Path cacheRoot, int retentionDays) throws IOException {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Path docsRoot = normalizedRoot.resolve("documents").normalize();
        if (!Files.isDirectory(docsRoot) || !docsRoot.startsWith(normalizedRoot)) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retentionDays, 1));
        try (Stream<Path> stream = Files.list(docsRoot)) {
            stream.forEach(dir -> {
                try {
                    StatusFile status = readStatus(dir);
                    LocalDateTime updated = status != null ? status.updatedAt() : null;
                    if (updated == null) {
                        updated = LocalDateTime.ofInstant(
                                Files.getLastModifiedTime(dir).toInstant(), ZoneId.systemDefault());
                    }
                    if (updated.isBefore(cutoff)) {
                        deleteRecursively(dir);
                        log.info("[OfficePreview] 清理过期缓存目录: {}", dir);
                    }
                } catch (IOException e) {
                    log.warn("[OfficePreview] 清理缓存目录失败 {}: {}", dir, e.getMessage());
                }
            });
        }
    }

    // ====== 异步转换执行（在虚拟线程上，锁外执行） ======

    private void doConvert(Long documentId, Path sourceFile, Path outputDir, Path outputFile,
                           long sourceMtime, long sourceSize, LocalDateTime startedAt) {
        String errorMessage = null;
        try {
            convertToPdf(sourceFile, outputDir, outputFile);
        } catch (Throwable t) {
            errorMessage = t.getMessage();
            log.error("Office 转换失败 documentId={}", documentId, t);
        }
        String finalStatus = errorMessage == null ? STATUS_DONE : STATUS_ERROR;
        try {
            writeStatus(outputDir, new StatusFile(finalStatus, sourceMtime, sourceSize,
                    startedAt, LocalDateTime.now(), errorMessage));
        } catch (IOException e) {
            log.error("写入 Office 预览状态失败 documentId={}", documentId, e);
        }
    }

    // ====== 状态文件读写 ======

    /** 磁盘状态文件记录，含源文件指纹用于检测替换/hash 碰撞。 */
    private record StatusFile(
            String status,
            long sourceMtime,
            long sourceSize,
            LocalDateTime startedAt,
            LocalDateTime updatedAt,
            String errorMessage
    ) {}

    private StatusFile readStatus(Path outputDir) {
        Path statusFile = statusFile(outputDir);
        try {
            String content = Files.readString(statusFile);
            return objectMapper.readValue(content, StatusFile.class);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.warn("读取 Office 预览状态失败 {}: {}", statusFile, e.getMessage());
            return null;
        }
    }

    private void writeStatus(Path outputDir, StatusFile status) throws IOException {
        Path statusFile = statusFile(outputDir);
        Path tmp = outputDir.resolve(".status.json.tmp");
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(status);
        Files.writeString(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, statusFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ====== 转换核心（保留原逻辑） ======

    // protected 便于测试 subclass override，避免依赖真实 LibreOffice。
    protected void convertToPdf(Path sourceFile, Path outputDir, Path outputFile) throws IOException {
        Path profileDir = outputDir.resolve(".lo-profile").normalize();
        Files.createDirectories(profileDir);

        ProcessBuilder builder = new ProcessBuilder(
                converterPath,
                "--headless",
                "--nologo",
                "--nofirststartwizard",
                "--nolockcheck",
                "-env:UserInstallation=" + profileDir.toUri(),
                "--convert-to",
                "pdf",
                "--outdir",
                outputDir.toString(),
                sourceFile.toString()
        );
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new IOException("未找到 Office 预览转换器，请安装 LibreOffice 或配置 OFFICE_CONVERTER_PATH", ex);
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Office 文件转换被中断", ex);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Office 文件转换超时（" + Duration.ofSeconds(timeoutSeconds).toSeconds() + " 秒）");
        }

        if (!Files.isRegularFile(outputFile)) {
            throw new IOException("Office 文件转换失败，退出码：" + process.exitValue());
        }
    }

    // ====== 辅助 ======

    public boolean isCacheFresh(Path sourceFile, Path outputFile) throws IOException {
        return Files.isRegularFile(outputFile)
                && Files.getLastModifiedTime(outputFile).toMillis() >= Files.getLastModifiedTime(sourceFile).toMillis()
                && Files.size(outputFile) > 0;
    }

    private boolean isStale(StatusFile status) {
        if (status.startedAt() == null) {
            return true;
        }
        return Duration.between(status.startedAt(), LocalDateTime.now()).toSeconds() > 2 * timeoutSeconds;
    }

    private boolean fingerprintMatches(StatusFile status, long sourceMtime, long sourceSize) {
        return status.sourceMtime() == sourceMtime && status.sourceSize() == sourceSize;
    }

    private OfficePreviewStatus toDto(StatusFile status, Long documentId, boolean pdfReady) {
        return new OfficePreviewStatus(status.status(), documentId,
                status.startedAt(), status.updatedAt(), status.errorMessage(), pdfReady);
    }

    private Path outputDir(Path cacheRoot, Long documentId) {
        return cacheRoot.resolve("documents").resolve(String.valueOf(documentId)).normalize();
    }

    private Path pdfPath(Path outputDir, Path sourceFile) {
        return outputDir.resolve(stripExtension(sourceFile.getFileName().toString()) + ".pdf").normalize();
    }

    private Path statusFile(Path outputDir) {
        return outputDir.resolve(".status.json");
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                stream.forEach(p -> {
                    try {
                        deleteRecursively(p);
                    } catch (IOException e) {
                        log.warn("[OfficePreview] 删除子项失败 {}: {}", p, e.getMessage());
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }

    private String extension(String fileName) {
        String name = String.valueOf(fileName).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
