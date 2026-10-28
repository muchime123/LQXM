package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyProfileReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected, read-only review endpoint. */
@RestController
@RequestMapping("/api/website/admin/company-profile/readiness")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyProfileReadinessController {
    private final WebsiteCompanyProfileReadinessService service;

    @GetMapping
    public WebsiteCompanyProfileReadinessService.ReadinessView check() {
        return service.check();
    }
}
