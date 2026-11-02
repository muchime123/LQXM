package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteSocialLinkParityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/website/admin/social-links/parity")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteSocialLinkParityController {
    private final WebsiteSocialLinkParityService service;

    @GetMapping
    public WebsiteSocialLinkParityService.ParityView compare() { return service.compare(); }
}
