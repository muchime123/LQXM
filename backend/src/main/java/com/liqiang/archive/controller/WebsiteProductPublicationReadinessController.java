package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductPublicationReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Editorial preflight; this endpoint never publishes content. */
@RestController
@RequestMapping("/api/website/admin/products")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductPublicationReadinessController {
    private final WebsiteProductPublicationReadinessService service;

    @GetMapping("/{id}/publication-readiness")
    public WebsiteProductPublicationReadinessService.ReadinessView check(@PathVariable Long id) {
        return service.check(id);
    }
}
