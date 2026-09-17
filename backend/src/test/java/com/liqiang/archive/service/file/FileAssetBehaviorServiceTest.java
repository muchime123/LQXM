package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAssetBehaviorServiceTest {
    @Test
    void rejectsMissingAndDuplicateAdaptersAtStartup() {
        assertThrows(IllegalStateException.class, () -> new FileAssetBehaviorService(List.of()));
        FakeAdapter first = adapter(Set.of(Capability.PREVIEW), Set.of(Capability.PREVIEW));
        FakeAdapter duplicate = adapter(Set.of(Capability.DOWNLOAD), Set.of(Capability.DOWNLOAD));
        assertThrows(IllegalStateException.class, () -> new FileAssetBehaviorService(List.of(first, duplicate)));
    }

    @Test
    void exposesDefensiveCapabilitySnapshots() {
        FakeAdapter adapter = adapter(Set.of(Capability.PREVIEW, Capability.DOWNLOAD), Set.of(Capability.PREVIEW));
        FileAssetBehaviorService service = service(adapter);
        assertEquals(Set.of(Capability.PREVIEW, Capability.DOWNLOAD), service.capabilities(OwnerType.COMPANY));
        assertEquals(Set.of(Capability.PREVIEW, Capability.DOWNLOAD), service.allCapabilities().get(OwnerType.COMPANY));
        assertThrows(UnsupportedOperationException.class,
                () -> service.capabilities(OwnerType.COMPANY).add(Capability.DELETE));
    }

    @Test
    void requiresCapabilityAtBothOwnerAndHandleLevels() {
        FakeAdapter ownerDenied = adapter(Set.of(Capability.PREVIEW), Set.of(Capability.PREVIEW));
        assertThrows(UnsupportedOperationException.class,
                () -> service(ownerDenied).getForCapability(OwnerType.COMPANY, 42L, Capability.DOWNLOAD));
        assertEquals(0, ownerDenied.getCalls);

        FakeAdapter handleDenied = adapter(Set.of(Capability.PREVIEW, Capability.DOWNLOAD), Set.of(Capability.PREVIEW));
        assertThrows(UnsupportedOperationException.class,
                () -> service(handleDenied).getForCapability(OwnerType.COMPANY, 42L, Capability.DOWNLOAD));
        assertEquals(1, handleDenied.getCalls);
    }

    @Test
    void delegatesPermanentDeleteOnlyAfterCapabilityCheck() {
        FakeAdapter adapter = adapter(Set.of(Capability.PERMANENT_DELETE), Set.of(Capability.PERMANENT_DELETE));
        service(adapter).permanentDelete(OwnerType.COMPANY, 42L, true);
        assertEquals(42L, adapter.permanentlyDeletedId);
        assertTrue(adapter.deletePhysical);
    }

    @Test
    void validatesReorderPayloadBeforeDelegating() {
        FakeAdapter adapter = adapter(Set.of(Capability.REORDER), Set.of(Capability.REORDER));
        FileAssetBehaviorService service = service(adapter);
        assertThrows(IllegalArgumentException.class, () -> service.reorder(OwnerType.COMPANY, null));
        assertThrows(IllegalArgumentException.class, () -> service.reorder(OwnerType.COMPANY,
                List.of(new FileAssetAdapter.ReorderItem(42L, 0), new FileAssetAdapter.ReorderItem(42L, 1))));
        assertTrue(adapter.reordered.isEmpty());
    }

    @Test
    void checksEveryHandleBeforeApplyingReorder() {
        FakeAdapter adapter = adapter(Set.of(Capability.REORDER), Set.of(Capability.REORDER));
        adapter.deniedAssetId = 43L;
        FileAssetBehaviorService service = service(adapter);
        assertThrows(UnsupportedOperationException.class, () -> service.reorder(OwnerType.COMPANY,
                List.of(new FileAssetAdapter.ReorderItem(42L, 0), new FileAssetAdapter.ReorderItem(43L, 1))));
        assertTrue(adapter.reordered.isEmpty());
    }

    @Test
    void validatesRequiredActionArguments() {
        FakeAdapter adapter = adapter(Set.of(Capability.MOVE_CATEGORY, Capability.MOVE_OWNER),
                Set.of(Capability.MOVE_CATEGORY, Capability.MOVE_OWNER));
        FileAssetBehaviorService service = service(adapter);
        assertThrows(IllegalArgumentException.class, () -> service.moveCategory(OwnerType.COMPANY, 42L, " "));
        assertThrows(IllegalArgumentException.class, () -> service.moveOwner(OwnerType.COMPANY, 42L, null, 7L));
        assertThrows(IllegalArgumentException.class,
                () -> service.moveOwner(OwnerType.COMPANY, 42L, OwnerType.PRODUCT_MODEL, null));
    }

    private static FileAssetBehaviorService service(FakeAdapter adapter) {
        return new FileAssetBehaviorService(List.of(adapter));
    }

    private static FakeAdapter adapter(Set<Capability> ownerCapabilities, Set<Capability> handleCapabilities) {
        return new FakeAdapter(ownerCapabilities, handleCapabilities);
    }

    private static final class FakeAdapter implements FileAssetAdapter {
        private final Set<Capability> ownerCapabilities;
        private final Set<Capability> handleCapabilities;
        private int getCalls;
        private Long deniedAssetId;
        private Long permanentlyDeletedId;
        private boolean deletePhysical;
        private List<ReorderItem> reordered = new ArrayList<>();

        private FakeAdapter(Set<Capability> ownerCapabilities, Set<Capability> handleCapabilities) {
            this.ownerCapabilities = ownerCapabilities;
            this.handleCapabilities = handleCapabilities;
        }

        @Override public OwnerType ownerType() { return OwnerType.COMPANY; }
        @Override public Set<Capability> capabilities() { return ownerCapabilities; }
        @Override public List<FileHandle> list(Long ownerId) { return List.of(handle(42L)); }
        @Override public FileHandle get(Long assetId) { getCalls++; return handle(assetId); }

        @Override
        public void permanentDelete(Long assetId, boolean deletePhysical) {
            this.permanentlyDeletedId = assetId;
            this.deletePhysical = deletePhysical;
        }

        @Override public void reorder(List<ReorderItem> items) { this.reordered = List.copyOf(items); }

        private FileHandle handle(Long assetId) {
            Set<Capability> capabilities = assetId.equals(deniedAssetId) ? Set.of() : handleCapabilities;
            return new FileHandle(assetId, OwnerType.COMPANY, 1L, "company_asset", "company_asset", null,
                    "sample.png", "sample.png", "image/png", 10L, null, false, 0, false,
                    null, null, null, capabilities);
        }
    }
}
