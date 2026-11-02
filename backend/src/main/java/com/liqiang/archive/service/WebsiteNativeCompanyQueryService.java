package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Published website company copy only; never falls back to internal archive data. */
@Service
@RequiredArgsConstructor
public class WebsiteNativeCompanyQueryService {
    private final WebsiteCompanyProfileRepository profiles;

    @Transactional(readOnly = true)
    public WebsiteCompanyProfile published() {
        return profiles.findById(1L)
                .filter(profile -> "PUBLISHED".equals(profile.getStatus()))
                .orElse(null);
    }
}
