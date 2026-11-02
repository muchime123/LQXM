package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteSocialLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/website/admin/social-links")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteSocialLinkAdminController {
    private final WebsiteSocialLinkService service;

    @GetMapping
    public List<WebsiteSocialLink> list() { return service.list(); }
    @PostMapping
    public WebsiteSocialLink create(@RequestBody WebsiteSocialLinkService.Fields fields) { return service.create(fields); }
    @PutMapping("/{id}")
    public WebsiteSocialLink update(@PathVariable Long id, @RequestBody WebsiteSocialLinkService.Fields fields) {
        return service.update(id, fields);
    }
    @PostMapping("/{id}/publish")
    public WebsiteSocialLink publish(@PathVariable Long id) { return service.publish(id); }
    @DeleteMapping("/{id}")
    public void archive(@PathVariable Long id) { service.archive(id); }
}
