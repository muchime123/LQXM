package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.OfficePreviewStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OfficePreviewService 异步状态机测试。
 *
 * <p>注入 {@link Runnable#run} 同步执行器，doConvert 在 startOrGetStatus 返回前同步完成，
 * 使状态机行为确定可测；通过 subclass override {@code convertToPdf} 避免依赖真实 LibreOffice。
 */
class OfficePreviewServiceTest {

    private static class TestableService extends OfficePreviewService {
        final AtomicInteger convertCount = new AtomicInteger();
        volatile boolean shouldFail = false;

        TestableService() {
            super("soffice", 60, Runnable::run, new ObjectMapper().findAndRegisterModules());
        }

        @Override
        protected void convertToPdf(Path sourceFile, Path outputDir, Path outputFile) throws IOException {
            convertCount.incrementAndGet();
            if (shouldFail) {
                throw new IOException("convert failed");
            }
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "%PDF-1.4 fake content");
        }
    }

    private Path writeSource(Path dir, String name) throws IOException {
        Path source = dir.resolve(name);
        Files.writeString(source, "office binary content");
        return source;
    }

    // ====== NONE → PENDING → DONE ======

    @Test
    void startOrGetStatusCompletesConversionAndCachesDone(@TempDir Path tmp) throws Exception {
        Path source = writeSource(tmp, "report.docx");
        Path cacheRoot = tmp.resolve(".preview-cache");
        TestableService svc = new TestableService();

        // 同步执行器：doConvert 在返回前完成，但返回的是写入前的 pending 快照
        OfficePreviewStatus first = svc.startOrGetStatus(1L, source, cacheRoot);
        assertEquals("pending", first.status());
        assertFalse(first.pdfReady());
        assertEquals(1, svc.convertCount.get());

        // 第二次：缓存命中 done
        OfficePreviewStatus second = svc.startOrGetStatus(1L, source, cacheRoot);
        assertEquals("done", second.status());
        assertTrue(second.pdfReady());
        // 不重复转换
        assertEquals(1, svc.convertCount.get());
    }

    // ====== PENDING 去重（已完成不重复转换，核心价值） ======

    @Test
    void doneCacheDoesNotReconvert(@TempDir Path tmp) throws Exception {
        Path source = writeSource(tmp, "sheet.xlsx");
        Path cacheRoot = tmp.resolve(".preview-cache");
        TestableService svc = new TestableService();

        svc.startOrGetStatus(7L, source, cacheRoot);
        svc.startOrGetStatus(7L, source, cacheRoot);
        svc.startOrGetStatus(7L, source, cacheRoot);

        assertEquals(1, svc.convertCount.get());
    }

    // ====== ERROR 不自动重试 ======

    @Test
    void errorStatusDoesNotRetry(@TempDir Path tmp) throws Exception {
        Path source = writeSource(tmp, "slides.pptx");
        Path cacheRoot = tmp.resolve(".preview-cache");
        TestableService svc = new TestableService();
        svc.shouldFail = true;

        // 同步执行器：doConvert 在返回前完成，但返回的是写入前的 pending 快照
        OfficePreviewStatus first = svc.startOrGetStatus(2L, source, cacheRoot);
        assertEquals("pending", first.status());
        assertFalse(first.pdfReady());
        assertEquals(1, svc.convertCount.get());

        // 再次请求：ERROR 且指纹匹配 → 不重试
        OfficePreviewStatus second = svc.startOrGetStatus(2L, source, cacheRoot);
        assertEquals("error", second.status());
        assertEquals(1, svc.convertCount.get());
    }

    // ====== stale-pending 恢复 ======

    @Test
    void stalePendingRetriggers(@TempDir Path tmp) throws Exception {
        Path source = writeSource(tmp, "doc.doc");
        Path cacheRoot = tmp.resolve(".preview-cache");
        long mtime = Files.getLastModifiedTime(source).toMillis();
        long size = Files.size(source);

        // 手写一个 stale-pending 状态文件（startedAt 为很久以前）
        Path outputDir = cacheRoot.resolve("documents").resolve("3");
        Files.createDirectories(outputDir);
        String staleJson = """
                {"status":"pending","sourceMtime":%d,"sourceSize":%d,"startedAt":"2020-01-01T00:00:00","updatedAt":"2020-01-01T00:00:00","errorMessage":null}
                """.formatted(mtime, size);
        Files.writeString(outputDir.resolve(".status.json"), staleJson);

        TestableService svc = new TestableService();
        OfficePreviewStatus status = svc.startOrGetStatus(3L, source, cacheRoot);
        // stale → 重触发（同步执行器完成）
        assertEquals(1, svc.convertCount.get());
        // stale 的 pending 被重置为新的 pending（同步完成后磁盘为 done）
        assertEquals("pending", status.status());
    }

    // ====== 源指纹不匹配重触发 ======

    @Test
    void fingerprintMismatchRetriggers(@TempDir Path tmp) throws Exception {
        Path source = writeSource(tmp, "doc.rtf");
        Path cacheRoot = tmp.resolve(".preview-cache");
        TestableService svc = new TestableService();

        svc.startOrGetStatus(4L, source, cacheRoot);
        assertEquals(1, svc.convertCount.get());

        // 模拟源文件被替换：mtime 推到未来
        Files.setLastModifiedTime(source,
                FileTime.from(Instant.now().plusSeconds(3600)));

        svc.startOrGetStatus(4L, source, cacheRoot);
        // 指纹不匹配 → 重触发
        assertEquals(2, svc.convertCount.get());
    }

    // ====== getStatus 只读不触发 ======

    @Test
    void getStatusIsReadOnly(@TempDir Path tmp) throws Exception {
        Path source = writeSource(tmp, "note.wps");
        Path cacheRoot = tmp.resolve(".preview-cache");
        TestableService svc = new TestableService();

        OfficePreviewStatus before = svc.getStatus(5L, source, cacheRoot);
        assertEquals("none", before.status());
        assertEquals(0, svc.convertCount.get());

        svc.startOrGetStatus(5L, source, cacheRoot);

        OfficePreviewStatus after = svc.getStatus(5L, source, cacheRoot);
        assertEquals("done", after.status());
        assertTrue(after.pdfReady());
        // getStatus 不触发转换
        assertEquals(1, svc.convertCount.get());
    }

    // ====== cleanStaleCache ======

    @Test
    void cleanStaleCacheRemovesOldDirsKeepsFresh(@TempDir Path tmp) throws Exception {
        Path cacheRoot = tmp.resolve(".preview-cache");
        Path docsRoot = cacheRoot.resolve("documents");

        // 过期目录（updatedAt 为 2020 年）
        Path oldDir = docsRoot.resolve("100");
        Files.createDirectories(oldDir);
        Files.writeString(oldDir.resolve(".status.json"),
                """
                {"status":"done","sourceMtime":0,"sourceSize":0,"startedAt":"2020-01-01T00:00:00","updatedAt":"2020-01-01T00:00:00","errorMessage":null}
                """);

        // 新鲜目录（updatedAt 为现在）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Path freshDir = docsRoot.resolve("200");
        Files.createDirectories(freshDir);
        Files.writeString(freshDir.resolve(".status.json"),
                """
                {"status":"done","sourceMtime":0,"sourceSize":0,"startedAt":"%s","updatedAt":"%s","errorMessage":null}
                """.formatted(now, now));

        TestableService svc = new TestableService();
        svc.cleanStaleCache(cacheRoot, 30);

        assertFalse(Files.exists(oldDir));
        assertTrue(Files.exists(freshDir));
    }
}
