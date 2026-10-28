package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteProductVariant;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductVariantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 官网产品规格管理接口，不接受内部产品规格 ID。 */
@RestController
@RequestMapping("/api/website/admin/products/{productId}/variants")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductVariantAdminController {
    private final WebsiteProductVariantService service;

    @GetMapping
    public List<VariantView> list(@PathVariable Long productId) {
        return service.list(productId).stream().map(VariantView::from).toList();
    }

    @PostMapping
    public VariantView createDraft(@PathVariable Long productId,
                                   @RequestBody WebsiteProductVariantService.VariantFields fields) {
        return VariantView.from(service.createDraft(productId, fields));
    }

    @PutMapping("/{id}")
    public VariantView updateDraft(@PathVariable Long productId, @PathVariable Long id,
                                   @RequestBody WebsiteProductVariantService.VariantFields fields) {
        return VariantView.from(service.updateDraft(productId, id, fields));
    }

    public record VariantView(Long id, Long productId, String name, String publicSku,
                              String dimensions, String description, Integer sortOrder, String status) {
        static VariantView from(WebsiteProductVariant variant) {
            return new VariantView(variant.getId(), variant.getProductId(), variant.getName(),
                    variant.getPublicSku(), variant.getDimensions(), variant.getDescription(),
                    variant.getSortOrder(), variant.getStatus());
        }
    }
}
