package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileAssetActionResultContractTest {

    @Test
    void validatesUpdatedTagHandleIdentity() {
        ActionAdapter adapter = new ActionAdapter();
        adapter.actionResult = handle(99L, OwnerType.COMPANY, adapter.capabilities());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(adapter).updateTags(OwnerType.COMPANY, 42L, "new-tag", true));

        assertEquals("File adapter returned mismatched assetId: expected 42, actual 99", error.getMessage());
    }

    @Test
    void validatesDefaultActionHandleOwnerType() {
        ActionAdapter adapter = new ActionAdapter();
        adapter.actionResult = handle(42L, OwnerType.DOCUMENT, adapter.capabilities());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(adapter).setDefault(OwnerType.COMPANY, 42L));

        assertEquals("File adapter returned mismatched ownerType: expected COMPANY, actual DOCUMENT", error.getMessage());
    }

    @Test
    void validatesActionResultCapabilities() {
        ActionAdapter adapter = new ActionAdapter();
        adapter.actionResult = handle(
                42L,
                OwnerType.COMPANY,
                Set.of(Capability.UPDATE_TAGS, Capability.SET_DEFAULT, Capability.PERMANENT_DELETE)
        );

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(adapter).updateTags(OwnerType.COMPANY, 42L, "new-tag", true));

        assertEquals(
                "File handle exposes capabilities not declared by ownerType COMPANY: [PERMANENT_DELETE]",
                error.getMessage()
        );
    }

    @Test
    void allowsProductTagUpdateToChangeOwnerFromModelToVariant() {
        ActionAdapter modelAdapter = new ActionAdapter(OwnerType.PRODUCT_MODEL);
        ActionAdapter variantAdapter = new ActionAdapter(OwnerType.PRODUCT_VARIANT);
        FileHandle linkedToVariant = handle(42L, OwnerType.PRODUCT_VARIANT, variantAdapter.capabilities());
        modelAdapter.actionResult = linkedToVariant;

        FileHandle result = new FileAssetBehaviorService(List.of(modelAdapter, variantAdapter))
                .updateTags(OwnerType.PRODUCT_MODEL, 42L, "一出一,方模具", true);

        assertSame(linkedToVariant, result);
    }

    @Test
    void resolvesCurrentProductOwnerWhenRequestUsesStaleModelOwner() {
        ActionAdapter modelAdapter = new ActionAdapter(OwnerType.PRODUCT_MODEL);
        ActionAdapter variantAdapter = new ActionAdapter(OwnerType.PRODUCT_VARIANT);
        FileHandle linkedToVariant = handle(42L, OwnerType.PRODUCT_VARIANT, variantAdapter.capabilities());
        modelAdapter.current = linkedToVariant;
        variantAdapter.current = linkedToVariant;
        variantAdapter.actionResult = linkedToVariant;

        FileHandle result = new FileAssetBehaviorService(List.of(modelAdapter, variantAdapter))
                .updateTags(OwnerType.PRODUCT_MODEL, 42L, "一出一,方模具", true);

        assertSame(linkedToVariant, result);
    }

    private static FileAssetBehaviorService service(FileAssetAdapter adapter) {
        return new FileAssetBehaviorService(List.of(adapter));
    }

    private static FileHandle handle(Long assetId, OwnerType ownerType, Set<Capability> capabilities) {
        return new FileHandle(assetId, ownerType, 1L, "company_asset", "company_asset", null,
                "sample.png", "sample.png", "image/png", 10L, null, false, 0, false,
                null, null, null, capabilities);
    }

    private static final class ActionAdapter implements FileAssetAdapter {
        private final OwnerType ownerType;
        private final Set<Capability> capabilities = Set.of(Capability.UPDATE_TAGS, Capability.SET_DEFAULT);
        private FileHandle current;
        private FileHandle actionResult;

        private ActionAdapter() {
            this(OwnerType.COMPANY);
        }

        private ActionAdapter(OwnerType ownerType) {
            this.ownerType = ownerType;
            this.current = handle(42L, ownerType, capabilities);
            this.actionResult = current;
        }

        @Override public OwnerType ownerType() { return ownerType; }
        @Override public Set<Capability> capabilities() { return capabilities; }
        @Override public List<FileHandle> list(Long ownerId) { return List.of(current); }
        @Override public FileHandle get(Long assetId) { return current; }
        @Override public FileHandle updateTags(Long assetId, String tags, boolean replace) { return actionResult; }
        @Override public FileHandle setDefault(Long assetId) { return actionResult; }
    }
}
