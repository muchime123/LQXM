package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductCutoverReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Technical preflight only; does not flip the public product source. */
@RestController
@RequestMapping("/api/website/admin/native-products/cutover-readiness")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductCutoverReadinessController {
    private final WebsiteProductCutoverReadinessService service;

    @GetMapping
    public WebsiteProductCutoverReadinessService.CutoverReadinessView check() {
        return service.check();
    }
}
