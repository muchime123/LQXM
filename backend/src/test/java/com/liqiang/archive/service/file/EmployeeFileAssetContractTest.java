package com.liqiang.archive.service.file;

import com.liqiang.archive.config.DesktopProperties;
import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.entity.EmployeeFile;
import com.liqiang.archive.repository.EmployeeFileRepository;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.service.FileStorageService;
import com.liqiang.archive.util.FileCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 员工档案文件（OwnerType = EMPLOYEE）接入文件资产框架的契约测试。
 *
 * <p>钉死三件事，任何一处漂移都要在这里变红：
 * <ol>
 *   <li>线上取值与目录口径：{@code employee} / {@code employee_file} → {@code employees/{id}/employee_file/}；</li>
 *   <li>两处能力集同源：适配器声明的能力 = {@code FileHandle.from(EmployeeFile)} 返回的能力；</li>
 *   <li>路径解析走 EMPLOYEE 分支，而不是静默落到平铺目录。</li>
 * </ol>
 */
class EmployeeFileAssetContractTest {

    @TempDir
    Path tempDir;

    @Test
    void employeeOwnerTypeAndCategoryMapToOneWireValueAndScope() {
        assertEquals("employee", OwnerType.EMPLOYEE.value());
        assertEquals(OwnerType.EMPLOYEE, OwnerType.from("employee"));
        assertEquals(OwnerType.EMPLOYEE, OwnerType.from("EMPLOYEE"));

        assertEquals("employee_file", FileCategory.folderOf("employee_file"));
        assertEquals(FileCategory.StorageScope.EMPLOYEE, FileCategory.scopeOf("employee_file"));
    }

    @Test
    void adapterAndHandleDeclareTheSameEmployeeCapabilities() {
        EmployeeFileAssetAdapter adapter = new EmployeeFileAssetAdapter(
                mock(EmployeeFileRepository.class), new FileHandleMapper(), mock(FileStorageService.class));

        assertEquals(OwnerType.EMPLOYEE, adapter.ownerType());
        assertEquals(FileAssetCapabilities.EMPLOYEE_FILE, adapter.capabilities());
        // 员工档案文件不跨主体搬迁：能力集里不得出现 MOVE_OWNER
        assertFalse(adapter.capabilities().contains(Capability.MOVE_OWNER));

        FileHandle handle = FileHandle.from(employeeFile(7L, "idcard.png"));
        assertEquals(OwnerType.EMPLOYEE, handle.ownerType());
        assertEquals(7L, handle.ownerId());
        assertEquals("employee_file", handle.storageCategory());
        assertEquals(7L, handle.storageOwnerId());
        // ★ 同一个 ownerType 的能力只允许一处定义：Handle 与 Adapter 必须逐字相同
        assertEquals(adapter.capabilities(), handle.capabilities());
    }

    @Test
    void employeeScopeResolvesIntoEmployeesCategoryFolder() {
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.fileRoot()).thenReturn(tempDir.resolve("files"));
        when(storage.employeeDir(7L)).thenReturn(tempDir.resolve("files/employees/7"));
        FileAssetLocationService locator = new FileAssetLocationService(storage, new DesktopProperties());

        Path resolved = locator.resolvePath(FileHandle.from(employeeFile(7L, "idcard.png")));

        // 走 EMPLOYEE 分支：employees/{id}/employee_file/，不得退化为平铺目录
        assertEquals(
                tempDir.resolve("files/employees/7/employee_file/idcard.png").normalize(),
                resolved);
    }

    @Test
    void permanentDeleteOnlyTouchesDiskWhenRequested() {
        EmployeeFileRepository repo = mock(EmployeeFileRepository.class);
        FileStorageService storage = mock(FileStorageService.class);
        EmployeeFileAssetAdapter adapter = new EmployeeFileAssetAdapter(repo, new FileHandleMapper(), storage);
        EmployeeFile file = employeeFile(7L, "idcard.png");

        when(repo.findById(5L)).thenReturn(Optional.of(file));
        adapter.permanentDelete(5L, true);
        // ★ 目录键必须是存储键 employee_file，不是业务类别 id_card —— 传错会静默删不掉文件
        verify(storage).deleteIfExists(7L, "employee_file", "idcard.png");
        verify(repo).delete(file);

        reset(storage, repo);
        when(repo.findById(5L)).thenReturn(Optional.of(file));
        adapter.permanentDelete(5L, false);
        // 仅删记录、保留物理文件时不得碰磁盘
        verify(storage, never()).deleteIfExists(anyLong(), anyString(), anyString());
    }

    private static EmployeeFile employeeFile(Long employeeId, String fileName) {
        EmployeeFile file = new EmployeeFile();
        file.setId(5L);
        file.setEmployeeId(employeeId);
        file.setCategory("id_card");
        file.setFileName(fileName);
        file.setOriginalFileName(fileName);
        file.setMimeType("image/png");
        file.setFileSize(1024L);
        return file;
    }
}
