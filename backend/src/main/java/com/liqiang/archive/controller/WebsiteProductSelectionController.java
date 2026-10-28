package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Draft-only website filter metadata editor. */
@RestController
@RequestMapping("/api/website/admin/products")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductSelectionController {
    private final WebsiteProductSelectionService service;

    @PutMapping("/{id}/selection")
    public WebsiteProductSelectionService.SelectionView update(@PathVariable Long id,
            @RequestBody WebsiteProductSelectionService.SelectionFields fields) {
        return service.update(id, fields);
    }
}
