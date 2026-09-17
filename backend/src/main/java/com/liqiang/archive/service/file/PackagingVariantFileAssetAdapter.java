package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.repository.PackagingAssetRepository;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.service.PackagingService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PackagingVariantFileAssetAdapter extends PackagingFileAssetAdapter {

    public PackagingVariantFileAssetAdapter(PackagingAssetRepository assetRepository,
                                            FileHandleMapper fileHandleMapper,
                                            PackagingService packagingService) {
        super(assetRepository, fileHandleMapper, packagingService);
    }

    @Override
    public OwnerType ownerType() {
        return OwnerType.PACKAGING_VARIANT;
    }

    @Override
    public List<FileHandle> list(Long ownerId) {
        return repository.findByVariantIdOrderBySortOrderAscIdAsc(ownerId)
                .stream()
                .map(fileHandleMapper::from)
                .toList();
    }
}
