package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAssetCapabilityContractTest {

    private static final Map<Capability, MethodSignature> ACTION_METHODS = Map.of(
            Capability.UPDATE_TAGS, new MethodSignature("updateTags", Long.class, String.class, boolean.class),
            Capability.SET_DEFAULT, new MethodSignature("setDefault", Long.class),
            Capability.REORDER, new MethodSignature("reorder", List.class),
            Capability.MOVE_OWNER, new MethodSignature("moveOwner", Long.class, OwnerType.class, Long.class),
            Capability.MOVE_CATEGORY, new MethodSignature("moveCategory", Long.class, String.class),
            Capability.DELETE, new MethodSignature("softDelete", Long.class),
            Capability.RESTORE, new MethodSignature("restore", Long.class),
            Capability.PERMANENT_DELETE, new MethodSignature("permanentDelete", Long.class, boolean.class)
    );

    @Test
    void sharedCapabilitySetsMatchTheirAdapterImplementations() throws NoSuchMethodException {
        assertImplemented(CatalogFileAssetAdapter.class, FileAssetCapabilities.PRODUCT_ASSET);
        assertImplemented(PackagingFileAssetAdapter.class, FileAssetCapabilities.PACKAGING_ASSET);
        assertImplemented(CompanyFileAssetAdapter.class, FileAssetCapabilities.COMPANY_ASSET);
        assertImplemented(CertificateFileAssetAdapter.class, FileAssetCapabilities.CERTIFICATE);
    }

    @Test
    void ownerSpecificCapabilityBoundariesRemainExplicit() {
        assertTrue(FileAssetCapabilities.PRODUCT_ASSET.containsAll(
                Set.of(Capability.MOVE_OWNER, Capability.MOVE_CATEGORY, Capability.REORDER)));
        assertTrue(FileAssetCapabilities.PACKAGING_ASSET.contains(Capability.REORDER));
        assertTrue(!FileAssetCapabilities.PACKAGING_ASSET.contains(Capability.MOVE_OWNER));
        assertTrue(!FileAssetCapabilities.PACKAGING_ASSET.contains(Capability.MOVE_CATEGORY));
        assertEquals(Set.of(Capability.PREVIEW, Capability.DOWNLOAD, Capability.DELETE, Capability.OPEN_FOLDER),
                FileAssetCapabilities.CERTIFICATE);
    }

    @Test
    void fieldLevelCompanyFilesCannotGainRecordLevelMutations() {
        assertEquals(Set.of(Capability.PREVIEW, Capability.DOWNLOAD, Capability.DELETE, Capability.OPEN_FOLDER),
                FileAssetCapabilities.COMPANY_FIELD_FILE);
    }

    private static void assertImplemented(Class<? extends FileAssetAdapter> adapterType,
                                          Set<Capability> capabilities) throws NoSuchMethodException {
        for (Capability capability : capabilities) {
            MethodSignature signature = ACTION_METHODS.get(capability);
            if (signature == null) {
                continue;
            }
            Method method = adapterType.getMethod(signature.name(), signature.parameterTypes());
            assertNotEquals(FileAssetAdapter.class, method.getDeclaringClass(),
                    () -> adapterType.getSimpleName() + " declares " + capability
                            + " but inherits the unsupported default implementation");
        }
    }

    private record MethodSignature(String name, Class<?>... parameterTypes) {
    }
}
