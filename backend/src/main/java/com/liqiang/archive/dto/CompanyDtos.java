package com.liqiang.archive.dto;

import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.entity.CompanyAccount;
import com.liqiang.archive.entity.CompanyCertification;
import com.liqiang.archive.entity.CompanyTeamMember;
import com.liqiang.archive.entity.CompanyMilestone;
import com.liqiang.archive.entity.CompanyAsset;
import com.liqiang.archive.entity.KnowledgeCategory;
import com.liqiang.archive.entity.KnowledgeArticle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class CompanyDtos {

    public record CompanyInfoView(
            Long id,
            String companyName,
            String formerName,
            String companyNameEn,
            String brandName,
            String brandNameEn,
            String brandSlogan,
            String mission,
            String vision,
            String values,
            String businessModel,
            String serviceCapabilities,
            String customerTypes,
            String applicationScenarios,
            String unifiedSocialCode,
            String legalRepresentative,
            String companyType,
            String registeredCapital,
            String paidCapital,
            Integer insuredCount,
            String enterpriseScale,
            LocalDate establishmentDate,
            String businessTerm,
            String businessScope,
            String registeredAddress,
            String officeAddress,
            String factoryAddress,
            String phone,
            String email,
            String website,
            String corePositioning,
            String mainBusiness,
            String targetIndustries,
            String advantages,
            BigDecimal revenueDevicePct,
            BigDecimal revenueConsumablePct,
            String licenseFileName,
            String sealFileName,
            String logoFileName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static CompanyInfoView from(CompanyInfo info) {
            return new CompanyInfoView(
                    info.getId(),
                    info.getCompanyName(),
                    info.getFormerName(),
                    info.getCompanyNameEn(),
                    info.getBrandName(),
                    info.getBrandNameEn(),
                    info.getBrandSlogan(),
                    info.getMission(),
                    info.getVision(),
                    info.getValues(),
                    info.getBusinessModel(),
                    info.getServiceCapabilities(),
                    info.getCustomerTypes(),
                    info.getApplicationScenarios(),
                    info.getUnifiedSocialCode(),
                    info.getLegalRepresentative(),
                    info.getCompanyType(),
                    info.getRegisteredCapital(),
                    info.getPaidCapital(),
                    info.getInsuredCount(),
                    info.getEnterpriseScale(),
                    info.getEstablishmentDate(),
                    info.getBusinessTerm(),
                    info.getBusinessScope(),
                    info.getRegisteredAddress(),
                    info.getOfficeAddress(),
                    info.getFactoryAddress(),
                    info.getPhone(),
                    info.getEmail(),
                    info.getWebsite(),
                    info.getCorePositioning(),
                    info.getMainBusiness(),
                    info.getTargetIndustries(),
                    info.getAdvantages(),
                    info.getRevenueDevicePct(),
                    info.getRevenueConsumablePct(),
                    info.getLicenseFileName(),
                    info.getSealFileName(),
                    info.getLogoFileName(),
                    info.getCreatedAt(),
                    info.getUpdatedAt()
            );
        }
    }

    public record AccountView(
            Long id,
            String accountName,
            String accountType,
            String bankName,
            String accountNumber,
            String accountHolder,
            String currency,
            String swiftCode,
            String iban,
            String routingNumber,
            String bankAddress,
            String bankLocation,
            String domesticBankCode,
            String qrCodeFile,
            Boolean isPrimary,
            String notes,
            Integer sortOrder,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static AccountView from(CompanyAccount account) {
            return new AccountView(
                    account.getId(), account.getAccountName(), account.getAccountType(),
                    account.getBankName(), account.getAccountNumber(), account.getAccountHolder(),
                    account.getCurrency(), account.getSwiftCode(), account.getIban(),
                    account.getRoutingNumber(), account.getBankAddress(), account.getBankLocation(),
                    account.getDomesticBankCode(), account.getQrCodeFile(), account.getIsPrimary(),
                    account.getNotes(), account.getSortOrder(),
                    account.getCreatedAt(), account.getUpdatedAt()
            );
        }
    }

    public record MilestoneView(
            Long id,
            Integer year,
            String title,
            String description,
            String icon,
            String fileName,
            Integer sortOrder,
            Boolean websiteEnabled,
            LocalDateTime createdAt
    ) {
        public static MilestoneView from(CompanyMilestone milestone) {
            return new MilestoneView(
                    milestone.getId(), milestone.getYear(), milestone.getTitle(),
                    milestone.getDescription(), milestone.getIcon(), milestone.getFileName(),
                    milestone.getSortOrder(), milestone.getWebsiteEnabled(), milestone.getCreatedAt()
            );
        }
    }

    public record AssetView(
            Long id,
            String category,
            String fileName,
            String tags,
            Boolean defaultAsset,
            Integer sortOrder,
            String description,
            Boolean fileExists,
            Long fileSize,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String coverFileName
    ) {
        public static AssetView from(CompanyAsset asset) {
            return new AssetView(
                    asset.getId(), asset.getCategory(), asset.getFileName(),
                    asset.getTags(), asset.getDefaultAsset(), asset.getSortOrder(),
                    asset.getDescription(), asset.getFileExists(), asset.getFileSize(),
                    asset.getCreatedAt(), asset.getUpdatedAt(),
                    asset.getCoverFileName()
            );
        }
    }

    public record KnowledgeCategoryView(
            Long id,
            String name,
            String nameEn,
            String description,
            Integer sortOrder,
            LocalDateTime createdAt
    ) {
        public static KnowledgeCategoryView from(KnowledgeCategory category) {
            return new KnowledgeCategoryView(
                    category.getId(), category.getName(), category.getNameEn(),
                    category.getDescription(), category.getSortOrder(),
                    category.getCreatedAt()
            );
        }
    }

    public record KnowledgeArticleView(
            Long id,
            Long categoryId,
            String title,
            String content,
            String tags,
            String author,
            String source,
            Integer sortOrder,
            Integer viewCount,
            String status,
            Boolean websiteVisible,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static KnowledgeArticleView from(KnowledgeArticle article) {
            return new KnowledgeArticleView(
                    article.getId(), article.getCategoryId(), article.getTitle(),
                    article.getContent(), article.getTags(), article.getAuthor(),
                    article.getSource(), article.getSortOrder(), article.getViewCount(),
                    article.getStatus(), article.getWebsiteVisible(), article.getCreatedAt(), article.getUpdatedAt()
            );
        }
    }

    public record BatchResult(int updated) {
    }

    // ==================== 公司资质与合规文件（检测报告、营业执照、ISO 认证等） ====================

    public record CertificationView(
            Long id,
            String name,
            String nameEn,
            String category,
            String certNumber,
            LocalDate issueDate,
            LocalDate expiryDate,
            Boolean isPermanent,
            Long daysRemaining,
            String issuingAuthority,
            String fileName,
            String description,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static CertificationView from(CompanyCertification c) {
            long days = 0L;
            if (Boolean.FALSE.equals(c.getIsPermanent()) && c.getExpiryDate() != null) {
                days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), c.getExpiryDate());
            }
            String status = c.getStatus() != null ? c.getStatus() : "有效";
            return new CertificationView(
                    c.getId(), c.getName(), c.getNameEn(), c.getCategory(),
                    c.getCertNumber(), c.getIssueDate(), c.getExpiryDate(),
                    c.getIsPermanent(), days,
                    c.getIssuingAuthority(), c.getFileName(), c.getDescription(), status,
                    c.getCreatedAt(), c.getUpdatedAt()
            );
        }
    }

    public record CertificationPayload(
            String name,
            String nameEn,
            String category,
            String certNumber,
            LocalDate issueDate,
            LocalDate expiryDate,
            Boolean isPermanent,
            String issuingAuthority,
            String fileName,
            String description,
            String status
    ) {
    }
}
