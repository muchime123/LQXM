package com.liqiang.archive.controller;

import com.liqiang.archive.entity.*;
import com.liqiang.archive.service.CompanyCertificationService;
import com.liqiang.archive.service.CompanyInfoService;
import com.liqiang.archive.dto.CompanyDtos.*;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyInfoController {

    private final CompanyInfoService service;
    private final CompanyCertificationService certificationService;

    // ===== 公司统计 =====

    @GetMapping("/stats")
    public ResponseEntity<java.util.Map<String, Object>> getCompanyStats() {
        return ResponseEntity.ok(service.getCompanyStats());
    }

    // ===== 公司基本信息 =====

    @GetMapping("/info")
    public ResponseEntity<CompanyInfoView> getCompanyInfo() {
        return ResponseEntity.ok(CompanyInfoView.from(service.getCompanyInfo()));
    }

    @RequirePermission("company.manage")
    @PutMapping("/info")
    public ResponseEntity<CompanyInfoView> updateCompanyInfo(@RequestBody CompanyInfo info) {
        return ResponseEntity.ok(CompanyInfoView.from(service.updateCompanyInfo(info)));
    }

    // ===== 财务账户 =====

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountView>> getAccounts() {
        return ResponseEntity.ok(map(service.getAccounts(), AccountView::from));
    }

    @RequirePermission("company.manage")
    @PostMapping("/accounts")
    public ResponseEntity<AccountView> createAccount(@RequestBody CompanyAccount account) {
        return ResponseEntity.ok(AccountView.from(service.createAccount(account)));
    }

    @RequirePermission("company.manage")
    @PutMapping("/accounts/{id}")
    public ResponseEntity<AccountView> updateAccount(@PathVariable Long id,
                                                         @RequestBody CompanyAccount account) {
        return ResponseEntity.ok(AccountView.from(service.updateAccount(id, account)));
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        service.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }

    // ===== 知识库分类 =====

    @GetMapping("/kb/categories")
    public ResponseEntity<List<KnowledgeCategoryView>> getKbCategories() {
        return ResponseEntity.ok(map(service.getKbCategories(), KnowledgeCategoryView::from));
    }

    @RequirePermission("company.manage")
    @PostMapping("/kb/categories")
    public ResponseEntity<KnowledgeCategoryView> createKbCategory(@RequestBody KnowledgeCategory category) {
        return ResponseEntity.ok(KnowledgeCategoryView.from(service.createKbCategory(category)));
    }

    @RequirePermission("company.manage")
    @PutMapping("/kb/categories/{id}")
    public ResponseEntity<KnowledgeCategoryView> updateKbCategory(@PathVariable Long id,
                                                               @RequestBody KnowledgeCategory category) {
        return ResponseEntity.ok(KnowledgeCategoryView.from(service.updateKbCategory(id, category)));
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/kb/categories/{id}")
    public ResponseEntity<Void> deleteKbCategory(@PathVariable Long id) {
        service.deleteKbCategory(id);
        return ResponseEntity.noContent().build();
    }

    // ===== 知识库文章 =====

    @GetMapping("/kb/articles")
    public ResponseEntity<List<KnowledgeArticleView>> getKbArticles(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(map(service.getKbArticles(categoryId, keyword, status), KnowledgeArticleView::from));
    }

    @RequirePermission("company.manage")
    @PostMapping("/kb/articles")
    public ResponseEntity<KnowledgeArticleView> createKbArticle(@RequestBody KnowledgeArticle article) {
        return ResponseEntity.ok(KnowledgeArticleView.from(service.createKbArticle(article)));
    }

    @RequirePermission("company.manage")
    @PutMapping("/kb/articles/{id}")
    public ResponseEntity<KnowledgeArticleView> updateKbArticle(@PathVariable Long id,
                                                             @RequestBody KnowledgeArticle article) {
        return ResponseEntity.ok(KnowledgeArticleView.from(service.updateKbArticle(id, article)));
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/kb/articles/{id}")
    public ResponseEntity<Void> deleteKbArticle(@PathVariable Long id) {
        service.deleteKbArticle(id);
        return ResponseEntity.noContent().build();
    }

    @RequirePermission("company.manage")
    @PostMapping("/kb/articles/{id}/view")
    public ResponseEntity<KnowledgeArticleView> incrementKbArticleView(@PathVariable Long id) {
        return ResponseEntity.ok(KnowledgeArticleView.from(service.incrementKbArticleView(id)));
    }

    // ===== 发展历程 =====

    @GetMapping("/milestones")
    public ResponseEntity<List<MilestoneView>> getMilestones() {
        return ResponseEntity.ok(map(service.getMilestones(), MilestoneView::from));
    }

    @RequirePermission("company.manage")
    @PostMapping("/milestones")
    public ResponseEntity<MilestoneView> createMilestone(@RequestBody CompanyMilestone milestone) {
        return ResponseEntity.ok(MilestoneView.from(service.createMilestone(milestone)));
    }

    @RequirePermission("company.manage")
    @PutMapping("/milestones/{id}")
    public ResponseEntity<MilestoneView> updateMilestone(@PathVariable Long id,
                                                             @RequestBody CompanyMilestone milestone) {
        return ResponseEntity.ok(MilestoneView.from(service.updateMilestone(id, milestone)));
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/milestones/{id}")
    public ResponseEntity<Void> deleteMilestone(@PathVariable Long id) {
        service.deleteMilestone(id);
        return ResponseEntity.noContent().build();
    }

    // ===== 品牌资产 =====

    @GetMapping("/assets")
    public ResponseEntity<List<AssetView>> getAssets(
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(map(service.getAssets(category), AssetView::from));
    }

    @RequirePermission("company.manage")
    @PostMapping("/assets")
    public ResponseEntity<AssetView> createAsset(@RequestBody CompanyAsset asset) {
        return ResponseEntity.ok(AssetView.from(service.createAsset(asset)));
    }

    // ===== 回收站 =====

    @GetMapping("/assets/trash")
    public ResponseEntity<List<AssetView>> getTrashedAssets() {
        return ResponseEntity.ok(map(service.getTrashedAssets(), AssetView::from));
    }

    // ===== 公司资质与合规文件（检测报告、营业执照、ISO 认证等） =====

    @GetMapping("/certifications")
    public ResponseEntity<List<CertificationView>> getCertifications(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(certificationService.list(category, keyword));
    }

    @RequirePermission("company.manage")
    @PostMapping("/certifications")
    public ResponseEntity<CertificationView> createCertification(@RequestBody CertificationPayload payload) {
        return ResponseEntity.ok(certificationService.create(payload));
    }

    @RequirePermission("company.manage")
    @PutMapping("/certifications/{id}")
    public ResponseEntity<CertificationView> updateCertification(@PathVariable Long id,
                                                                     @RequestBody CertificationPayload payload) {
        return ResponseEntity.ok(certificationService.update(id, payload));
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/certifications/{id}")
    public ResponseEntity<Void> deleteCertification(@PathVariable Long id) {
        certificationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @RequirePermission("company.manage")
    @DeleteMapping("/certifications/batch")
    public ResponseEntity<BatchResult> batchDeleteCertifications(@RequestBody List<Long> ids) {
        return ResponseEntity.ok(new BatchResult(certificationService.batchDelete(ids)));
    }

    @RequirePermission("company.manage")
    @PutMapping("/certifications/batch")
    public ResponseEntity<BatchResult> batchUpdateCertifications(@RequestBody BatchFieldsRequest req) {
        return ResponseEntity.ok(new BatchResult(certificationService.batchUpdateFields(req.ids(), req.fields())));
    }

    public record BatchFieldsRequest(List<Long> ids, Map<String, Object> fields) {}

    private static <T, R> List<R> map(List<T> items, Function<T, R> mapper) {
        return items.stream().map(mapper).toList();
    }
}
