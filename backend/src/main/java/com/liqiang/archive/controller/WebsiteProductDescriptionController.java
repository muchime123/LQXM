package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductDescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/products")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductDescriptionController {
    private final WebsiteProductDescriptionService service;

    @PutMapping("/{id}/description")
    public WebsiteProductDescriptionService.DescriptionView update(@PathVariable Long id,
            @RequestBody WebsiteProductDescriptionService.DescriptionFields fields) {
        return service.update(id, fields);
    }
}
