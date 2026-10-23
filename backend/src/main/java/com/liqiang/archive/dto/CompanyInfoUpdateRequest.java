package com.liqiang.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Fields owned by the company profile editor. File references are managed separately. */
@Getter
@Setter
public class CompanyInfoUpdateRequest {
    private final java.util.Set<String> supplied = new java.util.HashSet<>();
    public boolean has(String field) { return supplied.contains(field); }
    private String companyName;
    public void setCompanyName(String value) { this.companyName = value; supplied.add("companyName"); }
    private String formerName;
    public void setFormerName(String value) { this.formerName = value; supplied.add("formerName"); }
    private String companyNameEn;
    public void setCompanyNameEn(String value) { this.companyNameEn = value; supplied.add("companyNameEn"); }
    private String brandName;
    public void setBrandName(String value) { this.brandName = value; supplied.add("brandName"); }
    private String brandNameEn;
    public void setBrandNameEn(String value) { this.brandNameEn = value; supplied.add("brandNameEn"); }
    private String brandSlogan;
    public void setBrandSlogan(String value) { this.brandSlogan = value; supplied.add("brandSlogan"); }
    private String mission;
    public void setMission(String value) { this.mission = value; supplied.add("mission"); }
    private String vision;
    public void setVision(String value) { this.vision = value; supplied.add("vision"); }
    private String values;
    public void setValues(String value) { this.values = value; supplied.add("values"); }
    private String businessModel;
    public void setBusinessModel(String value) { this.businessModel = value; supplied.add("businessModel"); }
    private String serviceCapabilities;
    public void setServiceCapabilities(String value) { this.serviceCapabilities = value; supplied.add("serviceCapabilities"); }
    private String customerTypes;
    public void setCustomerTypes(String value) { this.customerTypes = value; supplied.add("customerTypes"); }
    private String applicationScenarios;
    public void setApplicationScenarios(String value) { this.applicationScenarios = value; supplied.add("applicationScenarios"); }
    private String unifiedSocialCode;
    public void setUnifiedSocialCode(String value) { this.unifiedSocialCode = value; supplied.add("unifiedSocialCode"); }
    private String legalRepresentative;
    public void setLegalRepresentative(String value) { this.legalRepresentative = value; supplied.add("legalRepresentative"); }
    private String companyType;
    public void setCompanyType(String value) { this.companyType = value; supplied.add("companyType"); }
    private String registeredCapital;
    public void setRegisteredCapital(String value) { this.registeredCapital = value; supplied.add("registeredCapital"); }
    private String paidCapital;
    public void setPaidCapital(String value) { this.paidCapital = value; supplied.add("paidCapital"); }
    private Integer insuredCount;
    public void setInsuredCount(Integer value) { this.insuredCount = value; supplied.add("insuredCount"); }
    private String enterpriseScale;
    public void setEnterpriseScale(String value) { this.enterpriseScale = value; supplied.add("enterpriseScale"); }
    private LocalDate establishmentDate;
    public void setEstablishmentDate(LocalDate value) { this.establishmentDate = value; supplied.add("establishmentDate"); }
    private String businessTerm;
    public void setBusinessTerm(String value) { this.businessTerm = value; supplied.add("businessTerm"); }
    private String businessScope;
    public void setBusinessScope(String value) { this.businessScope = value; supplied.add("businessScope"); }
    private String registeredAddress;
    public void setRegisteredAddress(String value) { this.registeredAddress = value; supplied.add("registeredAddress"); }
    private String officeAddress;
    public void setOfficeAddress(String value) { this.officeAddress = value; supplied.add("officeAddress"); }
    private String factoryAddress;
    public void setFactoryAddress(String value) { this.factoryAddress = value; supplied.add("factoryAddress"); }
    private String phone;
    public void setPhone(String value) { this.phone = value; supplied.add("phone"); }
    private String email;
    public void setEmail(String value) { this.email = value; supplied.add("email"); }
    private String website;
    public void setWebsite(String value) { this.website = value; supplied.add("website"); }
    private String corePositioning;
    public void setCorePositioning(String value) { this.corePositioning = value; supplied.add("corePositioning"); }
    private String mainBusiness;
    public void setMainBusiness(String value) { this.mainBusiness = value; supplied.add("mainBusiness"); }
    private String targetIndustries;
    public void setTargetIndustries(String value) { this.targetIndustries = value; supplied.add("targetIndustries"); }
    private String advantages;
    public void setAdvantages(String value) { this.advantages = value; supplied.add("advantages"); }
    private BigDecimal revenueDevicePct;
    public void setRevenueDevicePct(BigDecimal value) { this.revenueDevicePct = value; supplied.add("revenueDevicePct"); }
    private BigDecimal revenueConsumablePct;
    public void setRevenueConsumablePct(BigDecimal value) { this.revenueConsumablePct = value; supplied.add("revenueConsumablePct"); }
}
