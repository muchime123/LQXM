package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductGalleryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/products/{productId}/gallery")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductGalleryController {
    private final WebsiteProductGalleryService service;

    @PostMapping
    public void add(@PathVariable Long productId, @RequestBody WebsiteProductGalleryService.GalleryFields fields) {
        service.add(productId, fields);
    }

    @PutMapping("/{assignmentId}")
    public void update(@PathVariable Long productId, @PathVariable Long assignmentId,
            @RequestBody WebsiteProductGalleryService.GalleryFields fields) {
        service.update(productId, assignmentId, fields);
    }
}
