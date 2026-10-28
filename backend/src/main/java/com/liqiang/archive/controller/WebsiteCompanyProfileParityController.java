package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyProfileParityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/company-profile/parity")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyProfileParityController {
    private final WebsiteCompanyProfileParityService parity;

    @GetMapping
    public WebsiteCompanyProfileParityService.ParityView compare() {
        return parity.compare();
    }
}
