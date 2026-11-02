package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyMilestoneParityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/company-milestones/parity")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyMilestoneParityController {
    private final WebsiteCompanyMilestoneParityService service;

    @GetMapping
    public WebsiteCompanyMilestoneParityService.ParityView compare() {
        return service.compare();
    }
}
