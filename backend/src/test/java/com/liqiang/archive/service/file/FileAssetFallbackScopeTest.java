package com.liqiang.archive.service.file;

import com.liqiang.archive.config.DesktopProperties;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileAssetFallbackScopeTest {

    @TempDir
    Path tempDir;

    @Test
    void packagingAliasUsesPackagingScopeEvenWhenFolderMatchesProductCategory() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        Path fileRoot = tempDir.resolve("files");
        Path packagingDir = tempDir.resolve("packaging").resolve("17");
        when(storage.fileRoot()).thenReturn(fileRoot);
        when(storage.packagingDir(17L)).thenReturn(packagingDir);
        FileAssetLocationService service = new FileAssetLocationService(storage, new DesktopProperties());

        Path resolved = resolvePath(service, OwnerType.PACKAGING_MODEL, 17L,
                "pkg_main_image", "main_image", "cover.png");

        assertEquals(packagingDir.resolve("main_image").resolve("cover.png").normalize(), resolved);
    }

    @Test
    void productAliasContinuesToUseProductScope() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        Path fileRoot = tempDir.resolve("files");
        Path productDir = tempDir.resolve("products").resolve("8");
        when(storage.fileRoot()).thenReturn(fileRoot);
        when(storage.productDirs(8L)).thenReturn(List.of(productDir));
        when(storage.productDir(8L)).thenReturn(productDir);
        FileAssetLocationService service = new FileAssetLocationService(storage, new DesktopProperties());

        Path resolved = resolvePath(service, OwnerType.PRODUCT_MODEL, 8L,
                "sku", "sku_image", "sku.png");

        assertEquals(productDir.resolve("sku_image").resolve("sku.png").normalize(), resolved);
    }

    private static Path resolvePath(FileAssetLocationService service, OwnerType ownerType, Long ownerId,
                                    String category, String folder, String fileName) throws Exception {
        Method method = FileAssetLocationService.class.getDeclaredMethod(
                "resolvePath", OwnerType.class, Long.class, String.class, String.class, String.class
        );
        method.setAccessible(true);
        return (Path) method.invoke(service, ownerType, ownerId, category, folder, fileName);
    }
}
