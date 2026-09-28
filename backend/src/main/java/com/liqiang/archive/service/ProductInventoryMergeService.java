package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.util.ProductFeeFilter;
import com.liqiang.archive.util.UnitNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 氚云产品信息 + 仓库明细台账「合并入库」服务。
 *
 * <p>两阶段流程：
 * <ol>
 *   <li>产品信息 / 仓库明细各自经 {@link ProductInventoryLedgerImportService} 批次化导入台账（新增/更新，不覆盖）</li>
 *   <li>从台账合并 → 过滤费用项 → 预览（相对当前库存余额逐行判定 新增/更新/忽略，带旧值→新值）→ 确认入库</li>
 * </ol>
 *
 * <p>零/负库存语义（勿回退）：数量=0 的行不跳过——已有余额行则生成「清零」UPDATE 目标，
 * 无余额行才忽略；数量&lt;0 或缺失的行进 REVIEW（NEGATIVE_QUANTITY / QUANTITY_MISSING）由人工裁决。
 * 确认时数量/价格以服务端按台账重算为准，请求体只承担行选择职责。
 *
 * <p>反向对账（勿回退）：以最新台账为全量快照，余额&gt;0 但快照中已无此行（上游整行移除）
 * 的余额行会生成 issue=VANISHED_FROM_SNAPSHOT 的 READY 清零目标，确认时按余额行主键直接归零。
 * 确认时数量/价格以服务端按台账重算为准，请求体只承担行选择职责。
 *
 * <p>确认按「目标值」语义写入：把库存余额修正为台账值（新行插入、差异行更新、一致行忽略），
 * 重复确认不会重复累加数量（幂等）。
 */
@Service
@RequiredArgsConstructor
public class ProductInventoryMergeService {

    private final JdbcTemplate jdbc;
    private final SourceIdentityService sourceIdentityService;
    private final CurrentUserService users;
    private final ObjectMapper objectMapper;

    // 费用名单与单位归一统一到 util（口径单一来源，勿在本类另写一份）：
    //   费用 → ProductFeeFilter（含「类目命中」与「编号兜底」两条判定）
    //   单位 → UnitNormalizer（去空白/尾随噪声 + 别名，同物不同写的单位必须归一）

    // ===== 台账查询 =====

    /**
     * 台账行通用「导入与系统信息」列（产品信息 / 仓库明细同构，2026-09-16 补）。
     *
     * <p>氚云全字段导出在业务列之后携带系统列（数据ID / 数据标题 / 创建人 / 拥有者 /
     * 所属部门 / 创建时间 / 修改时间 / 数据状态），详情页据此回答「这行数据从氚云哪条记录来、
     * 什么时候建的、最后一次改是什么时候」。source_* 为氚云侧观测值，只读回显。
     */
    private static final String LEDGER_SYSTEM_COLUMNS =
            " source_object_id AS sourceObjectId, source_title AS sourceTitle, owner_name AS ownerName," +
            " department_name AS departmentName, created_by_name AS createdByName, data_status AS dataStatus," +
            " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
            " source_hash AS sourceHash, last_synced_at AS lastSyncedAt," +
            " created_at AS createdAt, updated_at AS updatedAt";

    public Map<String, Object> productCatalogLedger(int page, int size, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (product_code LIKE ? OR product_name LIKE ? OR product_category LIKE ?)");
            String kw = "%" + keyword + "%";
            params.addAll(List.of(kw, kw, kw));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_product_catalog" + where, Long.class, params.toArray());
        int offset = (page - 1) * size;
        params.addAll(List.of(size, offset));
        var items = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, product_category AS category, product_code AS productCode," +
                        " product_name AS productName, specification, unit, purchase_price AS purchasePrice," +
                        LEDGER_SYSTEM_COLUMNS +
                        " FROM h3_product_catalog" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> warehouseStockLedger(int page, int size, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (product_code LIKE ? OR product_name LIKE ? OR warehouse LIKE ? OR product_category LIKE ?)");
            String kw = "%" + keyword + "%";
            params.addAll(List.of(kw, kw, kw, kw));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_warehouse_stock" + where, Long.class, params.toArray());
        int offset = (page - 1) * size;
        params.addAll(List.of(size, offset));
        var items = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, warehouse, product_category AS category," +
                        " product_code AS productCode, product_name AS productName, specification, unit," +
                        " quantity, purchase_price AS purchasePrice," + LEDGER_SYSTEM_COLUMNS +
                        " FROM h3_warehouse_stock" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    // ===== 台账单行详情（与列表同构字段 + 全字段，供 H3LedgerPanel 详情抽屉） =====

    public Map<String, Object> productCatalogDetail(Long id) {
        var rows = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, product_category AS category, product_code AS productCode," +
                        " product_name AS productName, specification, unit, purchase_price AS purchasePrice," +
                        LEDGER_SYSTEM_COLUMNS + " FROM h3_product_catalog WHERE id=?",
                id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("产品台账记录不存在（id=" + id + "）");
        return Map.of("kind", "product", "row", rows.getFirst());
    }

    public Map<String, Object> warehouseStockDetail(Long id) {
        var rows = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, warehouse, product_category AS category," +
                        " product_code AS productCode, product_name AS productName, specification, unit," +
                        " quantity, purchase_price AS purchasePrice," + LEDGER_SYSTEM_COLUMNS +
                        " FROM h3_warehouse_stock WHERE id=?",
                id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("仓库明细台账记录不存在（id=" + id + "）");
        return Map.of("kind", "stock", "row", rows.getFirst());
    }

    // ===== 经销商信息台账（只读；导入由 ProductInventoryLedgerImportService 负责） =====

    public Map<String, Object> dealerLedger(int page, int size, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (dealer_code LIKE ? OR dealer_name LIKE ? OR contact LIKE ?"
                    + " OR owner_salesperson LIKE ? OR address LIKE ?)");
            String kw = "%" + keyword + "%";
            params.addAll(List.of(kw, kw, kw, kw, kw));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_dealer_catalog" + where, Long.class, params.toArray());
        int offset = (page - 1) * size;
        params.addAll(List.of(size, offset));
        var items = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, dealer_code AS dealerCode, dealer_name AS dealerName," +
                        " contact, address, remark, owner_salesperson AS ownerSalesperson," +
                        " data_status AS dataStatus, source_object_id AS sourceObjectId," +
                        " source_title AS sourceTitle, owner_name AS ownerName," +
                        " department_name AS departmentName, created_by_name AS createdByName," +
                        " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
                        " source_hash AS sourceHash, last_synced_at AS lastSyncedAt," +
                        " created_at AS createdAt, updated_at AS updatedAt" +
                        " FROM h3_dealer_catalog" + where + " ORDER BY dealer_code DESC LIMIT ? OFFSET ?",
                params.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }

    public Map<String, Object> dealerDetail(Long id) {
        var rows = jdbc.queryForList(
                "SELECT id, import_batch_id AS batchId, dealer_code AS dealerCode, dealer_name AS dealerName," +
                        " contact, address, remark, owner_salesperson AS ownerSalesperson," +
                        " data_status AS dataStatus, source_object_id AS sourceObjectId," +
                        " source_title AS sourceTitle, owner_name AS ownerName," +
                        " department_name AS departmentName, created_by_name AS createdByName," +
                        " source_created_at AS sourceCreatedAt, source_modified_at AS sourceModifiedAt," +
                        " source_hash AS sourceHash, last_synced_at AS lastSyncedAt," +
                        " created_at AS createdAt, updated_at AS updatedAt" +
                        " FROM h3_dealer_catalog WHERE id=?",
                id);
        if (rows.isEmpty()) throw new java.util.NoSuchElementException("经销商台账记录不存在（id=" + id + "）");
        return Map.of("kind", "dealer", "row", rows.getFirst());
    }

    // ===== 合并预览（从台账读取，与当前库存余额 diff） =====

    public MergePreview preview() {
        // 产品信息台账
        var productMap = new HashMap<String, ProductInfo>();
        jdbc.query("SELECT product_category,product_code,product_name,specification,unit,purchase_price FROM h3_product_catalog",
                (rs, rowNum) -> new ProductInfo(
                        rs.getString("product_code"), rs.getString("product_category"),
                        rs.getString("product_name"), rs.getString("specification"),
                        rs.getString("unit"), rs.getBigDecimal("purchase_price")))
                .forEach(pi -> productMap.put(pi.productCode(), pi));

        // 仓库明细台账
        var stockRows = new ArrayList<StockRow>();
        jdbc.query("SELECT warehouse,product_category,product_code,product_name,specification,unit,quantity,purchase_price FROM h3_warehouse_stock",
                (rs, rowNum) -> new StockRow(
                        rs.getString("warehouse"), rs.getString("product_category"), rs.getString("product_code"),
                        rs.getString("product_name"), rs.getString("specification"), rs.getString("unit"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("purchase_price")))
                .forEach(stockRows::add);

        return doMerge(productMap, stockRows);
    }

    /** 代发仓：{@code DROP_SHIP} 不持有实物库存，期初不进账（决策 {@code #38}⑥）。 */
    public static final String DROP_SHIP_WAREHOUSE = "代发仓";

    /**
     * 开账干跑（决策 {@code #38}）：产出「切换日 T 开账会发生什么」的对账清单。<b>只读，不写任何表。</b>
     *
     * <p><b>为什么复用 {@link #preview()} 而不是另写一套</b>：预览里包含了「费用项过滤 → 数量异常入
     * REVIEW → 身份解析降级链 → 按 (仓库,解析后产品) 聚合 → 新增/更新/忽略判定」的全部分支，重写一份
     * 必然与它漂移（铁律：同一业务口径禁两套实现）。故此处只在其结果上套 #38 的口径。
     *
     * <p><b>#38 与现状 preview 的口径差异（干跑必须报出来，否则 T 日会少开账）</b>：
     * <ol>
     *   <li><b>代发仓不进账</b>（#38⑥）：preview 不区分仓库，此处后置剔除；</li>
     *   <li><b>缺价与编号对不上：金额置 null、行仍进账</b>（#38⑤）：preview 会判
     *       {@code PRICE_MISSING}/{@code PRODUCT_NOT_FOUND} 为 REVIEW 拦下 —— 与 #38 相反。
     *       此处把它们单列为 {@code policyGaps}（<b>不擅自改 confirm 行为</b>），供 T 日前决断。</li>
     * </ol>
     *
     * <p><b>务必注意</b>：{@code policyGaps} 里的行按 #38 是「进账的」，但当前 {@code confirm}
     * 会跳过它们。若 T 日直接执行 confirm 而不先处理，期初将<b>静默少开这批行</b>。
     *
     * @return {@code policy}（口径声明）/ {@code summary}（计数与金额）/{@code opening}（进账明细）/
     *         {@code policyGaps}（口径缺口，全量）/ {@code blocked}（真异常）/ {@code excluded}（不进账）
     */
    public Map<String, Object> openingPreflight() {
        MergePreview preview = preview();

        List<Map<String, Object>> opening = new ArrayList<>();
        List<Map<String, Object>> policyGaps = new ArrayList<>();
        List<Map<String, Object>> blocked = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        Set<String> openingProducts = new LinkedHashSet<>();
        Set<String> gapProducts = new LinkedHashSet<>();
        BigDecimal openingAmount = BigDecimal.ZERO;
        int dropShipRows = 0, dropShipPositiveRows = 0, notPositiveRows = 0, nullIdentityRows = 0;

        for (MergedItem it : preview.items()) {
            Map<String, Object> row = openingRowOf(it);

            // #38④ 名称 / 编号 为 NULL：费用判据（含 ProductFeeFilter 的编号兜底）覆盖不到 →
            //   挂异常清单，不进账。三值逻辑会让这类行从一切「= / <> 比较」里静默漏网
            //   （实测进账集外仍有 1 行有量的 NULL 行：编号 295QZDQTJ、缺名称）。
            if (it.productName() == null || trim(it.productName()).isEmpty()
                    || it.productCode() == null || trim(it.productCode()).isEmpty()) {
                row.put("disposition", "EXCLUDED_NULL_IDENTITY");
                excluded.add(row);
                nullIdentityRows++;
                continue;
            }

            // #38⑥ 代发仓：DROP_SHIP 不持有库存 → 不进账
            if (DROP_SHIP_WAREHOUSE.equals(trim(it.warehouse()))) {
                row.put("disposition", "EXCLUDED_DROP_SHIP");
                excluded.add(row);
                dropShipRows++;
                if (it.quantity() != null && it.quantity().signum() > 0) dropShipPositiveRows++;
                continue;
            }
            // #38③ 只开有数的行：零 / 负 / 数量缺失一律不进账
            if (it.quantity() == null || it.quantity().signum() <= 0) {
                row.put("disposition", "EXCLUDED_NOT_POSITIVE");
                excluded.add(row);
                notPositiveRows++;
                continue;
            }
            // #38⑤ 缺价 / 编号对不上产品目录：金额置 null、行仍进账
            if ("PRICE_MISSING".equals(it.issue()) || "PRODUCT_NOT_FOUND".equals(it.issue())) {
                row.put("disposition", "POLICY_GAP_AMOUNT_NULL");
                row.put("stockAmount", null);
                policyGaps.add(row);
                gapProducts.add(trim(it.productCode()));
                continue;
            }
            // 其余 REVIEW：真异常（身份歧义 / 仓库缺失等），T 日也不该进账
            if (!"READY".equals(it.status())) {
                row.put("disposition", "BLOCKED_" + (it.issue() == null ? "UNKNOWN" : it.issue()));
                blocked.add(row);
                continue;
            }
            // 按 #38 会进账
            row.put("disposition", "OPENING");
            opening.add(row);
            openingProducts.add(trim(it.productCode()));
            if (it.stockAmount() != null) openingAmount = openingAmount.add(it.stockAmount());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("openingRows", opening.size());
        summary.put("openingProducts", openingProducts.size());
        summary.put("openingAmount", openingAmount.setScale(2, RoundingMode.HALF_UP));
        summary.put("policyGapRows", policyGaps.size());
        summary.put("policyGapProducts", gapProducts.size());
        summary.put("blockedRows", blocked.size());
        summary.put("excludedRows", excluded.size());
        summary.put("excludedDropShip", dropShipRows);
        summary.put("excludedDropShipPositive", dropShipPositiveRows);
        summary.put("excludedNotPositive", notPositiveRows);
        summary.put("excludedNullIdentity", nullIdentityRows);
        summary.put("previewTotalRows", preview.totalCount());
        summary.put("previewReadyRows", preview.readyCount());
        summary.put("previewReviewRows", preview.reviewCount());
        summary.put("previewSkippedFee", preview.skippedFee());
        summary.put("previewSkippedZero", preview.skippedZero());
        summary.put("snapshotTime", preview.snapshotTime());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", List.of(
                "只开数量 > 0 的行（决策 #38③）",
                "代发仓不进账（决策 #38⑥）",
                "费用/非产品项按 ProductFeeFilter 剔除（唯一实现）",
                "缺价与编号对不上：金额置 null、行仍进账（决策 #38⑤）"));
        result.put("summary", summary);
        result.put("opening", opening);
        result.put("policyGaps", policyGaps);
        result.put("blocked", blocked);
        result.put("excluded", excluded);
        return result;
    }

    /** 干跑行：只暴露对账需要的列，避免把内部聚合细节泄进接口。 */
    private static Map<String, Object> openingRowOf(MergedItem it) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("warehouse", it.warehouse());
        row.put("productCode", it.productCode());
        row.put("productName", it.productName());
        row.put("specification", it.specification());
        row.put("unit", it.unit());
        row.put("quantity", it.quantity());
        row.put("purchasePrice", it.purchasePrice());
        row.put("stockAmount", it.stockAmount());
        row.put("status", it.status());
        row.put("issue", it.issue());
        row.put("sourceRows", it.sourceRows());
        return row;
    }

    private MergePreview doMerge(Map<String, ProductInfo> productMap, List<StockRow> stockRows) {
        MergeContext ctx = loadMergeContext();
        Map<String, List<BalanceRow>> balanceIndex = loadBalanceIndex();
        List<MergedItem> items = new ArrayList<>();
        int skippedZero = 0, skippedFee = 0, skippedNegative = 0, unmatched = 0;
        int createCount = 0, updateCount = 0, unchangedCount = 0;

        // 聚合单元（2026-09-17 幂等修复，勿回退为「逐台账行写同一余额行」）：
        //   台账业务键含编号且只增不删，同一 (解析后产品, 仓库) 常常存在多行——上游换编号后
        //   老编号的 0 量残影行会长期留存。若逐行生成目标值，多行会轮流覆盖同一余额行，
        //   于是每次导入都报几十条 UPDATE、永远收敛不了。
        //   因此先按 (仓库, 解析产品) 聚合成一个合并单元，一个键只产生一个目标值：
        //     · 组内 quantity > 0 的行：求和作为目标数量（多行都有量 = 同物多码，求和）
        //     · 组内 quantity = 0 的残影行：只记录不写入（不得覆盖有量行）
        LinkedHashMap<String, MergeGroup> groups = new LinkedHashMap<>();

        for (var sr : stockRows) {
            // 费用/非产品项过滤：类目命中或编号兜底（口径统一在 ProductFeeFilter，勿在此另写名单）
            if (ProductFeeFilter.isFee(sr.category, sr.productCode)) { skippedFee++; continue; }

            var e = enrich(productMap, sr);

            // 数量缺失/负数：不静默丢弃，进 REVIEW 让人工裁决（盘亏负数、坏行都不该无声消失）；不参与聚合
            if (sr.quantity == null || sr.quantity.signum() < 0) {
                items.add(reviewItem(sr, e, sr.quantity == null ? "QUANTITY_MISSING" : "NEGATIVE_QUANTITY"));
                if (sr.quantity == null) skippedZero++; else skippedNegative++;
                continue;
            }

            // 以下情形一律逐行进 REVIEW、不并入聚合单元：并入会让「求和」静默漏算它们的数量
            if (!e.matched()) {
                items.add(reviewItem(sr, e, "PRODUCT_NOT_FOUND"));
                unmatched++;
                continue;
            }
            if (!ctx.warehouseIdByName.containsKey(sr.warehouse)) {
                items.add(reviewItem(sr, e, "WAREHOUSE_MISSING"));
                continue;
            }

            // 身份解析降级链：①编号一致 → ②编码漂移降级 → ③名称+规格+单位唯一 →
            // ④名称命中且规格/单位不冲突 → ⑤多候选只入 REVIEW（口径与 OutboundRelationGovernanceService 一致）
            ProductResolution pr = resolveProduct(sr.productCode, e.productName(), e.specification(), e.unit(), ctx);
            if (pr.ambiguous()) {
                items.add(reviewItem(sr, e, "PRODUCT_AMBIGUOUS"));
                continue;
            }

            groups.computeIfAbsent(groupKey(sr.warehouse, e, pr), k -> new MergeGroup(sr.warehouse, pr.productId()))
                    .add(sr, e);
        }

        // ===== 逐合并单元判定 新增/更新/忽略（仅 READY 单元参与确认） =====
        for (var g : groups.values()) {
            List<GroupRow> groupRows = g.rows;
            List<GroupRow> positives = groupRows.stream()
                    .filter(r -> r.sr().quantity().signum() > 0)
                    .sorted(Comparator.comparing((GroupRow r) -> r.sr().quantity().abs()).reversed()
                            .thenComparing(r -> trim(r.sr().productCode())))
                    .toList();
            List<GroupRow> zeros = groupRows.stream().filter(r -> r.sr().quantity().signum() == 0).toList();

            String sourceRows = groupRows.stream()
                    .map(r -> trim(r.sr().productCode()) + ":" + plainNum(r.sr().quantity()))
                    .collect(java.util.stream.Collectors.joining(" | "));
            // 被忽略的 0 量残影行：不写入，但保留来源可见性（避免「台账里有、余额里没有」的莫名消失）
            String ignoredRows = zeros.isEmpty() ? null : zeros.stream()
                    .map(r -> trim(r.sr().productCode()) + ":0")
                    .collect(java.util.stream.Collectors.joining(" | "));

            GroupRow rep = !positives.isEmpty() ? positives.getFirst()
                    : (!zeros.isEmpty() ? zeros.getFirst() : groupRows.getFirst());
            Enriched e = rep.e();
            String unit = e.unit();
            String repCode = trim(rep.sr().productCode());
            List<BalanceRow> balances = (g.productId == null)
                    ? List.of()
                    : balanceIndex.getOrDefault(g.productId + "|" + ctx.warehouseIdByName.get(g.warehouse), List.of());

            // (a) 组内全是 0 量残影行：上游已清零 → 只有已存在余额行才有同步意义（把余额修成 0）
            if (positives.isEmpty()) {
                if (balances.isEmpty()) { skippedZero++; continue; }
                var bal = matchUnitRowBalance(balances, unit);
                BigDecimal prevQuantity = bal.quantity();
                BigDecimal prevPurchasePrice = bal.purchasePrice();
                BigDecimal targetPrice = e.purchasePrice() != null ? e.purchasePrice() : prevPurchasePrice;
                boolean alreadyZero = bigEq(prevQuantity, BigDecimal.ZERO);
                String action = alreadyZero ? "UNCHANGED" : "UPDATE";
                if (alreadyZero) unchangedCount++; else updateCount++;
                items.add(new MergedItem(g.warehouse, e.category(), repCode,
                        e.matched() ? repCode : null, e.productName(), e.specification(), unit,
                        BigDecimal.ZERO, targetPrice, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        "READY", null, e.customized(), rep.sr().specification(), rep.sr().unit(),
                        action, prevQuantity, prevPurchasePrice, null, sourceRows, ignoredRows));
                continue;
            }

            // (b) 目标数量 = 组内有量行求和；目标价格取「数量最大来源行」的价格（与行序无关的确定性口径）
            BigDecimal targetQuantity = positives.stream()
                    .map(r -> r.sr().quantity())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal price = null;
            for (var r : positives) {
                if (r.e().purchasePrice() != null) { price = r.e().purchasePrice(); break; }
            }
            BigDecimal stockAmount = price != null
                    ? targetQuantity.multiply(price).setScale(4, RoundingMode.HALF_UP) : null;

            String status = "READY";
            String issue = null;
            // 清零行不要求价格齐全（把余额归零不需要新价格），有量行必须有正价格
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) { status = "REVIEW"; issue = "PRICE_MISSING"; }

            String action = null;
            BigDecimal prevQuantity = null;
            BigDecimal prevPurchasePrice = null;
            if ("READY".equals(status)) {
                if (balances.isEmpty()) {
                    action = "CREATE"; createCount++;
                } else {
                    var bal = matchUnitRowBalance(balances, unit);
                    prevQuantity = bal.quantity();
                    prevPurchasePrice = bal.purchasePrice();
                    if (bigEq(prevQuantity, targetQuantity) && bigEq(prevPurchasePrice, price)) {
                        action = "UNCHANGED"; unchangedCount++;
                    } else {
                        action = "UPDATE"; updateCount++;
                    }
                }
            }

            items.add(new MergedItem(g.warehouse, e.category(), repCode,
                    e.matched() ? repCode : null, e.productName(), e.specification(), unit,
                    targetQuantity, price, stockAmount, status, issue, e.customized(),
                    rep.sr().specification(), rep.sr().unit(),
                    action, prevQuantity, prevPurchasePrice, null, sourceRows, ignoredRows));
        }


        // ===== 反向对账：余额>0 但最新台账快照已无此行（上游整行移除卖完）→ 生成清零目标 =====
        int vanishedCount = appendVanishedReconciliation(items, stockRows, balanceIndex);
        String snapshotTime = latestStockSnapshotTime();

        int ready = (int) items.stream().filter(i -> "READY".equals(i.status())).count();
        int review = (int) items.stream().filter(i -> "REVIEW".equals(i.status())).count();
        BigDecimal totalAmount = items.stream()
                .map(i -> i.stockAmount() != null ? i.stockAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new MergePreview(items, items.size(), ready, review,
                skippedZero, skippedFee, skippedNegative, unmatched, totalAmount,
                createCount, updateCount, unchangedCount,
                vanishedCount, snapshotTime);
    }
    /** 逐台账行生成 REVIEW 目标（产品未匹配 / 仓库缺失 / 数量异常），统一出口保证口径一致。 */
    private MergedItem reviewItem(StockRow sr, Enriched e, String issue) {
        BigDecimal amount = (e.purchasePrice() != null && sr.quantity() != null)
                ? sr.quantity().multiply(e.purchasePrice()).setScale(4, RoundingMode.HALF_UP) : null;
        return new MergedItem(sr.warehouse, e.category(), sr.productCode,
                e.matched() ? sr.productCode : null, e.productName(), e.specification(), e.unit(),
                sr.quantity, e.purchasePrice(), amount, "REVIEW", issue, e.customized(),
                sr.specification, sr.unit, null, null, null, null, null, null);
    }

    /**
     * 合并单元分组键：仓库 + 解析后产品。
     *
     * <p>已绑定产品用产品 id（同一产品的氚云编号可能有多个，按编号分组会把它们拆成多个目标值）；
     * 未绑定（全新产品）用「名称+规格+单位」占位——**不能只按名称分组**：同名不同规格是两个
     * 不同产品（2026-09-17 业务规则），按名称分组会把它们混成一个目标值。
     */
    private String groupKey(String warehouse, Enriched e, ProductResolution pr) {
        String wh = trim(warehouse);
        return pr.productId() != null
                ? wh + "\u0001P" + pr.productId()
                : wh + "\u0001N" + nsuKey(e.productName(), e.specification(), e.unit());
    }

    /** 一个合并单元：同一 (仓库, 解析后产品) 的全部台账来源行。 */
    private static final class MergeGroup {
        private final String warehouse;
        private final Long productId;
        private final List<GroupRow> rows = new ArrayList<>();

        private MergeGroup(String warehouse, Long productId) {
            this.warehouse = warehouse;
            this.productId = productId;
        }

        private void add(StockRow sr, Enriched e) { rows.add(new GroupRow(sr, e)); }
    }

    /** 合并单元内的一个台账来源行（含产品信息补全后的展示字段）。 */
    private record GroupRow(StockRow sr, Enriched e) {}

    /** 数量展示口径：去掉无意义的尾随零，避免「3.0」与「3」在 diff 里看起来像变化。 */
    private static String plainNum(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }



    /**
     * 反向对账（缺口 A）：氚云可能把卖完的产品整行移出仓库明细（而非置零），这类「消失」
     * 在正向合并里永远看不到。以当前台账为全量快照，找出「余额&gt;0 但快照中无此行」的余额行，
     * 生成「旧数量 → 0」的 READY 清零目标（issue=VANISHED_FROM_SNAPSHOT），随合并确认一并写入。
     *
     * <p>匹配口径（2026-09-17 修正）：仓库 + 氚云编号（或产品名），<b>刻意不含单位</b>。
     * 两份台账的单位口径经常不一致（产品信息台账「台」/ 仓库明细「套」，全库实测 110 个编号存在
     * 这类冲突），含单位比对会把「其实还在、只是单位写法不同」的余额行判成「已消失」而误清零——
     * 那是不可逆的错账。故这里只判存在性，宁可漏判（少清零）也不误清。
     * 产品优先按 sales_product_source_aliases 的编号匹配；完全没有编号别名时才退回产品名匹配。
     */
    private int appendVanishedReconciliation(List<MergedItem> items, List<StockRow> stockRows,
                                             Map<String, List<BalanceRow>> balanceIndex) {
        // 快照存在性集合：仓库|编号 与 仓库|名称 双键（刻意不含单位，见方法注释的误清零说明）
        Set<String> snapshotKeys = new HashSet<>();
        for (var sr : stockRows) {
            String wh = trim(sr.warehouse);
            snapshotKeys.add(wh + "\u0001" + trim(sr.productCode));
            snapshotKeys.add(wh + "\u0001" + trim(sr.productName));
        }

        // 仓库名/别名反向映射：id → Set<String>（系统名 + 全部氚云别名）
        // 与正向合并 loadMergeContext 的 warehouseIdByName 口径完全一致，杜绝「正向能找到、反向找不到」的死循环
        Map<Long, Set<String>> warehouseAliasesById = new HashMap<>();
        jdbc.query("SELECT id, warehouse_name FROM warehouses WHERE active=1",
                (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("warehouse_name")})
                .forEach(arr -> warehouseAliasesById.computeIfAbsent((Long) arr[0], k -> new HashSet<>())
                        .add(trim((String) arr[1])));
        jdbc.query("""
                SELECT target_id, source_name FROM source_identity_aliases
                WHERE source_system='H3YUN' AND entity_type='WAREHOUSE'
                  AND resolution_status IN ('AUTO','CONFIRMED') AND target_id IS NOT NULL
                """, (rs, n) -> new Object[]{rs.getLong("target_id"), rs.getString("source_name")})
                .forEach(arr -> warehouseAliasesById.computeIfAbsent((Long) arr[0], k -> new HashSet<>())
                        .add(trim((String) arr[1])));

        Map<Long, Set<String>> aliasCodesByProductId = new HashMap<>();
        jdbc.query("SELECT sales_product_id, source_product_code FROM sales_product_source_aliases" +
                        " WHERE source_system='H3YUN' AND resolution_status='RESOLVED' AND sales_product_id IS NOT NULL",
                (rs, n) -> new Object[]{rs.getLong("sales_product_id"), rs.getString("source_product_code")})
                .forEach(arr -> aliasCodesByProductId.computeIfAbsent((Long) arr[0], k -> new HashSet<>())
                        .add(String.valueOf(arr[1])));

        Map<Long, Object[]> productInfoById = new HashMap<>(); // [name, spec, internalCode]
        Set<Long> pids = new HashSet<>();
        balanceIndex.values().forEach(bs -> bs.forEach(b -> pids.add(b.productId())));
        if (!pids.isEmpty()) {
            String in = pids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            jdbc.query("SELECT id, product_name, COALESCE(specification,'') AS spec, product_code FROM sales_products WHERE id IN (" + in + ")",
                    (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("product_name"),
                            rs.getString("spec"), rs.getString("product_code")})
                    .forEach(arr -> productInfoById.put((Long) arr[0], arr));
        }

        int vanished = 0;
        for (var balances : balanceIndex.values()) {
            for (var bal : balances) {
                if (bal.quantity() == null || bal.quantity().signum() <= 0) continue;
                Set<String> whAliases = warehouseAliasesById.get(bal.warehouseId());
                if (whAliases == null || whAliases.isEmpty()) continue; // 仓库已删无法对账，留人工
                Set<String> codes = aliasCodesByProductId.get(bal.productId());
                boolean present;
                if (codes != null && !codes.isEmpty()) {
                    // 任一仓库名/别名 × 任一编号别名 命中即视为存在（不含单位，避免口径差异导致误清零）
                    present = false;
                    for (String wh : whAliases) {
                        for (String code : codes) {
                            if (snapshotKeys.contains(trim(wh) + "\u0001" + trim(code))) { present = true; break; }
                        }
                        if (present) break;
                    }
                } else {
                    Object[] pi = productInfoById.get(bal.productId());
                    present = false;
                    if (pi != null) {
                        for (String wh : whAliases) {
                            if (snapshotKeys.contains(trim(wh) + "\u0001" + trim((String) pi[1]))) { present = true; break; }
                        }
                    }
                }
                if (present) continue;

                Object[] pi = productInfoById.get(bal.productId());
                String name = pi != null ? (String) pi[1] : null;
                String spec = pi != null ? (String) pi[2] : null;
                String repCode = (codes != null && !codes.isEmpty()) ? codes.iterator().next()
                        : (pi != null ? (String) pi[3] : null);
                // 展示用仓库名：优先取系统名（warehouses.warehouse_name），找不到时用第一个别名
                String displayWh = whAliases.stream().filter(a -> a != null).findFirst().orElse(null);
                vanished++;
                items.add(new MergedItem(displayWh, null, repCode, repCode, name, spec, bal.unit(),
                        BigDecimal.ZERO, bal.purchasePrice(), BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        "READY", "VANISHED_FROM_SNAPSHOT", isCustomized(name, repCode),
                        null, null, "UPDATE", bal.quantity(), bal.purchasePrice(), bal.id(), null, null));
            }
        }
        return vanished;
    }

    /**
     * 最近一次完成的仓库明细台账快照时间（用于预览提示「对账基准时点」）。
     */
    private String latestStockSnapshotTime() {
        var ts = jdbc.queryForObject(
                "SELECT MAX(completed_at) FROM h3_import_batches WHERE data_type='WAREHOUSE_STOCK' AND status='COMPLETED'",
                java.sql.Timestamp.class);
        return ts == null ? null : ts.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** 最近一次完成的仓库明细批次 id：写入 OPENING 流水的 source_id，使库存变动可追溯到具体批次。 */
    private Long latestStockBatchId() {
        var ids = jdbc.queryForList(
                "SELECT id FROM h3_import_batches WHERE data_type='WAREHOUSE_STOCK' AND status='COMPLETED'" +
                        " ORDER BY completed_at DESC, id DESC LIMIT 1", Long.class);
        return ids.isEmpty() ? 0L : ids.getFirst();
    }

    private record Enriched(String category, String productName, String specification, String unit,
                            BigDecimal purchasePrice, boolean matched, boolean customized) {}

    /**
     * 产品信息台账优先补全仓库明细行的展示字段（类目/名称/规格/单位/价格），与原内联逻辑一致。
     *
     * <p>采购价口径（2026-09-16 固化）：产品信息台账是<b>唯一有效来源</b>。
     * {@code sr.purchasePrice} 是恒空槽位——氚云仓库明细导出该列表头存在但值全为 null
     * （实测 16305 行 100% null），这里的兜底分支实际永远取不到；保留它只为防
     * 未来氚云导出结构变化时静默丢价，而不是把它当作真实的价格候选。
     */
    private Enriched enrich(Map<String, ProductInfo> productMap, StockRow sr) {
        var pi = productMap.get(sr.productCode);
        boolean matched = (pi != null);
        String category = matched && pi.category != null ? pi.category : sr.category;
        String productName = matched && pi.productName != null ? pi.productName : sr.productName;
        String specification = matched && pi.specification != null ? pi.specification : sr.specification;
        String unit = normalizeUnit(matched && pi.unit != null ? pi.unit : sr.unit);
        BigDecimal purchasePrice = matched && pi.purchasePrice != null ? pi.purchasePrice : sr.purchasePrice;
        return new Enriched(category, productName, specification, unit, purchasePrice,
                matched, isCustomized(productName, sr.productCode));
    }

    /** 一次性加载全部库存余额并按 产品|仓库 建索引，避免合并预览逐行 N+1 查询。 */
    private Map<String, List<BalanceRow>> loadBalanceIndex() {
        Map<String, List<BalanceRow>> idx = new HashMap<>();
        jdbc.query("SELECT id, sales_product_id AS productId, warehouse_id AS warehouseId," +
                        " quantity, purchase_price AS purchasePrice, unit FROM product_inventory_balances",
                (rs, n) -> new BalanceRow(rs.getLong("id"), rs.getLong("productId"), rs.getLong("warehouseId"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("purchasePrice"), rs.getString("unit")))
                .forEach(b -> idx.computeIfAbsent(b.productId() + "|" + b.warehouseId(), k -> new ArrayList<>()).add(b));
        return idx;
    }

    /** 多单位余额行中优先命中与台账一致单位的行（行对象版）。 */
    private BalanceRow matchUnitRowBalance(List<BalanceRow> balances, String unit) {
        if (balances.size() == 1) return balances.getFirst();
        String target = normalizeUnit(unit);
        for (var bal : balances) {
            if (Objects.equals(normalizeUnit(bal.unit()), target)) return bal;
        }
        return balances.getFirst();
    }

    // ===== 确认入库（目标值幂等写入：差异行修正、一致行忽略、新行新增） =====

    /**
     * 请求体只作为「选择哪些行」的依据；数量/价格/单位一律按业务键（仓库+编号+单位）
     * 从当前台账服务端重算——防止预览与确认之间台账变化或前端篡改造成错账。
     */
    @Transactional
    public Map<String, Object> confirm(List<MergedItem> requested) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("没有需要导入的库存记录");

        Map<String, MergedItem> fresh = new HashMap<>();
        for (var item : preview().items()) fresh.putIfAbsent(itemKey(item), item);
        // 与预览同源的解析索引：确认时按同一降级链绑定，避免「预览绑 A、确认绑 B」
        MergeContext ctx = loadMergeContext();
        Long sourceBatchId = latestStockBatchId();

        Map<String, Long> warehouseCache = new HashMap<>();
        var user = users.current();
        LocalDateTime now = LocalDateTime.now();
        int productCreated = 0, createdCount = 0, updatedCount = 0, unchangedCount = 0, skippedCount = 0;
        // 收集本次合并的更新明细，用于批次「查看 X 条更新」（field-changes 风格，前端以 diff 列表渲染）
        List<Map<String, Object>> previewRows = new ArrayList<>();

        for (var req : requested) {
            MergedItem item = fresh.get(itemKey(req));
            if (item == null || !"READY".equals(item.status()) || item.action() == null) { skippedCount++; continue; }
            if ("UNCHANGED".equals(item.action())) { unchangedCount++; continue; }

            // 反向对账清零行：直接按余额行主键锁定处理，不经 ensureProduct/仓库解析（杜绝误建产品）
            if (item.balanceRowId() != null) {
                var rows = jdbc.queryForList(
                        "SELECT warehouse_id AS warehouseId, sales_product_id AS productId, quantity," +
                                " purchase_price AS purchasePrice, unit FROM product_inventory_balances WHERE id=? FOR UPDATE",
                        item.balanceRowId());
                if (rows.isEmpty()) { skippedCount++; continue; }
                var bal = rows.getFirst();
                BigDecimal prevQty = big(bal.get("quantity"));
                if (prevQty == null || prevQty.signum() <= 0) { unchangedCount++; continue; } // 已被别的动作清掉，幂等忽略
                jdbc.update("UPDATE product_inventory_balances SET quantity=0, stock_amount=0 WHERE id=?", item.balanceRowId());
                long whId = ((Number) bal.get("warehouseId")).longValue();
                long pid = ((Number) bal.get("productId")).longValue();
                String unit = bal.get("unit") == null ? "个" : String.valueOf(bal.get("unit"));
                String tmp = "MERGE-" + UUID.randomUUID();
                jdbc.update("INSERT INTO product_inventory_transactions(" +
                                "transaction_no,warehouse_id,sales_product_id,finished_goods_lot_id," +
                                "product_name_snapshot,specification_snapshot,unit,transaction_type," +
                                "quantity_change,balance_after,source_type,source_id,source_no," +
                                "operator_employee_id,operator_name,occurred_at,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        tmp, whId, pid, (Object) null,
                        item.productName(), item.specification(), unit, "OPENING",
                        prevQty.negate(), BigDecimal.ZERO, "OPENING", sourceBatchId, "MERGE-IMPORT",
                        user.employeeId(), user.name(), now,
                        "台账对账清零（快照中已无此行）：" + item.warehouse() + " 编号=" + item.productCode());
                long txId = jdbc.queryForObject("SELECT id FROM product_inventory_transactions WHERE transaction_no=?", Long.class, tmp);
                jdbc.update("UPDATE product_inventory_transactions SET transaction_no=? WHERE id=?", "PI" + String.format("%08d", txId), txId);
                // 记录清零更新明细
                List<Map<String, String>> changes = new ArrayList<>();
                changes.add(changeItem("数量", prevQty.toPlainString(), "0"));
                previewRows.add(previewRow(item, "UPDATE", changes));
                updatedCount++;
                continue;
            }

            Long warehouseId = warehouseCache.computeIfAbsent(item.warehouse(), wh -> {
                return sourceIdentityService.resolveWarehouse(wh);
            });
            if (warehouseId == null) throw new IllegalStateException("仓库不存在：" + item.warehouse());

            EnsuredProduct ep = ensureProduct(item, ctx);
            if (ep == null) { skippedCount++; continue; } // 多候选歧义：跳过而非盲绑/误建
            if (ep.created()) productCreated++;
            long productId = ep.productId();

            String unit = item.unit() != null ? item.unit() : "个";
            BigDecimal price = item.purchasePrice();
            BigDecimal stockAmount = item.stockAmount() != null ? item.stockAmount()
                    : (price != null ? item.quantity().multiply(price).setScale(4, RoundingMode.HALF_UP) : null);

            // 按 仓库+产品 找全部余额行并锁定，保证并发/重复确认安全
            var balances = jdbc.queryForList(
                    "SELECT id, quantity, purchase_price AS purchasePrice, unit FROM product_inventory_balances" +
                            " WHERE warehouse_id=? AND sales_product_id=? ORDER BY id FOR UPDATE",
                    warehouseId, productId);

            BigDecimal after = item.quantity();
            BigDecimal quantityChange;
            if (balances.isEmpty()) {
                if (after.signum() == 0) { skippedCount++; continue; }
                // 新库存行
                jdbc.update("INSERT INTO product_inventory_balances(warehouse_id,sales_product_id,unit,quantity,purchase_price,stock_amount) " +
                                "VALUES(?,?,?,?,?,?)",
                        warehouseId, productId, unit, after, price, stockAmount);
                quantityChange = after;
                previewRows.add(previewRow(item, "CREATE", List.of(
                        changeItem("数量", "0", after.toPlainString()),
                        changeItem("采购价", "—", price == null ? "—" : price.toPlainString()))));
                createdCount++;
            } else {
                Map<String, Object> bal = matchUnitBalance(balances, unit);
                BigDecimal prevQty = big(bal.get("quantity"));
                BigDecimal prevPrice = big(bal.get("purchasePrice"));
                if (bigEq(prevQty, after) && bigEq(prevPrice, price)) {
                    // 与台账一致：忽略，不产生流水、不重复累加
                    unchangedCount++;
                    continue;
                }
                quantityChange = after.subtract(prevQty);
                jdbc.update("UPDATE product_inventory_balances SET unit=?,quantity=?,purchase_price=?,stock_amount=? WHERE id=?",
                        unit, after, price, stockAmount, bal.get("id"));
                List<Map<String, String>> changes = new ArrayList<>();
                if (!bigEq(prevQty, after)) {
                    changes.add(changeItem("数量", prevQty == null ? "0" : prevQty.toPlainString(), after.toPlainString()));
                }
                if (!bigEq(prevPrice, price)) {
                    changes.add(changeItem("采购价",
                            prevPrice == null ? "—" : prevPrice.toPlainString(),
                            price == null ? "—" : price.toPlainString()));
                }
                if (!changes.isEmpty()) previewRows.add(previewRow(item, "UPDATE", changes));
                updatedCount++;
            }

            String tmp = "MERGE-" + UUID.randomUUID();
            jdbc.update("INSERT INTO product_inventory_transactions(" +
                            "transaction_no,warehouse_id,sales_product_id,finished_goods_lot_id," +
                            "product_name_snapshot,specification_snapshot,unit,transaction_type," +
                            "quantity_change,balance_after,source_type,source_id,source_no," +
                            "operator_employee_id,operator_name,occurred_at,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tmp, warehouseId, productId, (Object) null,
                    item.productName(), item.specification(), unit, "OPENING",
                    quantityChange, after, "OPENING", sourceBatchId, "MERGE-IMPORT",
                    user.employeeId(), user.name(), now,
                    "台账合并导入：" + item.warehouse() + " 编号=" + item.productCode());
            long txId = jdbc.queryForObject("SELECT id FROM product_inventory_transactions WHERE transaction_no=?", Long.class, tmp);
            jdbc.update("UPDATE product_inventory_transactions SET transaction_no=? WHERE id=?", "PI" + String.format("%08d", txId), txId);
        }
        long batchId = recordMergeBatch(createdCount, updatedCount, unchangedCount, productCreated, user.name(), previewRows);
        return Map.of(
                "batchId", batchId,
                "importedCount", createdCount + updatedCount,
                "createdCount", createdCount, "updatedCount", updatedCount,
                "unchangedCount", unchangedCount, "productCreated", productCreated,
                "skippedCount", skippedCount);
    }

    /** 构造一行更新明细，供「查看 X 条更新」抽屉展示（与 LedgerImportPreview.rows 兼容）。 */
    private Map<String, Object> previewRow(MergedItem item, String action, List<Map<String, String>> changes) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("warehouse", item.warehouse());
        p.put("productCode", item.productCode());
        p.put("businessKey", item.warehouse() + " / " + item.productCode() + " / " + (item.unit() == null ? "个" : item.unit()));
        p.put("productName", item.productName());
        p.put("title", item.productName());
        p.put("changes", changes);
        return p;
    }

    private Map<String, String> changeItem(String path, String oldValue, String newValue) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("path", path);
        c.put("oldValue", oldValue);
        c.put("newValue", newValue);
        return c;
    }

    /**
     * 读取并返回合并入库批次的「更新明细」预览。
     * 用于「导入与批次」列表上「查看 X 条更新」按钮 —— 与 ledger preview 端点返回结构兼容：
     * 顶层 { batchId, dataType, totalCount, createdCount, updatedCount, unchangedCount, rows }。
     */
    public Map<String, Object> mergePreviewOf(Long batchId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, data_type, file_name, status, progress_pct, total_count," +
                        " created_count, updated_count, unchanged_count, preview_json" +
                        " FROM h3_import_batches WHERE id=?", batchId);
        if (row == null) throw new IllegalArgumentException("批次不存在：" + batchId);
        String status = String.valueOf(row.get("status"));
        if (!"COMPLETED".equals(status) && !"PARTIAL_FAILED".equals(status)) {
            throw new IllegalStateException("批次尚未完成，无法预览：status=" + status);
        }
        Object cached = row.get("preview_json");
        List<Map<String, Object>> previewRows = List.of();
        if (cached != null && !String.valueOf(cached).isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = objectMapper.readValue(
                        String.valueOf(cached),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                previewRows = parsed;
            } catch (Exception e) {
                throw new IllegalStateException("批次预览缓存解析失败：id=" + batchId, e);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", row.get("id"));
        body.put("dataType", row.get("data_type"));
        body.put("fileName", row.get("file_name"));
        body.put("totalCount", row.get("total_count"));
        body.put("createdCount", row.get("created_count"));
        body.put("updatedCount", row.get("updated_count"));
        body.put("unchangedCount", row.get("unchanged_count"));
        body.put("rows", previewRows);
        return body;
    }

    /**
     * 在 h3_import_batches 中登记一条「库存合并入库」批次，让氚云导入中心「导入与批次」
     * 也能展示合并入库历史与统计。data_type=INVENTORY_MERGE 区别于文件导入批次；
     * file_name 标注为「库存合并入库」便于在批次列表中识别；同步事务已结束，状态直接置
     * COMPLETED；total_count 不含 skipped（REVIEW/不匹配行不归入新增/更新/忽略统计）。
     * previewRows 为本次合并产生的 CREATED/UPDATED 明细，序列化为 preview_json 缓存，
     * 供批次列表「查看 X 条更新」按钮读取（mergePreviewOf）。
     */
    private long recordMergeBatch(int createdCount, int updatedCount, int unchangedCount,
                                   int productCreated, String operatorName,
                                   List<Map<String, Object>> previewRows) {
        int total = createdCount + updatedCount + unchangedCount;
        String fileName = "库存合并入库" + (operatorName == null ? "" : "（" + operatorName + "）")
                + (productCreated > 0 ? " · 新建产品 " + productCreated : "");
        String previewJsonTmp;
        try {
            previewJsonTmp = previewRows.isEmpty() ? null : objectMapper.writeValueAsString(previewRows);
        } catch (Exception e) {
            previewJsonTmp = null;
        }
        final String previewJson = previewJsonTmp;
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO h3_import_batches(file_name,file_size,file_hash,data_type,field_version," +
                            "status,progress_stage,progress_pct,total_count,created_count,updated_count,unchanged_count," +
                            "preview_json,completed_at) VALUES(?,0,NULL,'INVENTORY_MERGE',NULL,'COMPLETED','DONE',100,?,?,?,?,?,NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fileName);
            ps.setInt(2, total);
            ps.setInt(3, createdCount);
            ps.setInt(4, updatedCount);
            ps.setInt(5, unchangedCount);
            ps.setString(6, previewJson);
            return ps;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /** 合并行业务键：仓库+氚云编号+归一单位（与台账去重键同口径）。 */
    private static String itemKey(MergedItem i) {
        String base = (i.warehouse() == null ? "" : i.warehouse()) + "\u0001"
                + (i.productCode() == null ? "" : i.productCode()) + "\u0001"
                + (i.unit() == null ? "" : i.unit());
        return i.balanceRowId() != null ? base + "\u0001#" + i.balanceRowId() : base;
    }

    // ===== 合并辅助 =====

    private record MergeContext(Map<String, Long> warehouseIdByName,
                                Map<String, Long> aliasByCode,
                                Map<String, List<Long>> idsByNsu,
                                Map<String, List<Long>> idsByName,
                                Map<Long, String[]> keyById,
                                /** 台账专属编号空间：product_code → id（由台账合并建档产生，编号即稳定键）。 */
                                Map<String, Long> productIdByGeneratedCode) {}

    /**
     * 身份解析结果：{@code productId} 非空 = 绑定既有产品；{@code ambiguous} = 多候选
     * （只入 REVIEW，不自动绑定）；两者皆空 = 全新产品（交 confirm 建档）。
     */
    private record ProductResolution(Long productId, boolean ambiguous) {}

    /**
     * 身份解析降级链（与 {@code OutboundRelationGovernanceService} 同口径，勿另写一套）：
     * <ol>
     *   <li>① 编号命中且名称/规格/单位不冲突 → 采信</li>
     *   <li>①b 编号命中但字段轻微不符，且该编号已建过 H3-&lt;编号&gt; 档案 → 绑回（台账专属编号空间）</li>
     *   <li>② 编号命中但名称明显不符 → 视为编码漂移，继续降级</li>
     *   <li>③ 名称+规格+单位唯一命中 → 采信</li>
     *   <li>④ 名称命中、且按规格/单位过滤后唯一 → 采信（同名不同规格 = 两个产品，互不吸附）</li>
     *   <li>⑤ 多候选 → REVIEW（{@code PRODUCT_AMBIGUOUS}）；无候选 / 名称命中但规格全不符 → 全新产品</li>
     * </ol>
     * 为什么不再「回退第一候选」：47 个编号跨台账指向不同产品、354 个产品同物不同码，
     * 盲绑会把两个真实不同的产品混在一起，后果比留待人工裁决严重。
     * 为什么④要再过滤规格/单位（2026-09-17 修正）：实测「2014*5.5透明抗冷冻-1200只」在
     * sales_products 里有 15g/18g/14g 三条——同名不同规格按业务规则是两个产品，
     * 只按名称唯一吸附会让两个产品的台账行写进同一条余额行（价格/数量来回振荡）。
     */
    private ProductResolution resolveProduct(String code, String name, String spec, String unit, MergeContext ctx) {
        Long byCode = code == null || code.isBlank() ? null : ctx.aliasByCode().get(code);
        if (byCode != null && consistent(byCode, name, spec, unit, ctx)) return new ProductResolution(byCode, false);

        // ①b 台账专属编号空间：H3-<氚云编号> 只由台账合并建档产生，编号本身就是稳定键，
        //     所以名称/规格轻微不符时（实测氚云改字：「48一出九片膜下模具」→「48一出九上下模具」）
        //     不再改判为「全新产品」。否则预览会虚报 CREATE，而确认端按 generateProductCode
        //     仍会绑回同一产品 → 预览/确认口径分裂，且每次预览都残留一条假 CREATE（永不收敛）。
        Long byGenerated = code == null || code.isBlank() ? null
                : ctx.productIdByGeneratedCode().get(generateProductCode(code));
        if (byGenerated != null) return new ProductResolution(byGenerated, false);

        List<Long> byNsu = ctx.idsByNsu().getOrDefault(nsuKey(name, spec, unit), List.of());
        if (byNsu.size() == 1) return new ProductResolution(byNsu.getFirst(), false);

        // ④ 名称命中后，用规格/单位把「同名不同规格」的产品剔除（空值不参与判定，规格缺失不算冲突）
        List<Long> byName = ctx.idsByName().getOrDefault(nameKey(name), List.of()).stream()
                .filter(id -> consistent(id, name, spec, unit, ctx))
                .toList();
        if (byName.size() == 1) return new ProductResolution(byName.getFirst(), false);

        if (byNsu.size() > 1 || byName.size() > 1) return new ProductResolution(null, true);
        return new ProductResolution(null, false);
    }

    /** ①的冲突检测：名称必须一致（两侧都有值时）；规格/单位同理，空值不判。 */
    private boolean consistent(long productId, String name, String spec, String unit, MergeContext ctx) {
        String[] k = ctx.keyById().get(productId);
        if (k == null) return true;
        String dbName = trim(k[0]);
        String inName = trim(name);
        if (!dbName.isEmpty() && !inName.isEmpty() && !dbName.equalsIgnoreCase(inName)) return false;
        String dbSpec = trim(k[1]);
        String inSpec = trim(spec);
        if (!dbSpec.isEmpty() && !inSpec.isEmpty() && !dbSpec.equals(inSpec)) return false;
        String dbUnit = nullToEmpty(normalizeUnit(k[2]));
        String inUnit = nullToEmpty(normalizeUnit(unit));
        return dbUnit.isEmpty() || inUnit.isEmpty() || dbUnit.equals(inUnit);
    }

    /** 名称+规格+单位键：单位先过归一，再走与出库关系治理同一套 nsuKey 口径。 */
    private String nsuKey(String name, String spec, String unit) {
        return OutboundRelationGovernanceService.nsuKey(trim(name), trim(spec), nullToEmpty(normalizeUnit(unit)));
    }

    /** 名称键（与出库关系治理的 normalize 同语义：去空白 + 小写）。 */
    private static String nameKey(String name) {
        return trim(name).replace(" ", "").replace("\u3000", "").toLowerCase(java.util.Locale.ROOT);
    }

    private MergeContext loadMergeContext() {
        Map<String, Long> warehouseIdByName = new HashMap<>();
        jdbc.query("SELECT id, warehouse_name FROM warehouses WHERE active=1",
                (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("warehouse_name")})
                .forEach(arr -> warehouseIdByName.put(String.valueOf(arr[1]), (Long) arr[0]));
        jdbc.query("""
                SELECT source_name,target_id FROM source_identity_aliases
                WHERE source_system='H3YUN' AND entity_type='WAREHOUSE'
                  AND resolution_status IN ('AUTO','CONFIRMED') AND target_id IS NOT NULL
                """, (rs, n) -> new Object[]{rs.getString("source_name"), rs.getLong("target_id")})
                .forEach(arr -> warehouseIdByName.putIfAbsent(String.valueOf(arr[0]), (Long) arr[1]));

        Map<String, Long> aliasByCode = new HashMap<>();
        jdbc.query("SELECT source_product_code, sales_product_id FROM sales_product_source_aliases" +
                        " WHERE resolution_status='RESOLVED' AND sales_product_id IS NOT NULL AND source_system='H3YUN'",
                (rs, n) -> new Object[]{rs.getString("source_product_code"), rs.getLong("sales_product_id")})
                .forEach(arr -> aliasByCode.putIfAbsent(String.valueOf(arr[0]), (Long) arr[1]));
        jdbc.query("""
                SELECT source_key,target_id FROM source_identity_aliases
                WHERE source_system='H3YUN' AND entity_type='PRODUCT'
                  AND resolution_status IN ('AUTO','CONFIRMED') AND target_id IS NOT NULL
                """, (rs, n) -> new Object[]{rs.getString("source_key"), rs.getLong("target_id")})
                .forEach(arr -> aliasByCode.put(String.valueOf(arr[0]), (Long) arr[1]));

        // 候选索引必须保留「多重性」：唯一才采信，多候选是歧义信号（旧实现 putIfAbsent 会掩盖歧义）
        Map<String, List<Long>> idsByNsu = new HashMap<>();
        Map<String, List<Long>> idsByName = new HashMap<>();
        Map<Long, String[]> keyById = new HashMap<>();
        jdbc.query("SELECT id, product_name, COALESCE(specification,'') AS spec, unit" +
                        " FROM sales_products WHERE deleted_at IS NULL",
                (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("product_name"),
                        rs.getString("spec"), rs.getString("unit")})
                .forEach(arr -> {
                    long id = (Long) arr[0];
                    String name = trim((String) arr[1]);
                    String spec = trim((String) arr[2]);
                    String unit = trim((String) arr[3]);
                    keyById.put(id, new String[]{name, spec, unit});
                    if (name.isEmpty()) return;
                    idsByNsu.computeIfAbsent(nsuKey(name, spec, unit), k -> new ArrayList<>()).add(id);
                    idsByName.computeIfAbsent(nameKey(name), k -> new ArrayList<>()).add(id);
                });

        // 台账专属编号空间 H3-<氚云编号>（含已软删：确认端会 restore，预览必须同口径）
        Map<String, Long> productIdByGeneratedCode = new HashMap<>();
        jdbc.query("SELECT id, product_code FROM sales_products WHERE product_code LIKE 'H3-%'",
                (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("product_code")})
                .forEach(arr -> productIdByGeneratedCode.put(String.valueOf(arr[1]), (Long) arr[0]));

        return new MergeContext(warehouseIdByName, aliasByCode, idsByNsu, idsByName, keyById,
                productIdByGeneratedCode);
    }

    private record EnsuredProduct(long productId, boolean created) {}

    /**
     * 找到或创建目标产品主数据。绑定走与预览完全一致的降级链（{@link #resolveProduct}），
     * 保证「预览说绑谁、确认就绑谁」；不再有无单位命中时回退第一候选的盲绑。
     *
     * @return null 表示多候选歧义，调用方应跳过该行（不绑定、不建档）
     */
    private EnsuredProduct ensureProduct(MergedItem item, MergeContext ctx) {
        ProductResolution pr = resolveProduct(item.productCode(), item.productName(),
                item.specification(), item.unit(), ctx);
        if (pr.ambiguous()) return null;
        if (pr.productId() != null) {
            long productId = pr.productId();
            restoreProduct(productId);
            saveProductAlias(item, productId);
            sourceIdentityService.promoteProductResolution(
                    item.productCode(), item.productName(), item.specification(), item.unit(), productId);
            return new EnsuredProduct(productId, false);
        }

        String code = generateProductCode(item.productCode());
        boolean codeAlreadyExists = !jdbc.queryForList(
                "SELECT id FROM sales_products WHERE product_code=? LIMIT 1", Long.class, code).isEmpty();
        jdbc.update("INSERT INTO sales_products(product_code,product_name,specification,unit,category,source,status,is_customized,external_source,external_id,remark) " +
                        "VALUES(?,?,?,?,?,'H3YUN_IMPORT','PENDING_REVIEW',?,'氚云',?,'台账合并导入') " +
                        "ON DUPLICATE KEY UPDATE deleted_at=NULL",
                code, item.productName(), item.specification(), item.unit(), item.category(),
                item.isCustomized() ? 1 : 0, item.productCode());

        long newId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?", Long.class, code);
        saveProductAlias(item, newId);
        sourceIdentityService.promoteProductResolution(
                item.productCode(), item.productName(), item.specification(), item.unit(), newId);
        return new EnsuredProduct(newId, !codeAlreadyExists);
    }

    /** 保存氚云编号到交易产品的稳定映射，使后续预览和重复确认直接复用同一产品。 */
    private void saveProductAlias(MergedItem item, long productId) {
        String hash = sha256(item.productCode() + "|" + item.productName() + "|" + (item.specification() != null ? item.specification() : ""));
        jdbc.update("INSERT INTO sales_product_source_aliases" +
                        "(source_system,external_id,source_row,source_product_code,source_name,source_specification,source_unit,source_category,source_hash,resolution_status,sales_product_id) " +
                        "VALUES('H3YUN',?,0,?,?,?,?,?,?,'RESOLVED',?) " +
                        "ON DUPLICATE KEY UPDATE source_product_code=VALUES(source_product_code),source_name=VALUES(source_name)," +
                        "source_specification=VALUES(source_specification),source_unit=VALUES(source_unit)," +
                        "source_category=VALUES(source_category),source_hash=VALUES(source_hash)," +
                        "resolution_status='RESOLVED',sales_product_id=VALUES(sales_product_id),last_seen_at=NOW()",
                item.productCode(), item.productCode(), item.productName(), item.specification(), item.unit(), item.category(), hash, productId);
    }

    private void restoreProduct(long productId) {
        jdbc.update("UPDATE sales_products SET deleted_at=NULL WHERE id=? AND deleted_at IS NOT NULL", productId);
    }

    /** 多单位余额行中优先命中与台账一致单位的行。 */
    private Map<String, Object> matchUnitBalance(List<Map<String, Object>> balances, String unit) {
        if (balances.size() == 1) return balances.getFirst();
        String target = normalizeUnit(unit);
        for (var bal : balances) {
            if (Objects.equals(normalizeUnit(bal.get("unit") == null ? null : String.valueOf(bal.get("unit"))), target)) return bal;
        }
        return balances.getFirst();
    }

    private String generateProductCode(String h3Code) { return "H3-" + h3Code; }
    private boolean isCustomized(String name, String code) {
        String n = (name != null ? name : "") + (code != null ? code : "");
        return n.contains("定做") || n.contains("订做");
    }
    private String normalizeUnit(String unit) { return UnitNormalizer.normalize(unit); }

    private static BigDecimal big(Object v) { return v == null ? null : new BigDecimal(String.valueOf(v)); }
    private static boolean bigEq(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return a.compareTo(b) == 0;
    }
    private static String sha256(String s) { return org.apache.commons.codec.digest.DigestUtils.sha256Hex(s); }

    private static String trim(String v) { return v == null ? "" : v.trim(); }
    private static String nullToEmpty(String v) { return v == null ? "" : v; }

    // ===== 内部记录 =====
    private record ProductInfo(String productCode, String category, String productName, String specification, String unit, BigDecimal purchasePrice) {}
    private record StockRow(String warehouse, String category, String productCode, String productName, String specification, String unit, BigDecimal quantity, BigDecimal purchasePrice) {}
    private record BalanceRow(long id, long productId, long warehouseId, BigDecimal quantity, BigDecimal purchasePrice, String unit) {}

    public record MergedItem(
            String warehouse, String category, String productCode, String productInfoCode,
            String productName, String specification, String unit,
            BigDecimal quantity, BigDecimal purchasePrice, BigDecimal stockAmount,
            String status, String issue, boolean isCustomized,
            String warehouseSpec, String warehouseUnit,
            String action, BigDecimal prevQuantity, BigDecimal prevPurchasePrice,
            Long balanceRowId,
            /**
             * 本行是一个「合并单元」的聚合结果：同 (仓库, 解析后产品) 的多个氚云编号已合并为一个目标值。
             * 展示格式「编号:数量 | 编号:数量」，供人工核对聚合是否正确（2026-09-17 幂等修复）。
             */
            String sourceRows,
            /** 被忽略的 0 量残影行（「编号:0 | …」）：不写入余额，但保留来源可见性。 */
            String ignoredRows) {}

    public record MergePreview(
            List<MergedItem> items, int totalCount, int readyCount, int reviewCount,
            int skippedZero, int skippedFee, int skippedNegative, int unmatchedCount,
            BigDecimal totalAmount,
            int createCount, int updateCount, int unchangedCount,
            int vanishedCount, String snapshotTime) {}
}
