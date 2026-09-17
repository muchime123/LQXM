package com.liqiang.archive.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liqiang.archive.dto.CustomerDtos.CityDistribution;
import com.liqiang.archive.dto.CustomerDtos.DistrictDistribution;
import com.liqiang.archive.dto.CustomerDtos.ContactPayload;
import com.liqiang.archive.dto.CustomerDtos.ContactSummary;
import com.liqiang.archive.dto.CustomerDtos.CustomerDetail;
import com.liqiang.archive.dto.CustomerDtos.CustomerDistribution;
import com.liqiang.archive.dto.CustomerDtos.CustomerOverview;
import com.liqiang.archive.dto.CustomerDtos.OutboundReference;
import com.liqiang.archive.dto.CustomerDtos.CustomerPayload;
import com.liqiang.archive.dto.CustomerDtos.CustomerSummary;
import com.liqiang.archive.dto.CustomerDtos.FilterOptions;
import com.liqiang.archive.dto.CustomerDtos.ProvinceDistribution;
import com.liqiang.archive.dto.CustomerDtos.SalespersonPayload;
import com.liqiang.archive.dto.CustomerDtos.SalespersonSummary;
import com.liqiang.archive.dto.CustomerDtos.AddressPayload;
import com.liqiang.archive.dto.CustomerDtos.AddressSummary;
import com.liqiang.archive.dto.CustomerDtos.MergeHistorySummary;
import com.liqiang.archive.dto.CustomerDtos.SourceIdentitySummary;
import com.liqiang.archive.entity.Customer;
import com.liqiang.archive.entity.CustomerContact;
import com.liqiang.archive.entity.CustomerRelationStatus;
import com.liqiang.archive.entity.CustomerSalespersonRelation;
import com.liqiang.archive.entity.CustomerType;
import com.liqiang.archive.repository.CustomerContactRepository;
import com.liqiang.archive.repository.CustomerRepository;
import com.liqiang.archive.repository.CustomerSalespersonRelationRepository;
import com.liqiang.archive.util.ChinaRegionDictionary;
import com.liqiang.archive.util.StringUtils;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final CustomerSalespersonRelationRepository relationRepository;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;
    private final CustomerAddressTidyService addressTidyService;

    /** 通用批量更新允许修改的字段白名单（名称/编号不批量改，避免误改主档案）。 */
    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "customerType", "industry", "province", "city", "district",
            "source", "level", "relationStatus", "status", "remark"
    );

    private static final String ROLE_PRIMARY = "PRIMARY";
    private static final String ROLE_HISTORY = "HISTORY";
    private static final String ROLE_COLLABORATOR = "COLLABORATOR";

    /** 「未标注地区」：省份为空或不是官方省名（无法落到地图上）。参数需追加 {@code officialProvinces()}。 */
    private static final String NO_REGION_COND = "(c.province IS NULL OR c.province = ''"
            + " OR (c.province NOT IN (" + ChinaRegionDictionary.officialProvincePlaceholders() + ")"
            + "     AND c.province <> '" + ChinaRegionDictionary.OVERSEAS_PROVINCE + "'))";

    /** 「待补市县」：省份已合法，但市/区县缺失或治理状态非 NORMAL。参数需追加 {@code officialProvinces()}。 */
    private static final String PARTIAL_REGION_COND = "(c.province IS NOT NULL AND c.province <> ''"
            + " AND c.province IN (" + ChinaRegionDictionary.officialProvincePlaceholders() + ")"
            + " AND (c.city IS NULL OR c.city = '' OR c.district IS NULL OR c.district = ''"
            + "      OR c.address_tidy_status <> 'NORMAL'))";

    // ==================== 枚举转换 ====================

    /** 前端中文 → DB 英文（用于入参与搜索筛选）。 */
    private static String toDbType(String value) {
        if (value == null || value.isBlank()) return null;
        String dbValue = CustomerType.toDbValue(value.trim());
        return dbValue != null ? dbValue : value.trim();
    }

    private static String toDbRelationStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String dbValue = CustomerRelationStatus.toDbValue(value.trim());
        return dbValue != null ? dbValue : value.trim();
    }

    /** 前端中文（启用/停用）→ DB 英文（ACTIVE/INACTIVE）。 */
    private static String toDbStatus(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim()) {
            case "启用" -> "ACTIVE";
            case "停用" -> "INACTIVE";
            default -> value.trim();
        };
    }

    /** DB 英文 → 前端中文（列表/详情展示）。 */
    private static String toLabel(String dbValue) {
        if (dbValue == null) return null;
        String label = CustomerType.toDisplayName(dbValue);
        if (label != null && !label.equals(dbValue)) return label;
        label = CustomerRelationStatus.toDisplayName(dbValue);
        if (label != null && !label.equals(dbValue)) return label;
        return switch (dbValue) {
            case "ACTIVE" -> "启用";
            case "INACTIVE" -> "停用";
            case "KEY" -> "重点";
            case "COMMON" -> "普通";
            default -> dbValue;
        };
    }

    private static String statusDb(String value) {
        return value == null ? null : switch (value) {
            case "启用" -> "ACTIVE";
            case "停用" -> "INACTIVE";
            default -> value;
        };
    }

    // ==================== 区域口径（统一走 ChinaRegionDictionary 字典） ====================

    /** 清洗客户档案中的省份原值 → 规范全名（系统字典口径）；无法识别的写法原样返回（计入排行但不上地图）。 */
    private static String canonicalProvince(String raw) {
        return ChinaRegionDictionary.canonicalProvinceName(raw);
    }

    /** 归一后的（省、市、区县）三元组。 */
    private record RegionValue(String province, String city, String district) {
    }

    /**
     * 把档案地区三元组收敛到区划字典规范全名（简称补齐、繁体归一）；空值语义保留：
     * null 表示未提交该字段，空串保留以便显式清空；无法识别写法简化后原样返回。
     */
    private static RegionValue standardRegion(String province, String city, String district) {
        String p = canonicalField(province, ChinaRegionDictionary::canonicalProvinceName);
        String c = canonicalField(city, v -> ChinaRegionDictionary.canonicalCityName(v, p));
        String d = canonicalField(district, v -> ChinaRegionDictionary.canonicalDistrictName(v, p));
        return new RegionValue(p, c, d);
    }

    private static String canonicalField(String raw, java.util.function.Function<String, String> canonical) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return raw;
        return canonical.apply(t);
    }

    // ==================== 查询 ====================

    /**
     * 客户主列表搜索。联系人/收货地址/业务员均支持"档案 + 销售出库"双口径命中：
     * - salesperson：业务员关系表（customer_salesperson_relations）或已确认出库（sales_outbounds.salesperson_name）
     * - contact：联系人档案（customer_contacts 姓名/手机/微信）
     * - address：收货地址（customer_addresses）或出库单上的地区/客户地址/联系电话
     *
     * <p>地区口径：{@code noRegion=true} 为「未标注地区」——省份为空或不是官方省名（上不了地图）；
     * {@code regionPartial=true} 为「待补市县」——省份已合法但市/区县缺失或地址解析状态非 NORMAL。
     * 二者互斥，省份已合法但缺市县不再计入未标注地区。
     */
    @Transactional
    public Page<CustomerSummary> search(
            String customerType, String industry, String province, String city, String district,
            String relationStatus, String status, String salesperson,
            String keyword, Boolean noRegion, Boolean regionPartial,
            String contact, String address, Pageable pageable) {
        boolean filterNoRegion = Boolean.TRUE.equals(noRegion);
        boolean filterPartial = !filterNoRegion && Boolean.TRUE.equals(regionPartial);
        if (filterNoRegion || filterPartial) addressTidyService.scanStatuses();

        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        conds.add("c.deleted_at IS NULL");

        String dbType = toDbType(customerType);
        if (dbType != null) {
            conds.add("c.customer_type = ?");
            params.add(dbType);
        }
        String dbIndustry = StringUtils.blankToNull(industry);
        if (dbIndustry != null) {
            conds.add("c.industry = ?");
            params.add(dbIndustry);
        }
        if (filterNoRegion) {
            conds.add(NO_REGION_COND);
            params.addAll(ChinaRegionDictionary.officialProvinces());
        } else if (filterPartial) {
            conds.add(PARTIAL_REGION_COND);
            params.addAll(ChinaRegionDictionary.officialProvinces());
        } else {
            String p = StringUtils.blankToNull(province);
            if (p != null) {
                conds.add("c.province = ?");
                params.add(p);
            }
            String cy = StringUtils.blankToNull(city);
            if (cy != null) {
                conds.add("c.city = ?");
                params.add(cy);
            }
            String d = StringUtils.blankToNull(district);
            if (d != null) {
                conds.add("c.district = ?");
                params.add(d);
            }
        }
        String dbRelation = toDbRelationStatus(relationStatus);
        if (dbRelation != null) {
            conds.add("c.relation_status = ?");
            params.add(dbRelation);
        }
        String dbStatus = toDbStatus(status);
        if (dbStatus != null) {
            conds.add("c.status = ?");
            params.add(dbStatus);
        }

        String sp = StringUtils.blankToNull(salesperson);
        if (sp != null) {
            conds.add("("
                    + "EXISTS (SELECT 1 FROM customer_salesperson_relations r "
                    + "        WHERE r.customer_id = c.id AND r.salesperson_name = ?) "
                    + "OR EXISTS (SELECT 1 FROM sales_outbounds so "
                    + "        WHERE so.customer_id = c.id AND so.outbound_status='CONFIRMED' AND so.salesperson_name = ?)"
                    + ")");
            params.add(sp);
            params.add(sp);
        }

        String kw = StringUtils.blankToNull(keyword);
        if (kw != null) {
            String like = "%" + kw + "%";
            conds.add("("
                    + "c.customer_name LIKE ? OR c.short_name LIKE ? OR c.customer_code LIKE ? OR c.credit_code LIKE ? "
                    + "OR EXISTS (SELECT 1 FROM customer_contacts cc "
                    + "        WHERE cc.customer_id = c.id AND (cc.name LIKE ? OR cc.mobile LIKE ?))"
                    + ")");
            for (int i = 0; i < 6; i++) params.add(like);
        }

        String co = StringUtils.blankToNull(contact);
        if (co != null) {
            String like = "%" + co + "%";
            conds.add("EXISTS (SELECT 1 FROM customer_contacts cc "
                    + "WHERE cc.customer_id = c.id "
                    + "AND (cc.name LIKE ? OR cc.mobile LIKE ? OR cc.wechat LIKE ?))");
            for (int i = 0; i < 3; i++) params.add(like);
        }

        String ad = StringUtils.blankToNull(address);
        if (ad != null) {
            String like = "%" + ad + "%";
            conds.add("("
                    + "EXISTS (SELECT 1 FROM customer_addresses a "
                    + "        WHERE a.customer_id = c.id "
                    + "        AND (COALESCE(a.province, '') LIKE ? OR COALESCE(a.city, '') LIKE ? "
                    + "             OR COALESCE(a.district, '') LIKE ? OR COALESCE(a.address_name, '') LIKE ? "
                    + "             OR COALESCE(a.detail_address, '') LIKE ? OR COALESCE(a.contact_name, '') LIKE ? "
                    + "             OR COALESCE(a.contact_phone, '') LIKE ?)) "
                    + "OR EXISTS (SELECT 1 FROM sales_outbounds so2 "
                    + "        WHERE so2.customer_id = c.id AND so2.outbound_status='CONFIRMED' "
                    + "        AND (COALESCE(so2.region, '') LIKE ? "
                    + "             OR COALESCE(so2.customer_address, '') LIKE ? OR COALESCE(so2.contact_phone, '') LIKE ?))"
                    + ")");
            for (int i = 0; i < 10; i++) params.add(like);
        }

        String where = " WHERE " + String.join(" AND ", conds);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customers c" + where, Long.class, params.toArray());

        int size = pageable.getPageSize() > 0 ? Math.min(pageable.getPageSize(), 200) : 20;
        long offset = (long) pageable.getPageNumber() * size;
        List<Sort.Order> orders = pageable.getSort().stream().toList();
        Sort.Order sortOrder = orders.isEmpty() ? null : orders.get(0);
        String column = switch (sortOrder == null || sortOrder.getProperty() == null
                ? "" : sortOrder.getProperty()) {
            case "name", "customerName" -> "c.customer_name";
            case "shortName" -> "c.short_name";
            case "createdAt" -> "c.created_at";
            default -> "c.customer_code";
        };
        String direction = sortOrder != null && sortOrder.isDescending() ? "DESC" : "ASC";

        String pageSql = "SELECT c.id FROM customers c" + where
                + " ORDER BY " + column + " " + direction + " LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(offset);
        List<Long> ids = jdbc.queryForList(pageSql, Long.class, pageParams.toArray());

        List<CustomerSummary> content;
        if (ids.isEmpty()) {
            content = List.of();
        } else {
            Map<Long, Customer> byId = customerRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(Customer::getId, Function.identity()));
            List<Customer> ordered = new ArrayList<>(ids.size());
            for (Long id : ids) {
                Customer c = byId.get(id);
                if (c != null) ordered.add(c);
            }
            Map<Long, String> primarySalespersons = collectPrimarySalespersons(ordered);
            Map<Long, CustomerContact> primaryContacts = collectPrimaryContacts(ordered);
            content = ordered.stream().map(c -> CustomerSummary.from(
                    c,
                    primarySalespersons.get(c.getId()),
                    primaryContacts.get(c.getId()) == null ? null : primaryContacts.get(c.getId()).getName(),
                    primaryContacts.get(c.getId()) == null ? null : primaryContacts.get(c.getId()).getMobile(),
                    CustomerService::toLabel)).toList();
        }
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public CustomerDetail get(Long id) {
        return customerRepository.findById(id)
                .map(c -> CustomerDetail.from(c, CustomerService::toLabel))
                .orElseThrow(() -> new EntityNotFoundException("客户不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<AddressSummary> getAddresses(Long customerId) {
        requireCustomer(customerId);
        return jdbc.query("""
            SELECT a.id,a.customer_id,a.address_name,a.contact_name,a.contact_phone,a.province,a.city,a.district,
                   a.detail_address,a.is_default,a.source,a.remark,a.created_at,
                   COALESCE(u.usage_count,0) usage_count,u.first_used_date,u.last_used_date
            FROM customer_addresses a
            LEFT JOIN (
              SELECT customer_id,
                     LOWER(REGEXP_REPLACE(
                       REGEXP_REPLACE(REGEXP_REPLACE(TRIM(customer_address),'【[[:space:]]*[0-9]{3,8}】[[:space:]]*$',''),'\\\\[[[:space:]]*[0-9]{3,8}\\\\]$',''),
                       '[[:space:],，。.;；、]+','')) normalized_address,
                     COUNT(*) usage_count,MIN(COALESCE(ship_date,business_date)) first_used_date,
                     MAX(COALESCE(ship_date,business_date)) last_used_date
              FROM sales_outbounds
              WHERE customer_id=? AND outbound_status='CONFIRMED'
                AND customer_address IS NOT NULL AND TRIM(customer_address)<>''
              GROUP BY customer_id,LOWER(REGEXP_REPLACE(
                REGEXP_REPLACE(REGEXP_REPLACE(TRIM(customer_address),'【[[:space:]]*[0-9]{3,8}】[[:space:]]*$',''),'\\\\[[[:space:]]*[0-9]{3,8}\\\\]$',''),
                '[[:space:],，。.;；、]+',''))
            ) u ON u.customer_id=a.customer_id
               AND u.normalized_address=LOWER(REGEXP_REPLACE(TRIM(a.detail_address),'[[:space:],，。.;；、]+',''))
            WHERE a.customer_id=? ORDER BY a.is_default DESC,u.last_used_date DESC,a.id
            """, (rs, n) -> new AddressSummary(rs.getLong("id"), rs.getLong("customer_id"),
                rs.getString("address_name"), rs.getString("contact_name"), rs.getString("contact_phone"),
                rs.getString("province"), rs.getString("city"), rs.getString("district"),
                rs.getString("detail_address"), rs.getBoolean("is_default"), rs.getString("source"),
                rs.getString("remark"), rs.getLong("usage_count"),
                rs.getDate("first_used_date") == null ? null : rs.getDate("first_used_date").toLocalDate(),
                rs.getDate("last_used_date") == null ? null : rs.getDate("last_used_date").toLocalDate(),
                rs.getTimestamp("created_at").toLocalDateTime()), customerId, customerId);
    }

    @Transactional
    public AddressSummary addAddress(Long customerId, AddressPayload payload) {
        requireCustomer(customerId);
        String detail = StringUtils.blankToNull(payload.detailAddress());
        if (detail == null) throw new IllegalArgumentException("详细地址不能为空");
        if (Boolean.TRUE.equals(payload.isDefault()) || jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_addresses WHERE customer_id=?", Integer.class, customerId) == 0) {
            jdbc.update("UPDATE customer_addresses SET is_default=0 WHERE customer_id=?", customerId);
        }
        RegionValue region = standardRegion(payload.province(), payload.city(), payload.district());
        jdbc.update("""
            INSERT INTO customer_addresses(customer_id,address_name,contact_name,contact_phone,province,city,district,
              detail_address,normalized_address,is_default,source,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,'MANUAL',?)
            """, customerId, payload.addressName(), payload.contactName(), payload.contactPhone(), region.province(),
                region.city(), region.district(), detail, normalizeAddress(detail),
                Boolean.TRUE.equals(payload.isDefault()) ? 1 : 0, payload.remark());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        syncDefaultAddress(customerId);
        auditLogService.log("Customer", customerId, "收货地址", "CUST_ADDRESS_CREATE", detail);
        return getAddresses(customerId).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public AddressSummary updateAddress(Long customerId, Long addressId, AddressPayload payload) {
        requireAddress(customerId, addressId);
        String detail = StringUtils.blankToNull(payload.detailAddress());
        if (detail == null) throw new IllegalArgumentException("详细地址不能为空");
        if (Boolean.TRUE.equals(payload.isDefault())) jdbc.update("UPDATE customer_addresses SET is_default=0 WHERE customer_id=?", customerId);
        RegionValue region = standardRegion(payload.province(), payload.city(), payload.district());
        jdbc.update("""
            UPDATE customer_addresses SET address_name=?,contact_name=?,contact_phone=?,province=?,city=?,district=?,
              detail_address=?,normalized_address=?,is_default=?,remark=?,updated_at=NOW()
            WHERE id=? AND customer_id=?
            """, payload.addressName(), payload.contactName(), payload.contactPhone(), region.province(), region.city(),
                region.district(), detail, normalizeAddress(detail), Boolean.TRUE.equals(payload.isDefault()) ? 1 : 0,
                payload.remark(), addressId, customerId);
        ensureDefault(customerId); syncDefaultAddress(customerId);
        return getAddresses(customerId).stream().filter(a -> a.id().equals(addressId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteAddress(Long customerId, Long addressId) {
        requireAddress(customerId, addressId);
        jdbc.update("DELETE FROM customer_addresses WHERE id=? AND customer_id=?", addressId, customerId);
        ensureDefault(customerId); syncDefaultAddress(customerId);
        auditLogService.log("Customer", customerId, "收货地址", "CUST_ADDRESS_DELETE", "地址ID=" + addressId);
    }

    @Transactional(readOnly = true)
    public List<MergeHistorySummary> getMergeHistory(Long customerId) {
        requireCustomer(customerId);
        return jdbc.query("""
            SELECT id,merged_customer_id,merged_customer_code,merged_customer_name,merged_address,merge_reason,merged_at
            FROM customer_merge_history WHERE primary_customer_id=? ORDER BY merged_at DESC,id DESC
            """, (rs, n) -> new MergeHistorySummary(rs.getLong("id"), rs.getLong("merged_customer_id"),
                rs.getString("merged_customer_code"), rs.getString("merged_customer_name"), rs.getString("merged_address"),
                rs.getString("merge_reason"), rs.getTimestamp("merged_at").toLocalDateTime()), customerId);
    }

    public List<SourceIdentitySummary> getSourceIdentities(Long customerId) {
        requireCustomer(customerId);
        return jdbc.query("""
            SELECT a.id,a.source_system,a.source_key,a.source_name,a.resolution_status,a.confidence,
                   a.occurrence_count,a.resolution_note,a.resolved_at,
                   COUNT(o.id) outbound_count,
                   MIN(COALESCE(o.ship_date,o.business_date)) first_outbound_date,
                   MAX(COALESCE(o.ship_date,o.business_date)) last_outbound_date,
                   GROUP_CONCAT(DISTINCT NULLIF(TRIM(o.contact_phone),'') ORDER BY o.contact_phone SEPARATOR '\\n') phones,
                   GROUP_CONCAT(DISTINCT NULLIF(TRIM(o.customer_address),'') ORDER BY o.customer_address SEPARATOR '\\n') addresses
            FROM source_identity_aliases a
            LEFT JOIN sales_outbounds o ON o.customer_id=a.target_id
              AND LOWER(REPLACE(REPLACE(TRIM(o.customer_name),' ',''),'　',''))=a.normalized_name
              AND o.outbound_status='CONFIRMED'
            WHERE a.entity_type='CUSTOMER' AND a.target_id=?
              AND a.resolution_status IN ('AUTO','CONFIRMED')
            GROUP BY a.id,a.source_system,a.source_key,a.source_name,a.resolution_status,a.confidence,
                     a.occurrence_count,a.resolution_note,a.resolved_at
            ORDER BY outbound_count DESC,a.occurrence_count DESC,a.id
            """, (rs, n) -> new SourceIdentitySummary(
                rs.getLong("id"), rs.getString("source_system"), rs.getString("source_key"),
                rs.getString("source_name"), rs.getString("resolution_status"),
                rs.getBigDecimal("confidence"), rs.getLong("occurrence_count"),
                rs.getString("resolution_note"), rs.getTimestamp("resolved_at") == null ? null
                    : rs.getTimestamp("resolved_at").toLocalDateTime(),
                rs.getLong("outbound_count"),
                rs.getDate("first_outbound_date") == null ? null : rs.getDate("first_outbound_date").toLocalDate(),
                rs.getDate("last_outbound_date") == null ? null : rs.getDate("last_outbound_date").toLocalDate(),
                splitAggregatedValues(rs.getString("phones")), splitAggregatedValues(rs.getString("addresses"))), customerId);
    }

    private static List<String> splitAggregatedValues(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("\\n")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }

    private void requireAddress(Long customerId, Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer_addresses WHERE id=? AND customer_id=?", Integer.class, id, customerId);
        if (count == null || count == 0) throw new EntityNotFoundException("客户地址不存在: " + id);
    }
    private void ensureDefault(Long customerId) {
        Integer defaults = jdbc.queryForObject("SELECT COUNT(*) FROM customer_addresses WHERE customer_id=? AND is_default=1", Integer.class, customerId);
        if (defaults == null || defaults == 0) jdbc.update("UPDATE customer_addresses SET is_default=1 WHERE customer_id=? ORDER BY id LIMIT 1", customerId);
    }
    private void syncDefaultAddress(Long customerId) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT province,city,district,detail_address FROM customer_addresses WHERE customer_id=? AND is_default=1 ORDER BY id LIMIT 1", customerId);
        if (rows.isEmpty()) return;
        Map<String,Object> row = rows.getFirst();
        jdbc.update("UPDATE customers SET province=?,city=?,district=?,address=?,updated_at=NOW() WHERE id=?",
                row.get("province"), row.get("city"), row.get("district"), row.get("detail_address"), customerId);
    }
    private static String normalizeAddress(String value) { return value.trim().toLowerCase().replaceAll("[\\s,，。.;；、]+", ""); }

    // ==================== 增删改 ====================

    @Transactional
    public CustomerSummary create(CustomerPayload payload) {
        if (StringUtils.blankToNull(payload.customerName()) == null) {
            throw new IllegalArgumentException("客户名称不能为空");
        }
        Customer c = new Customer();
        c.setCustomerCode(nextCustomerCode());
        applyPayloadToEntity(c, payload);
        Customer saved = customerRepository.save(c);
        auditLogService.log("Customer", saved.getId(), saved.getCustomerName(), "CUST_CREATE", "创建客户档案");
        return CustomerSummary.from(saved, null, null, null, CustomerService::toLabel);
    }

    @Transactional
    public CustomerSummary update(Long id, CustomerPayload payload) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("客户不存在: " + id));
        applyPayloadToEntity(c, payload);
        Customer saved = customerRepository.save(c);
        auditLogService.log("Customer", id, c.getCustomerName(), "CUST_UPDATE", "更新客户档案");
        Map<Long, String> primary = collectPrimarySalespersons(List.of(saved));
        CustomerContact primaryContact = collectPrimaryContacts(List.of(saved)).get(id);
        return CustomerSummary.from(
                saved, primary.get(id),
                primaryContact == null ? null : primaryContact.getName(),
                primaryContact == null ? null : primaryContact.getMobile(),
                CustomerService::toLabel);
    }

    @Transactional
    public void delete(Long id) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("客户不存在: " + id));
        int outbounds = jdbc.update("UPDATE sales_outbounds SET customer_id=NULL WHERE customer_id=?", id);
        int identities = jdbc.update("""
            UPDATE source_identity_aliases
               SET target_id=NULL,resolution_status='REVIEW',issue_code='TARGET_CUSTOMER_DELETED',
                   resolution_note='原关联客户已删除，等待重新关联',updated_at=NOW()
             WHERE entity_type='CUSTOMER' AND target_id=?
            """, id);
        int clusters = jdbc.update("""
            UPDATE customer_source_cluster_mappings SET status='REVOKED',updated_at=NOW()
             WHERE customer_id=? AND status='CONFIRMED'
            """, id);
        c.setDeletedAt(java.time.LocalDateTime.now());
        customerRepository.save(c);
        auditLogService.log("Customer", id, c.getCustomerName(), "CUST_DELETE",
                "软删除客户 id=" + id + "；解除销售出库=" + outbounds
                        + "，退回复核身份=" + identities + "，撤销身份簇=" + clusters);
    }

    /** 生成下一个客户编号：CUS + 6 位序号（基于现库最大序号）。 */
    private String nextCustomerCode() {
        Long maxSeq = customerRepository.findMaxCustomerSequence();
        long seq = maxSeq == null ? 0 : maxSeq;
        return "CUS" + String.format("%06d", seq + 1);
    }

    /** 将 Payload 写入 Entity（枚举中文 → DB 英文，地区三元组先按区划字典归一为规范名）。 */
    private void applyPayloadToEntity(Customer c, CustomerPayload payload) {
        RegionValue region = standardRegion(payload.province(), payload.city(), payload.district());
        CustomerPayload converted = new CustomerPayload(
                payload.customerName(),
                payload.shortName(),
                toDbType(payload.customerType()),
                payload.industry(),
                payload.creditCode(),
                region.province(),
                region.city(),
                region.district(),
                payload.address(),
                payload.website(),
                payload.source(),
                payload.level(),
                toDbRelationStatus(payload.relationStatus()),
                statusDb(payload.status()),
                payload.remark()
        );
        converted.applyTo(c);
    }

    // ==================== 批量更新 ====================

    /**
     * 通用批量更新：按字段白名单 + 类型转换，将选中客户的指定字段统一修改。
     */
    @Transactional
    public int batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("未指定要修改的字段");
        }
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("不支持的批量修改字段: " + key);
            }
        }
        // 枚举字段取值校验
        if (fields.containsKey("customerType")) {
            String v = fields.get("customerType") == null ? null : fields.get("customerType").toString();
            if (CustomerType.fromDisplayName(StringUtils.nn(v)) == null) {
                throw new IllegalArgumentException("客户类型不合法: " + v);
            }
        }
        if (fields.containsKey("relationStatus")) {
            String v = fields.get("relationStatus") == null ? null : fields.get("relationStatus").toString();
            if (CustomerRelationStatus.fromDisplayName(StringUtils.nn(v)) == null) {
                throw new IllegalArgumentException("关系状态不合法: " + v);
            }
        }
        if (fields.containsKey("status")) {
            String v = fields.get("status") == null ? null : fields.get("status").toString();
            if (toDbStatus(v) == null) {
                throw new IllegalArgumentException("启用状态不合法: " + v);
            }
        }
        List<Customer> customers = customerRepository.findAllById(ids);
        for (Customer c : customers) {
            applyFieldUpdate(c, fields);
        }
        if (!customers.isEmpty()) {
            customerRepository.saveAll(customers);
        }
        int count = customers.size();
        auditLogService.log("Customer", null, "批量",
                "CUST_BATCH_UPDATE", "批量修改 " + count + " 个客户字段：" + String.join("、", fields.keySet()));
        return count;
    }

    /** 将 fields 中的白名单字段逐个应用到实体（只覆盖传入的字段）。
     *  地区字段按「省→市→区县」固定顺序归一：城市/区县以最终省份作为字典消歧前缀。 */
    private void applyFieldUpdate(Customer c, Map<String, Object> fields) {
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String key = e.getKey();
            String v = e.getValue() == null ? null : e.getValue().toString();
            switch (key) {
                case "customerType" -> c.setCustomerType(toDbType(v));
                case "industry" -> c.setIndustry(StringUtils.blankToNull(v));
                case "source" -> c.setSource(StringUtils.blankToNull(v));
                case "level" -> c.setLevel(StringUtils.blankToNull(v));
                case "relationStatus" -> c.setRelationStatus(toDbRelationStatus(v));
                case "status" -> c.setStatus(toDbStatus(v));
                case "remark" -> c.setRemark(v == null ? null : v);
                default -> { }
            }
        }
        boolean hasProvince = fields.containsKey("province");
        boolean hasCity = fields.containsKey("city");
        boolean hasDistrict = fields.containsKey("district");
        String province = hasProvince
                ? normalizeRegionValue(rawText(fields, "province"), ChinaRegionDictionary::canonicalProvinceName)
                : c.getProvince();
        if (hasProvince) c.setProvince(province);
        if (hasCity) {
            String rawCity = rawText(fields, "city");
            c.setCity(normalizeRegionValue(rawCity, v -> ChinaRegionDictionary.canonicalCityName(v, province)));
        }
        if (hasDistrict) {
            String rawDistrict = rawText(fields, "district");
            c.setDistrict(normalizeRegionValue(rawDistrict, v -> ChinaRegionDictionary.canonicalDistrictName(v, province)));
        }
    }

    private static String rawText(Map<String, Object> fields, String key) {
        Object o = fields.get(key);
        return o == null ? null : o.toString();
    }

    /** 归一地区单值：null/空白 → null（批量语义：清空该字段）；否则经字典规范化。 */
    private static String normalizeRegionValue(String raw, java.util.function.Function<String, String> canonical) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return canonical.apply(t);
    }

    // ==================== 区域分布 ====================

    /** 区域聚合的临时省占位（服务端内部结构，不对外暴露）。 */
    private static class ProvinceAgg {
        final String canonical;
        long total;
        /** 原始市写法 → 客户数（含未细分到区县的客户）。 */
        final Map<String, Long> cities = new HashMap<>();
        /** 原始市写法 → 原始区县写法 → 客户数（第三级，仅收录已标注区县的行）。 */
        final Map<String, Map<String, Long>> districts = new HashMap<>();

        ProvinceAgg(String canonical) {
            this.canonical = canonical;
        }
    }

    /**
     * 客户区域分布聚合：省/市/区县三级统一按区划字典规范名归并
     * （浙江/浙江省→浙江省；东莞/东莞市→东莞市；广州白云区→白云区…），分布展示名即规范名，
     * 与地图 feature 名及下方客户列表筛选同口径；无法识别写法原样计数并交由前端标记「未收录」。
     */
    @Transactional(readOnly = true)
    public CustomerDistribution distribution() {
        long total = customerRepository.count();
        long noRegion = customerRepository.countCustomersWithoutProvince(
                ChinaRegionDictionary.officialProvinces(), ChinaRegionDictionary.OVERSEAS_PROVINCE);
        long overseas = customerRepository.countOverseasCustomers(ChinaRegionDictionary.OVERSEAS_PROVINCE);
        long partial = customerRepository.countCustomersWithPartialRegion(
                ChinaRegionDictionary.officialProvinces());

        Map<String, ProvinceAgg> byProvince = new HashMap<>();
        long cityLocated = 0;
        long districtLocated = 0;
        for (Object[] row : customerRepository.findProvinceCityDistrictTuples()) {
            String rawProvince = row[0] == null ? null : row[0].toString().trim();
            if (rawProvince == null || rawProvince.isEmpty()) continue;
            String canonical = canonicalProvince(rawProvince);
            if (canonical == null || !ChinaRegionDictionary.isOfficialProvinceName(canonical)) continue;
            ProvinceAgg agg = byProvince.computeIfAbsent(canonical, ProvinceAgg::new);
            agg.total++;
            String rawCity = row[1] == null ? null : row[1].toString().trim();
            if (rawCity != null && !rawCity.isEmpty()) {
                String cityKey = ChinaRegionDictionary.canonicalCityName(rawCity, canonical);
                if (!CustomerAddressTidyService.isSelectableCity(cityKey, canonical)) continue;
                agg.cities.merge(cityKey, 1L, Long::sum);
                cityLocated++;
                String rawDistrict = row[2] == null ? null : row[2].toString().trim();
                if (rawDistrict != null && !rawDistrict.isEmpty()) {
                    String districtKey = ChinaRegionDictionary.canonicalDistrictName(rawDistrict, canonical);
                    if (!CustomerAddressTidyService.isSelectableDistrict(districtKey, canonical, cityKey)) continue;
                    agg.districts.computeIfAbsent(cityKey, k -> new HashMap<>())
                            .merge(districtKey, 1L, Long::sum);
                    districtLocated++;
                }
            }
        }

        List<ProvinceDistribution> provinces = byProvince.values().stream()
                .map(agg -> new ProvinceDistribution(
                        agg.canonical,
                        agg.canonical,
                        agg.total,
                        agg.total - agg.cities.values().stream().mapToLong(Long::longValue).sum(),
                        agg.cities.entrySet().stream()
                                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                                        .thenComparing(Map.Entry.comparingByKey()))
                                .map(e -> new CityDistribution(
                                        e.getKey(),
                                        e.getValue(),
                                        e.getValue() - agg.districts.getOrDefault(e.getKey(), Map.of())
                                                .values().stream().mapToLong(Long::longValue).sum(),
                                        agg.districts.getOrDefault(e.getKey(), Map.of()).entrySet().stream()
                                                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                                                        .thenComparing(Map.Entry.comparingByKey()))
                                                .map(de -> new DistrictDistribution(de.getKey(), de.getValue()))
                                                .toList()))
                                .toList()))
                .sorted(Comparator.comparingLong(ProvinceDistribution::count).reversed()
                        .thenComparing(ProvinceDistribution::name))
                .toList();

        long located = provinces.stream().mapToLong(ProvinceDistribution::count).sum();
        return new CustomerDistribution(total, located, cityLocated, districtLocated, partial,
                noRegion, overseas, provinces.size(), provinces);
    }

    // ==================== 筛选选项 ====================

    @Transactional(readOnly = true)
    public FilterOptions filterOptions() {
        List<String> types = Arrays.stream(CustomerType.values()).map(CustomerType::getDisplayName).toList();
        List<String> relations = Arrays.stream(CustomerRelationStatus.values())
                .map(CustomerRelationStatus::getDisplayName).toList();
        return new FilterOptions(
                types,
                relations,
                Arrays.asList("启用", "停用"),
                customerRepository.findIndustries(),
                customerRepository.findProvinces(),
                collectSalespersonOptions()
        );
    }

    /** 业务员筛选下拉 = 业务员关系档案 + 销售出库台账出现过的人员（去重，档案在前）。 */
    private List<String> collectSalespersonOptions() {
        List<String> names = new ArrayList<>(relationRepository.findDistinctSalespersonNames());
        List<String> outboundNames = jdbc.queryForList("""
            SELECT DISTINCT TRIM(salesperson_name) FROM sales_outbounds
            WHERE salesperson_name IS NOT NULL AND TRIM(salesperson_name) <> ''
            """, String.class);
        Set<String> seen = new HashSet<>(names);
        for (String name : outboundNames) {
            if (seen.add(name)) names.add(name);
        }
        return names;
    }

    // ==================== 联系人 ====================

    @Transactional(readOnly = true)
    public List<ContactSummary> getContacts(Long customerId) {
        requireCustomer(customerId);
        return contactRepository.findByCustomerIdOrderByIsPrimaryDescIdAsc(customerId)
                .stream().map(ContactSummary::from).collect(Collectors.toList());
    }

    @Transactional
    public ContactSummary addContact(Long customerId, ContactPayload payload) {
        requireCustomer(customerId);
        if (StringUtils.blankToNull(payload.name()) == null) {
            throw new IllegalArgumentException("联系人姓名不能为空");
        }
        CustomerContact c = new CustomerContact();
        c.setCustomerId(customerId);
        payload.applyTo(c);
        if (Boolean.TRUE.equals(c.getIsPrimary())) {
            demoteOtherPrimaryContacts(customerId, null);
        }
        CustomerContact saved = contactRepository.save(c);
        auditLogService.log("Customer", customerId, "联系人", "CUST_CONTACT_ADD", "新增联系人 " + saved.getName());
        return ContactSummary.from(saved);
    }

    @Transactional
    public ContactSummary updateContact(Long customerId, Long contactId, ContactPayload payload) {
        requireCustomer(customerId);
        CustomerContact c = contactRepository.findById(contactId)
                .orElseThrow(() -> new EntityNotFoundException("联系人不存在: " + contactId));
        if (!c.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("联系人不属于该客户");
        }
        boolean wantsPrimary = payload.isPrimary() == null ? Boolean.TRUE.equals(c.getIsPrimary())
                : Boolean.TRUE.equals(payload.isPrimary());
        if (wantsPrimary) {
            demoteOtherPrimaryContacts(customerId, contactId);
        }
        payload.applyTo(c);
        CustomerContact saved = contactRepository.save(c);
        auditLogService.log("Customer", customerId, "联系人", "CUST_CONTACT_UPDATE", "更新联系人 " + saved.getName());
        return ContactSummary.from(saved);
    }

    @Transactional
    public void deleteContact(Long customerId, Long contactId) {
        requireCustomer(customerId);
        CustomerContact c = contactRepository.findById(contactId)
                .orElseThrow(() -> new EntityNotFoundException("联系人不存在: " + contactId));
        if (!c.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("联系人不属于该客户");
        }
        contactRepository.delete(c);
        auditLogService.log("Customer", customerId, "联系人", "CUST_CONTACT_DELETE", "删除联系人 " + c.getName());
    }

    /** 设置主要联系人时，把该客户下其它主要联系人降级。 */
    private void demoteOtherPrimaryContacts(Long customerId, Long exceptId) {
        if (exceptId == null) jdbc.update("UPDATE customer_contacts SET is_primary=0 WHERE customer_id=? AND is_primary=1", customerId);
        else jdbc.update("UPDATE customer_contacts SET is_primary=0 WHERE customer_id=? AND is_primary=1 AND id<>?", customerId, exceptId);
    }

    // ==================== 业务员关系 ====================

    @Transactional(readOnly = true)
    public List<SalespersonSummary> getSalespersons(Long customerId) {
        requireCustomer(customerId);
        return relationRepository.findByCustomerIdOrderByRoleAscStartDateAscIdAsc(customerId)
                .stream().map(SalespersonSummary::from).collect(Collectors.toList());
    }

    @Transactional
    public SalespersonSummary addSalesperson(Long customerId, SalespersonPayload payload) {
        requireCustomer(customerId);
        if (StringUtils.blankToNull(payload.salespersonName()) == null) {
            throw new IllegalArgumentException("业务员姓名不能为空");
        }
        CustomerSalespersonRelation r = new CustomerSalespersonRelation();
        r.setCustomerId(customerId);
        payload.applyTo(r);
        r.setRole(normalizeRole(payload.role()));
        if (ROLE_PRIMARY.equals(r.getRole())) {
            demoteOtherPrimarySalespersons(customerId);
        }
        CustomerSalespersonRelation saved = relationRepository.save(r);
        auditLogService.log("Customer", customerId, "业务员", "CUST_SALES_ADD",
                "新增业务员关系 " + saved.getSalespersonName());
        return SalespersonSummary.from(saved);
    }

    @Transactional
    public SalespersonSummary updateSalesperson(Long customerId, Long relId, SalespersonPayload payload) {
        requireCustomer(customerId);
        CustomerSalespersonRelation r = relationRepository.findById(relId)
                .orElseThrow(() -> new EntityNotFoundException("业务员关系不存在: " + relId));
        if (!r.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("业务员关系不属于该客户");
        }
        String nextRole = payload.role() == null ? r.getRole() : normalizeRole(payload.role());
        if (ROLE_PRIMARY.equals(nextRole)) {
            demoteOtherPrimarySalespersons(customerId, relId);
        }
        payload.applyTo(r);
        r.setRole(nextRole);
        CustomerSalespersonRelation saved = relationRepository.save(r);
        auditLogService.log("Customer", customerId, "业务员", "CUST_SALES_UPDATE",
                "更新业务员关系 " + saved.getSalespersonName());
        return SalespersonSummary.from(saved);
    }

    @Transactional
    public void deleteSalesperson(Long customerId, Long relId) {
        requireCustomer(customerId);
        CustomerSalespersonRelation r = relationRepository.findById(relId)
                .orElseThrow(() -> new EntityNotFoundException("业务员关系不存在: " + relId));
        if (!r.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("业务员关系不属于该客户");
        }
        relationRepository.delete(r);
        auditLogService.log("Customer", customerId, "业务员", "CUST_SALES_DELETE",
                "删除业务员关系 " + r.getSalespersonName());
    }

    /** 新设 PRIMARY 时，把该客户原有 PRIMARY 降级为 COLLABORATOR。 */
    private void demoteOtherPrimarySalespersons(Long customerId) {
        jdbc.update("UPDATE customer_salesperson_relations SET role='COLLABORATOR' WHERE customer_id=? AND role='PRIMARY'", customerId);
    }

    private void demoteOtherPrimarySalespersons(Long customerId, Long exceptId) {
        jdbc.update("UPDATE customer_salesperson_relations SET role='COLLABORATOR' WHERE customer_id=? AND role='PRIMARY' AND id<>?", customerId, exceptId);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) return ROLE_COLLABORATOR;
        return switch (role.trim()) {
            case "PRIMARY", "HISTORY", "COLLABORATOR" -> role.trim();
            default -> ROLE_COLLABORATOR;
        };
    }

    // ==================== 概览统计（氚云销售出库唯一事实口径） ====================

    @Transactional(readOnly = true)
    public CustomerOverview getOverview(Long customerId) {
        requireCustomer(customerId);
        Map<String, Object> facts = jdbc.queryForMap("""
            SELECT COUNT(*) outbound_count,COALESCE(SUM(COALESCE(sales_amount,0)),0) total_sales_amount,
                   MIN(COALESCE(ship_date,business_date)) first_outbound_date,
                   MAX(COALESCE(ship_date,business_date)) last_outbound_date
            FROM sales_outbounds WHERE customer_id=? AND outbound_status='CONFIRMED'
            """, customerId);
        long outboundCount = ((Number) facts.get("outbound_count")).longValue();
        BigDecimal totalSalesAmount = (BigDecimal) facts.get("total_sales_amount");
        LocalDate firstOutboundDate = facts.get("first_outbound_date") == null ? null
                : ((java.sql.Date) facts.get("first_outbound_date")).toLocalDate();
        LocalDate lastOutboundDate = facts.get("last_outbound_date") == null ? null
                : ((java.sql.Date) facts.get("last_outbound_date")).toLocalDate();
        List<String> purchasedProducts = jdbc.queryForList("""
            SELECT i.product_name
            FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id
            WHERE o.customer_id=? AND o.outbound_status='CONFIRMED' AND i.line_type='PRODUCT'
              AND i.product_name IS NOT NULL AND TRIM(i.product_name)<>''
            GROUP BY i.product_name ORDER BY MAX(COALESCE(o.ship_date,o.business_date)) DESC LIMIT 100
            """, String.class, customerId);
        List<SalespersonSummary> salespersons = getSalespersons(customerId);
        List<ContactSummary> contacts = getContacts(customerId);
        return new CustomerOverview(outboundCount, totalSalesAmount, firstOutboundDate, lastOutboundDate,
                purchasedProducts, salespersons, contacts);
    }

    // ==================== 销售出库参考（档案补全用） ====================

    /**
     * 汇总该客户销售出库单里可用于补全档案的信息（地区标注、收货地址、联系电话、经销商、
     * 最近出库时间与发货方式），供编辑弹窗展示参考并由人工决定是否带入。
     * <p>建议省份优先取「出库单地区标注」的唯一省份；标注为「国外」时回落看收货地址能解析出
     * 哪个官方省份（港澳台算省级值），都定不下来则返回 null，由人工在弹窗里选择。
     */
    @Transactional(readOnly = true)
    public OutboundReference getOutboundReference(Long customerId) {
        requireCustomer(customerId);
        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT COUNT(*) order_count, MAX(COALESCE(ship_date,business_date)) last_date
                FROM sales_outbounds WHERE customer_id=?
                """, customerId);
        long orderCount = ((Number) facts.get("order_count")).longValue();
        if (orderCount == 0) {
            return new OutboundReference(0, null, null, List.of(), List.of(), List.of(), List.of(), null);
        }
        LocalDate lastShipDate = facts.get("last_date") == null ? null
                : ((java.sql.Date) facts.get("last_date")).toLocalDate();
        List<String> methods = distinctOutboundValues(customerId, "shipping_method", 3);
        List<String> regions = distinctOutboundValues(customerId, "region", 12);
        List<String> phones = distinctOutboundValues(customerId, "contact_phone", 6);
        List<String> dealers = distinctOutboundValues(customerId, "dealer_name", 6);
        List<String> addresses = distinctOutboundValues(customerId, "customer_address", 8);
        String suggested = null;
        for (String region : regions) {
            String province = ChinaRegionDictionary.canonicalProvinceFromRegionTag(region);
            if (province == null) continue;
            if (suggested != null && !suggested.equals(province)) { suggested = null; break; }
            suggested = province;
        }
        if (suggested == null) {
            for (String address : addresses) {
                String parsed = CustomerRegionService.diagnose(address).province();
                if (parsed == null) continue;
                if (suggested != null && !suggested.equals(parsed)) { suggested = null; break; }
                suggested = parsed;
            }
        }
        return new OutboundReference(orderCount, lastShipDate,
                methods.isEmpty() ? null : methods.getFirst(),
                regions, addresses, phones, dealers, suggested);
    }

    /** 取某列在出库单上的去重非空值（按最近出库倒序）。 */
    private List<String> distinctOutboundValues(Long customerId, String column, int limit) {
        return jdbc.queryForList("""
                SELECT value FROM (
                    SELECT TRIM(%s) value, MAX(COALESCE(ship_date,business_date)) last_date
                    FROM sales_outbounds
                    WHERE customer_id=? AND %s IS NOT NULL AND TRIM(%s)<>''
                    GROUP BY TRIM(%s)
                ) t ORDER BY last_date DESC LIMIT ?
                """.formatted(column, column, column, column),
                String.class, customerId, limit);
    }

    // ==================== 内部工具 ====================
    /** 批量取每个客户的当前负责人（PRIMARY 优先，取开始日期最近者；无 PRIMARY 取最近一条关系）。 */
    private Map<Long, String> collectPrimarySalespersons(List<Customer> customers) {
        if (customers.isEmpty()) return Map.of();
        List<Long> ids = customers.stream().map(Customer::getId).toList();
        Map<Long, List<CustomerSalespersonRelation>> byCustomer = relationRepository.findByCustomerIdIn(ids)
                .stream()
                .collect(Collectors.groupingBy(CustomerSalespersonRelation::getCustomerId));
        Map<Long, String> result = new HashMap<>();
        byCustomer.forEach((cid, rels) -> {
            Optional<CustomerSalespersonRelation> primary = rels.stream()
                    .filter(r -> ROLE_PRIMARY.equals(r.getRole()))
                    .max(byStartDesc());
            CustomerSalespersonRelation pick = primary.orElseGet(() -> rels.stream().max(byStartDesc()).orElse(null));
            if (pick != null) {
                result.put(cid, pick.getSalespersonName());
            }
        });
        return result;
    }

    /** 取"最近开始日期"的关系：开始日期大者优先，空日期垫底，同日期取新记录。 */
    private Comparator<CustomerSalespersonRelation> byStartDesc() {
        return Comparator
                .comparing(CustomerSalespersonRelation::getStartDate,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CustomerSalespersonRelation::getId, Comparator.reverseOrder());
    }

    /** 批量取各客户"主要联系人"（isPrimary 优先，否则取最早创建的那个）。 */
    private Map<Long, CustomerContact> collectPrimaryContacts(List<Customer> customers) {
        if (customers.isEmpty()) return Map.of();
        List<Long> ids = customers.stream().map(Customer::getId).toList();
        Map<Long, CustomerContact> result = new HashMap<>();
        contactRepository.findByCustomerIdIn(ids)
                .stream()
                .collect(Collectors.groupingBy(CustomerContact::getCustomerId))
                .forEach((cid, contacts) -> {
                    CustomerContact primary = contacts.stream()
                            .filter(c -> Boolean.TRUE.equals(c.getIsPrimary()))
                            .findFirst()
                            .orElse(contacts.get(0));
                    result.put(cid, primary);
                });
        return result;
    }

    private void requireCustomer(Long customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw new EntityNotFoundException("客户不存在: " + customerId);
        }
    }
}
