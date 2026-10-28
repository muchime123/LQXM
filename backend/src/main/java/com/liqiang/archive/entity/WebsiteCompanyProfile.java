package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Website-owned public company copy; never linked to the internal CompanyInfo row. */
@Entity
@Table(name = "website_company_profiles")
@Getter
@Setter
public class WebsiteCompanyProfile {
    @Id
    private Long id;

    @Column(name = "company_name", nullable = false, length = 300)
    private String companyName;
    @Column(name = "company_name_en", length = 300)
    private String companyNameEn;
    @Column(name = "brand_name", length = 200)
    private String brandName;
    @Column(name = "brand_name_en", length = 200)
    private String brandNameEn;
    @Column(name = "brand_slogan", length = 500)
    private String brandSlogan;
    @Column(columnDefinition = "TEXT")
    private String mission;
    @Column(columnDefinition = "TEXT")
    private String vision;
    @Column(name = "company_values", columnDefinition = "TEXT")
    private String values;
    @Column(name = "core_positioning", columnDefinition = "TEXT")
    private String corePositioning;
    @Column(name = "main_business", columnDefinition = "TEXT")
    private String mainBusiness;
    @Column(name = "target_industries", columnDefinition = "TEXT")
    private String targetIndustries;
    @Column(columnDefinition = "TEXT")
    private String advantages;
    @Column(name = "manufacturing_strengths", columnDefinition = "TEXT")
    private String manufacturingStrengths;
    @Column(name = "enterprise_scale", length = 200)
    private String enterpriseScale;
    @Column(name = "establishment_date")
    private LocalDate establishmentDate;
    @Column(name = "office_address", length = 500)
    private String officeAddress;
    @Column(name = "factory_address", length = 500)
    private String factoryAddress;
    @Column(length = 100)
    private String phone;
    @Column(length = 200)
    private String email;
    @Column(length = 500)
    private String website;
    @Column(name = "logo_media_id")
    private Long logoMediaId;
    @Column(nullable = false, length = 30)
    private String status = "DRAFT";
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
