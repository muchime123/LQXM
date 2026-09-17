package com.liqiang.archive.controller;

import com.liqiang.archive.dto.PlatformConfigDtos.PlatformConfigView;
import com.liqiang.archive.entity.PlatformConfig;
import com.liqiang.archive.service.PlatformConfigService;
import com.liqiang.archive.security.RequirePermission;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/platform-configs")
public class PlatformConfigController {

    private final PlatformConfigService service;

    public PlatformConfigController(PlatformConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<PlatformConfigView> getAll() {
        return service.getAll().stream().map(PlatformConfigView::from).toList();
    }

    @GetMapping("/active")
    public List<PlatformConfigView> getActive() {
        return service.getActive().stream().map(PlatformConfigView::from).toList();
    }

    @RequirePermission("system.config.manage")
    @PostMapping
    public PlatformConfigView create(@RequestBody PlatformConfig config) {
        return PlatformConfigView.from(service.create(config));
    }

    @RequirePermission("system.config.manage")
    @PutMapping("/{id}")
    public PlatformConfigView update(@PathVariable Long id, @RequestBody PlatformConfig config) {
        return PlatformConfigView.from(service.update(id, config));
    }

    @RequirePermission("system.config.manage")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
