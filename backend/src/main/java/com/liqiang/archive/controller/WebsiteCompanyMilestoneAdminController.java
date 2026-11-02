package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCompanyMilestoneService;
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
@RequestMapping("/api/website/admin/company-milestones")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCompanyMilestoneAdminController {
    private final WebsiteCompanyMilestoneService service;

    @GetMapping
    public List<WebsiteCompanyMilestone> list() { return service.list(); }
    @PostMapping
    public WebsiteCompanyMilestone create(@RequestBody WebsiteCompanyMilestoneService.Fields fields) { return service.create(fields); }
    @PutMapping("/{id}")
    public WebsiteCompanyMilestone update(@PathVariable Long id, @RequestBody WebsiteCompanyMilestoneService.Fields fields) {
        return service.update(id, fields);
    }
    @PostMapping("/{id}/publish")
    public WebsiteCompanyMilestone publish(@PathVariable Long id) { return service.publish(id); }
    @DeleteMapping("/{id}")
    public void archive(@PathVariable Long id) { service.archive(id); }
}
