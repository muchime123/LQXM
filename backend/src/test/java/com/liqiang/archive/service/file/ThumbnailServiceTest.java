package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThumbnailServiceTest {

    @TempDir
    Path tempDir;

    private ThumbnailService service;
    private FileAssetLocationService locationService;
    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.fileRoot()).thenReturn(tempDir);
        locationService = mock(FileAssetLocationService.class);
        when(locationService.fileRoot()).thenReturn(tempDir);
        service = new ThumbnailService(locationService);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up generated thumbnails
        Path thumbDir = tempDir.resolve(".thumbnails");
        if (Files.exists(thumbDir)) {
            try (var stream = Files.list(thumbDir)) {
                stream.forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    void imageSupportedReturnsTrue() {
        assertTrue(service.isSupported("jpg"));
        assertTrue(service.isSupported(".png"));
        assertTrue(service.isSupported("PDF"));
        assertTrue(service.isSupported(".pdf"));
        assertFalse(service.isSupported("doc"));
        assertFalse(service.isSupported(""));
        assertFalse(service.isSupported(null));
    }

    @Test
    void generatesImageThumbnail() throws Exception {
        // Create a 800x600 source image
        Path imageFile = tempDir.resolve("test.png");
        BufferedImage source = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        source.createGraphics().fillRect(0, 0, 800, 600);
        ImageIO.write(source, "png", imageFile.toFile());

        FileHandle handle = new FileHandle(
                1L, OwnerType.DOCUMENT, 1L, "document", "document", 1L,
                "test.png", "test.png", "image/png", 100L,
                null, false, 0, false, null, null, null, Set.of()
        );
        when(locationService.resolveExistingPath(handle)).thenReturn(imageFile);

        Path thumb = service.getOrCreateThumbnail(handle);

        assertTrue(Files.exists(thumb));
        assertTrue(thumb.getFileName().toString().endsWith(".jpg"));
        BufferedImage thumbImage = ImageIO.read(thumb.toFile());
        assertNotNull(thumbImage);
        // Should be scaled down to fit within 240x240
        assertTrue(thumbImage.getWidth() <= ThumbnailService.THUMBNAIL_SIZE);
        assertTrue(thumbImage.getHeight() <= ThumbnailService.THUMBNAIL_SIZE);
    }

    @Test
    void rejectsUnsupportedExtension() {
        FileHandle handle = new FileHandle(
                1L, OwnerType.DOCUMENT, 1L, "document", "document", 1L,
                "test.docx", "test.docx", null, 100L,
                null, false, 0, false, null, null, null, Set.of()
        );
        // resolveExistingPath is not even called because the check happens before
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> service.getOrCreateThumbnail(handle)
        );
        assertTrue(ex.getMessage().contains("docx"));
    }

    @Test
    void cachesByFileContent() throws Exception {
        // Create a small image
        Path imageFile = tempDir.resolve("cached.png");
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(source, "png", imageFile.toFile());

        FileHandle handle = new FileHandle(
                1L, OwnerType.DOCUMENT, 1L, "document", "document", 1L,
                "cached.png", "cached.png", "image/png", 100L,
                null, false, 0, false, null, null, null, Set.of()
        );
        when(locationService.resolveExistingPath(handle)).thenReturn(imageFile);

        Path first = service.getOrCreateThumbnail(handle);
        long firstMtime = Files.getLastModifiedTime(first).toMillis();
        long firstSize = Files.size(first);

        // Wait at least 10ms to ensure mtime would differ on regeneration
        Thread.sleep(20);

        Path second = service.getOrCreateThumbnail(handle);
        // Should return the same cached file
        assertEquals(first, second);
        assertEquals(firstSize, Files.size(second));
    }
}
