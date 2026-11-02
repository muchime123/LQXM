package com.liqiang.archive.controller;

import com.liqiang.archive.entity.WebsiteCredential;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.WebsiteCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

/** 官网资质独立草稿管理，不能放进 @PublicApi 控制器。 */
@RestController
@RequestMapping("/api/website/admin/credentials")
@RequirePermission("website.manage")
@RequiredArgsConstructor
public class WebsiteCredentialAdminController {
    private final WebsiteCredentialService service;

    @GetMapping
    public List<View> list() { return service.list().stream().map(View::from).toList(); }

    @PostMapping
    public View create(@RequestBody WebsiteCredentialService.Fields fields) {
        return View.from(service.create(fields));
    }

    @PutMapping("/{id}")
    public View update(@PathVariable Long id, @RequestBody WebsiteCredentialService.Fields fields) {
        return View.from(service.update(id, fields));
    }

    @PostMapping("/{id}/publish")
    public View publish(@PathVariable Long id) { return View.from(service.publish(id)); }

    @PostMapping("/{id}/unpublish")
    public View unpublish(@PathVariable Long id) { return View.from(service.unpublish(id)); }

    public record View(Long id, String name, String category, String publicDescription, String issuer,
                       LocalDate validUntil, Long mediaId, Integer sortOrder, String status) {
        static View from(WebsiteCredential item) {
            return new View(item.getId(), item.getName(), item.getCategory(), item.getPublicDescription(),
                    item.getIssuer(), item.getValidUntil(), item.getMediaId(), item.getSortOrder(), item.getStatus());
        }
    }
}
