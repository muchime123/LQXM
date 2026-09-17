package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.util.ChinaAddressParser;
import com.liqiang.archive.util.ProductSourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SourceIdentityService {
    private static final Pattern CONTACT_NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9])(?:\\+?\\d{1,4}[- ]?)?(?:0\\d{2,3}[- ]?)?\\d{7,11}(?![A-Za-z0-9])");
    private static final Pattern CLEAR_TRAILING_PHONE = Pattern.compile(
            "^\\s*([\\p{L}][\\p{L} .'-]{1,100}?)[,，\\s]+((?:\\+?\\d)[\\d -]{6,}\\d)[,，。\\s]*$");
    private static final Pattern CLEAR_ATTACHED_LATIN_PHONE = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z .'-]{1,60}?)(\\d{7,11})[,，。\\s]*$");
    /** 电话切分：聚类两两比较时会被高频调用，必须预编译，避免每次 Pattern.compile。 */
    private static final Pattern PHONE_TOKEN = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?(?:1[3-9]\\d{9}|0\\d{2,3}[- ]?\\d{7,8}|\\d{7,11})(?!\\d)");
    private static final Pattern ADDRESS_SEPARATOR = Pattern.compile("[\\s,，。.;；、]+");
    private static final Pattern PHONE_SEPARATOR = Pattern.compile("[\\s,，。.;；、-]+");
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    private final AuditLogService audit;
    /** 待处理口径索引：customerId -> 出库行身份统计（待处理/已处理 + 归一地址/电话集合），口径与候选簇抽屉完全一致。 */
    private volatile Map<Long, CustomerOutboundStats> outboundStats;
    private volatile long outboundStatsAt;
    /** 已确认身份映射的全局键集合（归一名称|簇键）。身份归属以映射为权威，判定与写入共用同一套键。 */
    private volatile Set<String> confirmedIdentityKeys;
    private volatile long confirmedIdentityKeysAt;
    /** 出库行本身不会在导入后改写客户名/地址/电话，缓存只需兜底导入与外部写入，故给一个短 TTL。 */
    private static final long PENDING_OUTBOUNDS_TTL_MS = 60_000L;

    public Long resolvedTarget(String entityType, String sourceKey, String sourceName) {
        String key = key(sourceKey, sourceName);
        if (key == null) return null;
        String normalized = normalize(sourceName);
        String identityCondition = !blank(sourceKey)
                ? "source_key=?"
                : "source_key=? OR (? IS NOT NULL AND normalized_name=?)";
        Object[] params = !blank(sourceKey)
                ? new Object[]{entityType, key}
                : new Object[]{entityType, key, normalized, normalized};
        String activeTarget = switch (entityType) {
            case "CUSTOMER" -> " AND EXISTS(SELECT 1 FROM customers c WHERE c.id=target_id AND c.deleted_at IS NULL)";
            case "EMPLOYEE" -> " AND EXISTS(SELECT 1 FROM employees e WHERE e.id=target_id AND e.status='ACTIVE')";
            case "PRODUCT" -> " AND EXISTS(SELECT 1 FROM sales_products p WHERE p.id=target_id AND p.deleted_at IS NULL)";
            case "WAREHOUSE" -> " AND EXISTS(SELECT 1 FROM warehouses w WHERE w.id=target_id AND w.active=1)";
            default -> "";
        };
        List<Long> ids = jdbc.query("""
            SELECT target_id FROM source_identity_aliases
            WHERE source_system='H3YUN' AND entity_type=? AND (""" + identityCondition + """
              ) AND resolution_status IN ('AUTO','CONFIRMED') AND target_id IS NOT NULL
            """ + activeTarget, (rs, n) -> rs.getLong(1), params);
        return ids.size() == 1 ? ids.getFirst() : null;
    }

    /**
     * 解析氚云交易产品。产品编码优先；没有编码时才使用名称、规格、单位组成的稳定来源键。
     * 未找到唯一产品时只进入复核队列，不在销售出库导入过程中静默新建产品；生产任务不参与解析。
     */
    @Transactional
    public Long resolveProduct(String sourceCode, String sourceName, String specification, String unit) {
        if (blank(sourceCode) && blank(sourceName)) return null;
        String sourceKey = ProductSourceKey.of(sourceCode, sourceName, specification, unit);
        Long mapped = resolvedTarget("PRODUCT", sourceKey, sourceName);
        if (mapped != null) return mapped;

        if (!blank(sourceCode)) {
            List<Long> aliases = jdbc.query("""
                SELECT sales_product_id FROM sales_product_source_aliases
                WHERE source_system='H3YUN' AND source_product_code=?
                  AND resolution_status='RESOLVED' AND sales_product_id IS NOT NULL
                """, (rs, n) -> rs.getLong(1), sourceCode.trim());
            if (aliases.size() == 1) {
                saveDecision("PRODUCT", sourceKey, sourceName, aliases.getFirst(), "AUTO", null,
                        "氚云产品编码命中已确认产品别名", 1.0);
                return aliases.getFirst();
            }
        }

        List<Long> exact = jdbc.query("""
            SELECT id FROM sales_products
            WHERE deleted_at IS NULL AND product_name=?
              AND COALESCE(specification,'')=? AND COALESCE(unit,'')=?
            ORDER BY id
            """, (rs, n) -> rs.getLong(1), clean(sourceName), clean(specification), clean(unit));
        if (exact.size() == 1) {
            saveDecision("PRODUCT", sourceKey, sourceName, exact.getFirst(), "AUTO", null,
                    "产品名称、规格和单位唯一命中", .98);
            return exact.getFirst();
        }
        saveDecision("PRODUCT", sourceKey, sourceName, null, "REVIEW",
                exact.isEmpty() ? "PRODUCT_NOT_FOUND" : "AMBIGUOUS_PRODUCT",
                exact.isEmpty() ? "未找到交易产品，等待人工选择或由库存合并建立" : "存在多个同名同规格产品，等待人工确认", null);
        return null;
    }

    /**
     * 库存台账合并建档后的联动（勿在出库导入路径调用）：
     * 把该产品来源键在身份解析里的 REVIEW 记录升级为 AUTO，并回填历史销售出库明细中
     * 尚未关联到任何产品的行——否则合并建立的产品无法解救出库侧已判 REVIEW 的存量。
     *
     * @return 回填的销售出库明细行数
     */
    @Transactional
    public int promoteProductResolution(String sourceCode, String sourceName, String specification,
                                        String unit, long productId) {
        if (blank(sourceCode) && blank(sourceName)) return 0;
        String sourceKey = ProductSourceKey.of(sourceCode, sourceName, specification, unit);
        jdbc.update("""
            UPDATE source_identity_aliases SET target_id=?, resolution_status='AUTO', issue_code=NULL,
              resolution_note='库存台账合并建档，自动解除复核', resolved_at=NOW()
            WHERE source_system='H3YUN' AND entity_type='PRODUCT'
              AND source_key=? AND resolution_status='REVIEW'
            """, productId, key(sourceKey, sourceName));
        return backfillUnresolved("PRODUCT", sourceKey, sourceName, productId);
    }

    private String existingStatus(String entityType, String sourceName) {
        return existingStatus(entityType, null, sourceName);
    }

    private String existingStatus(String entityType, String sourceKey, String sourceName) {
        String stableKey = key(sourceKey, sourceName);
        String normalized = normalize(sourceName);
        if (stableKey == null || normalized == null) return null;
        if (!blank(sourceKey)) {
            List<String> statuses = jdbc.query("""
                SELECT resolution_status FROM source_identity_aliases
                WHERE source_system='H3YUN' AND entity_type=? AND source_key=?
                ORDER BY id LIMIT 2
                """, (rs, n) -> rs.getString(1), entityType, stableKey);
            return statuses.size() == 1 ? statuses.getFirst() : null;
        }
        List<String> statuses = jdbc.query("""
            SELECT resolution_status FROM source_identity_aliases
            WHERE source_system='H3YUN' AND entity_type=? AND normalized_name=?
            ORDER BY id LIMIT 2
            """, (rs, n) -> rs.getString(1), entityType, normalized);
        return statuses.size() == 1 ? statuses.getFirst() : null;
    }

    @Transactional
    public Long resolveCustomer(String sourceName) {
        return resolveCustomer(sourceName, null);
    }

    private Long resolveCustomer(String sourceName, String address) {
        if (blank(sourceName)) return null;
        String name = sourceName.trim();
        Long mapped = resolvedTarget("CUSTOMER", null, name);
        if (mapped != null) return mapped;
        // 已进入复核或明确忽略的来源，在人工处理前不再走自动精确/模糊绑定。
        String existingStatus = existingStatus("CUSTOMER", name);
        if ("REVIEW".equals(existingStatus) || "IGNORED".equals(existingStatus)) return null;

        List<Long> exact = jdbc.query(
                "SELECT id FROM customers WHERE deleted_at IS NULL AND customer_name=? ORDER BY id",
                (rs, n) -> rs.getLong(1), name);
        if (exact.size() == 1) {
            saveDecision("CUSTOMER", null, name, exact.getFirst(), "AUTO", null, "客户全称唯一命中", 1.0);
            return exact.getFirst();
        }

        String normalized = normalize(name);
        List<Long> normalizedIds = jdbc.query("""
            SELECT id FROM customers WHERE deleted_at IS NULL
              AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
            ORDER BY id
            """, (rs, n) -> rs.getLong(1), normalized);
        if (normalizedIds.size() == 1) {
            saveDecision("CUSTOMER", null, name, normalizedIds.getFirst(), "AUTO", null,
                    "客户规范化全称唯一命中", .98);
            return normalizedIds.getFirst();
        }

        List<Long> candidates = name.length() < 2 ? List.of() : jdbc.query("""
            SELECT id FROM customers WHERE deleted_at IS NULL
              AND (customer_name LIKE CONCAT('%',?,'%') OR ? LIKE CONCAT('%',customer_name,'%'))
            ORDER BY id LIMIT 3
            """, (rs, n) -> rs.getLong(1), name, name);
        if (!candidates.isEmpty()) {
            saveDecision("CUSTOMER", null, name, null, "REVIEW", "AMBIGUOUS_CUSTOMER",
                    "发现“互为子串”候选，已停止自动绑定", null);
            return null;
        }

        Long created = createPendingCustomer(name, address);
        saveDecision("CUSTOMER", null, name, created, "AUTO", "NEW_CUSTOMER_REVIEW",
                "未发现候选，已建立待复核客户", .70);
        return created;
    }

    /**
     * 销售出库客户识别（销售重建设计 §2「客户档案」的识别优先级）。
     *
     * <p>优先级：① 人工已确认的来源簇映射 → ② 已确认的同一电话/地址组合 →
     * ③ 标准化电话精确一致 → ④ 客户名称与电话组合一致 → ⑤ 客户名称与标准化地址组合一致
     * → ⑥ 无电话冲突时才复用名称级结论 → ⑦ 仅名称相似只给候选。
     *
     * <p>只有「唯一命中」才自动绑定；多候选与泛化名称（王先生/李经理/客户…）一律只入复核队列，
     * 由销售出库关联工作台集中处理，不以泛化名称单独作为合并依据。
     */
    @Transactional
    public Long resolveCustomer(String sourceName, String address, String phone) {
        String name = clean(sourceName);
        if (blank(name)) return null;

        // ① 人工确认的来源簇（名称 + 地址/电话簇）：最具体，优先于名称级结论
        if (!blank(address) || !blank(phone)) {
            List<Long> mapped = jdbc.query("""
                SELECT m.customer_id FROM customer_source_cluster_mappings m
                JOIN customers c ON c.id=m.customer_id AND c.deleted_at IS NULL
                WHERE m.source_system='H3YUN' AND m.normalized_source_name=? AND m.cluster_key=? AND m.status='CONFIRMED'
                """, (rs, n) -> rs.getLong(1), identityName(sourceName), clusterKey(address, phone));
            if (mapped.size() == 1) return mapped.getFirst();
        }

        boolean generic = genericCustomerName(name);
        // 电话可能出现在电话列，也可能夹在地址文本里，两处一起取 token
        List<String> phones = phoneLookupTokens(Objects.toString(phone, "") + " " + Objects.toString(address, ""));
        String evidenceKey = (!blank(address) || !phones.isEmpty())
                ? "CUSTOMER_CLUSTER:" + clusterKey(address, phone) : null;
        // ② 算法已处理过的同一证据组合可直接复用；REVIEW/IGNORED 组合继续保持不自动绑定。
        Long evidenceDecision = resolvedTarget("CUSTOMER", evidenceKey, name);
        if (evidenceDecision != null) return evidenceDecision;
        String evidenceStatus = existingStatus("CUSTOMER", evidenceKey, name);
        if ("REVIEW".equals(evidenceStatus) || "IGNORED".equals(evidenceStatus)) return null;

        // 泛化名称不参与名称级比对，避免「王先生」被当成同一客户
        List<Long> byPhone = phones.isEmpty() ? List.of() : customerIdsByPhone(phones);
        List<Long> byName = generic ? List.of() : customerIdsByName(name);

        // ③ 标准化电话精确一致（电话是客户划分的主依据）
        if (byPhone.size() == 1) {
            saveDecision("CUSTOMER", evidenceKey, name, byPhone.getFirst(), "AUTO", null,
                    "标准化电话唯一命中：" + phones.getFirst(), .99);
            return byPhone.getFirst();
        }

        // ④ 客户名称与电话组合一致：电话命中多个客户时用名称收敛
        if (!byName.isEmpty() && !byPhone.isEmpty()) {
            List<Long> both = byName.stream().filter(byPhone::contains).toList();
            if (both.size() == 1) {
                saveDecision("CUSTOMER", evidenceKey, name, both.getFirst(), "AUTO", null,
                        "客户名称与电话组合唯一命中", .97);
                return both.getFirst();
            }
        }

        // ⑤ 客户名称与标准化地址组合一致
        if (!byName.isEmpty()) {
            List<Long> byAddress = customerIdsByNormalizedAddress(address);
            if (!byAddress.isEmpty()) {
                List<Long> both = byName.stream().filter(byAddress::contains).toList();
                if (both.size() == 1) {
                    saveDecision("CUSTOMER", evidenceKey, name, both.getFirst(), "AUTO", null,
                            "客户名称与标准化地址组合唯一命中", .95);
                    return both.getFirst();
                }
            }
        }

        // 电话命中多个客户且名称也收敛不了：只入复核，交工作台人工拆分
        if (byPhone.size() > 1) {
            saveDecision("CUSTOMER", evidenceKey, name, null, "REVIEW", "AMBIGUOUS_CUSTOMER_PHONE",
                    "同一电话对应多个客户，已停止自动绑定，待人工拆分", null);
            return null;
        }

        // 有有效电话、但电话尚未归属任何档案；若名称已命中既有客户，不能仅凭同名合并。
        // 该组合保留待复核，后续可确认是新联系人、电话误录或同名不同客户。
        if (!phones.isEmpty() && byPhone.isEmpty() && !byName.isEmpty()) {
            saveDecision("CUSTOMER", evidenceKey, name, null, "REVIEW", "KNOWN_NAME_NEW_PHONE",
                    "客户名称已有档案，但本次电话尚未归属，需确认是否为同一客户", null);
            return null;
        }

        // ⑥ 名称级旧结论只能在没有新电话冲突时复用。新电话尚未出现在任何档案中时，
        // 单独保留该证据组合待复核，不能让旧名称结论吞并新客户。
        Long decided = generic ? null : resolvedTarget("CUSTOMER", null, name);
        if (canReuseNameDecision(decided, phones, byPhone)) {
            return decided;
        }
        if (decided != null) {
            saveDecision("CUSTOMER", evidenceKey, name, null, "REVIEW", "KNOWN_NAME_NEW_PHONE",
                    "已有名称映射，但本次出现未归属的新电话，已停止自动绑定", null);
            return null;
        }
        String existingStatus = existingStatus("CUSTOMER", name);
        if ("REVIEW".equals(existingStatus) || "IGNORED".equals(existingStatus)) return null;

        // ⑦ 泛化名称不做名称级自动绑定，只按当前证据组合进入复核
        if (generic) {
            saveDecision("CUSTOMER", evidenceKey, name, null, "REVIEW", "GENERIC_NAME",
                    "泛化名称不能单独作为客户合并依据，请在销售出库关联工作台处理", null);
            return null;
        }

        // 新客户自动建档只接受「恰好一个有效且尚未占用的电话」。无电话时仅凭名称/地址建档，
        // 会把录入不规范的人名、联系人或地址碎片变成正式客户；多个电话则需要先判断是否属于同一客户。
        if (phones.size() == 1 && byPhone.isEmpty()) {
            String customerName = suggestedCustomerName(name, phones.getFirst());
            long created = createOutboundCustomer(customerName, phones, address);
            saveDecision("CUSTOMER", evidenceKey, name, created, "AUTO", null,
                    "唯一未占用有效电话，导入时安全建档", .96);
            rememberCustomerCluster(name, address, phone, created);
            return created;
        }
        saveDecision("CUSTOMER", evidenceKey, name, null, "REVIEW",
                phones.isEmpty() ? "NO_VALID_PHONE" : "MULTIPLE_PHONES",
                phones.isEmpty() ? "没有有效电话，不仅凭名称或地址自动建档" : "存在多个有效电话，等待身份簇判断",
                null);
        return null;
    }

    /**
     * 「源记录未变化但本地客户档案丢失」的批量缺口修复（2026-09-14 新增）。
     *
     * <p>氚云出库单在重导时若 source_hash 未变化会走 UNCHANGED 分支、不再重跑客户识别；
     * 一旦本地客户档案被清理导致 {@code sales_outbounds.customer_id} 被置空，该缺口会长期存在
     * （表现为「缺客户档案」计数降不下去，且多为自提等无电话单据）。本方法对 customer_id 为空的有效
     * 出库单按名称/地址/电话重跑一次识别：唯一命中即绑定既有档案，唯一未占用电话可安全建档；
     * 无有效电话、泛化名称与多候选（互为子串、共享电话）保持为空，交销售出库关联工作台人工处理。
     *
     * <p>幂等：只更新 customer_id 仍为空的行，重复执行不会重复建档。
     */
    @Transactional
    public Map<String, Object> healMissingOutboundCustomers() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,customer_name,customer_address,contact_phone FROM sales_outbounds
             WHERE external_source='H3YUN' AND customer_id IS NULL
               AND NOT (outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(status,''))) IN ('草稿','DRAFT'))
               AND customer_name IS NOT NULL AND TRIM(customer_name)<>''
             ORDER BY id
            """);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class);
        int healed = 0, unresolved = 0;
        for (Map<String, Object> row : rows) {
            Long target = resolveCustomer(Objects.toString(row.get("customer_name"), null),
                    Objects.toString(row.get("customer_address"), null),
                    Objects.toString(row.get("contact_phone"), null));
            if (target == null) {
                unresolved++;
                continue;
            }
            healed += jdbc.update(
                    "UPDATE sales_outbounds SET customer_id=? WHERE id=? AND customer_id IS NULL",
                    target, ((Number) row.get("id")).longValue());
        }
        int created = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Integer.class) - before;
        return Map.of("scanned", rows.size(), "healed", healed,
                "createdCustomers", created, "unresolved", unresolved);
    }

    private List<Long> customerIdsByName(String name) {
        String normalized = normalize(name);
        if (normalized == null) return List.of();
        return jdbc.query("""
            SELECT id FROM customers WHERE deleted_at IS NULL
              AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
            ORDER BY id
            """, (rs, n) -> rs.getLong(1), normalized);
    }

    /**
     * 按归一电话找客户。电话可能落在联系人手机、联系人电话原文、收货地址联系电话三处，
     * 三处都查并按客户去重；归一规则与抽屉里的 {@link #phoneTokens} 完全一致。
     */
    private List<Long> customerIdsByPhone(List<String> tokens) {
        if (tokens.isEmpty()) return List.of();
        StringBuilder predicate = new StringBuilder();
        List<Object> params = new ArrayList<>();
        String normalizeExpr = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(%s,' ',''),'-',''),'+',''),'(',''),')','')";
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) predicate.append(" OR ");
            predicate.append('(')
                    .append(String.format(normalizeExpr, "COALESCE(k.mobile,'')")).append(" LIKE ? OR ")
                    .append(String.format(normalizeExpr, "COALESCE(k.phone_raw,'')")).append(" LIKE ? OR ")
                    .append(String.format(normalizeExpr, "COALESCE(a.contact_phone,'')")).append(" LIKE ?)");
            params.add("%" + tokens.get(i) + "%");
            params.add("%" + tokens.get(i) + "%");
            params.add("%" + tokens.get(i) + "%");
        }
        return jdbc.query("""
            SELECT DISTINCT c.id FROM customers c
              LEFT JOIN customer_contacts k ON k.customer_id=c.id
              LEFT JOIN customer_addresses a ON a.customer_id=c.id
             WHERE c.deleted_at IS NULL AND (""" + predicate + ") ORDER BY c.id",
                (rs, n) -> rs.getLong(1), params.toArray());
    }

    private List<Long> customerIdsByNormalizedAddress(String address) {
        String normalized = blank(address) ? null : normalizedBusinessAddress(address);
        if (normalized == null || normalized.length() < 8) return List.of();
        List<Long> ids = new ArrayList<>();
        // 主档地址与收货地址两处都参与比对，口径与「标准化地址一致」一致
        ids.addAll(jdbc.query("""
            SELECT id FROM customers WHERE deleted_at IS NULL
              AND REPLACE(REPLACE(REPLACE(TRIM(COALESCE(address,'')),' ',''),'　',''),CHAR(10),'')=?
            """, (rs, n) -> rs.getLong(1), normalized));
        ids.addAll(jdbc.query("""
            SELECT DISTINCT customer_id FROM customer_addresses
             WHERE COALESCE(normalized_address,'')<>'' AND normalized_address=?
            """, (rs, n) -> rs.getLong(1), normalized));
        return ids.stream().distinct().toList();
    }

    /** 保守客户匹配：不创建客户，不用地址或模糊名称单独决定归属。 */
    public SafeCustomerMatch safeExistingCustomerMatch(String sourceName, String address, String phone) {
        String name=clean(sourceName);
        if(blank(name)) return null;
        List<Long> clustered=jdbc.query("""
            SELECT m.customer_id FROM customer_source_cluster_mappings m
            JOIN customers c ON c.id=m.customer_id AND c.deleted_at IS NULL
             WHERE m.source_system='H3YUN' AND m.normalized_source_name=? AND m.cluster_key=? AND m.status='CONFIRMED'
            """,(rs,n)->rs.getLong(1),identityName(name),clusterKey(address,phone));
        if(clustered.size()==1) return new SafeCustomerMatch(clustered.getFirst(),"CONFIRMED_CLUSTER","已确认电话/地址身份簇");
        List<String> phones=phoneLookupTokens(Objects.toString(phone,"")+" "+Objects.toString(address,""));
        List<Long> byPhone=phones.isEmpty()?List.of():customerIdsByPhone(phones);
        if(byPhone.size()==1) return new SafeCustomerMatch(byPhone.getFirst(),"UNIQUE_PHONE","电话唯一命中");
        if(!genericCustomerName(name)) {
            Long decided=resolvedTarget("CUSTOMER",null,name);
            // 无有效电话时才允许直接复用名称映射；有电话时必须能证明该号码属于目标客户。
            if(canReuseNameDecision(decided, phones, byPhone)) {
                return new SafeCustomerMatch(decided,"RESOLVED_IDENTITY","已确认客户身份映射");
            }
        }
        return null;
    }

    /** 名称映射复用护栏：有电话时，只有该电话也能命中同一目标客户才允许复用。 */
    static boolean canReuseNameDecision(Long decided, List<String> phones, List<Long> phoneMatches) {
        return decided != null && (phones == null || phones.isEmpty()
                || (phoneMatches != null && phoneMatches.contains(decided)));
    }

    /**
     * 保存逐条身份簇，防止同名不同电话在重导后重新混合。
     *
     * <p>本方法由销售出库关联工作台的三个**算法**流程调用（安全关联 / 多电话簇 / 电话簇建档），
     * 因此必须避开两类人工结论——缺了这道保护，自动流程会用算法结论静默改写人工结论，
     * 重导后整簇归错客户，且界面上看不出来（撤销了也"没用"）：
     * <ul>
     *   <li><b>人工拆分结论</b>（{@code split_run_id} 非空）：客户拆分工作台以此表为权威身份归属，
     *       其幂等护栏 {@code confirmedIdentityKeys} 正建立在「有 CONFIRMED 映射即已定过归属」之上。</li>
     *   <li><b>已撤销结论</b>（{@code status='REVOKED'}）：撤销是人工纠错动作，不得被自动流程复活成 CONFIRMED。</li>
     * </ul>
     * 自动来源（无 split_run_id、未撤销）仍允许更新，保留自愈能力；改写人工结论的正确通道是
     * 客户拆分工作台本身（{@code applyClusterDecision} 的 ON DUPLICATE 无条件写）。
     */
    public void rememberCustomerCluster(String sourceName,String address,String phone,long customerId) {
        // 空名称无法构成“名称 + 电话/地址”的稳定身份键；该类记录仍可依靠唯一电话重导关联。
        if(blank(sourceName)) return;
        jdbc.update("""
            INSERT INTO customer_source_cluster_mappings
              (source_system,normalized_source_name,cluster_key,customer_id,status)
            VALUES('H3YUN',?,?,?,'CONFIRMED')
            ON DUPLICATE KEY UPDATE
              customer_id=IF(split_run_id IS NULL AND status<>'REVOKED',VALUES(customer_id),customer_id),
              status=IF(status='REVOKED','REVOKED','CONFIRMED'),updated_at=NOW()
            """,identityName(sourceName),clusterKey(address,phone),customerId);
    }

    public record SafeCustomerMatch(long customerId,String method,String reason) {}

    public String customerIdentityKey(String sourceName,String address,String phone) {
        return identityKey(sourceName,clusterKey(address,phone));
    }

    public List<String> customerPhoneTokens(String value) { return phoneLookupTokens(value); }

    public String suggestedCustomerName(String sourceName,String phone) {
        String name=canonicalCustomerName(sourceName);
        if(blank(name)) name="客户";
        String digits=Objects.toString(phone,"").replaceAll("\\D","");
        if(digits.length()>=7&&name.endsWith(digits)) name=name.substring(0,name.length()-digits.length()).replaceAll("[\\s,，/\\-]+$","").trim();
        if(blank(name)) name="客户";
        String suffix=digits.length()>6?digits.substring(digits.length()-6):digits;
        return genericCustomerName(name)||platformPseudoCustomerName(name) ? name+"["+suffix+"]" : name;
    }

    public long createOutboundCustomer(String name,String phone,String address) {
        return createOutboundCustomer(name,blank(phone)?List.of():List.of(phone),address);
    }

    public long createOutboundCustomer(String name,List<String> phones,String address) {
        // 建档 INSERT 阶段即写入 address 与解析出的省/市/区县，与全量回填下线后的治本口径一致
        long id=createPendingCustomer(name, address);
        String contactName=name.length()>50?name.substring(0,50):name;
        String primaryPhone=phones.isEmpty()?null:phones.getFirst();
        if(!blank(address)) {
            jdbc.update("UPDATE customers SET remark='按销售出库唯一电话身份簇安全建档' WHERE id=?",id);
            jdbc.update("INSERT INTO customer_addresses(customer_id,address_name,contact_name,contact_phone,detail_address,normalized_address,is_default,source,remark) VALUES(?,'氚云出库地址',?,?,?,?,1,'H3YUN_SALES_OUTBOUND','按销售出库电话身份簇建档')",id,contactName,primaryPhone,address,normalizedBusinessAddress(address));
        }
        for(int i=0;i<phones.size();i++) {
            String phone=phones.get(i);
            jdbc.update("INSERT INTO customer_contacts(customer_id,name,mobile,phone_raw,contact_type,is_primary,remark) VALUES(?,?,?,?, '其他',?,'按销售出库电话身份簇建档')",id,contactName,phone,phone,i==0?1:0);
        }
        return id;
    }

    @Transactional
    public Long resolveWarehouse(String sourceName) {
        if (blank(sourceName)) return null;
        Long mapped = resolvedTarget("WAREHOUSE", null, sourceName);
        if (mapped != null) return mapped;
        List<Long> exact = jdbc.query(
                "SELECT id FROM warehouses WHERE active=1 AND TRIM(warehouse_name)=? ORDER BY id",
                (rs, n) -> rs.getLong(1), sourceName.trim());
        if (exact.size() == 1) {
            saveDecision("WAREHOUSE", null, sourceName, exact.getFirst(), "AUTO", null,
                    "仓库名称精确命中", 1.0);
            return exact.getFirst();
        }
        saveDecision("WAREHOUSE", null, sourceName, null, "REVIEW", "WAREHOUSE_NOT_FOUND",
                "未找到唯一正式仓库", null);
        return null;
    }

    public Map<String, Object> page(String entityType, String status, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!blank(entityType)) { where.append(" AND a.entity_type=?"); params.add(entityType.trim().toUpperCase(Locale.ROOT)); }
        if (!blank(status)) { where.append(" AND a.resolution_status=?"); params.add(status.trim().toUpperCase(Locale.ROOT)); }
        if (!blank(keyword)) {
            where.append(" AND (a.source_name LIKE ? OR a.source_key LIKE ? OR a.resolution_note LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM source_identity_aliases a" + where,
                Long.class, params.toArray());
        List<Object> listParams = new ArrayList<>(params);
        listParams.add(safeSize); listParams.add((safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT a.id,a.entity_type entityType,a.source_key sourceKey,a.source_name sourceName,
                   a.target_id targetId,a.resolution_status resolutionStatus,a.issue_code issueCode,
                   a.confidence,a.occurrence_count occurrenceCount,a.resolution_note resolutionNote,
                   a.resolved_by_name resolvedByName,a.resolved_at resolvedAt,a.updated_at updatedAt,
                   CASE a.entity_type
                     WHEN 'CUSTOMER' THEN (SELECT customer_name FROM customers WHERE id=a.target_id)
                     WHEN 'EMPLOYEE' THEN (SELECT name FROM employees WHERE id=a.target_id)
                     WHEN 'PRODUCT' THEN (SELECT product_name FROM sales_products WHERE id=a.target_id)
                     WHEN 'WAREHOUSE' THEN (SELECT warehouse_name FROM warehouses WHERE id=a.target_id)
                   END targetName
            FROM source_identity_aliases a
            """ + where + " ORDER BY FIELD(a.resolution_status,'REVIEW','AUTO','CONFIRMED','IGNORED'),a.updated_at DESC LIMIT ? OFFSET ?",
                listParams.toArray());
        for (Map<String, Object> item : items) {
            if ("CUSTOMER".equals(item.get("entityType"))) {
                CustomerCleanup cleanup = suggestCustomerCleanup(String.valueOf(item.get("sourceName")));
                item.put("suggestedName", cleanup.name());
                item.put("suggestedPhones", cleanup.phones());
                boolean sharedPhone = cleanup.phones().size() == 1 && isSharedCustomerPhone(cleanup.phones().getFirst());
                item.put("suggestedPhoneShared", sharedPhone);
                item.put("cleanupSuggested", cleanup.safe() && !sharedPhone);
                item.put("recommendedAction", recommendedAction(item,
                        new CustomerCleanup(cleanup.name(), cleanup.phones(), cleanup.safe() && !sharedPhone)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items); result.put("total", total); result.put("page", safePage); result.put("size", safeSize);
        result.put("counts", counts());
        result.put("audit", auditSummary());
        return result;
    }

    public Map<String, Object> auditSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerTotal", scalar("SELECT COUNT(*) FROM customers WHERE deleted_at IS NULL"));
        out.put("h3CustomerTotal", scalar("SELECT COUNT(*) FROM customers WHERE deleted_at IS NULL AND source='H3YUN_SALES_OUTBOUND'"));
        out.put("unboundOutboundCustomer", scalar("SELECT COUNT(*) FROM sales_outbounds WHERE customer_id IS NULL"));
        out.put("unboundProductionCustomer", scalar("SELECT COUNT(*) FROM production_orders WHERE customer_id IS NULL"));
        out.put("unboundOutboundEmployee", scalar("SELECT COUNT(*) FROM sales_outbounds WHERE employee_id IS NULL"));
        out.put("unboundOutboundProduct", scalar("SELECT COUNT(*) FROM sales_outbound_items WHERE line_type='PRODUCT' AND sales_product_id IS NULL"));
        out.put("reviewCustomer", scalar("SELECT COUNT(*) FROM source_identity_aliases WHERE entity_type='CUSTOMER' AND resolution_status='REVIEW'"));
        out.put("reviewProduct", scalar("SELECT COUNT(*) FROM source_identity_aliases WHERE entity_type='PRODUCT' AND resolution_status='REVIEW'"));
        out.put("reviewWarehouse", scalar("SELECT COUNT(*) FROM source_identity_aliases WHERE entity_type='WAREHOUSE' AND resolution_status='REVIEW'"));
        out.put("reviewEmployee", scalar("SELECT COUNT(*) FROM source_identity_aliases WHERE entity_type='EMPLOYEE' AND resolution_status='REVIEW'"));
        return out;
    }

    public Map<String, Object> targets(String entityType, String keyword) {
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        List<Map<String, Object>> items = switch (entityType.toUpperCase(Locale.ROOT)) {
            case "CUSTOMER" -> customerTargets(keyword);
            case "EMPLOYEE" -> jdbc.queryForList("SELECT id,name,employee_no code FROM employees WHERE status='ACTIVE' AND name LIKE ? ORDER BY name LIMIT 50", like);
            case "PRODUCT" -> jdbc.queryForList("SELECT id,product_name name,product_code code FROM sales_products WHERE deleted_at IS NULL AND product_name LIKE ? ORDER BY product_name LIMIT 50", like);
            case "WAREHOUSE" -> jdbc.queryForList("SELECT id,warehouse_name name,warehouse_code code FROM warehouses WHERE active=1 AND warehouse_name LIKE ? ORDER BY warehouse_name LIMIT 50", like);
            default -> throw new IllegalArgumentException("未知身份类型：" + entityType);
        };
        return Map.of("items", items);
    }

    private List<Map<String, Object>> customerTargets(String keyword) {
        String source = keyword == null ? "" : keyword.trim();
        String loose = normalizeLoose(source);
        String core = customerCore(loose);
        String anchor = core == null || core.isEmpty() ? source : core.substring(0, Math.min(2, core.length()));
        List<Map<String, Object>> pool = jdbc.queryForList("""
            SELECT id,customer_name name,customer_code code
            FROM customers
            WHERE deleted_at IS NULL AND (?='' OR customer_name LIKE CONCAT('%',?,'%')
              OR REPLACE(REPLACE(customer_name,'，',''),',','') LIKE CONCAT('%',?,'%'))
            ORDER BY customer_name LIMIT 500
            """, anchor, anchor, anchor);
        for (Map<String, Object> row : pool) {
            String candidate = String.valueOf(row.get("name"));
            String candidateLoose = normalizeLoose(candidate);
            String candidateCore = customerCore(candidateLoose);
            double score;
            String reason;
            if (Objects.equals(loose, candidateLoose)) { score = 1; reason = "去空格和标点后完全一致"; }
            else if (core != null && !core.isEmpty() && Objects.equals(core, candidateCore)) { score = .96; reason = "去公司后缀后完全一致"; }
            else if (core != null && candidateCore != null && (core.contains(candidateCore) || candidateCore.contains(core))) {
                score = (double) Math.min(core.length(), candidateCore.length()) / Math.max(core.length(), candidateCore.length());
                reason = "名称主体互相包含";
            } else { score = 0; reason = "关键词命中"; }
            row.put("confidence", Math.round(score * 1000d) / 1000d);
            row.put("matchReason", reason);
        }
        return pool.stream()
                .sorted(Comparator.<Map<String, Object>, Double>comparing(r -> ((Number) r.get("confidence")).doubleValue()).reversed()
                        .thenComparing(r -> String.valueOf(r.get("name"))))
                .limit(50).toList();
    }

    public Map<String, Object> safeBatchPreview() {
        List<Map<String, Object>> safe = safeBatchCandidates();
        long affectedFacts = safe.stream()
                .mapToLong(row -> row.get("occurrenceCount") instanceof Number n ? n.longValue() : 0)
                .sum();
        return Map.of("safeCount", safe.size(), "affectedFacts", affectedFacts,
                "items", safe.stream().limit(100).toList());
    }

    public Map<String, Object> linkedReviewPreview() {
        List<Map<String, Object>> rows = linkedReviewCandidates();
        Map<String, Long> byIssue = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byIssue.merge(String.valueOf(row.get("issueCode")), 1L, Long::sum);
        }
        long affected = rows.stream().mapToLong(row ->
                row.get("occurrenceCount") instanceof Number n ? n.longValue() : 0).sum();
        return Map.of("confirmCount", rows.size(), "affectedFacts", affected,
                "byIssue", byIssue, "items", rows.stream().limit(100).toList());
    }

    public Map<String, Object> newCustomerPreview() {
        List<NewCustomerCandidate> candidates = newCustomerCandidates();
        long facts = candidates.stream().mapToLong(NewCustomerCandidate::occurrenceCount).sum();
        List<Map<String, Object>> items = candidates.stream().limit(100).map(candidate -> Map.<String, Object>of(
                "customerName", candidate.customerName(),
                "sourceNames", candidate.rows().stream().map(row -> String.valueOf(row.get("sourceName"))).toList(),
                "identityCount", candidate.rows().size(),
                "occurrenceCount", candidate.occurrenceCount()
        )).toList();
        return Map.of("customerCount", candidates.size(), "identityCount",
                candidates.stream().mapToInt(candidate -> candidate.rows().size()).sum(),
                "affectedFacts", facts, "items", items);
    }

    public Map<String, Object> customerAddressMergePreview() {
        List<CustomerMergeGroup> groups = customerAddressMergeGroups();
        return Map.of("groupCount", groups.size(), "duplicateCount",
                groups.stream().mapToInt(group -> group.members().size() - 1).sum(),
                "items", groups.stream().limit(100).map(group -> Map.<String, Object>of(
                        "customerName", group.cleanName(), "address", group.cleanAddress(),
                        "members", group.members().stream().map(member -> Map.of(
                                "id", member.id(), "name", member.name(), "outboundCount", member.outboundCount())).toList()
                )).toList());
    }

    public Map<String, Object> customerAddressAudit() {
        List<Map<String, Object>> stats = jdbc.queryForList("""
            SELECT s.customer_id customerId,c.customer_name customerName,COUNT(*) outboundCount,
                   COUNT(DISTINCT LOWER(REPLACE(REPLACE(TRIM(s.customer_address),' ',''),'　',''))) addressCount
            FROM sales_outbounds s JOIN customers c ON c.id=s.customer_id AND c.deleted_at IS NULL
            WHERE s.customer_address IS NOT NULL AND TRIM(s.customer_address)<>''
            GROUP BY s.customer_id,c.customer_name
            """);
        long stable = 0, reasonable = 0, highRisk = 0, generic = 0;
        List<Map<String, Object>> risks = new ArrayList<>();
        for (Map<String, Object> row : stats) {
            String name = String.valueOf(row.get("customerName"));
            long count = ((Number) row.get("addressCount")).longValue();
            boolean genericName = genericCustomerName(name);
            String risk;
            String recommendation;
            if (genericName) { generic++; risk = "GENERIC_NAME"; recommendation = "按名称和地址重新拆分客户身份"; }
            else if (count >= 10) { highRisk++; risk = "ADDRESS_SCATTERED"; recommendation = "核查是否多个客户被错误合并"; }
            else if (count == 1) { stable++; risk = "STABLE"; recommendation = "可安全归集到地址簿"; }
            else { reasonable++; risk = "MULTI_ADDRESS"; recommendation = "保留为多个收货地址，暂不自动归集"; }
            if (!"STABLE".equals(risk)) {
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put("riskType", risk); item.put("recommendation", recommendation);
                risks.add(item);
            }
        }
        risks.sort(Comparator.<Map<String,Object>, Long>comparing(row -> ((Number) row.get("addressCount")).longValue()).reversed());
        return Map.of("addressedOutboundCount", scalar("SELECT COUNT(*) FROM sales_outbounds WHERE customer_address IS NOT NULL AND TRIM(customer_address)<>''"),
                "customerCount", stats.size(), "stableCustomerCount", stable, "multiAddressCustomerCount", reasonable,
                "highRiskCustomerCount", highRisk, "genericNameCustomerCount", generic,
                "missingCustomerOutboundCount", scalar("SELECT COUNT(*) FROM sales_outbounds WHERE customer_id IS NULL"),
                "items", risks.stream().limit(100).toList());
    }

    public Map<String, Object> customerAddressImportPreview() {
        List<Map<String, Object>> candidates = customerAddressImportCandidates();
        return Map.of("customerCount", candidates.size(), "addressCount", candidates.size(),
                "items", candidates.stream().limit(100).toList());
    }

    /**
     * 客户维度的出库行身份统计（待处理 / 已处理 + 归一地址集合 + 归一电话集合）。
     * 判定口径与候选簇抽屉完全一致：出库行若命中该客户的已确认身份簇
     * （normalized_source_name + cluster_key），即视为已处理。
     * 地址与电话在这里就用抽屉那套归一（{@link #normalizedBusinessAddress} / {@link #phoneTokens}）去重，
     * 列表里的「地址数 / 电话数」才不会再出现「列表说 36 个地址、点开只有 1 个簇」的口径打架。
     * 全量出库只做一次流式判定并缓存，避免列表与抽屉两套口径互相打架。
     */
    private Map<Long, CustomerOutboundStats> outboundStatsByCustomer() {
        Map<Long, CustomerOutboundStats> cached = outboundStats;
        if (cached != null && System.currentTimeMillis() - outboundStatsAt < PENDING_OUTBOUNDS_TTL_MS) return cached;
        synchronized (this) {
            cached = outboundStats;
            if (cached != null && System.currentTimeMillis() - outboundStatsAt < PENDING_OUTBOUNDS_TTL_MS) return cached;
            Set<String> decided = confirmedIdentityKeys();
            Map<Long, CustomerOutboundStats> stats = new HashMap<>();
            jdbc.query("""
                SELECT customer_id,customer_name,customer_address,contact_phone FROM sales_outbounds
                WHERE customer_id IS NOT NULL
                """, (RowCallbackHandler) rs -> {
                long customerId = rs.getLong(1);
                String address = rs.getString(3);
                String phone = rs.getString(4);
                CustomerOutboundStats counter = stats.computeIfAbsent(customerId, ignored -> new CustomerOutboundStats());
                String normalizedAddress = normalizedBusinessAddress(address);
                if (!blank(normalizedAddress)) counter.addresses.add(normalizedAddress);
                counter.phones.addAll(phoneTokens(phone));
                String key = identityKey(rs.getString(2), clusterKey(address, phone));
                if (decided.contains(key)) counter.settled++; else counter.pending++;
            });
            outboundStats = stats;
            outboundStatsAt = System.currentTimeMillis();
            return stats;
        }
    }

    /** 单客户出库行身份统计：待处理 / 已处理行数 + 归一后的地址与电话集合。 */
    private static final class CustomerOutboundStats {
        private long pending;
        private long settled;
        private final Set<String> addresses = new LinkedHashSet<>();
        private final Set<String> phones = new LinkedHashSet<>();
    }

    /**
     * 「多个真实客户被错误合并」的判定：主证据是互不相同的联系电话。
     * 一个真实客户不会同时留下 3 个以上互不相同的号码；而只有地址多、电话唯一的档案
     * 在抽屉里本来就只聚成 1 个簇（同电话必合并），判成高风险只会误导用户点进去发现无簇可拆。
     * 地址维度作为补充证据，用来捞「电话数量中等但地址散到不可能」的档案。
     */
    private static boolean mixedIdentity(long addressCount, long phoneCount) {
        return phoneCount >= 3 && (addressCount >= 5 || phoneCount >= 8);
    }

    /** 高置信混合：地址或电话数量本身已经远超单个真实客户的量级，默认直接进候选列表。 */
    private static boolean highConfidenceMixed(long addressCount, long phoneCount) {
        return addressCount >= 10 || phoneCount >= 10;
    }

    /**
     * 已确认身份映射的全局键集合（{@code 归一名称|簇键}）。
     * 身份归属的唯一权威是 customer_source_cluster_mappings：某个键一旦有 CONFIRMED 映射，
     * 就说明这个「来源名称 + 地址/电话」簇已经定过归属，不应再当作待处理重新拆分。
     * 判定用全局键而非「映射客户 = 当前客户」，是为了避免出库行归属与映射短暂不一致时
     * 反复出现同一个簇、被反复拆分（每处理一次就多建一个客户）。
     */
    private Set<String> confirmedIdentityKeys() {
        Set<String> cached = confirmedIdentityKeys;
        if (cached != null && System.currentTimeMillis() - confirmedIdentityKeysAt < PENDING_OUTBOUNDS_TTL_MS) return cached;
        synchronized (this) {
            cached = confirmedIdentityKeys;
            if (cached != null && System.currentTimeMillis() - confirmedIdentityKeysAt < PENDING_OUTBOUNDS_TTL_MS) return cached;
            Set<String> keys = new HashSet<>();
            jdbc.query("""
                SELECT normalized_source_name,cluster_key FROM customer_source_cluster_mappings
                WHERE status='CONFIRMED'
                """, (RowCallbackHandler) rs -> keys.add(identityKey(rs.getString(1), rs.getString(2))));
            confirmedIdentityKeys = keys;
            confirmedIdentityKeysAt = System.currentTimeMillis();
            return keys;
        }
    }

    /** 决策 / 撤销 / 批量处理会改变「已处理」集合，处理完立即失效索引。 */
    private void invalidatePendingOutbounds() {
        outboundStats = null;
        confirmedIdentityKeys = null;
    }

    /**
     * 高风险客户拆分工作台的风险候选列表：
     * 只保留「泛化名称客户」或「地址/电话混合客户」的客户，按泛化优先 + 待处理出库量降序展示，
     * 供用户进入后逐客户查看地址+电话候选簇并决定拆分归属。只读，不改变业务事实。
     *
     * <p>「地址分散」不再只看地址字符串个数：地址数多、电话唯一的档案在抽屉里本来就只聚成 1 个簇
     * （同电话必合并），判成高风险只会让用户点进去发现无簇可拆。判定改为以互不相同的联系电话为主证据
     * （见 {@link #mixedIdentity}），地址维度作为补充。中置信混合默认不占用候选列表，
     * 需要时用 mediumMixed 打开。
     */
    public Map<String, Object> highRiskCustomers(String risk, String keyword, int page, int size,
                                                boolean includeSettled, boolean mediumMixed) {
        List<Map<String, Object>> all = jdbc.queryForList("""
            SELECT s.customer_id customerId,c.customer_code customerCode,c.customer_name customerName,
                   COUNT(*) outboundCount,
                   COUNT(DISTINCT CASE WHEN s.salesperson_name IS NOT NULL AND TRIM(s.salesperson_name)<>''
                       THEN TRIM(s.salesperson_name) END) salespersonCount,
                   COALESCE(SUM(COALESCE(s.sales_amount,0)),0) salesAmount,
                   MIN(s.ship_date) firstShipDate,MAX(s.ship_date) lastShipDate
            FROM sales_outbounds s
            JOIN customers c ON c.id=s.customer_id AND c.deleted_at IS NULL AND c.status<>'INACTIVE'
            GROUP BY s.customer_id,c.customer_code,c.customer_name
            """);
        // 地址数 / 电话数一律取抽屉那套归一后的集合大小，列表与抽屉不再两套口径。
        Map<Long, CustomerOutboundStats> index = outboundStatsByCustomer();
        String riskFilter = clean(risk).toUpperCase(Locale.ROOT);
        String kw = blank(keyword) ? null : keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> risks = new ArrayList<>();
        long genericCount = 0, scatteredCount = 0, settledCustomerCount = 0, mediumMixedCount = 0;
        for (Map<String, Object> row : all) {
            String name = String.valueOf(row.get("customerName"));
            long customerId = ((Number) row.get("customerId")).longValue();
            long outboundCount = ((Number) row.get("outboundCount")).longValue();
            CustomerOutboundStats stats = index.get(customerId);
            long addressCount = stats == null ? 0 : stats.addresses.size();
            long phoneCount = stats == null ? 0 : stats.phones.size();
            boolean generic = genericCustomerName(name) || platformPseudoCustomerName(name);
            if (!generic && !mixedIdentity(addressCount, phoneCount)) continue;
            long pending = stats == null ? outboundCount : stats.pending;
            long settled = Math.max(0, outboundCount - pending);
            // 默认只列「还有待处理出库」的客户：已全部归入已确认身份簇的客户（含拆分产物）不再占位，
            // 需要回看或撤销时用 includeSettled 打开。
            if (pending == 0) {
                settledCustomerCount++;
                if (!includeSettled) continue;
            }
            boolean highMixed = highConfidenceMixed(addressCount, phoneCount);
            if (generic) genericCount++;
            else {
                scatteredCount++;
                if (!highMixed) mediumMixedCount++;
            }
            if (!generic && !highMixed && !mediumMixed) continue;
            String code = row.get("customerCode") == null ? "" : String.valueOf(row.get("customerCode"));
            if (kw != null && !name.toLowerCase(Locale.ROOT).contains(kw) && !code.toLowerCase(Locale.ROOT).contains(kw)) continue;
            if ("GENERIC_NAME".equals(riskFilter) && !generic) continue;
            if ("ADDRESS_SCATTERED".equals(riskFilter) && generic) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("customerId", row.get("customerId"));
            item.put("customerCode", row.get("customerCode"));
            item.put("customerName", name);
            item.put("riskType", generic ? "GENERIC_NAME" : "ADDRESS_SCATTERED");
            item.put("mixedLevel", generic || highMixed ? "HIGH" : "MEDIUM");
            item.put("outboundCount", row.get("outboundCount"));
            item.put("addressCount", addressCount);
            item.put("phoneCount", phoneCount);
            item.put("salespersonCount", row.get("salespersonCount"));
            item.put("salesAmount", row.get("salesAmount"));
            item.put("firstShipDate", row.get("firstShipDate"));
            item.put("lastShipDate", row.get("lastShipDate"));
            item.put("recommendation", generic
                    ? "客户名称过泛（先生/单字/平台用户），历史上多客户被错误归并，按地址+电话候选簇拆分"
                    : "同一档案下有 " + phoneCount + " 个互不相同的联系电话、"
                        + addressCount + " 个收货地址，核查是否多个真实客户被错误合并，按候选簇拆分");
            item.put("pendingOutboundCount", pending);
            item.put("settledOutboundCount", settled);
            item.put("settled", pending == 0);
            risks.add(item);
        }
        risks.sort((a, b) -> {
            int genericA = "GENERIC_NAME".equals(a.get("riskType")) ? 0 : 1;
            int genericB = "GENERIC_NAME".equals(b.get("riskType")) ? 0 : 1;
            if (genericA != genericB) return Integer.compare(genericA, genericB);
            long obA = ((Number) a.get("pendingOutboundCount")).longValue();
            long obB = ((Number) b.get("pendingOutboundCount")).longValue();
            if (obA != obB) return Long.compare(obB, obA);
            return Long.compare(((Number) a.get("customerId")).longValue(), ((Number) b.get("customerId")).longValue());
        });
        int from = Math.max(0, (Math.max(page, 1) - 1) * Math.max(size, 1));
        int to = Math.min(risks.size(), from + Math.max(size, 1));
        List<Map<String, Object>> pageItems = risks.subList(Math.min(from, risks.size()), to);
        for (Map<String, Object> item : pageItems) {
            annotatePendingClusters(item);
        }
        return Map.of("items", pageItems, "total", risks.size(),
                "genericNameCustomerCount", genericCount, "scatteredCustomerCount", scatteredCount,
                "settledCustomerCount", settledCustomerCount, "includeSettled", includeSettled,
                "mediumMixedCustomerCount", mediumMixedCount, "mediumMixed", mediumMixed);
    }

    /**
     * 在候选客户行上补注「待处理身份簇」个数（与抽屉同一套聚类结果）。
     * 待处理/已处理张数由 {@link #outboundStatsByCustomer()} 给出，
     * 两者一起展示，就不会再出现"有出库数、点开却没有候选簇"的误导。
     */
    private void annotatePendingClusters(Map<String, Object> item) {
        long customerId = ((Number) item.get("customerId")).longValue();
        item.put("pendingClusterCount", customerIdentityClusters(customerId).size());
    }

    private static boolean platformPseudoCustomerName(String name) {
        if (blank(name)) return false;
        return name.matches("(?i).*(淘宝|天猫|京东|拼多多|抖音|快手|微信|闲鱼|小红书|美团|饿了么|苏宁|国美|一号店)用户[\\d-]*")
                || name.matches("(?i)^(test|testing|测试|测试客户|临时|临时客户|无名|虚拟客户|未知用户)$");
    }

    /**
     * 将某个高风险客户的历史出库按“地址 + 联系电话”拆成可解释的候选簇。
     * 这里只给出建议，不自动拆客户，也不改历史事实。
     * 同时返回该客户「已处理」的身份簇（按处理记录归组），便于核对与撤销。
     */
    public Map<String, Object> customerAddressClusters(long customerId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE id=? AND deleted_at IS NULL", Integer.class, customerId);
        if (exists == null || exists == 0) throw new IllegalArgumentException("客户档案不存在：" + customerId);
        Map<String, Map<String, Object>> decided = confirmedClusterMappings();
        List<Map<String, Object>> pendingRows = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> settledByRun = new LinkedHashMap<>();
        for (Map<String, Object> row : identityRows(customerId)) {
            Map<String, Object> mapping = decided.get(rowIdentityKey(row));
            if (mapping == null) {
                pendingRows.add(row);
                continue;
            }
            Object runId = mapping.get("split_run_id");
            long groupKey = runId == null ? -((Number) mapping.get("id")).longValue() : ((Number) runId).longValue();
            settledByRun.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> items = clusterIdentityRows(pendingRows).stream().limit(500).map(cluster -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("clusterKey", cluster.key()); item.put("sourceName", cluster.sourceName());
            item.put("address", cluster.addresses().isEmpty() ? "" : cluster.addresses().getFirst());
            item.put("addressVariants", cluster.addresses()); item.put("contactPhone", String.join("、", cluster.phones()));
            item.put("phoneVariants", cluster.phones()); item.put("outboundCount", cluster.rows().size());
            item.put("salespersonCount", cluster.salespersons().size()); item.put("salespersons", String.join("、", cluster.salespersons()));
            item.put("firstShipDate", cluster.firstShipDate()); item.put("lastShipDate", cluster.lastShipDate());
            item.put("salesAmount", cluster.salesAmount()); item.put("confidence", cluster.confidence());
            item.put("suggestedCustomerName", suggestedClusterCustomerName(cluster));
            item.put("recommendation", cluster.recommendation());
            return item;
        }).toList();
        List<Map<String, Object>> settledItems = settledByRun.entrySet().stream()
                .map(entry -> settledClusterItem(entry.getKey(), entry.getValue())).toList();
        long outboundCount = items.stream().mapToLong(r -> ((Number) r.get("outboundCount")).longValue()).sum();
        long settledOutboundCount = settledItems.stream().mapToLong(r -> ((Number) r.get("outboundCount")).longValue()).sum();
        return Map.of("customerId", customerId, "clusterCount", items.size(),
                "outboundCount", outboundCount, "items", items,
                "settledClusterCount", settledItems.size(), "settledOutboundCount", settledOutboundCount,
                "settledItems", settledItems);
    }

    /**
     * 「已处理身份簇」明细：按一次处理记录（split run）归组，
     * 让列表里的"已处理张数"在这里能看到具体是哪一次处理、处理成了什么、是否可撤销。
     */
    private Map<String, Object> settledClusterItem(long runKey, List<Map<String, Object>> rows) {
        Map<String, Object> item = new LinkedHashMap<>();
        List<String> addresses = new ArrayList<>();
        List<String> phones = new ArrayList<>();
        List<String> salespersons = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        String first = null, last = null;
        for (Map<String, Object> row : rows) {
            String address = clean(Objects.toString(row.get("customer_address"), ""));
            if (!blank(address) && !addresses.contains(address)) addresses.add(address);
            for (String phone : phoneTokens(Objects.toString(row.get("contact_phone"), ""))) {
                if (!phones.contains(phone)) phones.add(phone);
            }
            String salesperson = clean(Objects.toString(row.get("salesperson_name"), ""));
            if (!blank(salesperson) && !salespersons.contains(salesperson)) salespersons.add(salesperson);
            String shipDate = Objects.toString(row.get("ship_date"), null);
            if (shipDate != null) {
                if (first == null || shipDate.compareTo(first) < 0) first = shipDate;
                if (last == null || shipDate.compareTo(last) > 0) last = shipDate;
            }
            Object salesAmount = row.get("sales_amount");
            if (salesAmount instanceof BigDecimal decimal) amount = amount.add(decimal);
        }
        item.put("address", addresses.isEmpty() ? "" : addresses.getFirst());
        item.put("addressVariants", addresses);
        item.put("contactPhone", String.join("、", phones));
        item.put("outboundCount", rows.size());
        item.put("salespersons", String.join("、", salespersons));
        item.put("firstShipDate", first);
        item.put("lastShipDate", last);
        item.put("salesAmount", amount);
        if (runKey <= 0) {
            item.put("revocable", false);
            return item;
        }
        Map<String, Object> run = jdbc.queryForMap("""
            SELECT id,source_customer_id sourceCustomerId,source_customer_name sourceCustomerName,
                   target_customer_id targetCustomerId,target_customer_name targetCustomerName,
                   action,status,outbound_count outboundCount,reason,created_by_name createdByName,created_at createdAt
            FROM customer_split_runs WHERE id=?
            """, runKey);
        item.put("runId", run.get("id"));
        item.put("action", run.get("action"));
        item.put("status", run.get("status"));
        item.put("decisionOutboundCount", run.get("outboundCount"));
        item.put("sourceCustomerId", run.get("sourceCustomerId"));
        item.put("sourceCustomerName", run.get("sourceCustomerName"));
        item.put("targetCustomerId", run.get("targetCustomerId"));
        item.put("targetCustomerName", run.get("targetCustomerName"));
        item.put("reason", run.get("reason"));
        item.put("decidedByName", run.get("createdByName"));
        item.put("decidedAt", run.get("createdAt"));
        item.put("revocable", "APPLIED".equals(run.get("status")) || "RECORDED".equals(run.get("status")));
        return item;
    }

    public Map<String, Object> customerSplitHistory(Long customerId) {
        String where = customerId == null ? "" : " WHERE r.source_customer_id=? OR r.target_customer_id=?";
        Object[] params = customerId == null ? new Object[]{} : new Object[]{customerId, customerId};
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT r.id,r.source_customer_id sourceCustomerId,r.source_customer_name sourceCustomerName,
                   r.target_customer_id targetCustomerId,r.target_customer_name targetCustomerName,
                   r.action,r.status,r.cluster_address address,r.cluster_phone contactPhone,r.reason,
                   r.outbound_count outboundCount,r.created_by_name createdByName,r.created_at createdAt,
                   r.undone_by_name undoneByName,r.undone_at undoneAt
            FROM customer_split_runs r
            """ + where + " ORDER BY r.id DESC LIMIT 200", params);
        return Map.of("items", items);
    }

    public Map<String, Object> genericCustomerBatchPreview() {
        List<Map<String,Object>> customers=jdbc.queryForList("""
            SELECT DISTINCT c.id,c.customer_name customerName FROM customers c
            JOIN sales_outbounds s ON s.customer_id=c.id
            WHERE c.deleted_at IS NULL AND c.status<>'INACTIVE' ORDER BY c.id
            """);
        long customerCount=0,createCount=0,keepCount=0,outboundCount=0;
        List<Map<String,Object>> samples=new ArrayList<>();
        for(Map<String,Object> customer:customers) {
            String name=Objects.toString(customer.get("customerName"),"");
            if(!genericCustomerName(name)&&!platformPseudoCustomerName(name)) continue;
            long id=((Number)customer.get("id")).longValue(); List<IdentityCluster> clusters=customerIdentityClusters(id);
            if(clusters.isEmpty()) continue; customerCount++;
            for(IdentityCluster cluster:clusters) {
                boolean create=autoCreateGenericCluster(cluster); if(create)createCount++;else keepCount++;
                outboundCount+=cluster.rows().size();
                if(samples.size()<100) samples.add(Map.of("customerId",id,"sourceName",name,"suggestedCustomerName",suggestedClusterCustomerName(cluster),
                        "action",create?"CREATE_NEW":"KEEP_CURRENT","outboundCount",cluster.rows().size(),"confidence",cluster.confidence(),
                        "address",cluster.addresses().isEmpty()?"":cluster.addresses().getFirst(),"phone",cluster.phones().isEmpty()?"":cluster.phones().getFirst()));
            }
        }
        return Map.of("customerCount",customerCount,"createCustomerCount",createCount,"keepCurrentCount",keepCount,
                "outboundCount",outboundCount,"items",samples);
    }

    @Transactional
    public Map<String, Object> confirmGenericCustomerBatch() {
        // 一次性取回「有销售出库的启用客户」，避免逐客户回表查名称（原实现 3 万余次单行查询）。
        List<Map<String,Object>> customers=jdbc.queryForList("""
            SELECT DISTINCT c.id id,c.customer_name customerName FROM customers c
            JOIN sales_outbounds s ON s.customer_id=c.id
            WHERE c.deleted_at IS NULL AND c.status<>'INACTIVE' ORDER BY c.id
            """);
        int processedCustomers=0,createdCustomers=0,mappedExisting=0,keptClusters=0,movedOutbounds=0,skippedClusters=0;
        // 复用索引只预载一次，替代逐簇全表扫描查询。
        // 复用目标排除「本身就是混合档案」的客户：按电话/地址命中它们只会把新簇继续并进大杂烩。
        Set<Long> mixedTargets=mixedCustomerIds();
        Map<String,Set<Long>> customersByPhone=customersByPhone(mixedTargets);
        Map<String,Set<Long>> customersByAddress=customersByAddress(mixedTargets);
        for(Map<String,Object> customer:customers) {
            String name=Objects.toString(customer.get("customerName"),"");
            if(!genericCustomerName(name)&&!platformPseudoCustomerName(name))continue;
            long id=((Number)customer.get("id")).longValue();
            // 身份簇只聚类一次，逐簇决策直接复用；重新聚类是平方级比较，逐簇重算会把批量治理推到立方级。
            List<IdentityCluster> snapshot=customerIdentityClusters(id);
            if(snapshot.isEmpty())continue;
            processedCustomers++;
            for(IdentityCluster cluster:snapshot) {
                boolean create=autoCreateGenericCluster(cluster);
                Long reusable=create?reusableCustomerForCluster(cluster,id,customersByPhone,customersByAddress):null;
                String action=create?(reusable==null?"CREATE_NEW":"MAP_EXISTING"):"KEEP_CURRENT";
                String reason=create?(reusable==null?"泛化名称批量治理：有效电话或完整地址形成独立身份簇":"泛化名称批量治理：电话或完整地址唯一命中已有客户"):
                        "泛化名称批量治理：证据不足，保留当前归属等待补充资料";
                String customerName=create&&reusable==null?suggestedClusterCustomerName(cluster):null;
                Map<String,Object> result=applyClusterDecision(id,cluster,cluster.key(),action,customerName,reusable,reason);
                // 快照算出的簇可能在本轮之前已被处理（幂等护栏返回 SKIPPED），跳过即可，不能整批失败。
                if("SKIPPED".equals(result.get("status"))){skippedClusters++;continue;}
                if(create){
                    if(reusable==null){
                        registerReusableKeys(cluster,((Number)result.get("targetCustomerId")).longValue(),customersByPhone,customersByAddress);
                        createdCustomers++;
                    }else mappedExisting++;
                    movedOutbounds+=((Number)result.get("movedOutbounds")).intValue();
                }else keptClusters++;
            }
        }
        audit.log("CustomerSplit",null,"泛化客户批量治理","BATCH_CONFIRM","处理源客户="+processedCustomers+"，新建客户="+createdCustomers+"，迁移出库="+movedOutbounds+"，跳过已处理簇="+skippedClusters);
        invalidatePendingOutbounds();
        return Map.of("processedCustomers",processedCustomers,"createdCustomers",createdCustomers,"mappedExisting",mappedExisting,"keptClusters",keptClusters,"movedOutbounds",movedOutbounds,"skippedClusters",skippedClusters);
    }

    private static boolean autoCreateGenericCluster(IdentityCluster cluster) {
        if(!cluster.phones().isEmpty())return true;
        return cluster.addresses().stream().anyMatch(SourceIdentityService::safeBusinessAddress);
    }

    /** 电话是客户身份的主依据；电话缺失时再用完整地址复用。命中多个档案时放弃自动判断。 */
    private Long reusableCustomerForCluster(IdentityCluster cluster,long sourceCustomerId,
                                            Map<String,Set<Long>> customersByPhone,Map<String,Set<Long>> customersByAddress) {
        for(String phone:cluster.phones()) {
            Long only=singleOtherCustomer(customersByPhone.get(phone),sourceCustomerId);
            if(only!=null)return only;
        }
        for(String address:cluster.addresses()) {
            if(!safeBusinessAddress(address))continue;
            Long only=singleOtherCustomer(customersByAddress.get(normalizedBusinessAddress(address)),sourceCustomerId);
            if(only!=null)return only;
        }
        return null;
    }

    /** 除源客户外恰好命中一个档案才可复用；命中多个（或没有）都放弃自动判断。 */
    private static Long singleOtherCustomer(Set<Long> candidates,long sourceCustomerId) {
        Long only=null;
        if(candidates==null)return null;
        for(Long id:candidates) {
            if(id==null||id==sourceCustomerId)continue;
            if(only!=null)return null;
            only=id;
        }
        return only;
    }

    /**
     * 本身就是「混合档案」的客户（{@link #mixedIdentity}）：它们名下的电话/地址本来就来自多个真实客户，
     * 不能拿它们当批量治理的复用目标，否则按电话/地址命中只会把新簇继续塞进同一个大杂烩。
     */
    private Set<Long> mixedCustomerIds() {
        Set<Long> result = new HashSet<>();
        for (Map.Entry<Long, CustomerOutboundStats> entry : outboundStatsByCustomer().entrySet()) {
            CustomerOutboundStats stats = entry.getValue();
            if (mixedIdentity(stats.addresses.size(), stats.phones.size())) result.add(entry.getKey());
        }
        return result;
    }

    /**
     * 批量治理的复用索引：一次性把「电话 → 客户」「规范化地址 → 客户」读进内存。
     * 逐簇查询等价条件含 REPLACE()，无法走索引（实测单次约 200ms），数千个簇即分钟级。
     * excluded 里的客户（本身就是混合档案）不参与复用命中。
     */
    private Map<String,Set<Long>> customersByPhone(Set<Long> excluded) {
        Map<String,Set<Long>> result=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
            SELECT c.id id,REPLACE(REPLACE(REPLACE(COALESCE(cc.mobile,''),' ',''),'-',''),'+86','') phone
            FROM customer_contacts cc JOIN customers c ON c.id=cc.customer_id
            WHERE c.deleted_at IS NULL AND c.status<>'INACTIVE'
            """)) {
            String phone=Objects.toString(row.get("phone"),"");
            if(blank(phone))continue;
            long customerId=((Number)row.get("id")).longValue();
            if(excluded.contains(customerId))continue;
            result.computeIfAbsent(phone,ignored->new LinkedHashSet<>()).add(customerId);
        }
        return result;
    }

    private Map<String,Set<Long>> customersByAddress(Set<Long> excluded) {
        Map<String,Set<Long>> result=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
            SELECT c.id id,ca.normalized_address address
            FROM customer_addresses ca JOIN customers c ON c.id=ca.customer_id
            WHERE c.deleted_at IS NULL AND c.status<>'INACTIVE'
            """)) {
            String address=Objects.toString(row.get("address"),"");
            if(blank(address))continue;
            long customerId=((Number)row.get("id")).longValue();
            if(excluded.contains(customerId))continue;
            result.computeIfAbsent(address,ignored->new LinkedHashSet<>()).add(customerId);
        }
        return result;
    }

    /** 新建客户后把它的电话/地址登记进复用索引，保持与逐簇查询一致的后续复用行为。 */
    private static void registerReusableKeys(IdentityCluster cluster,long targetCustomerId,
                                             Map<String,Set<Long>> customersByPhone,Map<String,Set<Long>> customersByAddress) {
        for(String phone:cluster.phones()) customersByPhone.computeIfAbsent(phone,ignored->new LinkedHashSet<>()).add(targetCustomerId);
        for(String address:cluster.addresses()) {
            if(!safeBusinessAddress(address))continue;
            customersByAddress.computeIfAbsent(normalizedBusinessAddress(address),ignored->new LinkedHashSet<>()).add(targetCustomerId);
        }
    }

    @Transactional
    public Map<String, Object> decideCustomerCluster(long customerId, Map<String, Object> body) {
        String action = clean(Objects.toString(body.get("action"), "")).toUpperCase(Locale.ROOT);
        if (!List.of("CREATE_NEW", "MAP_EXISTING", "KEEP_CURRENT", "DEFER").contains(action))
            throw new IllegalArgumentException("未知处理方式");
        String requestedKey = clean(Objects.toString(body.get("clusterKey"), ""));
        if (blank(requestedKey)) throw new IllegalArgumentException("缺少身份簇标识");
        IdentityCluster selected = customerIdentityClusters(customerId).stream()
                .filter(cluster -> requestedKey.equals(cluster.key())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("身份簇已变化，请刷新后重试"));
        Object value = body.get("targetCustomerId");
        Long targetCustomerId = value instanceof Number number ? number.longValue() : null;
        Map<String, Object> result = applyClusterDecision(customerId, selected, requestedKey, action,
                clean(Objects.toString(body.get("customerName"), "")), targetCustomerId,
                clean(Objects.toString(body.get("reason"), "")));
        if ("SKIPPED".equals(result.get("status")))
            throw new IllegalArgumentException("该身份簇已处理，请刷新后重试");
        return result;
    }

    /**
     * 执行单个身份簇的拆分决策。
     * 批量治理场景下身份簇已由调用方算好（{@code selected}），这里不再重复聚类——
     * 重新聚类是 O(地址×电话组合数²) 的两两比较，逐簇重算会让批量治理复杂度升到立方级。
     */
    private Map<String, Object> applyClusterDecision(long customerId, IdentityCluster selected, String requestedKey,
                                                     String action, String requestedName, Long targetCustomerId, String reason) {
        List<Map<String, Object>> clusterRows = selected.rows();
        if (clusterRows.isEmpty()) throw new IllegalArgumentException("身份簇已变化，请刷新后重试");
        // 幂等护栏：已经存在 CONFIRMED 身份映射的行，说明这个簇早就定过归属（或已归到其他客户）。
        // 缺了这道校验时，重复点击 / 重复批量每执行一次就会多建一个客户，形成「同一簇被反复拆分」。
        Set<String> decided = confirmedIdentityKeys();
        List<Map<String, Object>> outbounds = clusterRows.stream()
                .filter(row -> !decided.contains(rowIdentityKey(row))).toList();
        if (outbounds.isEmpty()) return Map.of("runId", 0L, "status", "SKIPPED", "movedOutbounds", 0,
                "targetCustomerId", customerId);
        Map<String, Object> source = jdbc.queryForMap("SELECT customer_name FROM customers WHERE id=? AND deleted_at IS NULL", customerId);
        String address = Objects.toString(outbounds.getFirst().get("customer_address"), "").trim();
        String phone = Objects.toString(outbounds.getFirst().get("contact_phone"), "").trim();
        Long targetId = customerId;
        boolean created = false;
        String sourceName = String.valueOf(source.get("customer_name"));
        String targetName = sourceName;
        String status = "RECORDED";
        if ("CREATE_NEW".equals(action)) {
            targetName = requestedName;
            if (blank(targetName)) targetName = targetNameForCluster(sourceName, address, phone, customerId);
            targetId = createSplitCustomer(targetName, address);
            created = true; status = "APPLIED";
        } else if ("MAP_EXISTING".equals(action)) {
            if (targetCustomerId == null || targetCustomerId == customerId)
                throw new IllegalArgumentException("请选择另一个客户档案");
            targetId = targetCustomerId;
            targetName = jdbc.queryForObject("SELECT customer_name FROM customers WHERE id=? AND deleted_at IS NULL", String.class, targetId);
            if (targetName == null) throw new IllegalArgumentException("目标客户不存在");
            status = "APPLIED";
        }
        var current = users.current();
        KeyHolder holder = new GeneratedKeyHolder();
        Long finalTargetId = targetId; String finalTargetName = targetName; String finalStatus = status;
        boolean finalCreated = created;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                INSERT INTO customer_split_runs(source_customer_id,target_customer_id,cluster_key,action,status,
                  source_customer_name,target_customer_name,cluster_address,cluster_phone,reason,created_customer,
                  outbound_count,created_by_employee_id,created_by_name)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, customerId); ps.setObject(2, finalTargetId); ps.setString(3, requestedKey);
            ps.setString(4, action); ps.setString(5, finalStatus); ps.setString(6, String.valueOf(source.get("customer_name")));
            ps.setString(7, finalTargetName); ps.setString(8, address); ps.setString(9, phone); ps.setString(10, reason);
            ps.setBoolean(11, finalCreated); ps.setInt(12, outbounds.size()); ps.setObject(13, current.employeeId()); ps.setString(14, current.name());
            return ps;
        }, holder);
        long runId = Objects.requireNonNull(holder.getKey()).longValue();
        if ("APPLIED".equals(status)) {
            for (Map<String, Object> row : outbounds) {
                long outboundId = ((Number) row.get("id")).longValue();
                jdbc.update("INSERT INTO customer_split_run_items(run_id,sales_outbound_id,previous_customer_id,applied_customer_id) VALUES(?,?,?,?)",
                        runId, outboundId, customerId, targetId);
                jdbc.update("UPDATE sales_outbounds SET customer_id=? WHERE id=? AND customer_id=?", targetId, outboundId, customerId);
            }
            ensureSplitAddress(targetId, address);
            ensureSplitContact(targetId, targetName, phone);
            // 源客户若因此次迁移导致无任何业务事实，自动停用；撤销拆分时可按标记恢复启用。
            deactivateSplitSourceIfEmpty(customerId);
        }
        if (!"DEFER".equals(action)) {
            for (Map<String, Object> outbound : outbounds) {
                String normalizedName = identityName(Objects.toString(outbound.get("customer_name"), ""));
                if (normalizedName == null) continue;
                String memberKey = clusterKey(Objects.toString(outbound.get("customer_address"), ""),
                        Objects.toString(outbound.get("contact_phone"), ""));
                jdbc.update("""
                    INSERT INTO customer_source_cluster_mappings(normalized_source_name,cluster_key,customer_id,split_run_id)
                    VALUES(?,?,?,?)
                    ON DUPLICATE KEY UPDATE customer_id=VALUES(customer_id),split_run_id=VALUES(split_run_id),status='CONFIRMED',updated_at=NOW()
                    """, normalizedName, memberKey, targetId, runId);
                // 名称级别名无法表达同名多客户，退回复核，后续销售出库改由身份簇优先解析。
                jdbc.update("""
                    UPDATE source_identity_aliases SET target_id=NULL,resolution_status='REVIEW',issue_code='AMBIGUOUS_CUSTOMER',
                      resolution_note='该来源名称已按地址/电话拆分，销售出库按身份簇解析',updated_at=NOW()
                    WHERE source_system='H3YUN' AND entity_type='CUSTOMER' AND normalized_name=?
                    """, normalizedName);
            }
        }
        audit.log("CustomerSplit", runId, String.valueOf(source.get("customer_name")), action,
                "身份簇出库=" + outbounds.size() + "，目标客户=" + targetName);
        if (!"DEFER".equals(action)) invalidatePendingOutbounds();
        return Map.of("runId", runId, "status", status, "movedOutbounds", "APPLIED".equals(status) ? outbounds.size() : 0,
                "targetCustomerId", targetId);
    }

    @Transactional
    public Map<String, Object> undoCustomerSplit(long runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM customer_split_runs WHERE id=? FOR UPDATE", runId);
        if (!"APPLIED".equals(run.get("status"))) throw new IllegalArgumentException("该记录不是可撤销状态");
        long sourceId = ((Number) run.get("source_customer_id")).longValue();
        long targetId = ((Number) run.get("target_customer_id")).longValue();
        int restored = jdbc.update("""
            UPDATE sales_outbounds s JOIN customer_split_run_items i ON i.sales_outbound_id=s.id
            SET s.customer_id=i.previous_customer_id
            WHERE i.run_id=? AND s.customer_id=i.applied_customer_id
            """, runId);
        var current = users.current();
        jdbc.update("UPDATE customer_split_runs SET status='UNDONE',undone_by_employee_id=?,undone_by_name=?,undone_at=NOW() WHERE id=?",
                current.employeeId(), current.name(), runId);
        jdbc.update("UPDATE customer_source_cluster_mappings SET status='REVOKED',updated_at=NOW() WHERE split_run_id=?", runId);
        invalidatePendingOutbounds();
        if (restored > 0) {
            // 恢复出库后，若源客户曾因“拆分后业务清零”被自动停用，则重新启用。
            jdbc.update("""
                UPDATE customers SET deleted_at=NULL,status='ACTIVE',updated_at=NOW()
                WHERE id=? AND deleted_at IS NOT NULL AND remark LIKE '%SPLIT_CLEARED_OUTBOUND%'
                """, sourceId);
        }
        if (truthy(run.get("created_customer"))) {
            Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM sales_outbounds WHERE customer_id=?", Long.class, targetId);
            if (remaining != null && remaining == 0) jdbc.update("UPDATE customers SET deleted_at=NOW(),status='INACTIVE' WHERE id=?", targetId);
        }
        audit.log("CustomerSplit", runId, String.valueOf(run.get("source_customer_name")), "UNDO", "恢复出库=" + restored);
        return Map.of("runId", runId, "restoredOutbounds", restored, "sourceCustomerId", sourceId);
    }

    /**
     * 撤销一次身份簇处理（候选簇抽屉里的「撤销」）：
     * - APPLIED（建立新客户 / 归入已有）：走撤销拆分，出库退回源客户；
     * - RECORDED（保留当前，未迁移出库）：只解除「已处理」标记，簇重新回到待处理列表。
     */
    @Transactional
    public Map<String, Object> revokeClusterDecision(long runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM customer_split_runs WHERE id=? FOR UPDATE", runId);
        String status = Objects.toString(run.get("status"), "");
        if ("APPLIED".equals(status)) return undoCustomerSplit(runId);
        if (!"RECORDED".equals(status)) throw new IllegalArgumentException("该记录已撤销或不可撤销");
        int revoked = jdbc.update("""
            UPDATE customer_source_cluster_mappings SET status='REVOKED',updated_at=NOW()
            WHERE split_run_id=? AND status='CONFIRMED'
            """, runId);
        var current = users.current();
        jdbc.update("UPDATE customer_split_runs SET status='UNDONE',undone_by_employee_id=?,undone_by_name=?,undone_at=NOW() WHERE id=?",
                current.employeeId(), current.name(), runId);
        audit.log("CustomerSplit", runId, String.valueOf(run.get("source_customer_name")), "REVOKE",
                "解除已处理标记，簇数=" + revoked);
        invalidatePendingOutbounds();
        return Map.of("runId", runId, "revokedClusters", revoked, "restoredOutbounds", 0,
                "sourceCustomerId", ((Number) run.get("source_customer_id")).longValue());
    }

    private void deactivateSplitSourceIfEmpty(long customerId) {
        Long facts = jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM sales_outbounds WHERE customer_id=?)
                 + (SELECT COUNT(*) FROM receipts WHERE customer_id=?)
                 + (SELECT COUNT(*) FROM production_orders WHERE customer_id=?)
                 + (SELECT COUNT(*) FROM manufacturing_orders WHERE customer_id=?)
                 + (SELECT COUNT(*) FROM sales_contracts WHERE customer_id=?)
            """, Long.class, customerId, customerId, customerId, customerId, customerId);
        if (facts != null && facts == 0) {
            jdbc.update("""
                UPDATE customers SET deleted_at=NOW(),status='INACTIVE',updated_at=NOW(),
                  remark=CONCAT_WS('；',NULLIF(remark,''),'SPLIT_CLEARED_OUTBOUND：拆分迁移后无业务事实，撤销拆分可恢复')
                WHERE id=?
                """, customerId);
        }
    }

    private Long createSplitCustomer(String name, String address) {
        Long id = createPendingCustomer(name, address);
        jdbc.update("UPDATE customers SET remark='由高风险客户身份簇拆分建立，可通过拆分记录撤销' WHERE id=?", id);
        return id;
    }

    private void ensureSplitAddress(long customerId, String address) {
        if (blank(address)) return;
        String normalized = normalizedBusinessAddress(address);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer_addresses WHERE customer_id=? AND normalized_address=?", Integer.class, customerId, normalized);
        if (count != null && count > 0) return;
        Integer all = jdbc.queryForObject("SELECT COUNT(*) FROM customer_addresses WHERE customer_id=?", Integer.class, customerId);
        jdbc.update("INSERT INTO customer_addresses(customer_id,address_name,detail_address,normalized_address,is_default,source,remark) VALUES(?,'拆分身份簇地址',?,?,?,'H3YUN_SALES_OUTBOUND_HISTORY','由客户拆分工作台归集')",
                customerId, address, normalized, all == null || all == 0 ? 1 : 0);
    }

    private void ensureSplitContact(long customerId, String name, String phone) {
        if (blank(phone)) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer_contacts WHERE customer_id=? AND REPLACE(REPLACE(COALESCE(mobile,''),' ',''),'-','')=REPLACE(REPLACE(?,' ',''),'-','')", Integer.class, customerId, phone);
        if (count != null && count > 0) return;
        Integer all = jdbc.queryForObject("SELECT COUNT(*) FROM customer_contacts WHERE customer_id=?", Integer.class, customerId);
        jdbc.update("INSERT INTO customer_contacts(customer_id,name,mobile,contact_type,is_primary,remark) VALUES(?,?,?,'其他',?,'由客户拆分工作台从出库快照归集')",
                customerId, name, phone, all == null || all == 0 ? 1 : 0);
    }

    private static String targetNameForCluster(String sourceName, String address, String phone, long customerId) {
        String suffix = !blank(phone) ? phone.replaceAll("\\D", "") : normalizedBusinessAddress(address);
        if (blank(suffix)) suffix = String.valueOf(customerId);
        suffix = suffix.length() > 6 ? suffix.substring(suffix.length() - 6) : suffix;
        return sourceName + "[" + suffix + "]";
    }

    private static String clusterKey(String address, String phone) {
        String raw = Objects.toString(normalizedBusinessAddress(address), "") + "|" +
                PHONE_SEPARATOR.matcher(clean(phone).toLowerCase(Locale.ROOT)).replaceAll("");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    /**
     * 电话是客户划分的主依据：同一有效电话归为同一客户，即使存在多个收货地址；
     * 电话缺失时，才用相同或高度近似地址归组。业务员不参与自动合并。
     */
    private List<IdentityCluster> customerIdentityClusters(long customerId) {
        Set<String> decided = confirmedIdentityKeys();
        List<Map<String, Object>> pending = identityRows(customerId).stream()
                .filter(row -> !decided.contains(rowIdentityKey(row))).toList();
        return clusterIdentityRows(pending);
    }

    /** 该客户的出库行：身份簇判定与抽屉共用同一份取数。 */
    private List<Map<String, Object>> identityRows(long customerId) {
        return jdbc.queryForList("""
            SELECT id,customer_id,customer_name,customer_address,contact_phone,salesperson_name,ship_date,
                   COALESCE(sales_amount,0) sales_amount
            FROM sales_outbounds WHERE customer_id=? ORDER BY id
            """, customerId);
    }

    /** 已确认身份映射：归一名称|簇键 → 映射行（带来源运行，供归属核对与撤销使用）。 */
    private Map<String, Map<String, Object>> confirmedClusterMappings() {
        Map<String, Map<String, Object>> decided = new LinkedHashMap<>();
        for (Map<String, Object> mapping : jdbc.queryForList("""
            SELECT id,normalized_source_name,cluster_key,customer_id,split_run_id FROM customer_source_cluster_mappings
            WHERE status='CONFIRMED'
            """)) {
            decided.put(identityKey(Objects.toString(mapping.get("normalized_source_name"), ""),
                    Objects.toString(mapping.get("cluster_key"), "")), mapping);
        }
        return decided;
    }

    /** 出库行的身份键：与簇映射落库时使用的键完全一致（名称归一 + 地址/电话簇键）。 */
    private static String rowIdentityKey(Map<String, Object> row) {
        return identityKey(Objects.toString(row.get("customer_name"), ""),
                clusterKey(Objects.toString(row.get("customer_address"), ""), Objects.toString(row.get("contact_phone"), "")));
    }

    /** 身份键：归一名称 + 簇键。落库、判定、幂等校验三处共用，避免出现第二套拼法。 */
    private static String identityKey(String sourceName, String clusterKey) {
        return Objects.toString(identityName(sourceName), "") + "|" + Objects.toString(clusterKey, "");
    }

    /** 把出库行聚成身份簇（电话是主依据，电话缺失时用相同或高度近似地址）。 */
    private List<IdentityCluster> clusterIdentityRows(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> atomic = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) atomic.computeIfAbsent(clusterKey(
                Objects.toString(row.get("customer_address"), ""), Objects.toString(row.get("contact_phone"), "")), ignored -> new ArrayList<>()).add(row);
        List<List<Map<String, Object>>> groups = new ArrayList<>(atomic.values());
        int[] parent = new int[groups.size()];
        for (int i=0;i<parent.length;i++) parent[i]=i;
        for (int i=0;i<groups.size();i++) for (int j=i+1;j<groups.size();j++) {
            Map<String,Object> a=groups.get(i).getFirst(), b=groups.get(j).getFirst();
            String aa=normalizedBusinessAddress(Objects.toString(a.get("customer_address"), ""));
            String ba=normalizedBusinessAddress(Objects.toString(b.get("customer_address"), ""));
            var ap=phoneTokens(Objects.toString(a.get("contact_phone"), ""));
            var bp=phoneTokens(Objects.toString(b.get("contact_phone"), ""));
            boolean samePhone=ap.stream().anyMatch(bp::contains);
            boolean sameAddress=!blank(aa) && aa.length()>=8 && aa.equals(ba);
            boolean closeAddress=addressSimilarity(aa,ba)>=0.88;
            if (samePhone || sameAddress || closeAddress) union(parent,i,j);
        }
        Map<Integer,List<Map<String,Object>>> merged=new LinkedHashMap<>();
        for(int i=0;i<groups.size();i++) merged.computeIfAbsent(find(parent,i), ignored->new ArrayList<>()).addAll(groups.get(i));
        List<IdentityCluster> result=new ArrayList<>();
        for(List<Map<String,Object>> members:merged.values()) result.add(toIdentityCluster(members));
        result.sort(Comparator.comparingInt((IdentityCluster c)->c.rows().size()).reversed()
                .thenComparing(IdentityCluster::lastShipDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private IdentityCluster toIdentityCluster(List<Map<String,Object>> rows) {
        List<String> addresses=rows.stream().map(r->clean(Objects.toString(r.get("customer_address"),""))).filter(v->!blank(v)).distinct().toList();
        List<String> phones=rows.stream().flatMap(r->phoneTokens(Objects.toString(r.get("contact_phone"),"")).stream()).distinct().toList();
        List<String> salespersons=rows.stream().map(r->clean(Objects.toString(r.get("salesperson_name"),""))).filter(v->!blank(v)).distinct().toList();
        String sourceName=rows.stream().map(r->clean(Objects.toString(r.get("customer_name"),""))).filter(v->!blank(v)).findFirst().orElse("待确认客户");
        String first=rows.stream().map(r->Objects.toString(r.get("ship_date"),null)).filter(Objects::nonNull).min(String::compareTo).orElse(null);
        String last=rows.stream().map(r->Objects.toString(r.get("ship_date"),null)).filter(Objects::nonNull).max(String::compareTo).orElse(null);
        java.math.BigDecimal amount=rows.stream().map(r->(java.math.BigDecimal)r.get("sales_amount")).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        List<String> memberKeys=rows.stream().map(r->clusterKey(Objects.toString(r.get("customer_address"),""),Objects.toString(r.get("contact_phone"),""))).distinct().sorted().toList();
        String key=sha256(String.join("|",memberKeys));
        String confidence=!phones.isEmpty() ? (phones.size()==1 ? "HIGH":"MEDIUM") : (addresses.size()<=2 ? "MEDIUM":"LOW");
        String reason=!phones.isEmpty() ? "已按相同电话归组，并归集该客户的不同收货地址" : addresses.size()>1 ? "电话缺失，已合并高度近似的地址写法" : "缺少可交叉验证的电话，请结合地址判断";
        return new IdentityCluster(key,sourceName,rows,addresses,phones,salespersons,first,last,amount,confidence,reason);
    }

    /**
     * 电话查找专用分词：先用与抽屉一致的 {@link #phoneTokens}；完全取不到时才把「夹在数字之间」的
     * 分隔符去掉再试一次，使「138 0000 0002」「138-0000-0002」这类写法也能参与匹配。
     * 只在原分词为空时兜底，避免把多个号码粘连成一个假号码。
     */
    private static List<String> phoneLookupTokens(String value) {
        if (blank(value)) return List.of();
        List<String> tokens = phoneTokens(value);
        if (!tokens.isEmpty()) return tokens;
        return phoneTokens(value.replaceAll("(?<=\\d)[\\s\\-()（）．.　]+(?=\\d)", ""));
    }

    private static List<String> phoneTokens(String value) {
        if(blank(value)) return List.of();
        Matcher matcher=PHONE_TOKEN.matcher(value);
        List<String> result=new ArrayList<>();
        while(matcher.find()) { String digits=matcher.group().replaceAll("\\D",""); if(digits.startsWith("86")&&digits.length()==13) digits=digits.substring(2); result.add(digits); }
        return result.stream().distinct().toList();
    }

    private static double addressSimilarity(String a,String b) {
        if(blank(a)||blank(b)||Math.min(a.length(),b.length())<8) return 0;
        if(a.equals(b)) return 1;
        if(Math.min(a.length(),b.length())>=10&&(a.contains(b)||b.contains(a))) return (double)Math.min(a.length(),b.length())/Math.max(a.length(),b.length());
        java.util.Set<String> left=bigrams(a),right=bigrams(b); long common=left.stream().filter(right::contains).count();
        return left.isEmpty()||right.isEmpty()?0:(2d*common)/(left.size()+right.size());
    }
    private static java.util.Set<String> bigrams(String value) { java.util.Set<String> out=new java.util.HashSet<>(); for(int i=0;i<value.length()-1;i++) out.add(value.substring(i,i+2)); return out; }
    private static int find(int[] parent,int x){ while(parent[x]!=x){parent[x]=parent[parent[x]];x=parent[x];}return x; }
    private static void union(int[] parent,int a,int b){int ra=find(parent,a),rb=find(parent,b);if(ra!=rb)parent[rb]=ra;}
    private static String sha256(String raw) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);} }
    private static String suggestedClusterCustomerName(IdentityCluster cluster) {
        String suffix=!cluster.phones().isEmpty()?cluster.phones().getFirst():(!cluster.addresses().isEmpty()?normalizedBusinessAddress(cluster.addresses().getFirst()):cluster.key());
        if(suffix.length()>6)suffix=suffix.substring(suffix.length()-6);
        return genericCustomerName(cluster.sourceName())||platformPseudoCustomerName(cluster.sourceName()) ? cluster.sourceName()+"["+suffix+"]" : cluster.sourceName();
    }
    private record IdentityCluster(String key,String sourceName,List<Map<String,Object>> rows,List<String> addresses,
                                   List<String> phones,List<String> salespersons,String firstShipDate,String lastShipDate,
                                   java.math.BigDecimal salesAmount,String confidence,String recommendation) {}

    /** 把错误客户别名退回待复核，并解除它对尚可纠正业务事实的绑定。 */
    @Transactional
    public Map<String, Object> reopenCustomerIdentity(long id) {
        Map<String, Object> row = one(id);
        if (!"CUSTOMER".equals(row.get("entity_type"))) throw new IllegalArgumentException("只能退回客户身份");
        if (!(row.get("target_id") instanceof Number target)) throw new IllegalArgumentException("该身份没有关联客户");
        String normalized = row.get("normalized_name") == null ? null : String.valueOf(row.get("normalized_name"));
        long targetId = target.longValue();
        int facts = 0;
        if (!blank(normalized)) {
            facts += jdbc.update("""
                UPDATE sales_outbounds SET customer_id=NULL WHERE customer_id=?
                  AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                """, targetId, normalized);
            facts += jdbc.update("""
                UPDATE production_orders SET customer_id=NULL WHERE customer_id=?
                  AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                """, targetId, normalized);
            facts += jdbc.update("""
                UPDATE receipts SET customer_id=NULL WHERE customer_id=?
                  AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                """, targetId, normalized);
        }
        jdbc.update("""
            UPDATE source_identity_aliases SET target_id=NULL,resolution_status='REVIEW',
              issue_code='MANUAL_REVIEW',confidence=NULL,resolution_note='从客户档案退回待复核',
              resolved_by_employee_id=NULL,resolved_by_name=NULL,resolved_at=NULL,updated_at=NOW()
            WHERE id=?
            """, id);
        audit.log("SourceIdentity", id, String.valueOf(row.get("source_name")), "REOPEN", "解除业务事实=" + facts);
        return Map.of("id", id, "unlinkedFacts", facts, "status", "REVIEW");
    }

    private List<Map<String, Object>> customerAddressImportCandidates() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT s.customer_id customerId,c.customer_name customerName,MIN(s.customer_address) address,
                   COUNT(*) outboundCount
            FROM sales_outbounds s JOIN customers c ON c.id=s.customer_id AND c.deleted_at IS NULL
            WHERE s.customer_address IS NOT NULL AND TRIM(s.customer_address)<>''
            GROUP BY s.customer_id,c.customer_name
            HAVING COUNT(DISTINCT LOWER(REPLACE(REPLACE(TRIM(s.customer_address),' ',''),'　','')))=1
            ORDER BY outboundCount DESC,s.customer_id
            """);
        return rows.stream().filter(row -> {
            String name = String.valueOf(row.get("customerName"));
            String address = String.valueOf(row.get("address")).trim();
            if (genericCustomerName(name) || !safeBusinessAddress(address)) return false;
            Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_addresses WHERE customer_id=? AND normalized_address=?
                """, Integer.class, row.get("customerId"), normalizedBusinessAddress(address));
            return exists != null && exists == 0;
        }).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("matchReason", "客户名称可靠，历史出库地址唯一");
            return item;
        }).toList();
    }

    @Transactional
    public Map<String, Object> confirmCustomerAddressImport() {
        List<Map<String, Object>> candidates = customerAddressImportCandidates();
        int inserted = 0;
        for (Map<String, Object> row : candidates) {
            long customerId = ((Number) row.get("customerId")).longValue();
            String address = String.valueOf(row.get("address")).trim();
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM customer_addresses WHERE customer_id=?", Integer.class, customerId);
            jdbc.update("""
                INSERT INTO customer_addresses(customer_id,address_name,detail_address,normalized_address,is_default,source,remark)
                VALUES(?,'历史出库地址',?,?,?,'H3YUN_SALES_OUTBOUND_HISTORY','客户名称可靠且历史出库地址唯一，系统安全归集')
                """, customerId, address, normalizedBusinessAddress(address), existing == null || existing == 0 ? 1 : 0);
            inserted++;
        }
        audit.log("CustomerAddress", null, "氚云销售出库", "SAFE_IMPORT", "安全归集地址=" + inserted);
        return Map.of("importedCustomers", candidates.size(), "importedAddresses", inserted);
    }

    private static boolean genericCustomerName(String name) {
        String cleaned = canonicalCustomerName(name);
        if (blank(cleaned) || cleaned.length() <= 1) return true;
        return cleaned.matches("(?i).*(先生|女士|小姐|经理|老板|客户|主管|师傅|工|总)$")
                || cleaned.matches("(?i)^(unknown|null|无|未知|客户|自提|x|l|j)$");
    }
    private static boolean safeBusinessAddress(String address) {
        if (blank(address) || normalizedBusinessAddress(address).length() < 10) return false;
        return !address.matches(".*(自提|客户自取|上门取|到付).*" )
                && !address.matches("^(浙江省|江苏省|广东省|安徽省|北京市|上海市|温州|厦门)[（(]?.*[）)]?$");
    }
    private static String normalizedBusinessAddress(String address) {
        return address == null ? null
                : ADDRESS_SEPARATOR.matcher(address.trim().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private List<CustomerMergeGroup> customerAddressMergeGroups() {
        Map<String, List<CustomerMergeMember>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
            SELECT c.id,c.customer_name,c.address,
                   (SELECT COUNT(*) FROM sales_outbounds s WHERE s.customer_id=c.id) outbound_count,
                   (SELECT COUNT(*) FROM sales_contracts sc WHERE sc.customer_id=c.id) immutable_count
            FROM customers c
            WHERE c.deleted_at IS NULL AND c.address IS NOT NULL AND CHAR_LENGTH(TRIM(c.address))>=12
            ORDER BY c.id
            """)) {
            String name = canonicalCustomerName(String.valueOf(row.get("customer_name")));
            String address = canonicalCustomerAddress(String.valueOf(row.get("address")));
            if (blank(name) || blank(address) || address.length() < 12) continue;
            String key = normalizeLoose(name) + "|" + normalizeLoose(address);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new CustomerMergeMember(
                    ((Number) row.get("id")).longValue(), String.valueOf(row.get("customer_name")),
                    ((Number) row.get("outbound_count")).longValue(),
                    ((Number) row.get("immutable_count")).longValue()));
        }
        List<CustomerMergeGroup> result = new ArrayList<>();
        for (List<CustomerMergeMember> members : grouped.values()) {
            if (members.size() < 2 || members.stream().filter(member -> member.immutableCount() > 0).count() > 1) continue;
            CustomerMergeMember first = members.getFirst();
            Map<String, Object> source = jdbc.queryForMap("SELECT customer_name,address FROM customers WHERE id=?", first.id());
            result.add(new CustomerMergeGroup(canonicalCustomerName(String.valueOf(source.get("customer_name"))),
                    canonicalCustomerAddress(String.valueOf(source.get("address"))), members));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> confirmCustomerAddressMerges() {
        List<CustomerMergeGroup> groups = customerAddressMergeGroups();
        int merged = 0;
        for (CustomerMergeGroup group : groups) {
            CustomerMergeMember survivor = group.members().stream()
                    .sorted(Comparator.comparingLong(CustomerMergeMember::immutableCount).reversed()
                            .thenComparing(CustomerMergeMember::outboundCount, Comparator.reverseOrder())
                            .thenComparingLong(CustomerMergeMember::id)).findFirst().orElseThrow();
            long keepId = survivor.id();
            Integer cleanNameExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM customers WHERE deleted_at IS NULL AND id<>? AND customer_name=?
                """, Integer.class, keepId, group.cleanName());
            String finalName = cleanNameExists != null && cleanNameExists > 0 ? survivor.name() : group.cleanName();
            jdbc.update("UPDATE customers SET customer_name=?,short_name=?,address=?,updated_at=NOW() WHERE id=?",
                    finalName, finalName.length() > 100 ? finalName.substring(0, 100) : finalName,
                    group.cleanAddress(), keepId);
            for (CustomerMergeMember member : group.members()) {
                if (member.id() == keepId) continue;
                mergeCustomerInto(member.id(), keepId, group.cleanName(), group.cleanAddress());
                merged++;
            }
        }
        return Map.of("mergedGroups", groups.size(), "mergedCustomers", merged);
    }

    /** 合并迁移行记录器：在真正搬行之前先把「将被搬走的行 id」记下来，供整批撤销使用。 */
    @FunctionalInterface
    public interface MergeRowSink {
        /** table/column 定位引用字段，extraWhere 为附加过滤（无则传空串）。 */
        void collect(String table, String column, String extraWhere, long fromId, long toId);
    }

    /** 业务事实表：引用 customer_id 的全部事实表都要跟着合并，漏一张就会留下孤儿引用。 */
    private static final List<String> MERGE_FACT_TABLES = List.of(
            "manufacturing_orders", "production_orders", "receipts", "sales_outbounds");

    /** 档案子表：同样引用 customer_id，合并后归到主客户名下。 */
    private static final List<String> MERGE_PROFILE_TABLES = List.of(
            "customer_contacts", "customer_addresses", "customer_salesperson_relations",
            "customer_source_cluster_mappings");

    private void mergeCustomerInto(long fromId, long toId, String cleanName, String cleanAddress) {
        mergeCustomerInto(fromId, toId, cleanName, cleanAddress, null);
    }

    /**
     * 把 from 档案并入 to：迁移全部引用该客户的业务事实与档案子表，留痕后软删除 from。
     *
     * <p>sink 非空时先把「将被迁移的行 id」记入 {@code customer_merge_moved_rows}，使整批合并可撤销。
     * 注意 {@code customer_merge_history.merged_customer_id} 是唯一键：同一档案一生只能被合并一次。
     * 另外 {@code customer_contacts/customer_addresses/customer_salesperson_relations} 的唯一键都含
     * customer_id，换主前需先降级冲突标记，否则会撞唯一键（详见方法内注释）。
     */
    private void mergeCustomerInto(long fromId, long toId, String cleanName, String cleanAddress, MergeRowSink sink) {
        for (String table : MERGE_FACT_TABLES) {
            if (sink != null) sink.collect(table, "customer_id", "", fromId, toId);
            jdbc.update("UPDATE " + table + " SET customer_id=? WHERE customer_id=?", toId, fromId);
        }
        if (sink != null) {
            for (String table : MERGE_PROFILE_TABLES) sink.collect(table, "customer_id", "", fromId, toId);
            sink.collect("source_identity_aliases", "target_id", "entity_type='CUSTOMER'", fromId, toId);
        }
        // 三张档案子表的唯一键都带 customer_id，换主会撞键，必须先消解冲突再搬运。
        // 与既有「地址 is_default 一律清零」同一思路：保留记录、只降级冲突标记，不删行。
        if (hasPrimaryContact(toId)) {
            jdbc.update("UPDATE customer_contacts SET is_primary=0 WHERE customer_id=? AND is_primary=1", fromId);
        }
        if (hasPrimarySalesperson(toId)) {
            jdbc.update("UPDATE customer_salesperson_relations SET role='COLLABORATOR' "
                    + "WHERE customer_id=? AND role='PRIMARY'", fromId);
        }
        // 同一规范化地址在目标档案已存在时，清空被并入行的 normalized_address（保留该地址的联系人/电话），
        // 否则会撞 uk_customer_address_normalized。
        jdbc.update("""
            UPDATE customer_addresses a
            JOIN customer_addresses b ON b.customer_id=? AND b.normalized_address=a.normalized_address
            SET a.normalized_address=NULL
            WHERE a.customer_id=? AND a.normalized_address IS NOT NULL
            """, toId, fromId);
        jdbc.update("UPDATE customer_contacts SET customer_id=? WHERE customer_id=?", toId, fromId);
        jdbc.update("UPDATE customer_addresses SET customer_id=?,is_default=0 WHERE customer_id=?", toId, fromId);
        jdbc.update("UPDATE customer_salesperson_relations SET customer_id=? WHERE customer_id=?", toId, fromId);
        // 身份簇映射：唯一键是 (source_system,normalized_source_name,cluster_key)，同一簇全局只有一行，
        // 因此换客户不会撞唯一键。
        jdbc.update("UPDATE customer_source_cluster_mappings SET customer_id=? WHERE customer_id=?", toId, fromId);
        jdbc.update("UPDATE source_identity_aliases SET target_id=? WHERE entity_type='CUSTOMER' AND target_id=?", toId, fromId);
        jdbc.update("""
            INSERT IGNORE INTO customer_merge_history
              (primary_customer_id,merged_customer_id,merged_customer_code,merged_customer_name,merged_address,merge_reason,merged_at)
            SELECT ?,id,customer_code,customer_name,address,'客户名称主体及完整地址一致',NOW()
            FROM customers WHERE id=?
            """, toId, fromId);
        jdbc.update("""
            UPDATE customers SET deleted_at=NOW(),status='INACTIVE',
              remark=CONCAT_WS('；',NULLIF(remark,''),?),updated_at=NOW() WHERE id=?
            """, "按相同客户名称主体和完整地址合并至客户ID=" + toId, fromId);
        audit.log("Customer", fromId, cleanName, "MERGE_BY_NAME_ADDRESS",
                "合并至客户ID=" + toId + "，规范地址=" + cleanAddress);
    }

    /** 目标档案是否已有主联系人（{@code uk_customer_contact_primary} 只允许一条）。 */
    private boolean hasPrimaryContact(long customerId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM customer_contacts WHERE customer_id=? AND is_primary=1)",
                Boolean.class, customerId);
        return Boolean.TRUE.equals(exists);
    }

    /** 目标档案是否已有主业务员（{@code uk_customer_salesperson_primary} 只允许一条）。 */
    private boolean hasPrimarySalesperson(long customerId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM customer_salesperson_relations WHERE customer_id=? AND role='PRIMARY')",
                Boolean.class, customerId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 按外部整理方案执行一次合并：复用 {@link #mergeCustomerInto} 的同一套迁移逻辑，
     * 额外把「迁移行明细 + 被并入档案改名前快照」落库，使这次合并可以按批次整体撤销。
     */
    @Transactional
    public void mergeByPlan(long fromId, long toId, String batchTag, String mergeGroup,
                            String cleanName, String cleanAddress) {
        snapshotPlanUndo(fromId, batchTag, mergeGroup);
        // 注意：文本块会去掉行尾空白，故 "FROM" 后必须显式补一个空格，否则与表名粘连。
        MergeRowSink sink = (table, column, extraWhere, from, to) -> jdbc.update("""
            INSERT INTO customer_merge_moved_rows
              (batch_tag,merge_group,from_customer_id,to_customer_id,table_name,column_name,row_id)
            SELECT ?,?,?,?,?,?,id FROM """ + " " + table + " WHERE " + column + "=?"
                + (extraWhere.isEmpty() ? "" : " AND " + extraWhere),
                batchTag, mergeGroup, from, to, table, column, from);
        mergeCustomerInto(fromId, toId, cleanName, cleanAddress, sink);
    }

    /** 按方案改名前/合并前快照客户原状态，供整批撤销还原。 */
    public void snapshotPlanUndo(long customerId, String batchTag, String mergeGroup) {
        jdbc.update("""
            INSERT IGNORE INTO customer_merge_plan_undo
              (batch_tag,merge_group,customer_id,customer_code,prev_name,prev_short_name,
               prev_address,prev_status,prev_remark)
            SELECT ?,?,id,customer_code,customer_name,short_name,address,status,remark
            FROM customers WHERE id=?
            """, batchTag, mergeGroup, customerId);
    }

    @Transactional
    public Map<String, Object> createCustomerFromIdentity(long id, String requestedName) {
        Map<String, Object> row = one(id);
        if (!"CUSTOMER".equals(row.get("entity_type"))) throw new IllegalArgumentException("只有客户身份可以建立客户档案");
        if (!"REVIEW".equals(row.get("resolution_status"))) throw new IllegalArgumentException("该身份已处理");
        if (row.get("target_id") != null) throw new IllegalArgumentException("该身份已有候选档案，请确认或整理现有档案");
        String sourceName = String.valueOf(row.get("source_name"));
        String name = blank(requestedName) ? canonicalCustomerName(sourceName) : requestedName.trim();
        if (blank(name) || name.length() < 2 || name.length() > 200) throw new IllegalArgumentException("客户名称应为2-200个字符");
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE deleted_at IS NULL AND customer_name=?",
                Integer.class, name);
        if (duplicate != null && duplicate > 0) throw new IllegalArgumentException("已存在同名客户，请使用“选择档案”进行关联");
        long customerId = createPendingCustomer(name);
        jdbc.update("UPDATE customers SET source='H3YUN_SALES_OUTBOUND',remark=? WHERE id=?",
                "由身份解析人工建立；氚云原文=" + sourceName, customerId);
        Map<String, Object> resolved = resolve(id, customerId, "人工确认从氚云销售出库建立客户档案");
        return Map.of("customerId", customerId, "customerName", name,
                "backfilledFacts", resolved.get("backfilledFacts"));
    }

    private List<NewCustomerCandidate> newCustomerCandidates() {
        Map<String, Long> existing = preferredCustomersByCanonicalName();
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
            SELECT id,source_name sourceName,occurrence_count occurrenceCount
            FROM source_identity_aliases
            WHERE entity_type='CUSTOMER' AND resolution_status='REVIEW'
              AND issue_code='UNRESOLVED_OUTBOUND_CUSTOMER' AND target_id IS NULL
            ORDER BY occurrence_count DESC,id
            """)) {
            String sourceName = String.valueOf(row.get("sourceName"));
            String cleaned = canonicalCustomerName(sourceName);
            String key = canonicalCustomerKey(cleaned);
            if (key == null || existing.containsKey(key) || containsComplexCustomerText(sourceName)
                    || CONTACT_NUMBER.matcher(sourceName).find() || !safeNewCustomerName(cleaned)) continue;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<NewCustomerCandidate> result = new ArrayList<>();
        for (List<Map<String, Object>> rows : groups.values()) {
            String customerName = canonicalCustomerName(String.valueOf(rows.getFirst().get("sourceName")));
            long occurrences = rows.stream().mapToLong(row ->
                    row.get("occurrenceCount") instanceof Number n ? n.longValue() : 0).sum();
            result.add(new NewCustomerCandidate(customerName, rows, occurrences));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> confirmNewCustomers() {
        List<NewCustomerCandidate> candidates = newCustomerCandidates();
        int identities = 0, backfilled = 0;
        for (NewCustomerCandidate candidate : candidates) {
            long customerId = createPendingCustomer(candidate.customerName());
            jdbc.update("""
                UPDATE customers SET source='H3YUN_SALES_OUTBOUND',
                  remark='由氚云销售出库客户名称安全建档；原始名称保留在身份映射中'
                WHERE id=?
                """, customerId);
            for (Map<String, Object> row : candidate.rows()) {
                Map<String, Object> resolved = resolve(((Number) row.get("id")).longValue(), customerId,
                        "氚云销售出库中存在客户且无已有档案候选，安全建立客户档案");
                identities++;
                backfilled += ((Number) resolved.get("backfilledFacts")).intValue();
            }
        }
        return Map.of("createdCustomers", candidates.size(), "confirmedIdentities", identities,
                "backfilledFacts", backfilled);
    }

    private List<Map<String, Object>> linkedReviewCandidates() {
        return jdbc.queryForList("""
            SELECT a.id,a.source_name sourceName,a.target_id targetId,c.customer_name targetName,
                   a.issue_code issueCode,a.occurrence_count occurrenceCount
            FROM source_identity_aliases a
            JOIN customers c ON c.id=a.target_id AND c.deleted_at IS NULL
            WHERE a.entity_type='CUSTOMER' AND a.resolution_status='REVIEW'
              AND a.target_id IS NOT NULL
              AND a.issue_code IN ('CUSTOMER_NAME_TOO_SHORT','CUSTOMER_NAME_CONTAINS_PHONE',
                                   'CUSTOMER_NAME_TOO_LONG','UNRESOLVED_OUTBOUND_CUSTOMER')
            ORDER BY a.occurrence_count DESC,a.id
            """);
    }

    @Transactional
    public Map<String, Object> confirmLinkedReviews() {
        List<Map<String, Object>> rows = linkedReviewCandidates();
        int backfilled = 0;
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            long targetId = ((Number) row.get("targetId")).longValue();
            Map<String, Object> result = resolve(id, targetId,
                    "已有唯一客户档案，身份关系确认；名称质量问题留待客户档案治理");
            backfilled += ((Number) result.get("backfilledFacts")).intValue();
        }
        return Map.of("confirmed", rows.size(), "backfilledFacts", backfilled);
    }

    public Map<String, Object> customerCleanupBatchPreview() {
        List<Map<String, Object>> all = customerCleanupCandidates();
        long affected = all.stream()
                .mapToLong(row -> row.get("occurrenceCount") instanceof Number n ? n.longValue() : 0)
                .sum();
        return Map.of("cleanupCount", all.size(), "affectedFacts", affected,
                "items", all.stream().limit(100).toList());
    }

    @Transactional
    public Map<String, Object> confirmCustomerCleanupBatch() {
        List<Map<String, Object>> items = customerCleanupCandidates();
        int cleaned = 0, backfilled = 0;
        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked")
            List<String> phones = (List<String>) item.get("phones");
            Map<String, Object> result = cleanupCustomer(
                    ((Number) item.get("id")).longValue(),
                    String.valueOf(item.get("cleanedName")),
                    phones.isEmpty() ? null : phones.getFirst(),
                    "清晰的姓名+单个尾部电话号码，批量整理");
            cleaned++;
            backfilled += ((Number) result.get("backfilledFacts")).intValue();
        }
        return Map.of("cleaned", cleaned, "backfilledFacts", backfilled);
    }

    private List<Map<String, Object>> customerCleanupCandidates() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,source_name sourceName,target_id targetId,occurrence_count occurrenceCount
            FROM source_identity_aliases
            WHERE entity_type='CUSTOMER' AND resolution_status='REVIEW'
              AND issue_code='CUSTOMER_NAME_CONTAINS_PHONE' AND target_id IS NOT NULL
            ORDER BY occurrence_count DESC,id
            """);
        List<Map<String, Object>> parsed = new ArrayList<>();
        Map<String, Integer> proposedCounts = new HashMap<>();
        Map<String, Integer> phoneCounts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            CustomerCleanup cleanup = suggestCustomerCleanup(String.valueOf(row.get("sourceName")));
            if (!cleanup.safe() || cleanup.phones().size() != 1) continue;
            String proposed = normalize(cleanup.name());
            proposedCounts.merge(proposed, 1, Integer::sum);
            phoneCounts.merge(cleanup.phones().getFirst(), 1, Integer::sum);
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("cleanedName", cleanup.name());
            item.put("phones", cleanup.phones());
            parsed.add(item);
        }
        return parsed.stream().filter(item -> {
            String proposed = normalize(String.valueOf(item.get("cleanedName")));
            if (proposedCounts.getOrDefault(proposed, 0) != 1) return false;
            @SuppressWarnings("unchecked")
            String phone = ((List<String>) item.get("phones")).getFirst();
            if (phoneCounts.getOrDefault(phone, 0) > 2 || isSharedCustomerPhone(phone)) return false;
            long targetId = ((Number) item.get("targetId")).longValue();
            Integer collision = jdbc.queryForObject("""
                SELECT COUNT(*) FROM customers WHERE deleted_at IS NULL AND id<>?
                  AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                """, Integer.class, targetId, proposed);
            return collision != null && collision == 0;
        }).toList();
    }

    private List<Map<String, Object>> safeBatchCandidates() {
        Map<String, Long> uniqueCustomers = preferredCustomersByCanonicalName();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.id,a.source_name sourceName,a.target_id targetId,
                   (SELECT customer_name FROM customers WHERE id=a.target_id) targetName,
                   a.occurrence_count occurrenceCount
            FROM source_identity_aliases a
            WHERE a.entity_type='CUSTOMER' AND a.resolution_status='REVIEW'
              AND a.issue_code='UNRESOLVED_OUTBOUND_CUSTOMER'
            ORDER BY a.occurrence_count DESC,a.id
            """);
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sourceName = String.valueOf(row.get("sourceName"));
            Long target = row.get("targetId") instanceof Number n ? n.longValue() : null;
            String targetName = row.get("targetName") == null ? null : String.valueOf(row.get("targetName"));
            if (target == null) target = uniqueCustomers.get(canonicalCustomerKey(sourceName));
            if (target == null) continue;
            if (targetName == null) {
                targetName = jdbc.queryForObject("SELECT customer_name FROM customers WHERE id=?", String.class, target);
            }
            if (!Objects.equals(canonicalCustomerKey(sourceName), canonicalCustomerKey(targetName))) continue;
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("targetId", target); item.put("targetName", targetName);
            safe.add(item);
        }
        return safe;
    }

    @Transactional
    public Map<String, Object> confirmSafeBatch() {
        List<Map<String, Object>> items = safeBatchCandidates();
        int confirmed = 0, backfilled = 0;
        for (Map<String, Object> item : items) {
            long id = ((Number) item.get("id")).longValue();
            long targetId = ((Number) item.get("targetId")).longValue();
            Map<String, Object> result = resolve(id, targetId, "严格规范化名称完全一致，安全批量确认");
            confirmed++;
            backfilled += ((Number) result.get("backfilledFacts")).intValue();
        }
        return Map.of("confirmed", confirmed, "backfilledFacts", backfilled);
    }

    private Map<String, Long> preferredCustomersByCanonicalName() {
        Map<String, List<CustomerCandidate>> groups = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
            SELECT c.id,c.customer_name,
                   (SELECT COUNT(*) FROM sales_outbounds s WHERE s.customer_id=c.id) outbound_count
            FROM customers c WHERE c.deleted_at IS NULL
            """)) {
            String name = String.valueOf(row.get("customer_name"));
            String key = canonicalCustomerKey(name);
            if (key == null) continue;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new CustomerCandidate(
                    ((Number) row.get("id")).longValue(), name,
                    ((Number) row.get("outbound_count")).longValue(),
                    name.trim().equals(canonicalCustomerName(name))));
        }
        Map<String, Long> preferred = new HashMap<>();
        for (Map.Entry<String, List<CustomerCandidate>> entry : groups.entrySet()) {
            List<CustomerCandidate> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(CustomerCandidate::clean).reversed()
                            .thenComparing(CustomerCandidate::outboundCount, Comparator.reverseOrder())
                            .thenComparingLong(CustomerCandidate::id))
                    .toList();
            CustomerCandidate first = sorted.getFirst();
            // 同一规范名称出现多份历史客户时，优先使用名称干净、已有出库关系、建立更早的档案。
            // 这里仅选择身份归属，不删除或合并重复客户，避免阻塞历史出库回填。
            if (first.clean()) preferred.put(entry.getKey(), first.id());
        }
        return preferred;
    }

    @Transactional
    public Map<String, Object> resolve(long id, long targetId, String note) {
        Map<String, Object> row = one(id);
        requireTarget(String.valueOf(row.get("entity_type")), targetId);
        var user = users.current();
        jdbc.update("""
            UPDATE source_identity_aliases SET target_id=?,resolution_status='CONFIRMED',
              issue_code=NULL,confidence=1,resolution_note=?,resolved_by_employee_id=?,
              resolved_by_name=?,resolved_at=NOW() WHERE id=?
            """, targetId, note, user.employeeId(), user.name(), id);
        int backfilled = backfillUnresolved(String.valueOf(row.get("entity_type")),
                String.valueOf(row.get("source_key")), String.valueOf(row.get("source_name")), targetId);
        audit.log("SourceIdentity", id, String.valueOf(row.get("source_name")), "RESOLVE",
                "类型=" + row.get("entity_type") + "，目标ID=" + targetId + "，回填事实=" + backfilled);
        return Map.of("id", id, "targetId", targetId, "backfilledFacts", backfilled);
    }

    /** 建设期映射改判预览：只统计来源姓名相同且仍指向当前旧目标的事实，不按姓名全量覆盖。 */
    public Map<String, Object> reassignPreview(long id, long newTargetId) {
        Map<String, Object> row = one(id);
        String type = String.valueOf(row.get("entity_type"));
        if (!"CUSTOMER".equals(type) && !"EMPLOYEE".equals(type)) {
            throw new IllegalArgumentException("当前仅支持客户和员工映射改判");
        }
        if (!"H3YUN".equals(row.get("source_system"))) throw new IllegalArgumentException("仅支持氚云来源身份");
        if (!(row.get("target_id") instanceof Number old)) throw new IllegalArgumentException("该映射尚未指向旧目标");
        requireTarget(type, newTargetId);
        long oldTargetId = old.longValue();
        if (oldTargetId == newTargetId) throw new IllegalArgumentException("新旧目标相同");
        String normalized = row.get("normalized_name") == null ? "" : String.valueOf(row.get("normalized_name"));
        if (blank(normalized)) throw new IllegalArgumentException("来源名称为空，无法限定安全改判范围");
        Long conflictingMappings = jdbc.queryForObject("""
                SELECT COUNT(*) FROM source_identity_aliases
                WHERE source_system='H3YUN' AND entity_type=? AND normalized_name=? AND id<>?
                  AND resolution_status='CONFIRMED' AND target_id IS NOT NULL AND target_id<>?
                """, Long.class, type, normalized, id, oldTargetId);
        if (conflictingMappings != null && conflictingMappings > 0) {
            throw new IllegalStateException("同一来源名称已有其他已确认目标，无法按名称安全批量改判，请先整理冲突映射");
        }
        String targetColumn = "CUSTOMER".equals(type) ? "customer_id" : "employee_id";
        String nameColumn = "CUSTOMER".equals(type) ? "customer_name" : "salesperson_name";
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : List.of("sales_outbounds", "production_orders", "receipts")) {
            String sql = "SELECT COUNT(*) FROM " + table + " WHERE external_source='H3YUN' AND " + targetColumn
                    + "=? AND LOWER(REPLACE(REPLACE(TRIM(" + nameColumn + "),' ',''),'　',''))=?";
            counts.put(table, jdbc.queryForObject(sql, Long.class, oldTargetId, normalized));
        }
        // 已冻结正式提成版本引用的员工归属不能直接改判，需走结算版本修正流程。
        long finalVersions = "EMPLOYEE".equals(type) ? jdbc.queryForObject("""
                SELECT COUNT(DISTINCT v.id) FROM commission_calculation_versions v
                JOIN commission_calculation_source_lines l ON l.version_id=v.id
                JOIN sales_outbounds o ON o.id=l.outbound_id
                WHERE v.status='FINAL' AND o.external_source='H3YUN' AND o.employee_id=?
                  AND LOWER(REPLACE(REPLACE(TRIM(o.salesperson_name),' ',''),'　',''))=?
                """, Long.class, oldTargetId, normalized) : 0;
        return Map.of("id", id, "entityType", type, "sourceName", String.valueOf(row.get("source_name")),
                "oldTargetId", oldTargetId, "newTargetId", newTargetId, "affected", counts,
                "finalCommissionVersions", finalVersions, "allowed", finalVersions == 0);
    }

    /** 校验并原子改判映射及仍指向旧目标的氚云事实；改判后按目标名称再次预览结果。 */
    @Transactional
    public Map<String, Object> reassign(long id, long newTargetId, String note) {
        Map<String, Object> preview = reassignPreview(id, newTargetId);
        if (!Boolean.TRUE.equals(preview.get("allowed"))) {
            throw new IllegalStateException("该员工归属已进入正式提成版本，不能直接改判");
        }
        String type = String.valueOf(preview.get("entityType"));
        long oldTargetId = ((Number) preview.get("oldTargetId")).longValue();
        String normalized = String.valueOf(one(id).get("normalized_name"));
        String targetColumn = "CUSTOMER".equals(type) ? "customer_id" : "employee_id";
        String nameColumn = "CUSTOMER".equals(type) ? "customer_name" : "salesperson_name";
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (String table : List.of("sales_outbounds", "production_orders", "receipts")) {
            String sql = "UPDATE " + table + " SET " + targetColumn + "=? WHERE external_source='H3YUN' AND "
                    + targetColumn + "=? AND LOWER(REPLACE(REPLACE(TRIM(" + nameColumn + "),' ',''),'　',''))=?";
            changed.put(table, jdbc.update(sql, newTargetId, oldTargetId, normalized));
        }
        var user = users.current();
        int identityChanged = jdbc.update("""
                UPDATE source_identity_aliases SET target_id=?,resolution_status='CONFIRMED',
                  issue_code=NULL,confidence=1,resolution_note=?,resolved_by_employee_id=?,
                  resolved_by_name=?,resolved_at=NOW(),updated_at=NOW() WHERE id=? AND target_id=?
                """, newTargetId, blank(note) ? "建设期映射改判" : note.trim(),
                user.employeeId(), user.name(), id, oldTargetId);
        if (identityChanged != 1) throw new IllegalStateException("映射目标在预览后发生变化，请重新预览");
        audit.log("SourceIdentity", id, String.valueOf(preview.get("sourceName")), "REASSIGN",
                "类型=" + type + "，旧目标=" + oldTargetId + "，新目标=" + newTargetId + "，影响=" + changed);
        return Map.of("id", id, "oldTargetId", oldTargetId, "newTargetId", newTargetId, "affected", changed);
    }

    @Transactional
    public Map<String, Object> cleanupCustomer(long id, String cleanedName, String phone, String note) {
        Map<String, Object> row = one(id);
        if (!"CUSTOMER".equals(row.get("entity_type"))) throw new IllegalArgumentException("只有客户身份可以整理档案");
        if (!(row.get("target_id") instanceof Number target)) throw new IllegalArgumentException("当前来源尚未关联客户，请改用“合并到已有客户”");
        String name = cleanedName == null ? "" : cleanedName.trim();
        if (name.length() < 2 || name.length() > 200) throw new IllegalArgumentException("整理后的客户名称应为 2-200 个字符");
        long targetId = target.longValue();
        List<Long> duplicate = jdbc.query("""
            SELECT id FROM customers WHERE deleted_at IS NULL AND id<>?
              AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
            LIMIT 2
            """, (rs, n) -> rs.getLong(1), targetId, normalize(name));
        if (!duplicate.isEmpty()) {
            throw new IllegalArgumentException("已存在同名正式客户，请使用“合并到已有客户”选择该档案");
        }
        String original = String.valueOf(row.get("source_name"));
        jdbc.update("UPDATE customers SET customer_name=?,short_name=?,remark=CONCAT_WS('；',NULLIF(remark,''),?),updated_at=NOW() WHERE id=?",
                name, name.length() > 100 ? name.substring(0, 100) : name,
                "氚云原客户文本：" + original, targetId);
        String cleanedPhone = phone == null ? null : phone.trim();
        if (!blank(cleanedPhone)) {
            Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_contacts
                WHERE customer_id=? AND (mobile=? OR phone_raw=?)
                """, Integer.class, targetId, cleanedPhone, cleanedPhone);
            if (exists == null || exists == 0) {
                jdbc.update("""
                    INSERT INTO customer_contacts(customer_id,name,mobile,phone_raw,contact_type,is_primary,remark,created_at,updated_at)
                    VALUES(?,?,?,?,'其他',
                      CASE WHEN EXISTS(SELECT 1 FROM customer_contacts x WHERE x.customer_id=?) THEN 0 ELSE 1 END,
                      ?,NOW(),NOW())
                    """, targetId, name, phoneCell(cleanedPhone), cleanedPhone, targetId,
                        "从氚云客户混合文本拆分；原文=" + original);
            }
        }
        var user = users.current();
        jdbc.update("""
            UPDATE source_identity_aliases SET resolution_status='CONFIRMED',issue_code=NULL,
              confidence=1,resolution_note=?,resolved_by_employee_id=?,resolved_by_name=?,
              resolved_at=NOW() WHERE id=?
            """, blank(note) ? "已整理客户名称和联系方式" : note.trim(),
                user.employeeId(), user.name(), id);
        int backfilled = backfillUnresolved("CUSTOMER", null, original, targetId);
        audit.log("Customer", targetId, name, "CLEANUP_H3_IDENTITY",
                "原文=" + original + "，电话=" + cleanedPhone + "，回填事实=" + backfilled);
        return Map.of("id", id, "targetId", targetId, "customerName", name,
                "backfilledFacts", backfilled);
    }

    @Transactional
    public void ignore(long id, String note) {
        Map<String, Object> row = one(id);
        var user = users.current();
        jdbc.update("""
            UPDATE source_identity_aliases SET target_id=NULL,resolution_status='IGNORED',
              resolution_note=?,resolved_by_employee_id=?,resolved_by_name=?,resolved_at=NOW() WHERE id=?
            """, note, user.employeeId(), user.name(), id);
        audit.log("SourceIdentity", id, String.valueOf(row.get("source_name")), "IGNORE", note);
    }

    private Map<String, Object> counts() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT resolution_status status,COUNT(*) count FROM source_identity_aliases GROUP BY resolution_status")) {
            out.put(String.valueOf(r.get("status")), r.get("count"));
        }
        return out;
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM source_identity_aliases WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("身份映射不存在：" + id);
        return rows.getFirst();
    }

    private void requireTarget(String type, long id) {
        String table = switch (type) {
            case "CUSTOMER" -> "customers";
            case "EMPLOYEE" -> "employees";
            case "PRODUCT" -> "sales_products";
            case "WAREHOUSE" -> "warehouses";
            default -> throw new IllegalArgumentException("未知身份类型：" + type);
        };
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id);
        if (count == null || count == 0) throw new IllegalArgumentException("目标档案不存在：" + id);
    }

    private int backfillUnresolved(String type, String sourceKey, String sourceName, long targetId) {
        if (blank(sourceName)) return 0;
        String normalized = normalize(sourceName);
        return switch (type) {
            case "CUSTOMER" ->
                    jdbc.update("""
                        UPDATE sales_outbounds SET customer_id=?
                        WHERE customer_id IS NULL
                          AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                        """, targetId, normalized)
                    + jdbc.update("""
                        UPDATE production_orders SET customer_id=?
                        WHERE customer_id IS NULL
                          AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                        """, targetId, normalized)
                    + jdbc.update("""
                        UPDATE receipts SET customer_id=?
                        WHERE customer_id IS NULL
                          AND LOWER(REPLACE(REPLACE(TRIM(customer_name),' ',''),'　',''))=?
                        """, targetId, normalized);
            case "EMPLOYEE" ->
                    jdbc.update("""
                        UPDATE sales_outbounds SET employee_id=?
                        WHERE employee_id IS NULL
                          AND LOWER(REPLACE(REPLACE(TRIM(salesperson_name),' ',''),'　',''))=?
                        """, targetId, normalized)
                    + jdbc.update("""
                        UPDATE production_orders SET employee_id=?
                        WHERE employee_id IS NULL
                          AND LOWER(REPLACE(REPLACE(TRIM(salesperson_name),' ',''),'　',''))=?
                        """, targetId, normalized)
                    + jdbc.update("""
                        UPDATE receipts SET employee_id=?
                        WHERE employee_id IS NULL
                          AND LOWER(REPLACE(REPLACE(TRIM(salesperson_name),' ',''),'　',''))=?
                        """, targetId, normalized);
            // 来源键表达式与工作台、Java 侧同源（ProductSourceKey.sqlExpr），此处不得再写第二份 SQL 副本。
            case "PRODUCT" -> jdbc.update("UPDATE sales_outbound_items SET sales_product_id=?"
                    + " WHERE sales_product_id IS NULL AND line_type='PRODUCT'"
                    + " AND (" + ProductSourceKey.sqlExpr("") + ")=?",
                    targetId, sourceKey);
            default -> 0;
        };
    }

    private void saveDecision(String type, String sourceKey, String sourceName, Long targetId,
                              String status, String issue, String note, Double confidence) {
        String key = key(sourceKey, sourceName);
        jdbc.update("""
            INSERT INTO source_identity_aliases
              (source_system,entity_type,source_key,source_name,normalized_name,target_id,
               resolution_status,issue_code,confidence,resolution_note)
            VALUES('H3YUN',?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE source_name=VALUES(source_name),normalized_name=VALUES(normalized_name),
              target_id=IF(resolution_status='CONFIRMED',target_id,VALUES(target_id)),
              resolution_status=IF(resolution_status='CONFIRMED',resolution_status,VALUES(resolution_status)),
              issue_code=IF(resolution_status='CONFIRMED',issue_code,VALUES(issue_code)),
              confidence=IF(resolution_status='CONFIRMED',confidence,VALUES(confidence)),
              resolution_note=IF(resolution_status='CONFIRMED',resolution_note,VALUES(resolution_note)),
              occurrence_count=occurrence_count+1,last_seen_at=NOW()
            """.replace("last_seen_at=NOW()", "updated_at=NOW()"),
                type, key, sourceName, normalize(sourceName), targetId, status, issue, confidence, note);
    }

    private Long createPendingCustomer(String name) {
        return createPendingCustomer(name, null);
    }

    /**
     * 建档即带省市区（治本）：调用方持有地址时，在 INSERT 阶段一并写入 address 与解析出的省/市/区县，
     * 不再依赖事后全量回填服务。解析委托 {@link ChinaAddressParser#parseLoose}（区划字典文本扫描兜底），
     * 解析不出的层级留空，不猜测。
     */
    private Long createPendingCustomer(String name, String address) {
        String addr = blank(address) ? null : address.trim();
        ChinaAddressParser.ParsedAddress region = addr == null ? null : ChinaAddressParser.parseLoose(addr);
        for (int attempt = 0; ; attempt++) {
            String code = nextCustomerCode();
            KeyHolder holder = new GeneratedKeyHolder();
            try {
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO customers(customer_code,customer_name,short_name,customer_type,source,
                          province,city,district,address,
                          relation_status,status,remark,created_at,updated_at)
                        VALUES(?,?,?,'OTHER','H3YUN_SALES_OUTBOUND',?,?,?,?, 'NORMAL','ACTIVE','由氚云销售出库自动建档，身份待复核',NOW(),NOW())
                        """, Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, code); ps.setString(2, name);
                    ps.setString(3, name.length() > 100 ? name.substring(0, 100) : name);
                    ps.setString(4, region == null ? null : region.province());
                    ps.setString(5, region == null ? null : region.city());
                    ps.setString(6, region == null ? null : region.district());
                    ps.setString(7, addr);
                    return ps;
                }, holder);
            } catch (DuplicateKeyException occupied) {
                // 其他入口（手工建档等）已占用该编号：重新对齐库内最大值后重试。
                if (attempt >= 4) throw occupied;
                resyncCustomerCodeSeq();
                continue;
            }
            return Objects.requireNonNull(holder.getKey()).longValue();
        }
    }

    /** 客户编号序号缓存：批量建档（可达数千个客户）时不再逐条扫描全表最大值。 */
    private long nextCustomerCodeSeq = -1L;

    private synchronized String nextCustomerCode() {
        if (nextCustomerCodeSeq < 0) resyncCustomerCodeSeq();
        return "CUS" + String.format("%06d", ++nextCustomerCodeSeq);
    }

    private synchronized void resyncCustomerCodeSeq() {
        Long maxSeq = jdbc.queryForObject("""
            SELECT MAX(CAST(SUBSTRING(customer_code FROM 4) AS UNSIGNED))
            FROM customers WHERE customer_code LIKE 'CUS%'
            """, Long.class);
        nextCustomerCodeSeq = maxSeq == null ? 0L : maxSeq;
    }

    private static String key(String sourceKey, String sourceName) {
        return !blank(sourceKey) ? sourceKey.trim() : normalize(sourceName);
    }
    /**
     * 产品来源键的三处口径已收敛到 {@link ProductSourceKey}（唯一实现），此处不再保留副本。
     * 历史上这里有一份独立实现，与 SQL 表达式在「全角空格编码」「首尾制表符/换行」上语义不同，
     * 会导致人工归类写进去、重导读不回来。改动务必同步 util，并由 {@code ProductSourceKeyContractTest} 把关。
     */
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    /**
     * tinyint(1) 列（如 customer_split_runs.created_customer）经 Connector/J 默认映射为 Boolean，
     * 直接强转 Number 会抛 ClassCastException。这里统一兼容 Boolean / Number / 字符串三种返回。
     */
    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) return number.intValue() != 0;
        return "1".equals(Objects.toString(value, ""));
    }
    private static String normalize(String value) {
        return blank(value) ? null : value.trim().replace(" ", "").replace("　", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 身份键里的「来源名称归一」。
     * 必须与库里的比较语义对齐：customer_source_cluster_mappings.normalized_source_name 的排序规则是
     * utf8mb4_0900_ai_ci，会把全角/半角标点（「王小姐，」与「王小姐,」）视作同一个值，而 Java 的精确串比较不会。
     * 早期只做「去空格 + 小写」，于是同一物理簇的两种书写变体被 Java 当成两个键：判定说「待处理」，
     * 落库却用 ON DUPLICATE KEY 命中另一变体的映射行并改写其归属，源客户于是反复出现同一个簇，
     * 每处理一次就多建一个客户。这里统一做 NFKC 折叠，让判定与落库用的是同一个键。
     */
    private static String identityName(String value) {
        if (blank(value)) return null;
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return folded.replace(" ", "").replace("　", "").trim().toLowerCase(Locale.ROOT);
    }
    private static String normalizeLoose(String value) {
        if (blank(value)) return null;
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s,，。.;；、·\\-_/\\\\()（）【】\\[\\]{}]+", "");
    }
    private static String canonicalCustomerName(String value) {
        if (blank(value)) return null;
        String name = value.replace("\u200B", "").replace("\uFEFF", "").trim();
        name = name.replaceAll("[\s,，。.;；、]+$", "").trim();
        name = name.replaceAll("[【\\[]\\s*\\d{3,8}\\s*[】\\]]$", "").trim();
        return name.replaceAll("[\s,，。.;；、]+$", "").trim();
    }
    private static String canonicalCustomerAddress(String value) {
        if (blank(value)) return null;
        String address = value.replace("\u200B", "").replace("\uFEFF", "").trim();
        address = address.replaceAll("[【\\[]\\s*\\d{3,8}\\s*[】\\]]$", "").trim();
        return address.replaceAll("[\s,，。.;；、]+$", "").trim();
    }
    private static String canonicalCustomerKey(String value) {
        return normalizeLoose(canonicalCustomerName(value));
    }
    private static String customerCore(String normalized) {
        if (normalized == null) return null;
        return normalized.replaceAll("(有限责任公司|股份有限公司|有限公司|合作社|经营部|工作室|公司)$", "");
    }
    private static CustomerCleanup suggestCustomerCleanup(String original) {
        if (blank(original)) return new CustomerCleanup(null, List.of(), false);
        Matcher clear = CLEAR_TRAILING_PHONE.matcher(original);
        if (!clear.matches()) clear = CLEAR_ATTACHED_LATIN_PHONE.matcher(original);
        if (clear.matches()) {
            String name = clear.group(1).trim().replaceAll("[，,。;；、]+$", "").trim();
            String phone = clear.group(2).trim();
            boolean safe = safeCustomerName(name) && !containsComplexCustomerText(name);
            return new CustomerCleanup(name, List.of(phone), safe);
        }
        List<String> phones = new ArrayList<>();
        Matcher matcher = CONTACT_NUMBER.matcher(original);
        StringBuffer remainder = new StringBuffer();
        while (matcher.find()) {
            String number = matcher.group().trim();
            if (number.replaceAll("\\D", "").length() >= 7) phones.add(number);
            matcher.appendReplacement(remainder, " ");
        }
        matcher.appendTail(remainder);
        String name = remainder.toString()
                .replaceAll("[，,。;；、]+$", "")
                .replaceAll("^[，,。;；、]+", "")
                .replaceAll("\\s+", " ").trim();
        boolean safe = false;
        return new CustomerCleanup(name.isEmpty() ? null : name, phones.stream().distinct().toList(), safe);
    }
    private static boolean containsComplexCustomerText(String name) {
        return name.contains("联系电话") || name.contains("转") || name.contains("会员")
                || name.contains("會員") || name.contains("物流+") || name.contains("收")
                || name.contains("[") || name.contains("_");
    }
    private static boolean safeCustomerName(String name) {
        if (name == null || name.length() < 2) return false;
        if (name.matches("[A-Za-z]+") && name.length() < 4) return false;
        return !name.matches("[A-Z]{2,4}");
    }
    private static boolean safeNewCustomerName(String name) {
        if (name == null || name.length() < 2 || name.length() > 100) return false;
        if (CONTACT_NUMBER.matcher(name).find() || containsComplexCustomerText(name)) return false;
        if (name.matches("[\\p{P}\\p{S}\\d\\s_]+") || name.matches("[A-Z]{2,4}")) return false;
        return !name.matches("[a-z]{1,3}");
    }
    private boolean isSharedCustomerPhone(String phone) {
        if (blank(phone)) return false;
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM source_identity_aliases
            WHERE entity_type='CUSTOMER' AND source_name LIKE CONCAT('%',?,'%')
            """, Integer.class, phone);
        return count != null && count >= 3;
    }
    private static String recommendedAction(Map<String, Object> item, CustomerCleanup cleanup) {
        if (cleanup.safe() && item.get("targetId") != null) return "CLEAN_CURRENT";
        if (item.get("targetId") != null) return "REVIEW_CURRENT";
        return "MERGE_OR_CREATE";
    }
    private static String phoneCell(String phone) {
        return phone != null && phone.length() <= 30 ? phone : null;
    }
    private record CustomerCleanup(String name, List<String> phones, boolean safe) {}
    private record CustomerCandidate(long id, String name, Long outboundCount, boolean clean) {}
    private record NewCustomerCandidate(String customerName, List<Map<String, Object>> rows, long occurrenceCount) {}
    private record CustomerMergeMember(long id, String name, long outboundCount, long immutableCount) {}
    private record CustomerMergeGroup(String cleanName, String cleanAddress, List<CustomerMergeMember> members) {}
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
