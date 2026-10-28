package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected editing path, separate from the public WebsiteController. */
@RestController
@RequestMapping("/api/website/admin/company-profile")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyProfileAdminController {
    private final WebsiteCompanyProfileService service;

    @GetMapping
    public WebsiteCompanyProfile get() {
        return service.get();
    }

    @PutMapping
    public WebsiteCompanyProfile saveDraft(@RequestBody WebsiteCompanyProfileService.ProfileFields fields) {
        return service.saveDraft(fields);
    }
}
