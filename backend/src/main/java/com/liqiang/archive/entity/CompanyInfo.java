package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "company_info")
public class CompanyInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "former_name", length = 200)
    private String formerName;

    @Column(name = "company_name_en", length = 300)
    private String companyNameEn;

    @Column(name = "brand_name", length = 100)
    private String brandName;

    @Column(name = "brand_name_en", length = 100)
    private String brandNameEn;

    @Column(name = "brand_slogan", length = 300)
    private String brandSlogan;

    @Column(columnDefinition = "TEXT")
    private String mission;

    @Column(columnDefinition = "TEXT")
    private String vision;

    @Column(name = "`values`", columnDefinition = "TEXT")
    private String values;

    @Column(columnDefinition = "TEXT")
    private String businessModel;

    @Column(columnDefinition = "TEXT")
    private String serviceCapabilities;

    @Column(columnDefinition = "TEXT")
    private String customerTypes;

    @Column(columnDefinition = "TEXT")
    private String applicationScenarios;

    @Column(name = "unified_social_code", length = 50)
    private String unifiedSocialCode;

    @Column(name = "legal_representative", length = 100)
    private String legalRepresentative;

    @Column(name = "company_type", length = 100)
    private String companyType;

    @Column(name = "registered_capital", length = 100)
    private String registeredCapital;

    @Column(name = "paid_capital", length = 100)
    private String paidCapital;

    @Column(name = "insured_count")
    private Integer insuredCount;

    @Column(name = "enterprise_scale", length = 50)
    private String enterpriseScale;

    @Column(name = "establishment_date")
    private LocalDate establishmentDate;

    @Column(name = "business_term", length = 100)
    private String businessTerm;

    @Column(name = "business_scope", columnDefinition = "TEXT")
    private String businessScope;

    @Column(name = "registered_address", length = 500)
    private String registeredAddress;

    @Column(name = "office_address", length = 500)
    private String officeAddress;

    @Column(name = "factory_address", length = 500)
    private String factoryAddress;

    @Column(length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 200)
    private String website;

    @Column(name = "core_positioning", length = 500)
    private String corePositioning;

    @Column(name = "main_business", columnDefinition = "TEXT")
    private String mainBusiness;

    @Column(name = "target_industries", columnDefinition = "TEXT")
    private String targetIndustries;

    @Column(columnDefinition = "TEXT")
    private String advantages;

    @Column(name = "manufacturing_strengths", columnDefinition = "TEXT")
    private String manufacturingStrengths;

    @Column(name = "revenue_device_pct", precision = 5, scale = 2)
    private BigDecimal revenueDevicePct;

    @Column(name = "revenue_consumable_pct", precision = 5, scale = 2)
    private BigDecimal revenueConsumablePct;

    @Column(name = "license_file_name", length = 500)
    private String licenseFileName;

    @Column(name = "seal_file_name", length = 500)
    private String sealFileName;

    @Column(name = "logo_file_name", length = 500)
    private String logoFileName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CompanyInfo() {
    }
}
