package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/** Website-owned company copy, edited independently of internal CompanyInfo. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyProfileService {
    private static final long PRIMARY_PROFILE_ID = 1L;

    private final WebsiteCompanyProfileRepository profiles;
    private final WebsiteMediaAssetRepository media;
    private final AuditLogService audit;

    @Transactional(readOnly = true)
    public WebsiteCompanyProfile get() {
        return profiles.findById(PRIMARY_PROFILE_ID).orElse(null);
    }

    @Transactional
    public WebsiteCompanyProfile saveDraft(ProfileFields fields) {
        validate(fields);
        WebsiteCompanyProfile profile = profiles.findById(PRIMARY_PROFILE_ID).orElseGet(() -> {
            WebsiteCompanyProfile created = new WebsiteCompanyProfile();
            created.setId(PRIMARY_PROFILE_ID);
            return created;
        });
        if (!"DRAFT".equals(profile.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已发布官网公司介绍不能通过草稿接口修改");
        }
        if (fields.logoMediaId() != null) {
            var asset = media.findByIdAndDeletedAtIsNull(fields.logoMediaId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网 Logo 图片不存在"));
            if (!"IMAGE".equals(asset.getMediaType()) || "ARCHIVED".equals(asset.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网 Logo 只能选择有效官网图片");
            }
        }
        profile.setCompanyName(fields.companyName().trim());
        profile.setCompanyNameEn(trim(fields.companyNameEn()));
        profile.setBrandName(trim(fields.brandName()));
        profile.setBrandNameEn(trim(fields.brandNameEn()));
        profile.setBrandSlogan(trim(fields.brandSlogan()));
        profile.setMission(trim(fields.mission()));
        profile.setVision(trim(fields.vision()));
        profile.setValues(trim(fields.values()));
        profile.setCorePositioning(trim(fields.corePositioning()));
        profile.setMainBusiness(trim(fields.mainBusiness()));
        profile.setTargetIndustries(trim(fields.targetIndustries()));
        profile.setAdvantages(trim(fields.advantages()));
        profile.setManufacturingStrengths(trim(fields.manufacturingStrengths()));
        profile.setEnterpriseScale(trim(fields.enterpriseScale()));
        profile.setEstablishmentDate(fields.establishmentDate());
        profile.setOfficeAddress(trim(fields.officeAddress()));
        profile.setFactoryAddress(trim(fields.factoryAddress()));
        profile.setPhone(trim(fields.phone()));
        profile.setEmail(trim(fields.email()));
        profile.setWebsite(trim(fields.website()));
        profile.setLogoMediaId(fields.logoMediaId());
        WebsiteCompanyProfile saved = profiles.save(profile);
        audit.log("WebsiteCompanyProfile", PRIMARY_PROFILE_ID, saved.getCompanyName(),
                "WEBSITE_COMPANY_PROFILE_DRAFT_SAVE", "保存官网独立公司介绍草稿");
        return saved;
    }

    private static void validate(ProfileFields f) {
        if (f == null || f.companyName() == null || f.companyName().isBlank()
                || tooLong(f.companyName(), 300) || tooLong(f.companyNameEn(), 300)
                || tooLong(f.brandName(), 200) || tooLong(f.brandNameEn(), 200)
                || tooLong(f.brandSlogan(), 500) || tooLong(f.enterpriseScale(), 200)
                || tooLong(f.officeAddress(), 500) || tooLong(f.factoryAddress(), 500)
                || tooLong(f.phone(), 100) || tooLong(f.email(), 200) || tooLong(f.website(), 500)
                || tooLong(f.mission(), 20_000) || tooLong(f.vision(), 20_000)
                || tooLong(f.values(), 20_000) || tooLong(f.corePositioning(), 20_000)
                || tooLong(f.mainBusiness(), 20_000) || tooLong(f.targetIndustries(), 20_000)
                || tooLong(f.advantages(), 20_000) || tooLong(f.manufacturingStrengths(), 20_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网公司介绍必填字段缺失或超过长度限制");
        }
        if (f.website() != null && !f.website().isBlank()) {
            try {
                var uri = java.net.URI.create(f.website().trim());
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) {
                    throw new IllegalArgumentException("invalid website URL");
                }
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "官网网址必须是有效的 HTTP 或 HTTPS 地址");
            }
        }
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ProfileFields(String companyName, String companyNameEn, String brandName,
                                String brandNameEn, String brandSlogan, String mission, String vision,
                                String values, String corePositioning, String mainBusiness,
                                String targetIndustries, String advantages, String manufacturingStrengths,
                                String enterpriseScale, LocalDate establishmentDate, String officeAddress,
                                String factoryAddress, String phone, String email, String website,
                                Long logoMediaId) {}
}
