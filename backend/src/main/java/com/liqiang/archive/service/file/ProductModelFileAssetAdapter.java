package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import com.liqiang.archive.service.CatalogTrashService;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.service.ProductCatalogService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductModelFileAssetAdapter extends CatalogFileAssetAdapter {

    public ProductModelFileAssetAdapter(ProductAssetRepository assetRepository,
                                        ProductVariantRepository variantRepository,
                                        ProductCatalogService catalogService,
                                        CatalogTrashService trashService,
                                        FileHandleMapper fileHandleMapper) {
        super(assetRepository, variantRepository, catalogService, trashService, fileHandleMapper);
    }

    @Override
    public OwnerType ownerType() {
        return OwnerType.PRODUCT_MODEL;
    }

    @Override
    public List<FileHandle> list(Long ownerId) {
        return repository.findByModelIdOrderBySortOrderAscIdAsc(ownerId)
                .stream()
                .map(fileHandleMapper::from)
                .toList();
    }
}
