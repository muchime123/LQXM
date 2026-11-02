package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsitePackagingVariant;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsitePackagingVariantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 官网包材规格管理接口，独立于内部包材 SKU。 */
@RestController
@RequestMapping("/api/website/admin/packaging/{itemId}/variants")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsitePackagingVariantAdminController {
    private final WebsitePackagingVariantService service;

    @GetMapping
    public List<View> list(@PathVariable Long itemId) {
        return service.list(itemId).stream().map(View::from).toList();
    }

    @PostMapping
    public View create(@PathVariable Long itemId, @RequestBody WebsitePackagingVariantService.Fields fields) {
        return View.from(service.create(itemId, fields));
    }

    @PutMapping("/{id}")
    public View update(@PathVariable Long itemId, @PathVariable Long id,
                       @RequestBody WebsitePackagingVariantService.Fields fields) {
        return View.from(service.update(itemId, id, fields));
    }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long itemId, @PathVariable Long id) {
        service.remove(itemId, id);
    }

    public record View(Long id, Long itemId, String name, String publicSku, String dimensions,
                       String capacityMl, String description, Integer sortOrder) {
        static View from(WebsitePackagingVariant item) {
            return new View(item.getId(), item.getItemId(), item.getName(), item.getPublicSku(),
                    item.getDimensions(), item.getCapacityMl(), item.getDescription(), item.getSortOrder());
        }
    }
}
