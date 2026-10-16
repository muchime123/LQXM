package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.entity.EmployeeFile;
import com.liqiang.archive.repository.EmployeeFileRepository;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.service.FileStorageService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 员工档案文件的文件资产适配器。
 * OwnerType = EMPLOYEE，第 8 个 Adapter。
 * 存储路径：{fileRoot}/employees/{employeeId}/employee_file/{fileName}
 */
@Component
public class EmployeeFileAssetAdapter extends AbstractFileAssetAdapter<EmployeeFile, EmployeeFileRepository> {

    /**
     * 存储目录键。员工档案文件一律落在 {@code employee_file/} 下，
     * 与 {@code FileDtos.FileHandle.from(EmployeeFile)} 写入的 storageCategory 必须一致。
     * ⚠️ 不要用 entity 的 category（id_card / labor_contract…）：那是业务分类，不是目录名。
     */
    public static final String STORAGE_CATEGORY = "employee_file";

    private static final Set<Capability> CAPABILITIES = FileAssetCapabilities.EMPLOYEE_FILE;

    private final FileStorageService fileStorageService;

    public EmployeeFileAssetAdapter(EmployeeFileRepository repository,
                                     FileHandleMapper fileHandleMapper,
                                     FileStorageService fileStorageService) {
        super(repository, fileHandleMapper);
        this.fileStorageService = fileStorageService;
    }

    @Override
    public OwnerType ownerType() {
        return OwnerType.EMPLOYEE;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<FileHandle> list(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            return repository.findAll().stream().map(fileHandleMapper::from).toList();
        }
        return repository.findByEmployeeIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(ownerId)
                .stream()
                .map(fileHandleMapper::from)
                .toList();
    }

    @Override
    public FileHandle get(Long assetId) {
        return fileHandleMapper.from(findEntity(assetId, "员工档案文件"));
    }

    @Override
    @Transactional
    public FileHandle updateTags(Long assetId, String tags, boolean replace) {
        EmployeeFile file = findEntity(assetId, "员工档案文件");
        file.setTags(mergeTags(file.getTags(), tags, replace));
        return fileHandleMapper.from(repository.save(file));
    }

    @Override
    @Transactional
    public FileHandle setDefault(Long assetId) {
        EmployeeFile file = findEntity(assetId, "员工档案文件");
        // 同一员工下同类别只允许一个默认
        repository.findByEmployeeIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(file.getEmployeeId())
                .stream()
                .filter(f -> file.getCategory().equals(f.getCategory()))
                .filter(f -> Boolean.TRUE.equals(f.getDefaultAsset()))
                .filter(f -> !f.getId().equals(assetId))
                .forEach(f -> {
                    f.setDefaultAsset(false);
                    repository.save(f);
                });
        file.setDefaultAsset(true);
        return fileHandleMapper.from(repository.save(file));
    }

    @Override
    @Transactional
    public void reorder(List<FileAssetAdapter.ReorderItem> items) {
        for (ReorderItem item : items) {
            EmployeeFile file = findEntity(item.id(), "员工档案文件");
            file.setSortOrder(item.sortOrder());
            repository.save(file);
        }
    }

    @Override
    @Transactional
    public void moveCategory(Long assetId, String targetCategory) {
        EmployeeFile file = findEntity(assetId, "员工档案文件");
        file.setCategory(targetCategory);
        repository.save(file);
    }

    @Override
    @Transactional
    public void softDelete(Long assetId) {
        EmployeeFile file = findEntity(assetId, "员工档案文件");
        file.setDeletedAt(java.time.LocalDateTime.now());
        repository.save(file);
    }

    @Override
    @Transactional
    public void restore(Long assetId) {
        EmployeeFile file = findEntity(assetId, "员工档案文件");
        file.setDeletedAt(null);
        repository.save(file);
    }

    @Override
    @Transactional
    public void permanentDelete(Long assetId, boolean deletePhysical) {
        repository.findById(assetId).ifPresent(file -> {
            if (deletePhysical) {
                // deletePhysical=true 必须真的清磁盘，否则永久删除后留下孤儿文件（与其它 7 个 Adapter 同一约定）
                fileStorageService.deleteIfExists(file.getEmployeeId(), STORAGE_CATEGORY, file.getFileName());
            }
            repository.delete(file);
        });
    }
}
