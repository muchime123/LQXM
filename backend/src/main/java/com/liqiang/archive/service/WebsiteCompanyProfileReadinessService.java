package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only gate for a future website-owned company profile publication. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyProfileReadinessService {
    private static final long PRIMARY_PROFILE_ID = 1L;

    private final WebsiteCompanyProfileRepository profiles;
    private final WebsiteMediaAssetRepository media;

    @Value("${app.files.root}")
    private String fileRoot;

    @Transactional(readOnly = true)
    public ReadinessView check() {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        WebsiteCompanyProfile profile = profiles.findById(PRIMARY_PROFILE_ID).orElse(null);
        if (profile == null) {
            blockers.add("尚无官网独立公司介绍草稿");
            return new ReadinessView(false, blockers, warnings);
        }
        if (blank(profile.getCompanyName())) blockers.add("公开公司名称为空");
        if (blank(profile.getMainBusiness())) blockers.add("主营业务介绍为空");
        if (blank(profile.getPhone()) && blank(profile.getEmail())) blockers.add("至少填写一个公开联系方式");
        if (blank(profile.getOfficeAddress()) && blank(profile.getFactoryAddress())) {
            blockers.add("至少填写一个公开地址");
        }
        checkLogo(profile.getLogoMediaId(), blockers);
        if (blank(profile.getCompanyNameEn())) warnings.add("英文公司名称待审阅");
        if (blank(profile.getBrandName())) warnings.add("品牌名称待审阅");
        if (profile.getEstablishmentDate() == null) warnings.add("成立日期待审阅");
        return new ReadinessView(blockers.isEmpty(), List.copyOf(blockers), List.copyOf(warnings));
    }

    private void checkLogo(Long id, List<String> blockers) {
        if (id == null) {
            blockers.add("官网 Logo 未设置");
            return;
        }
        WebsiteMediaAsset asset = media.findByIdAndDeletedAtIsNull(id).orElse(null);
        if (asset == null || !"IMAGE".equals(asset.getMediaType()) || !"PUBLISHED".equals(asset.getStatus())) {
            blockers.add("官网 Logo 不是已发布的官网图片");
            return;
        }
        if (blank(asset.getStorageKey())) {
            blockers.add("官网 Logo 文件路径为空");
            return;
        }
        Path root = Path.of(fileRoot).toAbsolutePath().normalize();
        Path websiteRoot = root.resolve("website");
        Path file = root.resolve(asset.getStorageKey()).normalize();
        try {
            if (!file.startsWith(websiteRoot) || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(websiteRoot.toRealPath())) {
                blockers.add("官网 Logo 文件不可读取或不在官网目录");
            }
        } catch (IOException | RuntimeException e) {
            blockers.add("官网 Logo 文件不可读取或不在官网目录");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ReadinessView(boolean ready, List<String> blockers, List<String> warnings) {}
}
