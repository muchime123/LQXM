package com.liqiang.archive.service.file;

import com.liqiang.archive.config.DesktopProperties;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAssetFilesystemIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesAndMeasuresUnicodeFileNamesOnTheRealFilesystem() throws IOException {
        FileStorageService storage = storage();
        Path file = tempDir.resolve("company").resolve("公司 标志（新版）.png");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "中文内容");
        FileAssetLocationService locations = new FileAssetLocationService(
                storage, new DesktopProperties());

        Map<String, Object> result = locations.localPath(handle("公司 标志（新版）.png"));

        assertEquals(true, result.get("found"));
        assertEquals(file.toAbsolutePath().normalize().toString(), result.get("localPath"));
        assertTrue(storage.exists("company_asset", "公司 标志（新版）.png"));
        assertEquals(Files.size(file), storage.fileSize("company_asset", "公司 标志（新版）.png"));
    }

    @Test
    void deletesUnicodeFileAndItsThumbnailTogether() throws IOException {
        FileStorageService storage = storage();
        String fileName = "产品手册 中文.png";
        Path file = tempDir.resolve("company").resolve(fileName);
        Path thumbnail = tempDir.resolve("company").resolve("thumbnails").resolve("thumb_" + fileName);
        Files.createDirectories(thumbnail.getParent());
        Files.writeString(file, "image");
        Files.writeString(thumbnail, "thumbnail");

        assertTrue(storage.deleteIfExists("company_asset", fileName));
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(thumbnail));
    }

    @Test
    void refusesCrossCategoryAndOutsideRootDeletion() throws IOException {
        Path storageRoot = tempDir.resolve("storage-root");
        Files.createDirectories(storageRoot);
        FileStorageService storage = new FileStorageService(storageRoot.toString());
        Path document = storageRoot.resolve("document").resolve("secret.pdf");
        Path outside = tempDir.resolve("outside-secret.pdf");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "secret");
        Files.writeString(outside, "outside");

        assertFalse(storage.deleteIfExists("company_asset", "../document/secret.pdf"));
        assertFalse(storage.deleteIfExists("company_asset", "..\\document\\secret.pdf"));
        assertFalse(storage.deleteIfExists("company_asset", "../../outside-secret.pdf"));
        assertTrue(Files.exists(document));
        assertTrue(Files.exists(outside));
    }

    @Test
    void neverTreatsDirectoriesOrUnsafeMoveNamesAsFiles() throws IOException {
        FileStorageService storage = storage();
        Path directory = tempDir.resolve("company").resolve("folder.pdf");
        Files.createDirectories(directory);

        assertFalse(storage.exists("company_asset", "folder.pdf"));
        assertEquals(0, storage.fileSize("company_asset", "folder.pdf"));
        assertFalse(storage.deleteIfExists("company_asset", "folder.pdf"));
        assertTrue(Files.isDirectory(directory));
        assertThrows(IllegalArgumentException.class,
                () -> storage.moveFile(1L, "main_image", 2L, "main_image", "../other.png"));
    }

    private FileStorageService storage() {
        return new FileStorageService(tempDir.toString());
    }

    private static FileHandle handle(String fileName) {
        return new FileHandle(7L, OwnerType.COMPANY, 1L, "company_asset", "company_asset", null,
                fileName, fileName, "image/png", null, null, false, 0, false,
                null, null, null, Set.of(Capability.OPEN_FOLDER));
    }
}
