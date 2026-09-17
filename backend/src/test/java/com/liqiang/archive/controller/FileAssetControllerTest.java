package com.liqiang.archive.controller;

import com.liqiang.archive.dto.FileAssetErrorCodes;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.file.FileAssetBehaviorService;
import com.liqiang.archive.service.file.FileAssetLocationService;
import com.liqiang.archive.service.file.ThumbnailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileAssetControllerTest {

    private FileAssetBehaviorService behaviorService;
    private FileAssetLocationService locationService;
    private ThumbnailService thumbnailService;
    private FileAssetController controller;

    @BeforeEach
    void setUp() {
        behaviorService = mock(FileAssetBehaviorService.class);
        locationService = mock(FileAssetLocationService.class);
        thumbnailService = mock(ThumbnailService.class);
        controller = new FileAssetController(behaviorService, locationService, thumbnailService);
    }

    @Test
    void previewRequiresPreviewCapabilityAndBuildsStorageRedirect() {
        FileHandle handle = handle(OwnerType.PRODUCT_MODEL, 42L, "main image.png", "main_image", 7L);
        when(behaviorService.getForCapability(OwnerType.PRODUCT_MODEL, 42L, Capability.PREVIEW))
                .thenReturn(handle);

        ResponseEntity<Void> redirect = controller.preview("product_model", 42L);

        assertEquals(HttpStatus.FOUND, redirect.getStatusCode());
        assertEquals("/api/files/7/main_image?encoded=bWFpbiBpbWFnZS5wbmc", redirect.getHeaders().getFirst("Location"));
        verify(behaviorService).getForCapability(OwnerType.PRODUCT_MODEL, 42L, Capability.PREVIEW);
    }

    @Test
    void legacyDocumentHandleUsesUnifiedStorageStream() {
        when(behaviorService.getForCapability(OwnerType.DOCUMENT, 8L, Capability.DOWNLOAD))
                .thenReturn(handle(OwnerType.DOCUMENT, 8L, "guide.docx", "document", null));

        assertEquals("/api/files/document?encoded=Z3VpZGUuZG9jeA", controller.download("document", 8L).getHeaders().getFirst("Location"));
    }

    @Test
    void deleteRoutesSoftAndPermanentSemanticsExplicitly() {
        controller.delete("company", 11L, false, true);
        verify(behaviorService).softDelete(OwnerType.COMPANY, 11L);
        verify(behaviorService, never()).permanentDelete(OwnerType.COMPANY, 11L, true);

        controller.delete("company", 12L, true, false);
        verify(behaviorService).permanentDelete(OwnerType.COMPANY, 12L, false);
    }

    @Test
    void localPathReturnsStableFallbackPayloadWhenCapabilityIsUnavailable() {
        when(behaviorService.getForCapability(OwnerType.DOCUMENT, 9L, Capability.OPEN_FOLDER))
                .thenThrow(new UnsupportedOperationException("not supported"));

        Map<String, Object> result = controller.localPath("document", 9L);

        assertEquals(false, result.get("found"));
        assertEquals("not supported", result.get("error"));
        assertEquals(FileAssetErrorCodes.FILE_BEHAVIOR_UNSUPPORTED, result.get("errorCode"));
        verify(locationService, never()).localPath(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsBlankFileNameBeforeBuildingRedirect() {
        when(behaviorService.getForCapability(OwnerType.COMPANY, 3L, Capability.PREVIEW))
                .thenReturn(handle(OwnerType.COMPANY, 3L, " ", "company_asset", null));

        assertThrows(IllegalArgumentException.class, () -> controller.preview("company", 3L));
    }

    @Test
    void rejectsUnknownOwnerBeforeCallingService() {
        assertThrows(IllegalArgumentException.class, () -> controller.get("unknown", 1L));
        verify(behaviorService, never()).get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static FileHandle handle(OwnerType ownerType, Long assetId, String fileName,
                                     String storageCategory, Long storageOwnerId) {
        return new FileHandle(assetId, ownerType, storageOwnerId, storageCategory, storageCategory, storageOwnerId,
                fileName, fileName, null, null, null, false, 0, false, null, null,
                null, Set.of(Capability.PREVIEW, Capability.DOWNLOAD, Capability.OPEN_FOLDER));
    }
}
