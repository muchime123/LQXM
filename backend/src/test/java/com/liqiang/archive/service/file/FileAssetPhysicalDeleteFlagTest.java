package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileAssetPhysicalDeleteFlagTest {

    @Test
    void preservesBothPhysicalDeleteChoicesAtTheUnifiedBoundary() {
        FileAssetAdapter adapter = mock(FileAssetAdapter.class);
        when(adapter.ownerType()).thenReturn(OwnerType.DOCUMENT);
        when(adapter.capabilities()).thenReturn(Set.of(Capability.PERMANENT_DELETE));
        when(adapter.get(41L)).thenReturn(handle());
        FileAssetBehaviorService service = new FileAssetBehaviorService(List.of(adapter));

        service.permanentDelete(OwnerType.DOCUMENT, 41L, false);
        service.permanentDelete(OwnerType.DOCUMENT, 41L, true);

        verify(adapter).permanentDelete(41L, false);
        verify(adapter).permanentDelete(41L, true);
    }

    private static FileHandle handle() {
        return new FileHandle(41L, OwnerType.DOCUMENT, 41L, "document", "document", null,
                "使用手册.pdf", "使用手册.pdf", "application/pdf", 10L, null,
                false, 0, true, null, null, null, Set.of(Capability.PERMANENT_DELETE));
    }
}
