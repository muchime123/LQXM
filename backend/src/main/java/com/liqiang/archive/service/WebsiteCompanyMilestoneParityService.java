package com.liqiang.archive.service;

import com.liqiang.archive.entity.CompanyMilestone;
import com.liqiang.archive.entity.WebsiteCompanyMilestone;
import com.liqiang.archive.repository.WebsiteCompanyMilestoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only comparison of currently public archive timeline and website-owned rows. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyMilestoneParityService {
    private final CompanyInfoService companyInfo;
    private final WebsiteCompanyMilestoneRepository websiteMilestones;

    @Transactional(readOnly = true)
    public ParityView compare() {
        List<CompanyMilestone> legacy = companyInfo.getMilestones().stream()
                .filter(item -> Boolean.TRUE.equals(item.getWebsiteEnabled())).toList();
        List<WebsiteCompanyMilestone> website = websiteMilestones.findByDeletedAtIsNullOrderByYearAscSortOrderAscIdAsc();
        boolean[] used = new boolean[website.size()];
        List<LegacyRow> rows = new ArrayList<>();
        long publishedMatches = 0;
        long draftMatches = 0;
        for (CompanyMilestone old : legacy) {
            int match = findMatch(old, website, used, "PUBLISHED");
            if (match >= 0) publishedMatches++;
            else {
                match = findMatch(old, website, used, "DRAFT");
                if (match >= 0) draftMatches++;
            }
            if (match >= 0) used[match] = true;
            rows.add(new LegacyRow(old.getId(), old.getYear(), old.getTitle(), old.getDescription(),
                    match < 0 ? null : website.get(match).getId(),
                    match < 0 ? "MISSING" : website.get(match).getStatus()));
        }
        List<WebsiteRow> unmatchedWebsite = new ArrayList<>();
        for (int i = 0; i < website.size(); i++) {
            if (used[i]) continue;
            WebsiteCompanyMilestone item = website.get(i);
            unmatchedWebsite.add(new WebsiteRow(item.getId(), item.getYear(), item.getTitle(),
                    item.getDescription(), item.getStatus()));
        }
        return new ParityView(legacy.size(), website.size(), publishedMatches, draftMatches,
                List.copyOf(rows), List.copyOf(unmatchedWebsite));
    }

    private static int findMatch(CompanyMilestone old, List<WebsiteCompanyMilestone> rows,
                                 boolean[] used, String status) {
        for (int i = 0; i < rows.size(); i++) {
            WebsiteCompanyMilestone item = rows.get(i);
            if (!used[i] && status.equals(item.getStatus())
                    && Objects.equals(old.getYear(), item.getYear())
                    && Objects.equals(old.getTitle(), item.getTitle())
                    && Objects.equals(old.getDescription(), item.getDescription())) return i;
        }
        return -1;
    }

    public record LegacyRow(Long legacyId, Integer year, String title, String description,
                            Long websiteId, String matchStatus) {}
    public record WebsiteRow(Long websiteId, Integer year, String title, String description, String status) {}
    public record ParityView(int legacyCount, int websiteCount, long publishedMatches,
                             long draftMatches, List<LegacyRow> legacyRows,
                             List<WebsiteRow> unmatchedWebsiteRows) {}
}
