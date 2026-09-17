package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileAssetHandleIdentityTest {

    @Test
    void rejectsNullHandlesFromAdapters() {
        FileAssetBehaviorService service = service(new IdentityAdapter(null));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.get(OwnerType.COMPANY, 42L));

        assertEquals("File adapter returned a null handle for ownerType COMPANY", error.getMessage());
    }

    @Test
    void rejectsMismatchedOwnerTypeBeforeRedirectOrAction() {
        FileAssetBehaviorService service = service(new IdentityAdapter(handle(42L, OwnerType.DOCUMENT)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.getForCapability(OwnerType.COMPANY, 42L, Capability.PREVIEW));

        assertEquals("File adapter returned mismatched ownerType: expected COMPANY, actual DOCUMENT", error.getMessage());
    }

    @Test
    void rejectsMismatchedAssetId() {
        FileAssetBehaviorService service = service(new IdentityAdapter(handle(99L, OwnerType.COMPANY)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.get(OwnerType.COMPANY, 42L));

        assertEquals("File adapter returned mismatched assetId: expected 42, actual 99", error.getMessage());
    }

    @Test
    void validatesEveryHandleReturnedByList() {
        IdentityAdapter adapter = new IdentityAdapter(handle(42L, OwnerType.COMPANY));
        adapter.listed = List.of(handle(42L, OwnerType.COMPANY), handle(43L, OwnerType.DOCUMENT));

        assertThrows(IllegalStateException.class, () -> service(adapter).list(OwnerType.COMPANY, 1L));
    }

    private static FileAssetBehaviorService service(FileAssetAdapter adapter) {
        return new FileAssetBehaviorService(List.of(adapter));
    }

    private static FileHandle handle(Long assetId, OwnerType ownerType) {
        return new FileHandle(assetId, ownerType, 1L, "company_asset", "company_asset", null,
                "sample.png", "sample.png", "image/png", 10L, null, false, 0, false,
                null, null, null, Set.of(Capability.PREVIEW));
    }

    private static final class IdentityAdapter implements FileAssetAdapter {
        private final FileHandle handle;
        private List<FileHandle> listed;

        private IdentityAdapter(FileHandle handle) {
            this.handle = handle;
            this.listed = handle == null ? List.of() : List.of(handle);
        }

        @Override public OwnerType ownerType() { return OwnerType.COMPANY; }
        @Override public Set<Capability> capabilities() { return Set.of(Capability.PREVIEW); }
        @Override public List<FileHandle> list(Long ownerId) { return listed; }
        @Override public FileHandle get(Long assetId) { return handle; }
    }
}
