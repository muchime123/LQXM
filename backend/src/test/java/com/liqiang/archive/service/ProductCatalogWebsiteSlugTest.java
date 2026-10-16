package com.liqiang.archive.service;

import com.liqiang.archive.entity.ProductModel;
import com.liqiang.archive.repository.ProductAssetRepository;
import com.liqiang.archive.repository.ProductMachineTierRepository;
import com.liqiang.archive.repository.ProductModelRepository;
import com.liqiang.archive.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 官网 URL 标识（product_models.website_slug）口径回归。
 *
 * <p>背景：该列上有唯一索引 uk_website_slug，MySQL 允许多个 NULL 并存，但 '' 全表只能有一行。
 * 编辑器表单的 slug 默认值是 ''，历史上被原样入库，占住 '' 槽位后导致所有空 slug 的
 * 新建/清空保存都撞 {@code Duplicate entry '' for key 'product_models.uk_website_slug'}。
 *
 * <p>本测试钉死：空白必须落 NULL（编辑器路径与批量路径同源），而非空白的唯一性校验不得被削弱。
 */
@ExtendWith(MockitoExtension.class)
class ProductCatalogWebsiteSlugTest {

    @Mock ProductModelRepository modelRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock ProductAssetRepository assetRepository;
    @Mock ProductMachineTierRepository machineTierRepository;
    @Mock FileStorageService fileStorageService;
    @Mock AuditLogService auditLogService;
    @Mock VideoPersonalWorkspaceService videoWorkspaceService;

    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ProductCatalogService(modelRepository, variantRepository, assetRepository, machineTierRepository,
                fileStorageService, auditLogService, videoWorkspaceService);
    }

    /** 新建主产品：前端 blankModel() 的 slug/summary 默认值是空串，入库前必须归一成 NULL。 */
    @Test
    void blankSlugOnCreateMustBeStoredAsNull() {
        ProductModel payload = new ProductModel();
        payload.setName("空 slug 新品");
        payload.setSortOrder(100);
        payload.setWebsiteSlug("");
        payload.setWebsiteSummary("   ");
        when(modelRepository.save(any(ProductModel.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createModel(payload);

        ProductModel persisted = capturedSave();
        assertNull(persisted.getWebsiteSlug(), "空串 slug 必须落 NULL，否则会占住唯一索引里的 '' 槽位");
        assertNull(persisted.getWebsiteSummary(), "空串 summary 同样归一成 NULL");
        // 空白 slug 不参与唯一性查询：MySQL 里空串与 NULL 是两回事，查了也没意义
        verify(modelRepository, never()).findByWebsiteSlug(any());
    }

    /** 编辑主产品：把 slug 清空保存，必须落 NULL（旧行为落 ''，会被已占位的 '' 行撞出 Duplicate entry）。 */
    @Test
    void clearingSlugOnUpdateMustBeStoredAsNull() {
        ProductModel existing = new ProductModel();
        existing.setId(7L);
        existing.setName("已有产品");
        existing.setWebsiteSlug("old-slug");
        when(modelRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(modelRepository.save(any(ProductModel.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductModel payload = new ProductModel();
        payload.setWebsiteSlug("");
        service.updateModel(7L, payload);

        assertNull(capturedSave().getWebsiteSlug());
    }

    /** 非空 slug 仍去首尾空白 + 转小写查重，归一化不得削弱唯一性校验。 */
    @Test
    void nonBlankSlugIsTrimmedAndStillUniquenessChecked() {
        ProductModel existing = new ProductModel();
        existing.setId(7L);
        existing.setName("已有产品");
        when(modelRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(modelRepository.findByWebsiteSlug("lq-450")).thenReturn(Optional.empty());
        when(modelRepository.save(any(ProductModel.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductModel payload = new ProductModel();
        payload.setWebsiteSlug("  LQ-450  ");
        service.updateModel(7L, payload);

        assertEquals("LQ-450", capturedSave().getWebsiteSlug());
        verify(modelRepository).findByWebsiteSlug("lq-450");
    }

    @Test
    void slugTakenByAnotherProductIsStillRejected() {
        ProductModel existing = new ProductModel();
        existing.setId(7L);
        existing.setName("已有产品");
        ProductModel other = new ProductModel();
        other.setId(99L);
        other.setName("别的产品");
        when(modelRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(modelRepository.findByWebsiteSlug("taken")).thenReturn(Optional.of(other));

        ProductModel payload = new ProductModel();
        payload.setWebsiteSlug("taken");

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> service.updateModel(7L, payload));
        assertTrue(ex.getMessage().contains("别的产品"), "冲突提示必须指出占用者：" + ex.getMessage());
        verify(modelRepository, never()).save(any());
    }

    /** 批量端点是同一条归一规则的另一个入口，必须与编辑器路径同源（两套分叉正是本故障的指因）。 */
    @Test
    void batchPathNormalizesBlankSlugToNullAsWell() {
        ProductModel model = new ProductModel();
        model.setId(7L);
        model.setName("批量产品");
        when(modelRepository.findByIdIn(List.of(7L))).thenReturn(List.of(model));

        service.batchUpdateWebsiteFields(List.of(7L), Map.of("websiteSlug", "   ", "websiteSummary", "  "));

        assertNull(model.getWebsiteSlug());
        assertNull(model.getWebsiteSummary());
    }

    private ProductModel capturedSave() {
        ArgumentCaptor<ProductModel> captor = ArgumentCaptor.forClass(ProductModel.class);
        verify(modelRepository).save(captor.capture());
        return captor.getValue();
    }
}
