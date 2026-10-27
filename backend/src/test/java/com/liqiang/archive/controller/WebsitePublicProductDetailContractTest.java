package com.liqiang.archive.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.CatalogDtos.ModelDetail;
import com.liqiang.archive.dto.CatalogDtos.ModelView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官网产品详情「兼容适配」字段的过渡契约锁定（P1-B 批次 1，方案 B）。
 *
 * <p>背景：产品 ↔ 包材 / 模具的三张旧适配关联表（product_model_packaging_links、
 * product_machine_tier_packaging_links、product_variant_packaging_links）已随批次 1 退役，
 * 读取旧链路已被删除。为了不对尚未识别的接口消费者产生破坏性变更，
 * {@code PublicProductDetail} 的两个兼容字段**暂时保留**，并恒定返回非 null 的空数组。
 *
 * <p>本测试锁定的就是这一条契约：**字段必须存在（不省略）、必须是非 null 的空数组、序列化后仍出现在 JSON 中**。
 * 移除条件写在 {@link WebsiteController.PublicProductDetail} 的 javadoc：
 * 待批次 2-A 确定官网正式配置模型后再决定由新模型接管还是删除字段。
 *
 * <p>旧聚合逻辑（不在本契约内）的行为差异仅作为历史参考，已于批次 1 从代码删除，可从 Git 历史找回。
 */
class WebsitePublicProductDetailContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compatibilityFieldsAreAlwaysNonNullAndEmpty() {
        WebsiteController.PublicProductDetail detail = WebsiteController.PublicProductDetail.from(sampleDetail());

        assertNotNull(detail.compatiblePackaging(), "兼容包材字段不得为 null");
        assertNotNull(detail.compatibleMolds(), "配套模具字段不得为 null");
        assertTrue(detail.compatiblePackaging().isEmpty());
        assertTrue(detail.compatibleMolds().isEmpty());
    }

    @Test
    void compatibilityFieldsAreSerializedInJson() throws Exception {
        WebsiteController.PublicProductDetail detail = WebsiteController.PublicProductDetail.from(sampleDetail());

        String json = mapper.writeValueAsString(detail);

        assertTrue(json.contains("\"compatiblePackaging\":[]"), "JSON 必须保留空数组字段而不是省略：" + json);
        assertTrue(json.contains("\"compatibleMolds\":[]"), "JSON 必须保留空数组字段而不是省略：" + json);
    }

    @Test
    void variantCountStillComesThrough() {
        WebsiteController.PublicProductDetail detail = WebsiteController.PublicProductDetail.from(sampleDetail());

        assertEquals(3L, detail.variantCount());
        assertFalse(detail.variants() == null);
    }

    private ModelDetail sampleDetail() {
        ModelView model = new ModelView(67L, "利强手压片膜封口机（斜头）", null, null, "托盒封口机", null, null,
                "ACTIVE", null, null, "image.png", 1, null, null, null, null, null,
                true, "product-67", "摘要", List.of(), List.of(), List.of(), "手动/小批量", null, null);
        return new ModelDetail(model, List.of(), List.of(), 3L, 0L);
    }
}
