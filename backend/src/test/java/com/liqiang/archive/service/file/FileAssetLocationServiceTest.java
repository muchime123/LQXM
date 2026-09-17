package com.liqiang.archive.service.file;

import com.liqiang.archive.config.DesktopProperties;
import com.liqiang.archive.dto.FileAssetErrorCodes;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileAssetLocationServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;
    private DesktopProperties desktopProperties;

    @BeforeEach
    void setUp() {
        fileStorageService = mock(FileStorageService.class);
        desktopProperties = new DesktopProperties();
        when(fileStorageService.fileRoot()).thenReturn(tempDir.resolve("registered-files"));
    }

    @Test
    void rejectsTraversalLikeFileNamesBeforeResolvingStorage() {
        FileAssetLocationService service = service();

        Map<String, Object> result = service.localPath(
                handle(OwnerType.COMPANY, 3L, "../secret.txt", "company_asset"));

        assertEquals(false, result.get("found"));
        assertEquals(FileAssetErrorCodes.INVALID_FILE, result.get("errorCode"));
        assertTrue(result.get("error").toString().startsWith("fileName is invalid"));
    }

    @Test
    void reportsInvalidCategoryWithStableErrorCode() {
        FileAssetLocationService service = service();

        Map<String, Object> result = service.localPath(
                handle(OwnerType.COMPANY, 3L, "logo.png", "not_registered"));

        assertEquals(false, result.get("found"));
        assertEquals(FileAssetErrorCodes.INVALID_CATEGORY, result.get("errorCode"));
    }

    @Test
    void doesNotLaunchExplorerWhenDesktopFeatureIsDisabled() {
        desktopProperties.setOpenFolderEnabled(false);
        FileAssetLocationService service = service();

        Map<String, Object> result = service.openFolder(
                handle(OwnerType.COMPANY, 9L, "logo.png", "company_asset"));

        assertFalse((Boolean) result.get("success"));
        assertEquals(FileAssetErrorCodes.OPEN_FOLDER_DISABLED, result.get("errorCode"));
    }

    private FileAssetLocationService service() {
        return new FileAssetLocationService(fileStorageService, desktopProperties);
    }

    private static FileHandle handle(OwnerType ownerType, Long assetId, String fileName, String category) {
        return new FileHandle(assetId, ownerType, assetId, category, category, null, fileName, fileName,
                null, null, null, false, 0, false, null, null, null,
                Set.of(Capability.OPEN_FOLDER));
    }
}
