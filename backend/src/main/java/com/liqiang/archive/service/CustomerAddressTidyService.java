package com.liqiang.archive.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.liqiang.archive.util.ChinaAddressParser;
import com.liqiang.archive.util.ChinaAddressParser.ParsedAddress;
import com.liqiang.archive.util.ChinaRegionDictionary;

import lombok.RequiredArgsConstructor;

/**
 * 客户地区治理状态（{@code customers.address_tidy_status}）扫描与「地址选择器可选性」判定。
 *
 * <p>以客户档案的「公司地址」（缺省时回退到最近一单已生效出库的客户地址）为源文本，
 * 用可复用解析器 {@link ChinaAddressParser} 推导省市县，<b>仅用于计算治理状态，不修改地址与省市区县字段</b>。
 * 状态口径：
 * <ul>
 *   <li>NORMAL —— 无需治理；</li>
 *   <li>NO_ADDRESS —— 档案无公司地址，但最近出库地址非空；</li>
 *   <li>MISSING —— 档案省市县存在可解析补齐的空白项；</li>
 *   <li>MISMATCH —— 档案省市县与地址解析结果不一致；</li>
 *   <li>PARTIAL —— 省份已合法可上地图，但市/区县缺失或无法识别（地址文本本身没有更细信息）；</li>
 *   <li>UNCLEAR —— 省份都无法确定（地址无省名或省份写法非法）；</li>
 *   <li>UNRECOGNIZED —— 地址或当前字段含地址选择器无法识别的值（非规范省/市/区县）。</li>
 * </ul>
 *
 * <p>该状态被客户列表/CSV 导出的「未标注地区 / 待补市县」筛选、区域分布统计与
 * 地区批量解析共用，保证各处异常口径一致。
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CustomerAddressTidyService {

    private static final String GROUP_NO_ADDRESS = "noAddress";
    private static final String GROUP_MISSING = "missing";
    private static final String GROUP_MISMATCH = "mismatch";
    private static final String GROUP_PARTIAL = "partial";
    private static final String GROUP_UNCLEAR = "unclear";
    private static final String GROUP_UNRECOGNIZED = "unrecognized";

    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_UNRECOGNIZED = "UNRECOGNIZED";

    private final JdbcTemplate jdbc;

    /** 客户档案 + 最近一单出库客户地址 的原始行。 */
    private record RawRow(
            long id,
            String customerCode,
            String customerName,
            String address,
            String province,
            String city,
            String district,
            String outboundAddress,
            String status) {
    }

    /** 重新计算全部客户的地区治理状态，但不修改地址及省市区县字段。 */
    @Transactional
    public int scanStatuses() {
        int changed = 0;
        for (RawRow row : loadRows()) {
            String before = notBlank(row.status());
            String group = classify(row);
            String after = statusForGroup(group);
            if (!after.equals(before)) {
                jdbc.update("UPDATE customers SET address_tidy_status=? WHERE id=? AND deleted_at IS NULL", after, row.id());
                changed++;
            }
        }
        return changed;
    }

    // ==================== 内部实现 ====================

    private List<RawRow> loadRows() {
        return jdbc.query("""
                SELECT c.id, c.customer_code, c.customer_name, c.address, c.province, c.city, c.district,
                       c.address_tidy_status, o.addr AS out_addr
                FROM customers c
                LEFT JOIN (
                    SELECT customer_id, customer_address AS addr
                    FROM (
                        SELECT customer_id, customer_address,
                               ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY ship_date DESC, id DESC) AS rn
                        FROM sales_outbounds
                        WHERE outbound_status = 'CONFIRMED' AND customer_address IS NOT NULL
                    ) x
                    WHERE x.rn = 1
                ) o ON o.customer_id = c.id
                WHERE c.deleted_at IS NULL
                ORDER BY c.id
                """, (rs, rowNum) -> new RawRow(
                rs.getLong("id"),
                rs.getString("customer_code"),
                rs.getString("customer_name"),
                rs.getString("address"),
                rs.getString("province"),
                rs.getString("city"),
                rs.getString("district"),
                rs.getString("out_addr"),
                rs.getString("address_tidy_status")));
    }

    /** 分类：返回治理分组；null 表示无需治理（地址与档案一致 / 无任何地址来源 / 境外客户）。 */
    private String classify(RawRow r) {
        // 境外客户（province=「境外」）没有大陆省市区县，不参与地区治理：既不进「未标注地区」，
        // 也不进「待补市县」，否则这类客户会永远挂在待处理清单里。
        if (ChinaRegionDictionary.isOverseasProvince(r.province())) return null;
        // 人工已填写且三级行政区划完整有效时，以档案字段为准。地址文本可能包含企业名、园区名等
        // 干扰词（例如“科山西工业区”会误命中“山西省”），自动解析不得反向推翻规范人工值。
        if (completeSelectableRegion(r)) return null;
        String curAddr = notBlank(r.address());
        String outAddr = notBlank(r.outboundAddress());
        String source = curAddr != null ? curAddr : outAddr;
        if (source == null) {
            return regionIncomplete(r) ? (provinceLabeled(r) ? GROUP_PARTIAL : GROUP_UNCLEAR) : null;
        }
        ParsedAddress parsed = ChinaAddressParser.parse(source);
        ParsedAddress outParsed = null;
        if (curAddr != null && outAddr != null && parsed.isEmpty()) {
            outParsed = ChinaAddressParser.parse(outAddr);
        }

        AtomicBoolean unrecognized = new AtomicBoolean(false);
        EffectiveValues eff = computeEffective(parsed, outParsed, r, unrecognized);
        if (unrecognized.get()) {
            return GROUP_UNRECOGNIZED;
        }

        if (curAddr == null) return GROUP_NO_ADDRESS;
        if (eff.province() == null && eff.city() == null && eff.district() == null) {
            return GROUP_UNCLEAR;
        }
        boolean hasFillable = false;
        boolean hasDiff = false;
        if (eff.province() != null) {
            if (notBlank(r.province()) == null) hasFillable = true;
            else if (!eff.province().equals(r.province())) hasDiff = true;
        }
        if (eff.city() != null) {
            if (notBlank(r.city()) == null) hasFillable = true;
            else if (!eff.city().equals(r.city())) hasDiff = true;
        }
        if (eff.district() != null) {
            if (notBlank(r.district()) == null) hasFillable = true;
            else if (!eff.district().equals(r.district())) hasDiff = true;
        }
        if (hasFillable) return GROUP_MISSING;
        if (hasDiff) return GROUP_MISMATCH;
        // 地址只能解析出部分层级时：省份已合法即算「待补市县」（可上地图，不算未标注地区）；
        // 只有省份都无法确定时才算 UNCLEAR。
        if (regionIncomplete(r)) return provinceLabeled(r) ? GROUP_PARTIAL : GROUP_UNCLEAR;
        return null;
    }

    /** 省份字段是否已是可上地图的官方省名（决定该客户算「未标注地区」还是「待补市县」）。 */
    private static boolean provinceLabeled(RawRow r) {
        String p = notBlank(r.province());
        return p != null && ChinaRegionDictionary.isOfficialProvinceName(p);
    }

    private boolean completeSelectableRegion(RawRow r) {
        String province = notBlank(r.province());
        String city = notBlank(r.city());
        String district = notBlank(r.district());
        return province != null && city != null && district != null
                && ChinaRegionDictionary.isOfficialProvinceName(province)
                && isSelectableCity(city, province)
                && isSelectableDistrict(district, province, city);
    }

    private boolean regionIncomplete(RawRow r) {
        return notBlank(r.province()) == null || notBlank(r.city()) == null || notBlank(r.district()) == null;
    }

    private String statusForGroup(String group) {
        return group == null ? STATUS_NORMAL : switch (group) {
            case GROUP_NO_ADDRESS -> "NO_ADDRESS";
            case GROUP_MISSING -> "MISSING";
            case GROUP_MISMATCH -> "MISMATCH";
            case GROUP_PARTIAL -> "PARTIAL";
            case GROUP_UNCLEAR -> "UNCLEAR";
            default -> STATUS_UNRECOGNIZED;
        };
    }

    /** 有效值（解析/出库/当前中第一个可被地址选择器识别的值）。 */
    private record EffectiveValues(String province, String city, String district) {
    }

    private EffectiveValues computeEffective(ParsedAddress parsed, ParsedAddress outParsed, RawRow r,
                                              AtomicBoolean unrecognized) {
        String province = chooseValue(parsed.province(),
                outParsed == null ? null : outParsed.province(), r.province(), "province", null, null, unrecognized);
        String city = chooseValue(parsed.city(),
                outParsed == null ? null : outParsed.city(), r.city(), "city", province, null, unrecognized);
        String district = chooseValue(parsed.district(),
                outParsed == null ? null : outParsed.district(), r.district(), "district", province, city, unrecognized);
        return new EffectiveValues(province, city, district);
    }

    /**
     * 按「解析值 → 出库解析值 → 当前值」优先级选取第一个可被地址选择器识别的值。
     * 若三者均非空但都无法识别，则标记为异常并返回 null。
     */
    private String chooseValue(String parsed, String outParsed, String current, String column,
                               String province, String city, AtomicBoolean unrecognized) {
        if (isSelectable(column, parsed, province, city)) return parsed;
        if (isSelectable(column, outParsed, province, city)) return outParsed;
        if (isSelectable(column, current, province, city)) return current;
        if (parsed != null || outParsed != null || current != null) {
            unrecognized.set(true);
        }
        return null;
    }

    /**
     * 判断某值是否为地址选择器中的可选项。
     * <ul>
     *   <li>province：规范省名（含直辖市全名）；</li>
     *   <li>city：若已知省份，必须归属该省；省份未知时只要是已知城市名即可保留；</li>
     *   <li>district：若已知省份，必须归属该省；若同时已知地级市，还必须归属该市。</li>
     * </ul>
     */
    private static boolean isSelectable(String column, String value, String province, String city) {
        if (value == null || value.isBlank()) return false;
        return switch (column) {
            case "province" -> ChinaAddressParser.isStandardProvince(value);
            case "city" -> isSelectableCity(value, province);
            case "district" -> isSelectableDistrict(value, province, city);
            default -> false;
        };
    }

    /**
     * 城市名能否被地址选择器识别：官方地级市需归属已知省份；县级市写法（含省直辖县级市）
     * 允许当市级使用并由字典确认其在该省的唯一归属；直辖市城市字段与省同名。
     */
    static boolean isSelectableCity(String value, String province) {
        return ChinaRegionDictionary.isUsableAsCity(value, province);
    }

    /** 区县名能否被地址选择器识别（省份已知时必须归属该省；地级市已知时还必须归属该市）。 */
    static boolean isSelectableDistrict(String value, String province, String city) {
        if (!ChinaRegionDictionary.isKnownDistrict(value)) return false;
        if (province == null || province.isBlank()) return true;
        if (city != null && !city.isBlank()
                && ChinaRegionDictionary.districtBelongsTo(value, province, city)) return true;
        // 县级市当市级时，区县字段允许与该县级市同名（如 市=仙桃市、区=仙桃市）
        if (ChinaRegionDictionary.isCountyCitySelfDistrict(value, province, city)) return true;
        ChinaRegionDictionary.DistrictResolve d = ChinaRegionDictionary.resolveDistrictToken(value, province);
        if (d == null) return false;
        if (ChinaRegionDictionary.isMunicipalityProvince(province)) return true;
        if (city == null || city.isBlank()) return true;
        return city.equals(d.city());
    }

    private static String notBlank(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
