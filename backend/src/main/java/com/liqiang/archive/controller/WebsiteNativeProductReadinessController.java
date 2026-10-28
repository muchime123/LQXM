package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteNativeProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Protected read-through of the future product source for pre-cutover checks. */
@RestController
@RequestMapping("/api/website/admin/native-products")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteNativeProductReadinessController {
    private final WebsiteNativeProductQueryService service;

    @GetMapping
    public WebsiteNativeProductQueryService.PageView list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String application,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) String process,
            @RequestParam(required = false) String capacity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return service.list(keyword, category, application, container, process, capacity, page, size);
    }

    @GetMapping("/featured")
    public List<WebsiteNativeProductQueryService.ProductView> featured(
            @RequestParam(defaultValue = "500") int limit) {
        return service.featured(limit);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return service.categories();
    }

    @GetMapping("/filter-options")
    public WebsiteNativeProductQueryService.CategoryFiltersView filterOptions() {
        return service.filterOptions();
    }

    @GetMapping("/drafts/{id}")
    public ResponseEntity<WebsiteNativeProductQueryService.DetailView> preview(@PathVariable Long id) {
        return service.previewDetail(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<WebsiteNativeProductQueryService.DetailView> detail(@PathVariable String slug) {
        return service.detail(slug).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
