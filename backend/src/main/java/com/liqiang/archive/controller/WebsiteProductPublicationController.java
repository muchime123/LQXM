package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteProductPublicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit publish commands kept outside the public website controller. */
@RestController
@RequestMapping("/api/website/admin/publication")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteProductPublicationController {
    private final WebsiteProductPublicationService service;

    @PostMapping("/media/{id}")
    public Long media(@PathVariable Long id) {
        return service.publishMedia(id).getId();
    }

    @PostMapping("/categories/{id}")
    public Long category(@PathVariable Long id) {
        return service.publishCategory(id).getId();
    }

    @PostMapping("/products/{id}")
    public Long product(@PathVariable Long id) {
        return service.publishProduct(id).getId();
    }
}
