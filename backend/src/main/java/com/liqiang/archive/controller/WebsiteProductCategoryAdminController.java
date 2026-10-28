package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteProductCategory;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 官网产品分类管理入口，与内部产品分类保持独立。 */
@RestController
@RequestMapping("/api/website/admin/product-categories")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductCategoryAdminController {
    private final WebsiteProductCategoryService service;

    @GetMapping
    public List<CategoryView> list() {
        return service.list().stream().map(CategoryView::from).toList();
    }

    @PostMapping
    public CategoryView createDraft(@RequestBody WebsiteProductCategoryService.CategoryFields fields) {
        return CategoryView.from(service.createDraft(fields));
    }

    @PutMapping("/{id}")
    public CategoryView updateDraft(@PathVariable Long id,
            @RequestBody WebsiteProductCategoryService.CategoryFields fields) {
        return CategoryView.from(service.updateDraft(id, fields));
    }

    public record CategoryView(Long id, String name, String slug, Integer sortOrder, String status) {
        static CategoryView from(WebsiteProductCategory category) {
            return new CategoryView(category.getId(), category.getName(), category.getSlug(),
                    category.getSortOrder(), category.getStatus());
        }
    }
}
