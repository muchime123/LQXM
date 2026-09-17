package com.liqiang.archive.service;

import com.liqiang.archive.dto.CatalogDtos.AssetMetadataRequest;
import com.liqiang.archive.dto.CatalogDtos.BatchAssetMetadataRequest;
import com.liqiang.archive.entity.ProductAsset;
import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.entity.ProductMachineTier;
import com.liqiang.archive.entity.ProductVariant;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.ProductMachineTierRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import com.liqiang.archive.service.AuditLogService;
import com.liqiang.archive.service.FileStorageService;
import com.liqiang.archive.service.VideoPersonalWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceVariantLinkTest {
    @Mock ProductModelRepository modelRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock ProductAssetRepository assetRepository;
    @Mock ProductMachineTierRepository machineTierRepository;
    @Mock FileStorageService fileStorageService;
    @Mock AuditLogService auditLogService;
    @Mock VideoPersonalWorkspaceService videoWorkspaceService;

    private ProductCatalogService service;
    private ProductModel model;

    @BeforeEach
    void setUp() {
        service = new ProductCatalogService(modelRepository, variantRepository, assetRepository, machineTierRepository,
                fileStorageService, auditLogService, videoWorkspaceService);
        model = new ProductModel();
        model.setId(10L);
        model.setName("测试产品");
    }

    @Test
    void keepSavesTagsWithoutChangingMachineScope() {
        ProductVariant current = variant(30L);
        ProductAsset asset = asset(20L, current, "旧标签");
        when(assetRepository.findById(20L)).thenReturn(Optional.of(asset));
        when(assetRepository.save(asset)).thenReturn(asset);

        service.updateAssetMetadata(20L, request("设备正面，一出四，方模具", "KEEP", List.of()));

        assertEquals("设备正面,一出四,方模具", asset.getTags());
        assertSame(current, asset.getVariant());
    }

    @Test
    void assignBindsMultipleMachineTiersFromTheSameProduct() {
        ProductMachineTier firstTier = tier(31L, "330");
        ProductMachineTier secondTier = tier(32L, "390");
        ProductAsset asset = asset(20L, null, null);
        when(assetRepository.findById(20L)).thenReturn(Optional.of(asset));
        when(machineTierRepository.findAllById(any())).thenReturn(List.of(firstTier, secondTier));
        when(assetRepository.save(asset)).thenReturn(asset);

        service.updateAssetMetadata(20L, request("操作视频", "ASSIGN", List.of(31L, 32L)));

        assertEquals("操作视频", asset.getTags());
        assertEquals(List.of(firstTier, secondTier), asset.getMachineTiers().stream().toList());
        assertSame(firstTier, asset.getMachineTier());
    }

    @Test
    void unlinkMustBeExplicit() {
        ProductAsset asset = asset(20L, variant(31L), "操作视频");
        when(assetRepository.findById(20L)).thenReturn(Optional.of(asset));
        when(assetRepository.save(asset)).thenReturn(asset);

        asset.setMachineTier(tier(31L, "330"));
        asset.getMachineTiers().add(asset.getMachineTier());
        service.updateAssetMetadata(20L, request("操作视频", "UNLINK", List.of()));

        assertNull(asset.getVariant());
        assertNull(asset.getMachineTier());
        assertEquals(0, asset.getMachineTiers().size());
    }

    @Test
    void batchAppendKeepsEveryExistingMachineScope() {
        ProductVariant firstVariant = variant(31L);
        ProductVariant secondVariant = variant(32L);
        ProductAsset first = asset(20L, firstVariant, "设备正面");
        ProductAsset second = asset(21L, secondVariant, null);
        when(assetRepository.findAllById(List.of(20L, 21L))).thenReturn(List.of(first, second));

        service.updateAssetMetadataBatch(new BatchAssetMetadataRequest(
                List.of(20L, 21L), "操作视频", "APPEND", "KEEP", null));

        assertEquals("设备正面,操作视频", first.getTags());
        assertEquals("操作视频", second.getTags());
        assertSame(firstVariant, first.getVariant());
        assertSame(secondVariant, second.getVariant());
    }

    private AssetMetadataRequest request(String tags, String action, List<Long> tierIds) {
        return new AssetMetadataRequest(tags, null, null, null, null, null,
                action, null, tierIds, null, null, null, null, null, null, null);
    }

    private ProductMachineTier tier(Long id, String code) {
        ProductMachineTier tier = new ProductMachineTier();
        tier.setId(id);
        tier.setModel(model);
        tier.setTierCode(code);
        tier.setSortOrder(1);
        return tier;
    }

    private ProductAsset asset(Long id, ProductVariant variant, String tags) {
        ProductAsset asset = new ProductAsset();
        asset.setId(id);
        asset.setModel(model);
        asset.setVariant(variant);
        asset.setCategory("device_video");
        asset.setFileName("asset.mp4");
        asset.setTags(tags);
        return asset;
    }

    private ProductVariant variant(Long id) {
        ProductVariant variant = new ProductVariant();
        variant.setId(id);
        variant.setModel(model);
        variant.setSortOrder(1);
        variant.setVariantName("方模具｜一出四｜175×130mm");
        return variant;
    }
}
