package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.entity.PackagingAsset;
import com.liqiang.archive.repository.PackagingAssetRepository;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.service.PackagingService;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

public abstract class PackagingFileAssetAdapter extends AbstractFileAssetAdapter<PackagingAsset, PackagingAssetRepository> {

    private static final Set<Capability> CAPABILITIES = FileAssetCapabilities.PACKAGING_ASSET;

    protected final PackagingService packagingService;

    protected PackagingFileAssetAdapter(PackagingAssetRepository assetRepository,
                                        FileHandleMapper fileHandleMapper,
                                        PackagingService packagingService) {
        super(assetRepository, fileHandleMapper);
        this.packagingService = packagingService;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public FileHandle get(Long assetId) {
        return fileHandleMapper.from(findEntity(assetId, "Packaging asset"));
    }

    @Override
    public FileHandle updateTags(Long assetId, String tags, boolean replace) {
        return fileHandleMapper.from(findEntity(packagingService.updateAssetTags(assetId, tags).id()));
    }

    @Override
    public FileHandle setDefault(Long assetId) {
        return fileHandleMapper.from(findEntity(packagingService.setDefaultAsset(assetId).id()));
    }

    @Override
    public void reorder(List<FileAssetAdapter.ReorderItem> items) {
        packagingService.reorderAssets(items.stream()
                .map(item -> new com.liqiang.archive.dto.PackagingDtos.ReorderItem(item.id(), item.sortOrder()))
                .toList());
    }

    @Override
    public void softDelete(Long assetId) {
        packagingService.deleteAsset(assetId);
    }

    @Override
    @Transactional
    public void restore(Long assetId) {
        repository.restoreById(assetId);
    }

    @Override
    @Transactional
    public void permanentDelete(Long assetId, boolean deletePhysical) {
        packagingService.permanentDeleteAsset(assetId, deletePhysical);
    }

    @Override
    @Transactional
    public FileHandle setCover(Long assetId, String coverFileName) {
        PackagingAsset asset = findEntity(assetId, "Packaging asset");
        asset.setCoverFileName(coverFileName);
        return fileHandleMapper.from(repository.save(asset));
    }
}
