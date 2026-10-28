package com.liqiang.archive.service;

import com.liqiang.archive.entity.CompanyInfo;
import com.liqiang.archive.entity.WebsiteCompanyProfile;
import com.liqiang.archive.repository.WebsiteCompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compares only the public company fields. This never copies archive data. */
@Service
@RequiredArgsConstructor
public class WebsiteCompanyProfileParityService {
    private final CompanyInfoService companyInfo;
    private final WebsiteCompanyProfileRepository profiles;

    @Transactional(readOnly = true)
    public ParityView compare() {
        CompanyInfo legacy = companyInfo.getCompanyInfo();
        WebsiteCompanyProfile draft = profiles.findById(1L).orElse(null);
        List<FieldDifference> fields = new ArrayList<>();
        add(fields, "companyName", legacy == null ? null : legacy.getCompanyName(), draft == null ? null : draft.getCompanyName());
        add(fields, "companyNameEn", legacy == null ? null : legacy.getCompanyNameEn(), draft == null ? null : draft.getCompanyNameEn());
        add(fields, "brandName", legacy == null ? null : legacy.getBrandName(), draft == null ? null : draft.getBrandName());
        add(fields, "brandNameEn", legacy == null ? null : legacy.getBrandNameEn(), draft == null ? null : draft.getBrandNameEn());
        add(fields, "brandSlogan", legacy == null ? null : legacy.getBrandSlogan(), draft == null ? null : draft.getBrandSlogan());
        add(fields, "mission", legacy == null ? null : legacy.getMission(), draft == null ? null : draft.getMission());
        add(fields, "vision", legacy == null ? null : legacy.getVision(), draft == null ? null : draft.getVision());
        add(fields, "values", legacy == null ? null : legacy.getValues(), draft == null ? null : draft.getValues());
        add(fields, "corePositioning", legacy == null ? null : legacy.getCorePositioning(), draft == null ? null : draft.getCorePositioning());
        add(fields, "mainBusiness", legacy == null ? null : legacy.getMainBusiness(), draft == null ? null : draft.getMainBusiness());
        add(fields, "targetIndustries", legacy == null ? null : legacy.getTargetIndustries(), draft == null ? null : draft.getTargetIndustries());
        add(fields, "advantages", legacy == null ? null : legacy.getAdvantages(), draft == null ? null : draft.getAdvantages());
        add(fields, "manufacturingStrengths", legacy == null ? null : legacy.getManufacturingStrengths(), draft == null ? null : draft.getManufacturingStrengths());
        add(fields, "enterpriseScale", legacy == null ? null : legacy.getEnterpriseScale(), draft == null ? null : draft.getEnterpriseScale());
        add(fields, "establishmentDate", legacy == null ? null : legacy.getEstablishmentDate(), draft == null ? null : draft.getEstablishmentDate());
        add(fields, "officeAddress", legacy == null ? null : legacy.getOfficeAddress(), draft == null ? null : draft.getOfficeAddress());
        add(fields, "factoryAddress", legacy == null ? null : legacy.getFactoryAddress(), draft == null ? null : draft.getFactoryAddress());
        add(fields, "phone", legacy == null ? null : legacy.getPhone(), draft == null ? null : draft.getPhone());
        add(fields, "email", legacy == null ? null : legacy.getEmail(), draft == null ? null : draft.getEmail());
        add(fields, "website", legacy == null ? null : legacy.getWebsite(), draft == null ? null : draft.getWebsite());
        long different = fields.stream().filter(FieldDifference::different).count();
        return new ParityView(legacy != null, draft != null, different, List.copyOf(fields));
    }

    private static void add(List<FieldDifference> fields, String key, Object legacy, Object draft) {
        String before = legacy == null ? null : legacy.toString();
        String after = draft == null ? null : draft.toString();
        fields.add(new FieldDifference(key, before, after, !Objects.equals(before, after)));
    }

    public record FieldDifference(String key, String legacy, String draft, boolean different) {}
    public record ParityView(boolean legacyExists, boolean draftExists, long differentCount,
                             List<FieldDifference> fields) {}
}
