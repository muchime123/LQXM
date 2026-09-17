package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.CatalogDtos.MoveAssetRequest;
import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import com.liqiang.archive.service.CatalogTrashService;
import com.liqiang.archive.service.FileHandleMapper;
import com.liqiang.archive.service.ProductCatalogService;
import jakarta.persistence.EntityNotFoundException;

import java.util.List;
import java.util.Set;

public abstract class CatalogFileAssetAdapter extends AbstractFileAssetAdapter<ProductAsset, ProductAssetRepository> {

    private static final Set<Capability> CAPABILITIES = FileAssetCapabilities.PRODUCT_ASSET;

    protected final ProductVariantRepository variantRepository;
    protected final ProductCatalogService catalogService;
    protected final CatalogTrashService trashService;

    protected CatalogFileAssetAdapter(ProductAssetRepository assetRepository,
                                      ProductVariantRepository variantRepository,
                                      ProductCatalogService catalogService,
                                      CatalogTrashService trashService,
                                      FileHandleMapper fileHandleMapper) {
        super(assetRepository, fileHandleMapper);
        this.variantRepository = variantRepository;
        this.catalogService = catalogService;
        this.trashService = trashService;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public FileHandle get(Long assetId) {
        return fileHandleMapper.from(findEntity(assetId, "Product asset"));
    }

    @Override
    public FileHandle updateTags(Long assetId, String tags, boolean replace) {
        return fileHandleMapper.from(catalogService.updateAssetTags(assetId, tags));
    }

    @Override
    public FileHandle setDefault(Long assetId) {
        return fileHandleMapper.from(catalogService.setDefaultAsset(assetId));
    }

    @Override
    public void reorder(List<FileAssetAdapter.ReorderItem> items) {
        catalogService.reorderAssets(items.stream()
                .map(item -> new com.liqiang.archive.dto.CatalogDtos.ReorderItem(item.id(), item.sortOrder()))
                .toList());
    }

    @Override
    public void moveOwner(Long assetId, OwnerType targetOwnerType, Long targetOwnerId) {
        ProductAsset asset = findEntity(assetId, "Product asset");
        MoveAssetRequest request = switch (targetOwnerType) {
            case PRODUCT_MODEL -> new MoveAssetRequest(targetOwnerId, null, asset.getCategory());
            case PRODUCT_VARIANT -> {
                ProductVariant variant = variantRepository.findById(targetOwnerId)
                        .orElseThrow(() -> new EntityNotFoundException("Target variant does not exist: " + targetOwnerId));
                yield new MoveAssetRequest(variant.getModel().getId(), targetOwnerId, asset.getCategory());
            }
            default -> throw new IllegalArgumentException("Product asset cannot move to ownerType: " + targetOwnerType);
        };
        catalogService.moveAsset(assetId, request);
    }

    @Override
    public void moveCategory(Long assetId, String targetCategory) {
        ProductAsset asset = findEntity(assetId, "Product asset");
        Long variantId = asset.getVariant() == null ? null : asset.getVariant().getId();
        catalogService.moveAsset(assetId, new MoveAssetRequest(asset.getModel().getId(), variantId, targetCategory));
    }

    @Override
    public void softDelete(Long assetId) {
        catalogService.deleteAsset(assetId, false);
    }

    @Override
    public void restore(Long assetId) {
        trashService.restoreAsset(assetId);
    }

    @Override
    public void permanentDelete(Long assetId, boolean deletePhysical) {
        trashService.permanentDeleteAsset(assetId, deletePhysical);
    }

    @Override
    public FileHandle setCover(Long assetId, String coverFileName) {
        ProductAsset asset = findEntity(assetId, "Product asset");
        asset.setCoverFileName(coverFileName);
        return fileHandleMapper.from(repository.save(asset));
    }
}
