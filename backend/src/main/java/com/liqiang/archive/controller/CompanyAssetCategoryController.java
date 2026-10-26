package com.liqiang.archive.controller;

import com.liqiang.archive.entity.CompanyAssetCategory;
import com.liqiang.archive.repository.CompanyAssetCategoryRepository;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/company/asset-categories")
@RequirePermission("company.read")
@RequiredArgsConstructor
public class CompanyAssetCategoryController {

    private final CompanyAssetCategoryRepository categoryRepo;

    @GetMapping
    public List<CompanyAssetCategory> list() {
        return categoryRepo.findAllByOrderBySortOrderAsc();
    }

    @RequirePermission("company.manage")
    @PostMapping
    public ResponseEntity<CompanyAssetCategory> create(@RequestBody CompanyAssetCategory body) {
        body.setId(null);
        if (body.getSortOrder() == null) body.setSortOrder(0);
        return ResponseEntity.ok(categoryRepo.save(body));
    }

    @RequirePermission("company.manage")
    @PutMapping("/{id}")
    public ResponseEntity<CompanyAssetCategory> update(@PathVariable Long id, @RequestBody CompanyAssetCategory body) {
        CompanyAssetCategory existing = categoryRepo.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("分类不存在: " + id));
        if (body.getName() != null) existing.setName(body.getName());
        if (body.getIcon() != null) existing.setIcon(body.getIcon());
        if (body.getSortOrder() != null) existing.setSortOrder(body.getSortOrder());
        return ResponseEntity.ok(categoryRepo.save(existing));
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        CompanyAssetCategory existing = categoryRepo.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("分类不存在: " + id));
        existing.setDeletedAt(LocalDateTime.now());
        categoryRepo.save(existing);
        return ResponseEntity.noContent().build();
    }
}
