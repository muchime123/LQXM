package com.liqiang.archive.dto;

import com.liqiang.archive.entity.Customer;
import com.liqiang.archive.entity.CustomerContact;
import com.liqiang.archive.entity.CustomerSalespersonRelation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.Map;

public class CustomerDtos {

    // ==================== 列表摘要 ====================

    /** 列表用轻量 DTO（枚举已翻译为中文）。contactName/contactMobile 为主要联系人信息，可能为 null。 */
    public record CustomerSummary(
            Long id,
            String customerCode,
            String customerName,
            String shortName,
            String customerType,
            String industry,
            String primarySalesperson,
            String contactName,
            String contactMobile,
            String province,
            String city,
            String district,
            String relationStatus,
            String status,
            LocalDateTime createdAt
    ) {
        public static CustomerSummary from(Customer c, String primarySalesperson, String contactName, String contactMobile, Function<String, String> translate) {
            return new CustomerSummary(
                    c.getId(),
                    c.getCustomerCode(),
                    c.getCustomerName(),
                    c.getShortName(),
                    translate.apply(c.getCustomerType()),
                    c.getIndustry(),
                    primarySalesperson,
                    contactName,
                    contactMobile,
                    c.getProvince(),
                    c.getCity(),
                    c.getDistrict(),
                    translate.apply(c.getRelationStatus()),
                    translate.apply(c.getStatus()),
                    c.getCreatedAt()
            );
        }
    }

    // ==================== 详情 ====================

    /** 客户详情（基础字段，联系人/业务员/统计通过子资源按需加载）。 */
    public record CustomerDetail(
            Long id,
            String customerCode,
            String customerName,
            String shortName,
            String customerType,
            String industry,
            String creditCode,
            String province,
            String city,
            String district,
            String address,
            String website,
            String source,
            String level,
            String relationStatus,
            String status,
            String remark,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static CustomerDetail from(Customer c, Function<String, String> translate) {
            return new CustomerDetail(
                    c.getId(),
                    c.getCustomerCode(),
                    c.getCustomerName(),
                    c.getShortName(),
                    translate.apply(c.getCustomerType()),
                    c.getIndustry(),
                    c.getCreditCode(),
                    c.getProvince(),
                    c.getCity(),
                    c.getDistrict(),
                    c.getAddress(),
                    c.getWebsite(),
                    c.getSource(),
                    c.getLevel(),
                    translate.apply(c.getRelationStatus()),
                    translate.apply(c.getStatus()),
                    c.getRemark(),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }

    // ==================== 创建/更新入参 ====================

    /** 前端提交的客户数据（枚举字段传中文，Service 转 DB 英文）。 */
    public record CustomerPayload(
            String customerName,
            String shortName,
            String customerType,
            String industry,
            String creditCode,
            String province,
            String city,
            String district,
            String address,
            String website,
            String source,
            String level,
            String relationStatus,
            String status,
            String remark
    ) {
        /** 将 Payload 写入 Entity（仅覆盖非空字段）。 */
        public void applyTo(Customer c) {
            if (customerName != null) c.setCustomerName(customerName);
            if (shortName != null) c.setShortName(shortName);
            if (customerType != null) c.setCustomerType(customerType);
            if (industry != null) c.setIndustry(industry);
            if (creditCode != null) c.setCreditCode(creditCode);
            if (province != null) c.setProvince(province);
            if (city != null) c.setCity(city);
            if (district != null) c.setDistrict(district);
            if (address != null) c.setAddress(address);
            if (website != null) c.setWebsite(website);
            if (source != null) c.setSource(source);
            if (level != null) c.setLevel(level);
            if (relationStatus != null) c.setRelationStatus(relationStatus);
            if (status != null) c.setStatus(status);
            if (remark != null) c.setRemark(remark);
        }
    }

    // ==================== 联系人 ====================

    public record ContactSummary(
            Long id,
            Long customerId,
            String name,
            String department,
            String position,
            String mobile,
            String wechat,
            String email,
            String contactType,
            Boolean isPrimary,
            String remark
    ) {
        public static ContactSummary from(CustomerContact c) {
            return new ContactSummary(
                    c.getId(),
                    c.getCustomerId(),
                    c.getName(),
                    c.getDepartment(),
                    c.getPosition(),
                    c.getMobile(),
                    c.getWechat(),
                    c.getEmail(),
                    c.getContactType(),
                    c.getIsPrimary(),
                    c.getRemark()
            );
        }
    }

    public record ContactPayload(
            String name,
            String department,
            String position,
            String mobile,
            String wechat,
            String email,
            String contactType,
            Boolean isPrimary,
            String remark
    ) {
        public void applyTo(CustomerContact c) {
            if (name != null) c.setName(name);
            if (department != null) c.setDepartment(department);
            if (position != null) c.setPosition(position);
            if (mobile != null) c.setMobile(mobile);
            if (wechat != null) c.setWechat(wechat);
            if (email != null) c.setEmail(email);
            if (contactType != null) c.setContactType(contactType);
            if (isPrimary != null) c.setIsPrimary(isPrimary);
            if (remark != null) c.setRemark(remark);
        }
    }

    public record AddressSummary(Long id, Long customerId, String addressName, String contactName,
                                 String contactPhone, String province, String city, String district,
                                 String detailAddress, Boolean isDefault, String source, String remark,
                                 long usageCount, LocalDate firstUsedDate, LocalDate lastUsedDate,
                                 LocalDateTime createdAt) {}

    public record AddressPayload(String addressName, String contactName, String contactPhone,
                                 String province, String city, String district, String detailAddress,
                                 Boolean isDefault, String remark) {}

    public record MergeHistorySummary(Long id, Long mergedCustomerId, String mergedCustomerCode,
                                      String mergedCustomerName, String mergedAddress,
                                      String mergeReason, LocalDateTime mergedAt) {}

    /**
     * 氚云原始客户身份与当前正式客户之间的可追溯映射。
     * 业务覆盖、电话和地址直接来自已经关联到该客户的销售出库事实，便于解释“为什么归到这里”。
     */
    public record SourceIdentitySummary(Long id, String sourceSystem, String sourceKey,
                                        String sourceName, String resolutionStatus,
                                        BigDecimal confidence, Long occurrenceCount,
                                        String resolutionNote, LocalDateTime resolvedAt,
                                        long outboundCount, LocalDate firstOutboundDate,
                                        LocalDate lastOutboundDate, List<String> phones,
                                        List<String> addresses) {}

    // ==================== 业务员关系 ====================

    public record SalespersonSummary(
            Long id,
            Long customerId,
            Long salespersonId,
            String salespersonName,
            String role,
            LocalDate startDate,
            LocalDate endDate
    ) {
        public static SalespersonSummary from(CustomerSalespersonRelation r) {
            return new SalespersonSummary(
                    r.getId(),
                    r.getCustomerId(),
                    r.getSalespersonId(),
                    r.getSalespersonName(),
                    r.getRole(),
                    r.getStartDate(),
                    r.getEndDate()
            );
        }
    }

    public record SalespersonPayload(
            Long salespersonId,
            String salespersonName,
            String role,
            LocalDate startDate,
            LocalDate endDate
    ) {
        public void applyTo(CustomerSalespersonRelation r) {
            if (salespersonId != null) r.setSalespersonId(salespersonId);
            if (salespersonName != null) r.setSalespersonName(salespersonName);
            if (role != null) r.setRole(role);
            if (startDate != null) r.setStartDate(startDate);
            if (endDate != null) r.setEndDate(endDate);
        }
    }

    // ==================== 详情页概览统计 ====================

    /**
     * 客户详情「概览」Tab 的自动聚合卡片。
     * P1 无合同表，合同数/金额/日期返回 0/null；P2 接入 sales_contracts 后实时计算。
     */
    public record CustomerOverview(
            long outboundCount,
            BigDecimal totalSalesAmount,
            LocalDate firstOutboundDate,
            LocalDate lastOutboundDate,
            List<String> purchasedProducts,
            List<SalespersonSummary> salespersons,
            List<ContactSummary> contacts
    ) {
    }

    // ==================== 筛选可用值 ====================

    /**
     * 客户档案编辑时的「销售出库参考」：把出库单里可用来补全档案的信息汇总给前端，
     * 由人工决定是否带入（地区标注、收货地址、联系电话、经销商、上市时间等）。
     */
    public record OutboundReference(
            long orderCount,
            /** 最近一次出库日期（发货日期优先、业务日期兜底） */
            LocalDate lastShipDate,
            /** 最近一单的发货方式，如「自提」「浙江胜宇国际货运」 */
            String lastShippingMethod,
            /** 出库单「地区」标注去重值（省级粒度，如「浙江省（浙）」「国外」） */
            List<String> regions,
            /** 出库单上的客户详细地址去重值（按最近出库倒序） */
            List<String> addresses,
            /** 出库单「联系方式」去重值 */
            List<String> contactPhones,
            /** 「选择经销商」去重值 */
            List<String> dealerNames,
            /** 建议省份：地区标注或地址能解析出的唯一官方省名（「境外」表示判定为境外客户） */
            String suggestedProvince
    ) {
    }

    public record FilterOptions(
            List<String> customerTypes,
            List<String> relationStatuses,
            List<String> statuses,
            List<String> industries,
            List<String> provinces,
            List<String> salespersons
    ) {
    }

    // ==================== 区域分布 ====================

    /** 某区县客户数（name 为区划字典归一后的名称）。 */
    public record DistrictDistribution(
            String name,
            long count
    ) {
    }

    /** 某市客户数（name 为规范名称）；districts 为该市已细分到区县的分布。 */
    public record CityDistribution(
            String name,
            long count,
            /** 已定位到该市、但尚未细分到有效区县的客户数。 */
            long unassignedDistrictCount,
            List<DistrictDistribution> districts
    ) {
    }

    /**
     * 某省级行政区客户聚合。
     * name 为清洗后的规范全名（与地图匹配，如 浙江省 / 广西壮族自治区）；
     * filterValue 与 name 均为规范省名，用于列表与地图保持同一筛选口径。
     */
    public record ProvinceDistribution(
            String name,
            String filterValue,
            long count,
            /** 已定位到该省、但尚未细分到有效城市的客户数。 */
            long unassignedCityCount,
            List<CityDistribution> cities
    ) {
    }

    /** 客户区域分布聚合结果：覆盖客户数 / 已标注省份数 / 未标注地区数 / 境外客户数 / 省（含市）排行。 */
    public record CustomerDistribution(
            long totalCount,
            long locatedCount,
            /** 已定位到有效城市的客户数（locatedCount 的子集）。 */
            long cityLocatedCount,
            /** 已定位到有效区县的客户数（cityLocatedCount 的子集）。 */
            long districtLocatedCount,
            /** 省份已合法，但市县缺失或治理状态异常的客户数。 */
            long partialRegionCount,
            long noRegionCount,
            /** 已判定为境外的客户数（province=「境外」，不上地图、不参与地区治理） */
            long overseasCount,
            int provinceCount,
            List<ProvinceDistribution> provinces
    ) {
    }

}
