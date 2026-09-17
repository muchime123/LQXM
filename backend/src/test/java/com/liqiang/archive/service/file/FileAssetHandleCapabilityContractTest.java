package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileAssetHandleCapabilityContractTest {

    @Test
    void rejectsNullListsFromAdapters() {
        ContractAdapter adapter = new ContractAdapter(Set.of(Capability.PREVIEW), handle(Set.of(Capability.PREVIEW)));
        adapter.listed = null;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(adapter).list(OwnerType.COMPANY, 1L));

        assertEquals("File adapter returned a null list for ownerType COMPANY", error.getMessage());
    }

    @Test
    void rejectsNullHandleCapabilitiesOnRead() {
        ContractAdapter adapter = new ContractAdapter(Set.of(Capability.PREVIEW), handle(null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(adapter).get(OwnerType.COMPANY, 42L));

        assertEquals("File handle capabilities must not be null for ownerType COMPANY", error.getMessage());
    }

    @Test
    void rejectsCapabilitiesNotDeclaredByTheOwnerAdapter() {
        ContractAdapter adapter = new ContractAdapter(
                Set.of(Capability.PREVIEW),
                handle(Set.of(Capability.PREVIEW, Capability.PERMANENT_DELETE))
        );

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(adapter).get(OwnerType.COMPANY, 42L));

        assertEquals(
                "File handle exposes capabilities not declared by ownerType COMPANY: [PERMANENT_DELETE]",
                error.getMessage()
        );
    }

    private static FileAssetBehaviorService service(FileAssetAdapter adapter) {
        return new FileAssetBehaviorService(List.of(adapter));
    }

    private static FileHandle handle(Set<Capability> capabilities) {
        return new FileHandle(42L, OwnerType.COMPANY, 1L, "company_asset", "company_asset", null,
                "sample.png", "sample.png", "image/png", 10L, null, false, 0, false,
                null, null, null, capabilities);
    }

    private static final class ContractAdapter implements FileAssetAdapter {
        private final Set<Capability> capabilities;
        private final FileHandle handle;
        private List<FileHandle> listed;

        private ContractAdapter(Set<Capability> capabilities, FileHandle handle) {
            this.capabilities = capabilities;
            this.handle = handle;
            this.listed = List.of(handle);
        }

        @Override public OwnerType ownerType() { return OwnerType.COMPANY; }
        @Override public Set<Capability> capabilities() { return capabilities; }
        @Override public List<FileHandle> list(Long ownerId) { return listed; }
        @Override public FileHandle get(Long assetId) { return handle; }
    }
}
