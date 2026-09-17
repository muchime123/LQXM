package com.liqiang.archive.service;

import com.liqiang.archive.entity.*;
import com.liqiang.archive.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyInfoService {

    private final CompanyInfoRepository companyInfoRepository;
    private final CompanyCertificationRepository certRepo;
    private final CompanyAccountRepository accountRepo;
    private final CompanyAssetRepository assetRepo;
    private final KnowledgeCategoryRepository kbCategoryRepo;
    private final KnowledgeArticleRepository kbArticleRepo;
    private final CertificateRepository certificateRepository;
    private final MediaAccountRepository mediaAccountRepository;
    private final CompanyMilestoneRepository milestoneRepo;
    private final FileStorageService fileStorageService;
    private final AuditLogService auditLogService;

    // ===== 公司统计 =====

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getCompanyStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();

        // 专利证书统计
        long certTotal = certificateRepository.count();
        stats.put("certificateTotal", certTotal);
        stats.put("certificateByCategory", toMap(certificateRepository.countByAssetCategory()));
        stats.put("certificateByStatus", toMap(certificateRepository.countByAssetStatus()));
        stats.put("certificateByPatentType", toMap(certificateRepository.countByPatentType()));
        stats.put("certificateByFeeStatus", toMap(certificateRepository.countByFeeStatus()));
        stats.put("certificateExpired", certificateRepository.countByAssetStatus("已失效"));

        // 公司证照统计
        long companyCertTotal = certRepo.count();
        stats.put("companyCertTotal", companyCertTotal);
        stats.put("companyCertByCategory", toMap(certRepo.countByCategory()));

        // 自媒体账号统计
        long accountTotal = mediaAccountRepository.count();
        stats.put("mediaAccountTotal", accountTotal);
        // 批量分组查询替代循环内逐平台 count（N+1 → 1）
        java.util.Map<String, Long> byPlatform = new java.util.LinkedHashMap<>();
        mediaAccountRepository.countByPlatformGroupByPlatform()
                .forEach(row -> byPlatform.put((String) row[0], (Long) row[1]));
        stats.put("mediaAccountByPlatform", byPlatform);

        return stats;
    }

    private java.util.Map<String, Long> toMap(List<Object[]> rows) {
        java.util.Map<String, Long> map = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = row[0] == null || String.valueOf(row[0]).isBlank() ? "未设置" : String.valueOf(row[0]);
            map.put(key, ((Number) row[1]).longValue());
        }
        return map;
    }

    // ===== 公司基本信息 =====

    @Transactional
    public CompanyInfo getCompanyInfo() {
        return companyInfoRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> {
                    CompanyInfo info = new CompanyInfo();
                    info.setCompanyName("浙江利强包装科技有限公司");
                    CompanyInfo saved = companyInfoRepository.save(info);
                    auditLogService.log("CompanyInfo", saved.getId(), saved.getCompanyName(),
                            "COMPANY_INFO_INIT", "初始化公司基本信息默认记录");
                    return saved;
                });
    }

    @Transactional
    public CompanyInfo updateCompanyInfo(CompanyInfo info) {
        CompanyInfo existing = getCompanyInfo();
        existing.setCompanyName(info.getCompanyName());
        existing.setFormerName(info.getFormerName());
        existing.setCompanyNameEn(info.getCompanyNameEn());
        existing.setBrandName(info.getBrandName());
        existing.setBrandNameEn(info.getBrandNameEn());
        existing.setBrandSlogan(info.getBrandSlogan());
        existing.setMission(info.getMission());
        existing.setVision(info.getVision());
        existing.setValues(info.getValues());
        existing.setBusinessModel(info.getBusinessModel());
        existing.setServiceCapabilities(info.getServiceCapabilities());
        existing.setCustomerTypes(info.getCustomerTypes());
        existing.setApplicationScenarios(info.getApplicationScenarios());
        existing.setUnifiedSocialCode(info.getUnifiedSocialCode());
        existing.setLegalRepresentative(info.getLegalRepresentative());
        existing.setCompanyType(info.getCompanyType());
        existing.setRegisteredCapital(info.getRegisteredCapital());
        existing.setPaidCapital(info.getPaidCapital());
        existing.setInsuredCount(info.getInsuredCount());
        existing.setEnterpriseScale(info.getEnterpriseScale());
        existing.setEstablishmentDate(info.getEstablishmentDate());
        existing.setBusinessTerm(info.getBusinessTerm());
        existing.setBusinessScope(info.getBusinessScope());
        existing.setRegisteredAddress(info.getRegisteredAddress());
        existing.setOfficeAddress(info.getOfficeAddress());
        existing.setFactoryAddress(info.getFactoryAddress());
        existing.setPhone(info.getPhone());
        existing.setEmail(info.getEmail());
        existing.setWebsite(info.getWebsite());
        existing.setCorePositioning(info.getCorePositioning());
        existing.setMainBusiness(info.getMainBusiness());
        existing.setTargetIndustries(info.getTargetIndustries());
        existing.setAdvantages(info.getAdvantages());
        existing.setRevenueDevicePct(info.getRevenueDevicePct());
        existing.setRevenueConsumablePct(info.getRevenueConsumablePct());
        existing.setLicenseFileName(info.getLicenseFileName());
        existing.setSealFileName(info.getSealFileName());
        existing.setLogoFileName(info.getLogoFileName());
        CompanyInfo saved = companyInfoRepository.save(existing);
        auditLogService.log("CompanyInfo", saved.getId(), saved.getCompanyName(),
                "COMPANY_INFO_UPDATE", "更新公司基本信息");
        return saved;
    }

    // ===== 财务账户 =====

    @Transactional(readOnly = true)
    public List<CompanyAccount> getAccounts() {
        return accountRepo.findAllByOrderBySortOrderAsc();
    }

    @Transactional
    public CompanyAccount createAccount(CompanyAccount account) {
        CompanyAccount saved = accountRepo.save(account);
        auditLogService.log("CompanyAccount", saved.getId(), saved.getAccountName(),
                "COMPANY_ACCOUNT_CREATE", "创建财务账户 " + saved.getAccountName());
        return saved;
    }

    @Transactional
    public CompanyAccount updateAccount(Long id, CompanyAccount account) {
        CompanyAccount existing = accountRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("账户不存在: " + id));
        existing.setAccountName(account.getAccountName());
        existing.setAccountType(account.getAccountType());
        existing.setBankName(account.getBankName());
        existing.setAccountNumber(account.getAccountNumber());
        existing.setAccountHolder(account.getAccountHolder());
        existing.setCurrency(account.getCurrency());
        existing.setSwiftCode(account.getSwiftCode());
        existing.setQrCodeFile(account.getQrCodeFile());
        existing.setIsPrimary(account.getIsPrimary());
        existing.setNotes(account.getNotes());
        existing.setSortOrder(account.getSortOrder());
        CompanyAccount saved = accountRepo.save(existing);
        auditLogService.log("CompanyAccount", saved.getId(), saved.getAccountName(),
                "COMPANY_ACCOUNT_UPDATE", "更新财务账户 " + saved.getAccountName());
        return saved;
    }

    @Transactional
    public void deleteAccount(Long id) {
        CompanyAccount existing = accountRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("账户不存在: " + id));
        accountRepo.deleteById(id);
        auditLogService.log("CompanyAccount", id, existing.getAccountName(),
                "COMPANY_ACCOUNT_DELETE", "删除财务账户 " + existing.getAccountName());
    }

    // ===== 知识库分类 =====

    @Transactional(readOnly = true)
    public List<KnowledgeCategory> getKbCategories() {
        return kbCategoryRepo.findAllByOrderBySortOrderAsc();
    }

    @Transactional
    public KnowledgeCategory createKbCategory(KnowledgeCategory category) {
        KnowledgeCategory saved = kbCategoryRepo.save(category);
        auditLogService.log("KnowledgeCategory", saved.getId(), saved.getName(),
                "KB_CATEGORY_CREATE", "创建知识库分类 " + saved.getName());
        return saved;
    }

    @Transactional
    public KnowledgeCategory updateKbCategory(Long id, KnowledgeCategory category) {
        KnowledgeCategory existing = kbCategoryRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("分类不存在: " + id));
        existing.setName(category.getName());
        existing.setNameEn(category.getNameEn());
        existing.setDescription(category.getDescription());
        existing.setSortOrder(category.getSortOrder());
        KnowledgeCategory saved = kbCategoryRepo.save(existing);
        auditLogService.log("KnowledgeCategory", saved.getId(), saved.getName(),
                "KB_CATEGORY_UPDATE", "更新知识库分类 " + saved.getName());
        return saved;
    }

    @Transactional
    public void deleteKbCategory(Long id) {
        KnowledgeCategory existing = kbCategoryRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("分类不存在: " + id));
        kbCategoryRepo.deleteById(id);
        auditLogService.log("KnowledgeCategory", id, existing.getName(),
                "KB_CATEGORY_DELETE", "删除知识库分类 " + existing.getName());
    }

    // ===== 知识库文章 =====

    @Transactional(readOnly = true)
    public List<KnowledgeArticle> getKbArticles(Long categoryId, String keyword, String status) {
        boolean includeDraft = status == null || status.isEmpty() || "all".equals(status);
        
        if (keyword != null && !keyword.isEmpty()) {
            return includeDraft ? kbArticleRepo.searchByKeywordAllStatus(keyword) : kbArticleRepo.searchByKeyword(keyword);
        }
        if (categoryId != null) {
            return includeDraft ? kbArticleRepo.findByCategoryIdOrderBySortOrderAsc(categoryId) 
                               : kbArticleRepo.findByCategoryIdAndStatusOrderBySortOrderAsc(categoryId, "published");
        }
        return includeDraft ? kbArticleRepo.findAllByOrderBySortOrderAsc() 
                           : kbArticleRepo.findByStatusOrderBySortOrderAsc("published");
    }

    @Transactional
    public KnowledgeArticle createKbArticle(KnowledgeArticle article) {
        if (article.getStatus() == null) {
            article.setStatus("published");
        }
        if (article.getWebsiteVisible() == null) {
            article.setWebsiteVisible(false);
        }
        KnowledgeArticle saved = kbArticleRepo.save(article);
        auditLogService.log("KnowledgeArticle", saved.getId(), saved.getTitle(),
                "KB_ARTICLE_CREATE", "创建知识库文章 " + saved.getTitle());
        return saved;
    }

    @Transactional
    public KnowledgeArticle updateKbArticle(Long id, KnowledgeArticle article) {
        KnowledgeArticle existing = kbArticleRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("文章不存在: " + id));
        existing.setCategoryId(article.getCategoryId());
        existing.setTitle(article.getTitle());
        existing.setContent(article.getContent());
        existing.setTags(article.getTags());
        existing.setAuthor(article.getAuthor());
        existing.setSource(article.getSource());
        existing.setSortOrder(article.getSortOrder());
        existing.setStatus(article.getStatus());
        existing.setWebsiteVisible(Boolean.TRUE.equals(article.getWebsiteVisible()));
        KnowledgeArticle saved = kbArticleRepo.save(existing);
        auditLogService.log("KnowledgeArticle", saved.getId(), saved.getTitle(),
                "KB_ARTICLE_UPDATE", "更新知识库文章 " + saved.getTitle());
        return saved;
    }

    @Transactional
    public void deleteKbArticle(Long id) {
        KnowledgeArticle existing = kbArticleRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("文章不存在: " + id));
        kbArticleRepo.deleteById(id);
        auditLogService.log("KnowledgeArticle", id, existing.getTitle(),
                "KB_ARTICLE_DELETE", "删除知识库文章 " + existing.getTitle());
    }

    @Transactional
    public KnowledgeArticle incrementKbArticleView(Long id) {
        KnowledgeArticle article = kbArticleRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("文章不存在: " + id));
        article.setViewCount((article.getViewCount() != null ? article.getViewCount() : 0) + 1);
        return kbArticleRepo.save(article);
    }

    // ===== 知识库文章（官网公开） =====

    /** 官网展示的文章（已发布 + websiteVisible），支持按分类与关键词过滤。 */
    @Transactional(readOnly = true)
    public List<KnowledgeArticle> getWebsiteKbArticles(Long categoryId, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return kbArticleRepo.searchByKeywordWebsiteVisible(keyword.trim());
        }
        if (categoryId != null) {
            return kbArticleRepo.findByCategoryIdAndStatusAndWebsiteVisibleTrueOrderBySortOrderAsc(categoryId, "published");
        }
        return kbArticleRepo.findByStatusAndWebsiteVisibleTrueOrderBySortOrderAsc("published");
    }

    /** 官网展示文章详情：仅返回已发布且 websiteVisible 的文章，并累加浏览量。 */
    @Transactional
    public KnowledgeArticle getWebsiteKbArticleDetail(Long id) {
        KnowledgeArticle article = kbArticleRepo.findById(id).orElse(null);
        if (article == null || !"published".equals(article.getStatus()) || !Boolean.TRUE.equals(article.getWebsiteVisible())) {
            return null;
        }
        article.setViewCount((article.getViewCount() != null ? article.getViewCount() : 0) + 1);
        return kbArticleRepo.save(article);
    }

    // ===== 品牌资产 =====

    private List<CompanyAsset> markAssetFileExists(List<CompanyAsset> assets) {
        assets.forEach(this::markAssetFileExists);
        return assets;
    }

    private CompanyAsset markAssetFileExists(CompanyAsset asset) {
        asset.setFileExists(fileStorageService.exists("company", asset.getFileName()));
        asset.setFileSize(fileStorageService.fileSize("company", asset.getFileName()));
        return asset;
    }

    @Transactional(readOnly = true)
    public List<CompanyAsset> getAssets(String category) {
        if (category != null && !category.isEmpty()) {
            return markAssetFileExists(assetRepo.findByCategoryOrderBySortOrderAsc(category));
        }
        return markAssetFileExists(assetRepo.findAllByOrderByCategoryAscSortOrderAsc());
    }

    @Transactional
    public CompanyAsset createAsset(CompanyAsset asset) {
        if (asset.getSortOrder() == null) asset.setSortOrder(0);
        if (asset.getDefaultAsset() == null) asset.setDefaultAsset(false);
        CompanyAsset saved = assetRepo.save(asset);
        auditLogService.log("CompanyAsset", saved.getId(), saved.getFileName(),
                "COMPANY_ASSET_CREATE", "创建品牌资产 " + saved.getCategory() + " / " + saved.getFileName());
        return saved;
    }

    @Transactional
    public CompanyAsset updateAsset(Long id, CompanyAsset asset) {
        CompanyAsset existing = assetRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("资产不存在: " + id));
        if (asset.getCategory() != null) {
            if (asset.getCategory().isBlank()) {
                throw new IllegalArgumentException("分类不能为空");
            }
            existing.setCategory(asset.getCategory());
        }
        if (asset.getFileName() != null) {
            if (asset.getFileName().isBlank()) {
                throw new IllegalArgumentException("文件名不能为空");
            }
            existing.setFileName(asset.getFileName());
        }
        if (asset.getTags() != null) existing.setTags(asset.getTags());
        if (asset.getDefaultAsset() != null) existing.setDefaultAsset(asset.getDefaultAsset());
        if (asset.getSortOrder() != null) existing.setSortOrder(asset.getSortOrder());
        if (asset.getDescription() != null) existing.setDescription(asset.getDescription());
        CompanyAsset saved = assetRepo.save(existing);
        auditLogService.log("CompanyAsset", saved.getId(), saved.getFileName(),
                "COMPANY_ASSET_UPDATE", "更新品牌资产 " + saved.getFileName());
        return saved;
    }

    @Transactional
    public void softDeleteAsset(Long id) {
        CompanyAsset existing = assetRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("资产不存在: " + id));
        assetRepo.softDelete(id, LocalDateTime.now());
        auditLogService.log("CompanyAsset", id, existing.getFileName(),
                "COMPANY_ASSET_SOFT_DELETE", "移入回收站：品牌资产 " + existing.getFileName());
    }

    @Transactional
    public CompanyAsset updateAssetTags(Long id, String tags) {
        CompanyAsset asset = assetRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("资产不存在: " + id));
        asset.setTags(tags);
        CompanyAsset saved = assetRepo.save(asset);
        auditLogService.log("CompanyAsset", saved.getId(), saved.getFileName(),
                "COMPANY_ASSET_UPDATE_TAGS", "更新品牌资产标签 " + saved.getFileName());
        return saved;
    }

    @Transactional
    public int batchUpdateAssetTags(List<Long> ids, String tags, String mode) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // 批量加载，避免循环内 N 次 SELECT
        List<CompanyAsset> assets = assetRepo.findAllById(ids);
        for (CompanyAsset a : assets) {
            if ("append".equals(mode) && a.getTags() != null && !a.getTags().isBlank()) {
                a.setTags(a.getTags() + "," + tags);
            } else {
                a.setTags(tags);
            }
        }
        if (!assets.isEmpty()) {
            assetRepo.saveAll(assets);
        }
        int count = assets.size();
        if (count > 0) {
            auditLogService.log("CompanyAsset", null, "品牌资产",
                    "COMPANY_ASSET_BATCH_UPDATE_TAGS", "批量更新标签：" + count + " 个资产（模式 " + mode + "）");
        }
        return count;
    }

    @Transactional
    public CompanyAsset setDefaultAsset(Long id) {
        CompanyAsset asset = assetRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("资产不存在: " + id));
        assetRepo.clearDefaultInCategory(asset.getCategory());
        asset.setDefaultAsset(true);
        CompanyAsset saved = assetRepo.save(asset);
        auditLogService.log("CompanyAsset", saved.getId(), saved.getFileName(),
                "COMPANY_ASSET_SET_DEFAULT", "设置默认资产 " + saved.getCategory() + " / " + saved.getFileName());
        return saved;
    }

    @Transactional
    public int batchMoveCategory(List<Long> ids, String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("分类不能为空");
        }
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // 批量加载，避免循环内 N 次 SELECT
        List<CompanyAsset> assets = assetRepo.findAllById(ids);
        for (CompanyAsset a : assets) {
            a.setCategory(category);
            a.setDefaultAsset(false);
        }
        if (!assets.isEmpty()) {
            assetRepo.saveAll(assets);
        }
        int count = assets.size();
        if (count > 0) {
            auditLogService.log("CompanyAsset", null, "品牌资产",
                    "COMPANY_ASSET_BATCH_MOVE_CATEGORY", "批量迁移分类：" + count + " 个资产 → " + category);
        }
        return count;
    }

    @Transactional
    public void reorderAssets(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 批量加载并内存更新，统一 saveAll，避免循环内 N 次原生 UPDATE
        Map<Long, Integer> sortMap = new java.util.HashMap<>();
        for (Map<String, Object> item : items) {
            Long id = ((Number) item.get("id")).longValue();
            Integer sortOrder = ((Number) item.get("sortOrder")).intValue();
            sortMap.put(id, sortOrder);
        }
        List<CompanyAsset> assets = assetRepo.findAllById(sortMap.keySet());
        for (CompanyAsset a : assets) {
            a.setSortOrder(sortMap.get(a.getId()));
        }
        if (!assets.isEmpty()) {
            assetRepo.saveAll(assets);
        }
        auditLogService.log("CompanyAsset", null, "品牌资产",
                "COMPANY_ASSET_REORDER", "重排序 " + assets.size() + " 个品牌资产");
    }

    // ===== 发展历程 =====

    @Transactional(readOnly = true)
    public List<CompanyMilestone> getMilestones() {
        return milestoneRepo.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.desc("year"),
                org.springframework.data.domain.Sort.Order.asc("sortOrder")
        ));
    }

    @Transactional
    public CompanyMilestone createMilestone(CompanyMilestone milestone) {
        if (milestone.getSortOrder() == null) milestone.setSortOrder(0);
        CompanyMilestone saved = milestoneRepo.save(milestone);
        auditLogService.log("CompanyMilestone", saved.getId(), saved.getTitle(),
                "COMPANY_MILESTONE_CREATE", "创建发展历程 " + saved.getTitle());
        return saved;
    }

    @Transactional
    public CompanyMilestone updateMilestone(Long id, CompanyMilestone milestone) {
        CompanyMilestone existing = milestoneRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("里程碑不存在: " + id));
        existing.setYear(milestone.getYear());
        existing.setTitle(milestone.getTitle());
        existing.setDescription(milestone.getDescription());
        existing.setIcon(milestone.getIcon());
        existing.setFileName(milestone.getFileName());
        existing.setSortOrder(milestone.getSortOrder());
        existing.setWebsiteEnabled(milestone.getWebsiteEnabled());
        CompanyMilestone saved = milestoneRepo.save(existing);
        auditLogService.log("CompanyMilestone", saved.getId(), saved.getTitle(),
                "COMPANY_MILESTONE_UPDATE", "更新发展历程 " + saved.getTitle());
        return saved;
    }

    @Transactional
    public void deleteMilestone(Long id) {
        CompanyMilestone existing = milestoneRepo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("里程碑不存在: " + id));
        milestoneRepo.deleteById(id);
        auditLogService.log("CompanyMilestone", id, existing.getTitle(),
                "COMPANY_MILESTONE_DELETE", "删除发展历程 " + existing.getTitle());
    }

    // ===== 回收站 =====

    @Transactional(readOnly = true)
    public List<CompanyAsset> getTrashedAssets() {
        return assetRepo.findDeleted();
    }

    @Transactional
    public void restoreAsset(Long id) {
        int rows = assetRepo.restore(id);
        if (rows == 0) throw new EntityNotFoundException("资产不存在: " + id);
        auditLogService.log("CompanyAsset", id, "品牌资产",
                "COMPANY_ASSET_RESTORE", "从回收站恢复品牌资产 #" + id);
    }

    @Transactional
    public void permanentDeleteAsset(Long id) {
        permanentDeleteAsset(id, true);
    }

    @Transactional
    public void permanentDeleteAsset(Long id, boolean deletePhysical) {
        if (deletePhysical) {
            CompanyAsset asset = assetRepo.findDeletedById(id)
                    .orElseThrow(() -> new EntityNotFoundException("Asset does not exist: " + id));
            fileStorageService.deleteIfExists("company", asset.getFileName());
        }
        int rows = assetRepo.permanentDelete(id);
        if (rows == 0) throw new EntityNotFoundException("资产不存在: " + id);
        auditLogService.log("CompanyAsset", id, "品牌资产",
                "COMPANY_ASSET_PERMANENT_DELETE", "永久删除品牌资产 #" + id + (deletePhysical ? "（含物理文件）" : ""));
    }
}
