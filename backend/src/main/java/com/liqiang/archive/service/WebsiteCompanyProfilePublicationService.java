package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/** Explicit publication of the reviewed website-owned company profile. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyProfilePublicationService {
    private final WebsiteCompanyProfileRepository profiles;
    private final WebsiteCompanyProfileReadinessService readiness;
    private final WebsiteMediaAssetRepository media;
    private final AuditLogService audit;

    @Transactional
    public WebsiteCompanyProfile publish() {
        WebsiteCompanyProfile profile = profiles.findById(1L)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "官网公司介绍草稿不存在"));
        if ("PUBLISHED".equals(profile.getStatus())) return profile;
        if (!"DRAFT".equals(profile.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿公司介绍可以发布");
        }
        var check = readiness.check();
        if (!check.ready()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "官网公司介绍未达到发布条件：" + String.join("；", check.blockers()));
        }
        var logo = media.findByIdAndDeletedAtIsNull(profile.getLogoMediaId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "官网 Logo 已不存在"));
        if (!"IMAGE".equals(logo.getMediaType())
                || !("DRAFT".equals(logo.getStatus()) || "PUBLISHED".equals(logo.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "官网 Logo 状态已变化");
        }
        if ("DRAFT".equals(logo.getStatus())) {
            logo.setStatus("PUBLISHED");
            media.save(logo);
        }
        profile.setStatus("PUBLISHED");
        profile.setPublishedAt(LocalDateTime.now());
        WebsiteCompanyProfile saved = profiles.save(profile);
        audit.log("WebsiteCompanyProfile", 1L, saved.getCompanyName(),
                "WEBSITE_COMPANY_PROFILE_PUBLISH", "发布官网独立公司介绍");
        return saved;
    }
}
