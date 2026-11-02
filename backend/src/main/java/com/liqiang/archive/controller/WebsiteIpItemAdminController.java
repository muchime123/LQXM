package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteIpItem;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteIpItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/website/admin/ip-items")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteIpItemAdminController {
    private final WebsiteIpItemService service;

    @GetMapping
    public List<WebsiteIpItem> list() { return service.list(); }
    @PostMapping
    public WebsiteIpItem create(@RequestBody WebsiteIpItemService.Fields fields) { return service.create(fields); }
    @PutMapping("/{id}")
    public WebsiteIpItem update(@PathVariable Long id, @RequestBody WebsiteIpItemService.Fields fields) {
        return service.update(id, fields);
    }
    @PostMapping("/{id}/publish")
    public WebsiteIpItem publish(@PathVariable Long id) { return service.publish(id); }
    @PostMapping("/{id}/unpublish")
    public WebsiteIpItem unpublish(@PathVariable Long id) { return service.unpublish(id); }
}
