package com.liqiang.archive.controller;

import com.liqiang.archive.dto.PageSectionDtos.PageSectionPayload;
import com.liqiang.archive.dto.PageSectionDtos.PageSectionView;
import com.liqiang.archive.dto.PageSectionDtos.PublicPageSectionView;
import com.liqiang.archive.service.WebsitePageSectionService;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 官网页面区块内容管理接口。
 * - 公开接口：GET /api/website/page-sections/{pageKey}
 * - 管理接口：/api/website/page-sections/admin/*
 */
@RestController
@RequestMapping("/api/website/page-sections")
@RequiredArgsConstructor
public class WebsitePageSectionController {

    private final WebsitePageSectionService sectionService;

    /** 公开接口：某页面下启用的区块列表。 */
    @PublicApi
    @GetMapping("/{pageKey}")
    public List<PublicPageSectionView> publicSections(@PathVariable String pageKey) {
        return sectionService.listPublic(pageKey.toUpperCase());
    }

    /** 管理接口：全量列表（支持按页面筛选）。 */
    @RequirePermission("website.manage")
    @GetMapping("/admin/list")
    public List<PageSectionView> adminList(@RequestParam(required = false) String pageKey) {
        return sectionService.listByPage(pageKey);
    }

    /** 管理接口：详情。 */
    @RequirePermission("website.manage")
    @GetMapping("/admin/{id}")
    public PageSectionView getById(@PathVariable Long id) {
        return sectionService.getById(id);
    }

    /** 管理接口：新增。 */
    @RequirePermission("website.manage")
    @PostMapping("/admin")
    public PageSectionView create(@RequestBody PageSectionPayload payload) {
        return sectionService.create(payload);
    }

    /** 管理接口：更新。 */
    @RequirePermission("website.manage")
    @PutMapping("/admin/{id}")
    public PageSectionView update(@PathVariable Long id, @RequestBody PageSectionPayload payload) {
        return sectionService.update(id, payload);
    }

    /** 管理接口：删除。 */
    @RequirePermission("website.manage")
    @DeleteMapping("/admin/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        sectionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 管理接口：批量启用/禁用。 */
    @RequirePermission("website.manage")
    @PutMapping("/admin/batch-enabled")
    public List<PageSectionView> batchEnabled(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return sectionService.batchUpdateEnabled(ids.stream().map(Number::longValue).toList(), enabled);
    }
}