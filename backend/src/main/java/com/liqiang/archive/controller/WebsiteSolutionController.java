package com.liqiang.archive.controller;

import com.liqiang.archive.dto.SolutionDtos.SolutionPayload;
import com.liqiang.archive.dto.SolutionDtos.SolutionSectionGroup;
import com.liqiang.archive.dto.SolutionDtos.SolutionView;
import com.liqiang.archive.service.WebsiteSolutionService;
import com.liqiang.archive.security.PublicApi;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/website/solutions")
@RequiredArgsConstructor
public class WebsiteSolutionController {

    private final WebsiteSolutionService solutionService;

    /** 公开接口：获取官网可见的解决方案分组数据 */
    @PublicApi
    @GetMapping("/public")
    public List<SolutionSectionGroup> publicSolutions() {
        return solutionService.listWebsiteVisible();
    }

    /** 官网方案文章列表。 */
    @PublicApi
    @GetMapping("/public/articles")
    public List<SolutionView> publicArticles() {
        return solutionService.listPublishedArticles();
    }

    /** 官网方案文章详情。 */
    @PublicApi
    @GetMapping("/public/articles/{slug}")
    public ResponseEntity<SolutionView> publicArticle(@PathVariable String slug) {
        try {
            return ResponseEntity.ok(solutionService.getPublishedArticle(slug));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 管理接口：全量列表（含隐藏） */
    @RequirePermission("website.manage")
    @GetMapping("/admin/list")
    public List<SolutionView> adminList() {
        return solutionService.listAll();
    }

    /** 管理接口：按分组返回 */
    @RequirePermission("website.manage")
    @GetMapping("/admin/grouped")
    public List<SolutionSectionGroup> adminGrouped() {
        return solutionService.listBySection();
    }

    /** 管理接口：详情 */
    @RequirePermission("website.manage")
    @GetMapping("/admin/{id}")
    public SolutionView getById(@PathVariable Long id) {
        return solutionService.getById(id);
    }

    /** 管理接口：新建 */
    @RequirePermission("website.manage")
    @PostMapping("/admin")
    public SolutionView create(@RequestBody SolutionPayload payload) {
        return solutionService.create(payload);
    }

    /** 管理接口：更新 */
    @RequirePermission("website.manage")
    @PutMapping("/admin/{id}")
    public SolutionView update(@PathVariable Long id, @RequestBody SolutionPayload payload) {
        return solutionService.update(id, payload);
    }

    /** 管理接口：删除 */
    @RequirePermission("website.manage")
    @DeleteMapping("/admin/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        solutionService.delete(id);
        return Map.of("success", true, "id", id);
    }

    /** 管理接口：批量更新官网可见性 */
    @RequirePermission("website.manage")
    @PutMapping("/admin/batch-visible")
    public List<SolutionView> batchVisible(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) body.get("ids");
        Boolean enabled = (Boolean) body.get("websiteEnabled");
        return solutionService.batchUpdateWebsiteEnabled(ids, enabled != null && enabled);
    }
}
