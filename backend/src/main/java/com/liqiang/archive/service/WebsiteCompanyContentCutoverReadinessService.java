package com.liqiang.archive.service;

import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Strict, read-only parity gate for switching company content to website-owned data. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyContentCutoverReadinessService {
    private final WebsiteCompanyProfileRepository profiles;
    private final WebsiteCompanyProfileReadinessService profileReadiness;
    private final WebsiteCompanyProfileParityService profileParity;
    private final WebsiteCompanyMilestoneParityService milestoneParity;
    private final WebsiteSocialLinkParityService socialParity;

    @Transactional(readOnly = true)
    public ReadinessView check() {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        var profile = profiles.findById(1L).orElse(null);
        if (profile == null || !"PUBLISHED".equals(profile.getStatus())) {
            blockers.add("官网独立公司介绍尚未发布");
        }
        var content = profileReadiness.check();
        blockers.addAll(content.blockers());
        warnings.addAll(content.warnings());
        var company = profileParity.compare();
        if (company.differentCount() > 0) {
            blockers.add("公司介绍与当前公开内容有 " + company.differentCount() + " 个字段不同，需逐项审阅");
        }
        var milestones = milestoneParity.compare();
        long missingPublished = milestones.legacyCount() - milestones.publishedMatches();
        if (missingPublished > 0) {
            blockers.add("当前公开发展历程有 " + missingPublished + " 条尚无完全一致的官网已发布记录");
        }
        long extraPublished = milestones.unmatchedWebsiteRows().stream()
                .filter(item -> "PUBLISHED".equals(item.status())).count();
        if (extraPublished > 0) {
            blockers.add("官网已发布发展历程另有 " + extraPublished + " 条与当前公开内容不同");
        }
        if (milestones.draftMatches() > 0) {
            warnings.add("有 " + milestones.draftMatches() + " 条旧公开历程仅匹配官网草稿");
        }
        var socials = socialParity.compare();
        long missingSocials = socials.legacyCount() - socials.publishedMatches();
        if (missingSocials > 0) {
            blockers.add("当前公开官方链接有 " + missingSocials + " 条尚无完全一致的官网已发布记录");
        }
        long extraSocials = socials.unmatchedWebsiteRows().stream()
                .filter(item -> "PUBLISHED".equals(item.status())).count();
        if (extraSocials > 0) {
            blockers.add("官网已发布官方链接另有 " + extraSocials + " 条与当前公开内容不同");
        }
        if (socials.draftMatches() > 0) {
            warnings.add("有 " + socials.draftMatches() + " 条旧公开官方链接仅匹配官网草稿");
        }
        return new ReadinessView(blockers.isEmpty(), List.copyOf(blockers), List.copyOf(warnings),
                company.differentCount(), milestones.legacyCount(), milestones.publishedMatches(), extraPublished,
                socials.legacyCount(), socials.publishedMatches(), extraSocials);
    }

    public record ReadinessView(boolean ready, List<String> blockers, List<String> warnings,
                                long companyFieldDifferences, int legacyMilestones,
                                long publishedMilestoneMatches, long extraPublishedMilestones,
                                int legacySocialLinks, long publishedSocialLinkMatches,
                                long extraPublishedSocialLinks) {}
}
