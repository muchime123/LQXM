package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyProfilePublicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/company-profile/publish")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyProfilePublicationController {
    private final WebsiteCompanyProfilePublicationService service;

    @PostMapping
    public Long publish() {
        return service.publish().getId();
    }
}
