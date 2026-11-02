package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyMilestoneService;
import com.liqiang.archive.service.WebsiteNativeCompanyQueryService;
import com.liqiang.archive.service.WebsiteSocialLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Preview of the exact public DTOs produced by the native company source. */
@RestController
@RequestMapping("/api/website/admin/company-content/preview")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyContentPreviewController {
    private final WebsiteNativeCompanyQueryService company;
    private final WebsiteCompanyMilestoneService milestones;
    private final WebsiteSocialLinkService socialLinks;

    @GetMapping
    @Transactional(readOnly = true)
    public PreviewView preview() {
        var profile = company.published();
        return new PreviewView(
                profile == null ? null : WebsiteController.PublicCompanyView.from(profile),
                milestones.published().stream().map(WebsiteController.PublicMilestoneView::from).toList(),
                socialLinks.published().stream().map(WebsiteController.PublicMediaAccountView::from).toList());
    }

    public record PreviewView(WebsiteController.PublicCompanyView company,
                              List<WebsiteController.PublicMilestoneView> milestones,
                              List<WebsiteController.PublicMediaAccountView> officialMediaAccounts) {}
}
