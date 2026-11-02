package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyContentCutoverReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/company-content/cutover-readiness")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyContentCutoverReadinessController {
    private final WebsiteCompanyContentCutoverReadinessService service;

    @GetMapping
    public WebsiteCompanyContentCutoverReadinessService.ReadinessView check() {
        return service.check();
    }
}
