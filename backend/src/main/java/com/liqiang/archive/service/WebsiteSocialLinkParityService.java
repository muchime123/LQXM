package com.liqiang.archive.service;

import com.liqiang.archive.entity.MediaAccount;
import com.liqiang.archive.entity.WebsiteSocialLink;
import com.liqiang.archive.repository.MediaAccountRepository;
import com.liqiang.archive.repository.WebsiteSocialLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only comparison of the current public accounts and website-owned links. */
@Service
@RequiredArgsConstructor
public class WebsiteSocialLinkParityService {
    private final MediaAccountRepository legacyAccounts;
    private final WebsiteSocialLinkRepository websiteLinks;

    @Transactional(readOnly = true)
    public ParityView compare() {
        List<MediaAccount> legacy = legacyAccounts.findByWebsiteOfficialTrueOrderByPlatformAscIdAsc();
        List<WebsiteSocialLink> website = websiteLinks.findByDeletedAtIsNullOrderBySortOrderAscIdAsc();
        boolean[] used = new boolean[website.size()];
        List<LegacyRow> rows = new ArrayList<>();
        long publishedMatches = 0;
        long draftMatches = 0;
        for (MediaAccount old : legacy) {
            int match = findMatch(old, website, used, "PUBLISHED");
            if (match >= 0) publishedMatches++;
            else {
                match = findMatch(old, website, used, "DRAFT");
                if (match >= 0) draftMatches++;
            }
            if (match >= 0) used[match] = true;
            rows.add(new LegacyRow(old.getId(), old.getPlatform(), old.getAccountName(), old.getLink(),
                    match < 0 ? null : website.get(match).getId(),
                    match < 0 ? "MISSING" : website.get(match).getStatus()));
        }
        List<WebsiteRow> unmatched = new ArrayList<>();
        for (int i = 0; i < website.size(); i++) {
            if (used[i]) continue;
            WebsiteSocialLink item = website.get(i);
            unmatched.add(new WebsiteRow(item.getId(), item.getPlatform(), item.getAccountName(),
                    item.getLink(), item.getStatus()));
        }
        return new ParityView(legacy.size(), website.size(), publishedMatches, draftMatches,
                List.copyOf(rows), List.copyOf(unmatched));
    }

    private static int findMatch(MediaAccount old, List<WebsiteSocialLink> rows, boolean[] used, String status) {
        for (int i = 0; i < rows.size(); i++) {
            WebsiteSocialLink item = rows.get(i);
            if (!used[i] && status.equals(item.getStatus())
                    && Objects.equals(old.getPlatform(), item.getPlatform())
                    && Objects.equals(old.getAccountName(), item.getAccountName())
                    && Objects.equals(old.getLink(), item.getLink())) return i;
        }
        return -1;
    }

    public record LegacyRow(Long legacyId, String platform, String accountName, String link,
                            Long websiteId, String matchStatus) {}
    public record WebsiteRow(Long websiteId, String platform, String accountName, String link, String status) {}
    public record ParityView(int legacyCount, int websiteCount, long publishedMatches, long draftMatches,
                             List<LegacyRow> legacyRows, List<WebsiteRow> unmatchedWebsiteRows) {}
}
