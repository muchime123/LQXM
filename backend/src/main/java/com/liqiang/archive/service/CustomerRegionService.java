package com.liqiang.archive.service;

import com.liqiang.archive.dto.CustomerRegionDtos.RegionAliasCreateRequest;
import com.liqiang.archive.dto.CustomerRegionDtos.RegionAliasItem;
import com.liqiang.archive.entity.CustomerRegionAlias;
import com.liqiang.archive.repository.CustomerRegionAliasRepository;
import com.liqiang.archive.util.ChinaRegionDictionary;
import com.liqiang.archive.util.ChinaAddressParser;
import com.liqiang.archive.util.ChinaRegionDictionary.UserRegionAlias;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 地区写法别名与地区缺口导出（客户档案 · 地区缺口治理）。
 * <p>
 * 别名是 ChinaRegionDictionary 官方字典的持久化扩展：登记后立即对客户域
 * 写入口归一、分布聚合与地图下钻生效（字典仅在官方无法识别写法时兜底命中）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerRegionService {

    private final CustomerRegionAliasRepository aliasRepository;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;
    private final CustomerAddressTidyService addressTidyService;

    /** 应用启动后把库内别名灌入字典静态注册表，保证分布/写入口在任意首次调用前已生效。 */
    @PostConstruct
    void loadAliasesIntoDictionary() {
        reloadAliasRegistry();
    }

    // ==================== 别名 CRUD ====================

    public List<RegionAliasItem> list() {
        return aliasRepository.findAllByOrderByKindAscCreatedAtAsc().stream().map(this::toItem).toList();
    }

    @Transactional
    public RegionAliasItem create(RegionAliasCreateRequest req) {
        if (req == null || req.kind() == null || req.alias() == null || req.target() == null) {
            throw new IllegalArgumentException("请填写完整的写法别名信息");
        }
        String kind = switch (req.kind().trim().toUpperCase()) {
            case "PROVINCE", "CITY", "DISTRICT" -> req.kind().trim().toUpperCase();
            default -> throw new IllegalArgumentException("级别必须为 PROVINCE/CITY/DISTRICT");
        };
        String alias = ChinaRegionDictionary.simplify(req.alias().trim());
        if (alias.isEmpty()) throw new IllegalArgumentException("请填写写法内容");
        if (alias.length() > 60) throw new IllegalArgumentException("写法内容过长（≤60字）");

        String target;
        String scope;
        switch (kind) {
            case "PROVINCE" -> {
                target = requireOfficialProvince(req.target(), "省级");
                if (ChinaRegionDictionary.isOfficialProvinceName(alias)) {
                    throw new IllegalArgumentException("「" + alias + "」已是官方省级名称，无需登记别名");
                }
                ensureNotAlreadyHandled(alias, kind, null);
                scope = null;
            }
            case "CITY" -> {
                String city = ChinaRegionDictionary.canonicalCityName(req.target().trim(), null);
                if (!ChinaRegionDictionary.isKnownCity(city)) {
                    throw new IllegalArgumentException("目标「" + req.target().trim() + "」不是字典中的地级市（需官方全名）");
                }
                scope = ChinaRegionDictionary.provinceOfCity(city);
                if (scope == null) throw new IllegalArgumentException("目标城市无法归属省份");
                target = city;
                if (ChinaRegionDictionary.isKnownCity(alias)) {
                    throw new IllegalArgumentException("「" + alias + "」已是官方市名，无需登记别名");
                }
                ensureNotAlreadyHandled(alias, kind, scope);
            }
            default -> {
                String province = requireOfficialProvince(req.province(), "归属省份");
                String district = req.target().trim();
                if (district.isEmpty()) throw new IllegalArgumentException("请填写目标区县");
                String dNorm = ChinaRegionDictionary.simplify(district);
                if (!ChinaRegionDictionary.isKnownDistrict(dNorm)) {
                    throw new IllegalArgumentException("目标「" + district + "」不是字典中的区县（需官方全名）");
                }
                if (!ChinaRegionDictionary.isDistrictInProvince(dNorm, province)) {
                    throw new IllegalArgumentException("「" + district + "」不属于「" + province + "」的字典区县");
                }
                if (ChinaRegionDictionary.isKnownDistrict(alias)) {
                    throw new IllegalArgumentException("「" + alias + "」已是官方区县名，无需登记别名");
                }
                ensureNotAlreadyHandled(alias, kind, province);
                scope = province;
                target = dNorm;
            }
        }

        ensureNotDuplicate(kind, alias, scope);
        CustomerRegionAlias entity = new CustomerRegionAlias();
        entity.setKind(kind);
        entity.setAliasText(alias);
        entity.setScopeProvince(scope);
        entity.setTargetName(target);
        String remark = req.remark() == null ? null : req.remark().trim();
        entity.setRemark(remark == null || remark.isEmpty() ? null : remark);
        CustomerRegionAlias saved = aliasRepository.save(entity);
        auditLogService.log("CustomerRegionAlias", saved.getId(), "地区写法别名", "REGION_ALIAS_CREATE",
                describe(entity));
        reloadAliasRegistry();
        return toItem(saved);
    }

    @Transactional
    public void delete(Long id) {
        CustomerRegionAlias entity = aliasRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("地区写法别名不存在: " + id));
        aliasRepository.deleteById(id);
        auditLogService.log("CustomerRegionAlias", id, "地区写法别名", "REGION_ALIAS_DELETE", describe(entity));
        reloadAliasRegistry();
    }

    private String requireOfficialProvince(String value, String label) {
        String p = value == null ? null : ChinaRegionDictionary.simplify(value.trim());
        if (p == null || p.isEmpty() || !ChinaRegionDictionary.isOfficialProvinceName(p)) {
            throw new IllegalArgumentException((label == null ? "" : label) + "必须是官方省级行政区名称");
        }
        return p;
    }

    /** 该写法在当前字典路径（含已有别名）下是否已被识别；已识别则拒绝登记（避免惰性/覆盖别名）。 */
    private void ensureNotAlreadyHandled(String alias, String kind, String scope) {
        String current;
        switch (kind) {
            case "PROVINCE" -> current = ChinaRegionDictionary.canonicalProvinceName(alias);
            case "CITY" -> current = ChinaRegionDictionary.canonicalCityName(alias, scope);
            default -> current = ChinaRegionDictionary.canonicalDistrictName(alias, scope);
        }
        if (current != null && !alias.equals(current)) {
            throw new IllegalArgumentException("该写法当前已能被字典识别为「" + current + "」，无需登记别名");
        }
    }

    private void ensureNotDuplicate(String kind, String alias, String scope) {
        for (CustomerRegionAlias it : aliasRepository.findAll()) {
            if (kind.equals(it.getKind()) && alias.equals(it.getAliasText())
                    && eqNullable(scope, it.getScopeProvince())) {
                throw new IllegalArgumentException("已存在相同写法别名（" + alias + "），请勿重复登记");
            }
        }
    }

    private static boolean eqNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void reloadAliasRegistry() {
        List<UserRegionAlias> entries = aliasRepository.findAllByOrderByKindAscCreatedAtAsc().stream()
                .map(a -> new UserRegionAlias(a.getKind(), a.getAliasText(), a.getScopeProvince(), a.getTargetName()))
                .toList();
        ChinaRegionDictionary.replaceUserAliases(entries);
        log.info("Reloaded customer region aliases into dictionary: {}", entries.size());
    }

    private String describe(CustomerRegionAlias a) {
        return "级别=" + a.getKind() + "，写法=" + a.getAliasText()
                + (a.getScopeProvince() == null ? "" : "，归属=" + a.getScopeProvince())
                + "，映射到=" + a.getTargetName();
    }

    private RegionAliasItem toItem(CustomerRegionAlias a) {
        return new RegionAliasItem(a.getId(), a.getKind(), a.getAliasText(), a.getScopeProvince(),
                a.getTargetName(), a.getRemark(), a.getCreatedAt());
    }

    // ==================== 地区缺口导出 ====================

    /**
     * 按列表筛选口径导出客户地区字段 CSV：noRegion=true 导出「未标注地区」（省份为空或非官方省名）；
     * regionPartial=true 导出「待补市县」（省份已合法但市/区县缺失或状态异常）。
     * 与客户列表的筛选语义一致，便于对待标注/未收录的客户做离线补录后闭环。
     */
    public String buildRegionCsv(Boolean noRegion, Boolean regionPartial, String province, String city, String district) {
        boolean filterNoRegion = Boolean.TRUE.equals(noRegion);
        boolean filterPartial = !filterNoRegion && Boolean.TRUE.equals(regionPartial);
        if (filterNoRegion || filterPartial) addressTidyService.scanStatuses();
        List<String> where = new ArrayList<>(List.of("deleted_at IS NULL"));
        List<Object> args = new ArrayList<>();
        if (filterNoRegion) {
            where.add("(province IS NULL OR province = ''"
                    + " OR (province NOT IN (" + ChinaRegionDictionary.officialProvincePlaceholders() + ")"
                    + "     AND province <> '" + ChinaRegionDictionary.OVERSEAS_PROVINCE + "'))");
            args.addAll(ChinaRegionDictionary.officialProvinces());
        } else if (filterPartial) {
            where.add("(province IS NOT NULL AND province <> ''"
                    + " AND province IN (" + ChinaRegionDictionary.officialProvincePlaceholders() + ")"
                    + " AND (city IS NULL OR city = '' OR district IS NULL OR district = ''"
                    + "      OR address_tidy_status <> 'NORMAL'))");
            args.addAll(ChinaRegionDictionary.officialProvinces());
        } else {
            appendEqual(where, args, "province", province);
            appendEqual(where, args, "city", city);
            appendEqual(where, args, "district", district);
        }
        // 注意：SQL 走 String.formatted 填充，MySQL 日期格式里的 % 必须写成 %% 转义
        String sql = """
                SELECT customer_code, customer_name, province, city, district, address,address_tidy_status,
                       DATE_FORMAT(created_at, '%%Y-%%m-%%d')
                FROM customers
                WHERE %s
                ORDER BY province, city, district, customer_code
                LIMIT 20000
                """.formatted(String.join(" AND ", where));
        List<Object[]> rows = jdbc.query(sql, args.toArray(),
                (rs, i) -> {
                    String address = rs.getString(6);
                    ParseResult p = diagnose(address);
                    return new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), address,
                            regionIssue(rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(7)),
                            p.summary(),
                            p.province(), p.city(), p.district(), rs.getString(8)};
                });

        StringBuilder sb = new StringBuilder();
        sb.append("客户编号,客户名称,省份,城市,区县,详细地址,异常原因,解析诊断,建议省份,建议城市,建议区县,创建日期\n");
        for (Object[] row : rows) {
            List<String> cells = new ArrayList<>(row.length);
            for (Object o : row) cells.add(o == null ? "" : csvEscape(o.toString()));
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString();
    }

    private static String regionIssue(String province, String city, String district, String status) {
        List<String> issues = new ArrayList<>();
        if (province == null || province.isBlank()) issues.add("省份未填写");
        if (city == null || city.isBlank()) issues.add("城市未填写");
        if (district == null || district.isBlank()) issues.add("区县未填写");
        if (status != null && !status.isBlank() && !"NORMAL".equals(status)) issues.add("地址解析状态=" + status);
        return String.join("；", issues);
    }

    private static void appendEqual(List<String> where, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) return;
        where.add(column + " = ?");
        args.add(value.trim());
    }

    // ==================== 地区缺口 CSV 导入 ====================

    public Map<String, Object> previewRegionCsv(MultipartFile file) throws IOException {
        List<RegionImportRow> rows = parseRegionCsv(file);
        long valid = rows.stream().filter(r -> "VALID".equals(r.status())).count();
        long unchanged = rows.stream().filter(r -> "UNCHANGED".equals(r.status())).count();
        return Map.of("total", rows.size(), "valid", valid, "unchanged", unchanged,
                "errors", rows.size() - valid - unchanged, "items", rows);
    }

    @Transactional
    public Map<String, Object> applyRegionCsv(MultipartFile file) throws IOException {
        List<RegionImportRow> rows = parseRegionCsv(file);
        int applied = 0;
        for (RegionImportRow row : rows) {
            if (!"VALID".equals(row.status())) continue;
            jdbc.update("UPDATE customers SET province=?,city=?,district=?,updated_at=NOW() "
                            + "WHERE customer_code=? AND deleted_at IS NULL",
                    row.province(), row.city(), row.district(), row.customerCode());
            applied++;
        }
        int statusChanged = applied == 0 ? 0 : addressTidyService.scanStatuses();
        int errors = (int) rows.stream().filter(r -> "ERROR".equals(r.status())).count();
        auditLogService.log("CustomerRegion", null, "客户地区 CSV", "REGION_CSV_IMPORT",
                "写回 " + applied + " 家，错误 " + errors + " 行，治理状态变化 " + statusChanged + " 条");
        return Map.of("total", rows.size(), "applied", applied, "errors", errors,
                "unchanged", rows.size() - applied - errors, "tidyStatusChanged", statusChanged);
    }

    public record RegionImportRow(int rowNumber, String customerCode, String customerName,
                                  String province, String city, String district,
                                  String status, String message) {}

    // ==================== 地址批量解析 ====================

    /**
     * 扫描地区字段不完整或不符合地址选择器的客户，并预览可安全写回的解析结果。
     * 只有地址能解析出完整且属于同一行政区划链的省/市/区县时才允许写回。
     */
    public Map<String, Object> previewAutoParse() {
        List<AutoParseRow> rows = buildAutoParseRows();
        long ready = rows.stream().filter(r -> "READY".equals(r.status())).count();
        return Map.of("total", rows.size(), "ready", ready, "errors", rows.size() - ready, "items", rows);
    }

    @Transactional
    public Map<String, Object> applyAutoParse() {
        List<AutoParseRow> rows = buildAutoParseRows();
        int applied = 0;
        for (AutoParseRow row : rows) {
            if (!"READY".equals(row.status())) continue;
            if (writeBackRow(row) > 0) applied++;
        }
        int statusChanged = addressTidyService.scanStatuses();
        int errors = (int) rows.stream().filter(r -> "ERROR".equals(r.status())).count();
        auditLogService.log("CustomerRegion", null, "客户地址批量解析", "REGION_AUTO_PARSE",
                "扫描 " + rows.size() + " 家，写回 " + applied + " 家，未解析 " + errors
                        + " 家，治理状态变化 " + statusChanged + " 条");
        return Map.of("total", rows.size(), "applied", applied, "errors", errors,
                "tidyStatusChanged", statusChanged);
    }

    /**
     * 按列写回解析结果：某一列只在「为空或不是规范值」时才写，已有合法值一律保留。
     * 省/市/区县能补几级就补几级，未补上的层级留给人工。
     */
    private int writeBackRow(AutoParseRow row) {
        boolean writeProvince = row.currentProvince() == null || row.currentProvince().isBlank()
                || !ChinaRegionDictionary.isOfficialProvinceName(
                        ChinaRegionDictionary.canonicalProvinceName(row.currentProvince()));
        boolean writeCity = writeProvince
                || !isSelectable(row.currentCity(), p -> CustomerAddressTidyService.isSelectableCity(p, row.province()));
        boolean writeDistrict = writeCity
                || !isSelectable(row.currentDistrict(),
                        d -> CustomerAddressTidyService.isSelectableDistrict(d, row.province(), row.city()));
        if (!writeProvince && !writeCity && !writeDistrict) return 0;

        StringBuilder sql = new StringBuilder("UPDATE customers SET updated_at=NOW()");
        List<Object> args = new ArrayList<>();
        if (writeProvince && row.province() != null) {
            sql.append(",province=?");
            args.add(row.province());
        }
        if (writeCity && row.city() != null) {
            sql.append(",city=?");
            args.add(row.city());
        }
        if (writeDistrict && row.district() != null) {
            sql.append(",district=?");
            args.add(row.district());
        }
        sql.append(" WHERE id=? AND deleted_at IS NULL");
        args.add(row.id());
        return jdbc.update(sql.toString(), args.toArray());
    }

    private static boolean isSelectable(String value, Predicate<String> checker) {
        if (value == null || value.isBlank()) return false;
        return checker.test(value);
    }

    public record AutoParseRow(Long id, String customerCode, String customerName, String address,
                               String currentProvince, String currentCity, String currentDistrict,
                               String province, String city, String district,
                               String status, String message) {}

    private List<AutoParseRow> buildAutoParseRows() {
        // 先重算治理状态，已具备合法三级字段但状态滞后的客户不会继续进入待处理清单。
        addressTidyService.scanStatuses();
        String sql = """
                SELECT id,customer_code,customer_name,address,province,city,district
                FROM customers
                WHERE deleted_at IS NULL
                  AND (province IS NULL OR province='' OR city IS NULL OR city=''
                       OR district IS NULL OR district='' OR address_tidy_status <> 'NORMAL')
                ORDER BY id
                LIMIT 20000
                """;
        return jdbc.query(sql, (rs, i) -> autoParseRow(
                rs.getLong("id"), rs.getString("customer_code"), rs.getString("customer_name"),
                rs.getString("address"), rs.getString("province"), rs.getString("city"),
                rs.getString("district")));
    }

    private AutoParseRow autoParseRow(Long id, String code, String name, String address,
                                      String currentProvince, String currentCity, String currentDistrict) {
        ParseResult p = diagnose(address);
        String error = null;
        if (p.ready() && p.conflict(currentProvince, currentCity, currentDistrict)) {
            error = "档案已填的省份与地址解析结果不一致，未自动覆盖";
        }
        return new AutoParseRow(id, code, name, address,
                currentProvince, currentCity, currentDistrict, p.province(), p.city(), p.district(),
                (p.ready() && error == null) ? "READY" : "ERROR",
                error != null ? error : p.summary());
    }

    /** 单条地址的解析诊断结果：可写回的省/市/区县 + 失败原因（null 表示至少能补省级）。 */
    public record ParseResult(String province, String city, String district, String reason) {

        /** 是否可写回：只要省份能定为官方省名即可（市/区县允许留空后续人工补）。 */
        boolean ready() {
            return reason == null && province != null;
        }

        /** 可写回层级的人话说明，用于预览列表与 CSV 导出。 */
        String summary() {
            if (reason != null || province == null) return reason == null ? "无法解析" : reason;
            if (district != null) return "可写回省/市/区县";
            if (city != null) return "可写回省/市（区县待人工补）";
            return "仅可写回省（市/区县待人工补）";
        }

        /**
         * 档案现有字段是否足以否决自动写回。只有「省份已合法且与地址解析结果不一致」才否决——
         * 市/区县即使不一致也不否决，写回时本来只补空白或非法值，不会覆盖已有合法值。
         */
        boolean conflict(String currentProvince, String currentCity, String currentDistrict) {
            if (!ready()) return false;
            String cp = currentProvince == null ? null
                    : ChinaRegionDictionary.canonicalProvinceName(currentProvince);
            return cp != null && ChinaRegionDictionary.isOfficialProvinceName(cp) && !cp.equals(province);
        }
    }

    /**
     * 解析单条地址并判定能写回的层级。规则（2026-09-14 起为「能补多少补多少」）：
     * <ol>
     *   <li>省份必须能解析为官方省名，否则整条不可写回（上不了地图，写了也没用）；</li>
     *   <li>市、区县逐级校验：能过就补，过不了留空——不再要求三级齐全才写回；</li>
     *   <li>县级市当市级（如 湖北省/天门市）且区县解析不出规范值时，区县回退为该县级市自身；</li>
     *   <li>解析优先走 {@link ChinaAddressParser#parseLoose}，带区划字典的文本扫描兜底。</li>
     * </ol>
     */
    public static ParseResult diagnose(String address) {
        if (address == null || address.isBlank()) {
            return new ParseResult(null, null, null, "详细地址为空");
        }
        ChinaAddressParser.ParsedAddress parsed = ChinaAddressParser.parseLoose(address);
        String province = ChinaRegionDictionary.canonicalProvinceName(parsed.province());
        if (province == null || !ChinaRegionDictionary.isOfficialProvinceName(province)) {
            return new ParseResult(null, null, null, "无法解析规范省份");
        }
        String city = parsed.city() == null ? null
                : ChinaRegionDictionary.canonicalCityName(parsed.city(), province);
        if (city == null || !CustomerAddressTidyService.isSelectableCity(city, province)) {
            return new ParseResult(province, null, null, null);
        }
        String district = parsed.district() == null ? null
                : ChinaRegionDictionary.canonicalDistrictName(parsed.district(), province);
        // 县级市自辖回退：湖北省/天门市 这类没有下辖区的县级市，区县取其自身
        if (district == null || !CustomerAddressTidyService.isSelectableDistrict(district, province, city)) {
            String self = ChinaRegionDictionary.asCityOfCountyLevelCity(city, province);
            if (self != null && self.equals(city)) district = city;
        }
        if (district == null || !CustomerAddressTidyService.isSelectableDistrict(district, province, city)) {
            return new ParseResult(province, city, null, null);
        }
        return new ParseResult(province, city, district, null);
    }

    // ==================== 出库单地区回填（无地址客户兜底） ====================

    /**
     * 「未标注省份 且 详细地址为空」的客户（典型：出库单运输方式全为自提，天然没有收货地址），
     * 退而求其次用其销售出库单上的氚云「地区」标注回填省份。地区标注是省级粒度
     * （如「浙江省（浙）」「内蒙古（蒙）」），故只补省级，市/区县留待人工。
     */
    public Map<String, Object> previewRegionFallback() {
        List<RegionFallbackRow> rows = buildRegionFallbackRows();
        long ready = rows.stream().filter(r -> "READY".equals(r.status())).count();
        return Map.of("total", rows.size(), "ready", ready, "errors", rows.size() - ready, "items", rows);
    }

    @Transactional
    public Map<String, Object> applyRegionFallback() {
        List<RegionFallbackRow> rows = buildRegionFallbackRows();
        int applied = 0;
        for (RegionFallbackRow row : rows) {
            if (!"READY".equals(row.status())) continue;
            applied += jdbc.update(
                    "UPDATE customers SET province=?, updated_at=NOW() WHERE id=? AND deleted_at IS NULL "
                            + "AND (province IS NULL OR province='')",
                    row.province(), row.id());
        }
        int statusChanged = applied == 0 ? 0 : addressTidyService.scanStatuses();
        int errors = (int) rows.stream().filter(r -> "ERROR".equals(r.status())).count();
        auditLogService.log("CustomerRegion", null, "出库单地区回填", "REGION_OUTBOUND_FALLBACK",
                "扫描 " + rows.size() + " 家，写回省份 " + applied + " 家，无法处理 " + errors
                        + " 家，治理状态变化 " + statusChanged + " 条");
        return Map.of("total", rows.size(), "applied", applied, "errors", errors,
                "tidyStatusChanged", statusChanged);
    }

    public record RegionFallbackRow(Long id, String customerCode, String customerName, String address,
                                    String outboundRegions, String province, String status, String message) {}

    private List<RegionFallbackRow> buildRegionFallbackRows() {
        String sql = """
                SELECT c.id, c.customer_code, c.customer_name, c.address,
                       GROUP_CONCAT(DISTINCT o.region ORDER BY o.region SEPARATOR ' | ') regions
                FROM customers c
                LEFT JOIN sales_outbounds o ON o.customer_id = c.id AND o.region IS NOT NULL AND o.region <> ''
                WHERE c.deleted_at IS NULL
                  AND (c.address IS NULL OR c.address = '')
                  AND (c.province IS NULL OR c.province = ''
                       OR (c.province NOT IN (%s) AND c.province <> '%s'))
                GROUP BY c.id
                ORDER BY c.id
                LIMIT 20000
                """.formatted(ChinaRegionDictionary.officialProvincePlaceholders(),
                ChinaRegionDictionary.OVERSEAS_PROVINCE);
        List<Object> args = new ArrayList<>(ChinaRegionDictionary.officialProvinces());
        return jdbc.query(sql, args.toArray(), (rs, i) -> {
            String regions = rs.getString(5);
            return regionFallbackRow(rs.getLong("id"), rs.getString("customer_code"),
                    rs.getString("customer_name"), rs.getString("address"), regions);
        });
    }

    private RegionFallbackRow regionFallbackRow(Long id, String code, String name, String address, String regions) {
        if (regions == null || regions.isBlank()) {
            return new RegionFallbackRow(id, code, name, address, null, null, "ERROR", "出库单没有地区信息");
        }
        // 「国外」等非省级行政区标注不参与省份判定，但保留在展示文本里
        var mapped = java.util.Arrays.stream(regions.split(" \\| "))
                .map(ChinaRegionDictionary::canonicalProvinceFromRegionTag)
                .filter(p -> p != null && ChinaRegionDictionary.isOfficialProvinceName(p))
                .distinct()
                .toList();
        if (mapped.size() > 1) {
            return new RegionFallbackRow(id, code, name, address, regions, null, "ERROR",
                    "出库单地区涉及多个省份（" + String.join("、", mapped) + "），需人工判定");
        }
        if (mapped.size() == 1) {
            return new RegionFallbackRow(id, code, name, address, regions, mapped.getFirst(), "READY",
                    "可回填省份（市/区县待人工补）");
        }
        if (java.util.Arrays.stream(regions.split(" \\| "))
                .anyMatch(ChinaRegionDictionary::isOverseasProvince)) {
            return new RegionFallbackRow(id, code, name, address, regions, null, "ERROR",
                    "出库单地区为「国外」，请在「境外客户处理」中判定归属");
        }
        return new RegionFallbackRow(id, code, name, address, regions, null, "ERROR",
                "出库单地区无法识别规范省份（" + regions + "）");
    }

    // ==================== 境外（国外）客户判定 ====================

    /**
     * 「出库单地区标注为「国外」」的客户判定清单。这类客户形态混杂：既可能是真实外贸客户
     * （外文地址、境外电话），也可能是国内经销商转发到港澳台/海外（典型：香港地址 + 国内经销商），
     * 因此不自动归类，逐条给出建议后由人工确认。
     */
    public Map<String, Object> previewOverseasDecisions() {
        List<OverseasRow> rows = buildOverseasRows();
        long overseas = rows.stream().filter(r -> "OVERSEAS".equals(r.suggested())).count();
        return Map.of("total", rows.size(), "overseas", overseas,
                "domestic", rows.size() - overseas, "items", rows);
    }

    /**
     * 写回人工判定：OVERSEAS → 省份置「境外」（不再参与大陆地区治理）；
     * DOMESTIC → 按档案地址解析出的省/市/区县回填；PENDING → 保持原状。
     */
    @Transactional
    public Map<String, Object> applyOverseasDecisions(List<OverseasDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) throw new IllegalArgumentException("请先选择需要处理的客户");
        int overseas = 0;
        int domestic = 0;
        int skipped = 0;
        for (OverseasDecision d : decisions) {
            if (d == null || d.customerId() == null) continue;
            String decision = d.decision() == null ? "PENDING" : d.decision().trim().toUpperCase();
            switch (decision) {
                case "OVERSEAS" -> {
                    jdbc.update("UPDATE customers SET province=?,city=NULL,district=NULL,updated_at=NOW() "
                                    + "WHERE id=? AND deleted_at IS NULL",
                            ChinaRegionDictionary.OVERSEAS_PROVINCE, d.customerId());
                    overseas++;
                }
                case "DOMESTIC" -> {
                    ParseResult p = diagnose(customerAddress(d.customerId()));
                    if (!p.ready()) {
                        skipped++;
                        break;
                    }
                    jdbc.update("UPDATE customers SET province=?,city=?,district=?,updated_at=NOW() "
                                    + "WHERE id=? AND deleted_at IS NULL",
                            p.province(), p.city(), p.district(), d.customerId());
                    domestic++;
                }
                default -> skipped++;
            }
        }
        int statusChanged = addressTidyService.scanStatuses();
        auditLogService.log("CustomerRegion", null, "境外客户判定", "REGION_OVERSEAS_DECISION",
                "提交 " + decisions.size() + " 家：标记境外 " + overseas + " 家，按地址回填 " + domestic
                        + " 家，保持待定 " + skipped + " 家，治理状态变化 " + statusChanged + " 条");
        return Map.of("overseas", overseas, "domestic", domestic, "skipped", skipped,
                "tidyStatusChanged", statusChanged);
    }

    /** 人工判定条目：OVERSEAS（标记境外）/ DOMESTIC（按地址回填）/ PENDING（保持待定）。 */
    public record OverseasDecision(Long customerId, String decision) {}

    public record OverseasRow(Long id, String customerCode, String customerName, String address,
                              String contactPhone, String outboundRegions, String dealerNames,
                              int orderCount, String suggested, String suggestedProvince,
                              String message) {}

    private List<OverseasRow> buildOverseasRows() {
        String sql = """
                SELECT c.id, c.customer_code, c.customer_name, c.address, c.province,
                       COUNT(o.id) order_count,
                       GROUP_CONCAT(DISTINCT o.region ORDER BY o.region SEPARATOR ' | ') regions,
                       GROUP_CONCAT(DISTINCT o.dealer_name ORDER BY o.dealer_name SEPARATOR ' | ') dealers,
                       SUBSTRING_INDEX(GROUP_CONCAT(o.contact_phone ORDER BY o.ship_date DESC), ',', 1) phone
                FROM customers c
                JOIN sales_outbounds o ON o.customer_id = c.id
                WHERE c.deleted_at IS NULL
                  AND o.region LIKE '%%国外%%'
                  AND (c.province IS NULL OR c.province <> '%s')
                GROUP BY c.id
                ORDER BY order_count DESC, c.id
                LIMIT 2000
                """.formatted(ChinaRegionDictionary.OVERSEAS_PROVINCE);
        return jdbc.query(sql, (rs, i) -> {
            Long id = rs.getLong("id");
            String address = rs.getString("address");
            ParseResult parsed = diagnose(address);
            boolean hasDomestic = parsed.ready();
            String phone = rs.getString("phone");
            String message = hasDomestic
                    ? "档案地址可解析出「" + parsed.summary() + "」，若不是真外贸客户可选「按地址回填」"
                    : (address == null || address.isBlank()
                            ? "档案无地址，需人工判断是否为真实外贸客户"
                            : "档案地址解析不出国内行政区划，通常为真实外贸客户");
            return new OverseasRow(id, rs.getString("customer_code"), rs.getString("customer_name"),
                    address, phone, rs.getString("regions"), rs.getString("dealers"),
                    rs.getInt("order_count"),
                    hasDomestic ? "DOMESTIC_FORWARD" : "OVERSEAS",
                    parsed.province(), message);
        });
    }

    private String customerAddress(Long customerId) {
        List<String> rows = jdbc.queryForList("SELECT address FROM customers WHERE id=? AND deleted_at IS NULL",
                String.class, customerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<RegionImportRow> parseRegionCsv(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择地区整理文件");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("导入文件不能超过 10MB");
        byte[] bytes = file.getBytes();
        List<List<String>> table = isExcel(bytes) ? parseExcel(bytes) : parseCsvText(bytes);
        if (table.isEmpty()) throw new IllegalArgumentException("CSV 文件没有内容");
        Map<String, Integer> headers = new HashMap<>();
        for (int i = 0; i < table.getFirst().size(); i++) headers.put(table.getFirst().get(i).trim(), i);
        aliasHeader(headers, "省份", "正式省份");
        aliasHeader(headers, "城市", "正式城市");
        aliasHeader(headers, "区县", "正式区县");
        for (String required : List.of("客户编号", "省份", "城市", "区县"))
            if (!headers.containsKey(required)) throw new IllegalArgumentException("导入文件缺少列：" + required);
        if (table.size() > 20001) throw new IllegalArgumentException("一次最多导入 20000 家客户");

        List<RegionImportRow> result = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            if (cells.stream().allMatch(v -> v == null || v.isBlank())) continue;
            String code = cell(cells, headers.get("客户编号"));
            String name = headers.containsKey("客户名称") ? cell(cells, headers.get("客户名称")) : null;
            String rawProvince = cell(cells, headers.get("省份"));
            String rawCity = cell(cells, headers.get("城市"));
            String rawDistrict = cell(cells, headers.get("区县"));
            if (code == null) {
                result.add(new RegionImportRow(i + 1, null, name, rawProvince, rawCity, rawDistrict,
                        "ERROR", "客户编号为空"));
                continue;
            }
            List<Map<String, Object>> customers = jdbc.queryForList(
                    "SELECT customer_name,province,city,district FROM customers WHERE customer_code=? AND deleted_at IS NULL", code);
            if (customers.isEmpty()) {
                result.add(new RegionImportRow(i + 1, code, name, rawProvince, rawCity, rawDistrict,
                        "ERROR", "客户编号不存在或档案已删除"));
                continue;
            }
            Map<String, Object> current = customers.getFirst();
            String province = ChinaRegionDictionary.canonicalProvinceName(rawProvince);
            if (province == null || !ChinaRegionDictionary.isOfficialProvinceName(province)) {
                result.add(new RegionImportRow(i + 1, code, String.valueOf(current.get("customer_name")),
                        rawProvince, rawCity, rawDistrict, "ERROR", "省份无法识别"));
                continue;
            }
            String city = rawCity == null ? null : ChinaRegionDictionary.canonicalCityName(rawCity, province);
            if (rawCity != null && !CustomerAddressTidyService.isSelectableCity(city, province)) {
                result.add(new RegionImportRow(i + 1, code, String.valueOf(current.get("customer_name")),
                        province, rawCity, rawDistrict, "ERROR", "城市不属于所选省份或无法识别"));
                continue;
            }
            String district = rawDistrict == null ? null : ChinaRegionDictionary.canonicalDistrictName(rawDistrict, province);
            if (rawDistrict != null && !CustomerAddressTidyService.isSelectableDistrict(district, province, city)) {
                result.add(new RegionImportRow(i + 1, code, String.valueOf(current.get("customer_name")),
                        province, city, rawDistrict, "ERROR", "区县不属于所选省市或无法识别"));
                continue;
            }
            boolean same = sameText(province, current.get("province")) && sameText(city, current.get("city"))
                    && sameText(district, current.get("district"));
            result.add(new RegionImportRow(i + 1, code, String.valueOf(current.get("customer_name")),
                    province, city, district, same ? "UNCHANGED" : "VALID", same ? "与当前档案一致" : "可写回"));
        }
        return result;
    }

    private static boolean isExcel(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                || bytes.length >= 8 && bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF;
    }

    private static List<List<String>> parseCsvText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return parseCsv(text);
    }

    /** 文件内容优先：即使用户把 Excel 工作簿保存成 .csv 扩展名，也按真实格式读取。 */
    private static List<List<String>> parseExcel(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("地区更新");
            if (sheet == null) sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<List<String>> table = new ArrayList<>();
            int width = sheet.getRow(sheet.getFirstRowNum()) == null ? 0
                    : sheet.getRow(sheet.getFirstRowNum()).getLastCellNum();
            for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
                Row source = sheet.getRow(i);
                List<String> row = new ArrayList<>();
                for (int c = 0; c < width; c++) row.add(source == null ? "" : formatter.formatCellValue(source.getCell(c)));
                table.add(row);
            }
            return table;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Excel 文件无法读取，请确认文件没有损坏", ex);
        }
    }

    private static void aliasHeader(Map<String, Integer> headers, String standard, String alias) {
        if (!headers.containsKey(standard) && headers.containsKey(alias)) headers.put(standard, headers.get(alias));
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (!row.isEmpty() || cell.length() > 0) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    private static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) return null;
        String value = row.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean sameText(String value, Object current) {
        String a = value == null ? "" : value.trim();
        String b = current == null ? "" : String.valueOf(current).trim();
        return a.equals(b);
    }

    private static String csvEscape(String v) {
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
