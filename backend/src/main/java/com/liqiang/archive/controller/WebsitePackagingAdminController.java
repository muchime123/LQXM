package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsitePackagingItem;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsitePackagingItemService;
import com.liqiang.archive.service.WebsitePackagingItemService.Fields;
import com.liqiang.archive.service.WebsitePackagingCutoverReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 官网包材草稿管理；不调用内部包材 Service。 */
@RestController
@RequestMapping("/api/website/admin/packaging")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsitePackagingAdminController {
    private final WebsitePackagingItemService service;
    private final WebsitePackagingCutoverReadinessService readiness;

    @GetMapping("/cutover-readiness")
    public WebsitePackagingCutoverReadinessService.Result cutoverReadiness() {
        return readiness.check();
    }

    @GetMapping
    public Page<ItemView> list(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size).map(ItemView::from);
    }

    @GetMapping("/{id}")
    public ItemView get(@PathVariable Long id) {
        return ItemView.from(service.get(id));
    }

    @PostMapping
    public ItemView create(@RequestBody Fields fields) {
        return ItemView.from(service.createDraft(fields));
    }

    @PutMapping("/{id}")
    public ItemView update(@PathVariable Long id, @RequestBody Fields fields) {
        return ItemView.from(service.updateDraft(id, fields));
    }

    @PostMapping("/{id}/publish")
    public ItemView publish(@PathVariable Long id) {
        return ItemView.from(service.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public ItemView unpublish(@PathVariable Long id) {
        return ItemView.from(service.unpublish(id));
    }

    public record ItemView(Long id, String slug, String name, String packagingKind,
                           String material, String shapeType, String capacityMl,
                           String applicable, Boolean customizable, Long coverMediaId,
                           Integer sortOrder, String status) {
        static ItemView from(WebsitePackagingItem item) {
            return new ItemView(item.getId(), item.getSlug(), item.getName(), item.getPackagingKind(),
                    item.getMaterial(), item.getShapeType(), item.getCapacityMl(), item.getApplicable(),
                    item.getCustomizable(), item.getCoverMediaId(), item.getSortOrder(), item.getStatus());
        }
    }
}
