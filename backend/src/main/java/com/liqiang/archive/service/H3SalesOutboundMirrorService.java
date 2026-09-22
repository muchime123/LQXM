package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * 氚云销售出库 → ERP 业务出库单「全量镜像」（双轨制的销售侧接线）。
 *
 * <h3>为什么要有这个类</h3>
 * 与生产侧（{@link H3ManufacturingMirrorService}）是<b>同一个结构性问题</b>：
 * <ul>
 *   <li><b>源轨道</b> {@code sales_outbounds}(18,966) / {@code sales_outbound_items}(35,159)：
 *       氚云导入的<span>真实出库单</span>，字段完整（客户/业务员/仓库/金额/毛利/收款/发货方式/明细），
 *       但<b>只读</b>，只作「氚云导入中心」的台账展示。</li>
 *   <li><b>业务轨道</b> {@code erp_sales_outbounds}：ERP 侧唯一能录单、能扣库存、能追溯的出库单，
 *       但<b>是空表</b>（0 行）。「销售管理 → 销售出库」页读的正是它。</li>
 * </ul>
 * 后果：真实的 18,966 张出库单在系统里没有任何业务入口，而能操作的那套流程底下是空的。
 *
 * <h3>双轨纪律（不可违反）</h3>
 * <ul>
 *   <li>镜像<b>只读源表、只写业务表</b>；{@code sales_outbounds} 一个字都不改
 *       （生产侧会回写 {@code linked_manufacturing_order_id}，销售侧<b>连回写都不做</b>——
 *       因为台账已有 {@code external_id} 可反查，没有指针失真的中间态问题）。</li>
 *   <li>镜像<b>不反向同步</b>：业务侧的改动绝不回写氚云台账。</li>
 *   <li>幂等靠两重保险：{@code uk_eso_external(external_source,external_id)} 唯一索引
 *       + 「源表左连业务表，只取连不上的」选取条件。</li>
 * </ul>
 *
 * <h3>⭐ 两个决定成败的字段口径（实测踩出来，不是猜的）</h3>
 * <ol>
 *   <li><b>业务员必须取 {@code employee_id}，不能取 {@code salesperson_id}</b>。
 *       源表里 {@code salesperson_id} 是<b>氚云侧原始 id</b>（7/15/23…），
 *       {@code employee_id} 才是<b>本地员工 id</b>（2/9/17…）。实测对照：
 *       {@code salesperson_id=23 名字是「李水云」}，而本地 {@code employees[23]} 是「于洋洋」——
 *       本地 id=17 才是李水云。<b>直接搬 salesperson_id 会让数据范围整片串台</b>
 *       （每个业务员看到的都是别人的单，且不报错）。生产侧镜像用的正是 {@code employee_id}，
 *       对照 {@code employees} 姓名 100% 吻合，本类照做。</li>
 *   <li><b>客户 {@code customer_id} 可以直接搬</b>——实测 18,757/18,757 命中本地
 *       {@code customers}（含 209 行为空 = 氚云没匹配上，镜像留空）。</li>
 * </ol>
 *
 * <h3>状态为什么一律落 DRAFT</h3>
 * ERP 出库单的 {@code POSTED} 语义是<b>扣减当前库存</b>。这 18,966 张是 2025-11 ~ 2026-09 的
 * <b>历史已发生事实</b>，若按氚云原状态（已生效）映射成 POSTED，会在镜像的瞬间把当前库存
 * 冲成巨额负数。所以：<b>一律 DRAFT</b>，氚云原状态存 {@code source_status_raw} 备查，
 * 由业务侧自行决定要不要过账。这也正好把「历史单该不该补扣库存」这个问题
 * <b>显式暴露</b>在业务侧，而不是悄悄替用户做一个危险的决定。
 */
@Service
public class H3SalesOutboundMirrorService {

    /**
     * ERP 表列宽上限（源列多为 TEXT，比 ERP 列宽，超长必须截断否则插入直接报错）。
     * 截断只发生在 ERP 落库这一步，源表一个字不改。
     */
    private static final int MAX_OUTBOUND_NO = 50;
    private static final int MAX_NAME = 300;
    private static final int MAX_PRODUCT_NAME = 500;
    private static final int MAX_SPEC = 1000;
    private static final int MAX_HEAD_REMARK = 1000;
    private static final int MAX_LINE_REMARK = 500;
    private static final int MAX_PENDING = 300;
    private static final int MAX_STATUS_RAW = 30;
    private static final int MAX_WAREHOUSE_NAME = 100;
    private static final int MAX_CATEGORY = 100;
    private static final int MAX_SPEC_UNIT = 40;

    /** 缺值占位：氚云可能整单没有产品名（实测 18 行），页面上要能一眼看出是源里就没有。 */
    private static final String BLANK_PRODUCT = "（氚云未填产品名）";

    private final JdbcTemplate jdbc;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;
    private final TransactionTemplate tx;

    public H3SalesOutboundMirrorService(JdbcTemplate jdbc,
                                        @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                        ErpActorResolver actors,
                                        PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.changeLog = changeLog;
        this.actors = actors;
        // 显式构造而不是注入 TransactionTemplate：本项目是双数据源（主库 + erp schema），
        // 出库单与明细都在主库，必须钉死用主库事务管理器（@Primary 的那个），
        // 不能靠自动配置碰运气——绑错池不报错、只静默失效。
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 单张出库单的镜像结果。 */
    public record MirrorResult(long outboundId, String outboundNo, boolean pendingData, String pendingReason) {}

    /**
     * 全量镜像入口：把尚未镜像的氚云出库单逐张转成 ERP 出库单（草稿）。
     *
     * <p>每张单<b>独立事务</b>——一张失败不影响其余，失败明细随返回值带出。
     * 可反复调用：已镜像的天然被跳过，因此「点几次」和「点一次全量」结果一致。
     *
     * @param limit 本批最多处理多少张（0 = 不限，建议页面上分批点，便于观察与止损）
     * @return 统计：{@code scanned/created/failed/pendingData/pendingSamples/errors/remaining}
     */
    public Map<String, Object> mirrorSalesOutbounds(int limit) {
        List<Map<String, Object>> heads = pendingSourceRows(limit);
        int created = 0, pendingData = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        List<String> pendingSamples = new ArrayList<>();

        // 明细按批一次查回再分组（避免 18,966 次 N+1 查询）。
        Map<Long, List<Map<String, Object>>> linesByParent = linesOf(heads);

        for (Map<String, Object> head : heads) {
            String no = str(head.get("outbound_no"));
            long sourceId = ((Number) head.get("id")).longValue();
            try {
                List<Map<String, Object>> lines = linesByParent.getOrDefault(sourceId, List.of());
                MirrorResult r = tx.execute(s -> doMirrorOne(head, lines));
                if (r == null) { failed++; errors.add(no + ": 事务未返回结果"); continue; }
                created++;
                if (r.pendingData()) {
                    pendingData++;
                    if (pendingSamples.size() < 10) pendingSamples.add(no + " → " + r.pendingReason());
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < 20) errors.add(no + ": " + rootMessage(e));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", heads.size());
        out.put("created", created);
        out.put("failed", failed);
        out.put("pendingData", pendingData);
        out.put("pendingSamples", pendingSamples);
        out.put("errors", errors);
        out.put("remaining", remainingSourceCount());
        return out;
    }

    /** 已镜像 / 未镜像计数：页面用来显示进度。 */
    public Map<String, Object> mirrorProgress() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceTotal", jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_outbounds", Long.class));
        out.put("mirrored", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sales_outbounds WHERE external_source='H3YUN'", Long.class));
        out.put("remaining", remainingSourceCount());
        out.put("pendingData", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sales_outbounds WHERE external_source='H3YUN'"
                        + " AND source_mirror_pending IS NOT NULL", Long.class));
        out.put("itemsMirrored", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sales_outbound_items i JOIN erp_sales_outbounds o"
                        + " ON o.id=i.outbound_id WHERE o.external_source='H3YUN'", Long.class));
        return out;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /**
     * 选取待镜像的氚云出库单。
     *
     * <p>用「左连业务表取连不上的」而不是靠任何指针列——业务表实际存在与否才是真幂等，
     * 指针列在中间态下会失真。
     *
     * <p>排序按业务日期 + 源 id：镜像后 id 的插入顺序 = 时间顺序，
     * 前端 {@code ORDER BY o.id DESC} 拿到的最新单在前，符合直觉。
     */
    private List<Map<String, Object>> pendingSourceRows(int limit) {
        String sql = """
            SELECT o.* FROM sales_outbounds o
            LEFT JOIN erp_sales_outbounds eo
              ON eo.external_source='H3YUN' AND eo.external_id=o.external_id
            WHERE eo.id IS NULL
            ORDER BY o.business_date, o.id
            """;
        if (limit > 0) sql = sql + " LIMIT " + limit;
        return jdbc.queryForList(sql);
    }

    private long remainingSourceCount() {
        Long n = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sales_outbounds o
            LEFT JOIN erp_sales_outbounds eo
              ON eo.external_source='H3YUN' AND eo.external_id=o.external_id
            WHERE eo.id IS NULL
            """, Long.class);
        return n == null ? 0L : n;
    }

    /** 批内一次取回全部明细并按父单 id 分组。 */
    private Map<Long, List<Map<String, Object>>> linesOf(List<Map<String, Object>> heads) {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        if (heads.isEmpty()) return out;
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> h : heads) ids.add(((Number) h.get("id")).longValue());
        // 分批 IN，避免 SQL 过长（单批最多 500 个 id）
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(from + 500, ids.size()));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            // 显式列而不是 SELECT *：明细表带 image_ref / attachment_info 两个 longtext，
            // 整批拉回来纯属浪费内存，镜像一行都用不到。
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id,sales_outbound_id,external_id,sequence_no,line_type,category,product_code,"
                            + " sales_product_id,product_name,specification,unit,quantity,sales_unit_price,"
                            + " sales_total,remark FROM sales_outbound_items WHERE sales_outbound_id IN ("
                            + in + ") ORDER BY sales_outbound_id, sequence_no", chunk.toArray());
            for (Map<String, Object> row : rows) {
                long pid = ((Number) row.get("sales_outbound_id")).longValue();
                out.computeIfAbsent(pid, k -> new ArrayList<>()).add(row);
            }
        }
        return out;
    }

    /** 镜像单张出库单（在一个独立事务内）。 */
    private MirrorResult doMirrorOne(Map<String, Object> head, List<Map<String, Object>> lines) {
        ErpActor actor = actors.current();
        String sourceNo = str(head.get("outbound_no"));
        String externalId = str(head.get("external_id"));
        String customerName = str(head.get("customer_name"));
        Long customerId = head.get("customer_id") == null ? null : ((Number) head.get("customer_id")).longValue();
        Long warehouseId = head.get("warehouse_id") == null ? null : ((Number) head.get("warehouse_id")).longValue();
        // ⚠️ employee_id = 本地员工 id（数据范围依据）；salesperson_id 是氚云原 id，不能用作外键。
        Long salespersonId = head.get("employee_id") == null ? null : ((Number) head.get("employee_id")).longValue();
        String salespersonName = str(head.get("salesperson_name"));
        BigDecimal salesAmount = dec(head.get("sales_amount"));
        java.sql.Date outboundDate = date(head.get("business_date"));
        if (outboundDate == null) outboundDate = date(head.get("ship_date"));

        PendingSum pending = evaluatePending(customerName, warehouseId, salespersonId, lines);

        jdbc.update("""
            INSERT INTO erp_sales_outbounds(outbound_no,status,customer_id,customer_name_snapshot,
              salesperson_id,salesperson_name_snapshot,warehouse_id,sales_order_ref,outbound_date,remark,
              total_amount,created_by_employee_id,created_by_name,created_at,
              external_source,external_id,source_outbound_no,source_status_raw,source_warehouse_name,
              source_ship_date,source_sales_amount,source_mirror_pending,source_mirrored_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
            """,
                clip(sourceNo, MAX_OUTBOUND_NO),
                "DRAFT",
                customerId,
                clip(customerName, MAX_NAME),
                salespersonId,
                clip(salespersonName, 100),
                warehouseId,
                null,                                   // 氚云没有明确的销售订单/合同号，不臆造
                outboundDate,
                clip(joinRemark(head), MAX_HEAD_REMARK),
                salesAmount,
                null,                                   // 历史单不是本系统创建的，不冒认创建人
                clip(str(head.get("source_created_by")), 100),
                head.get("source_created_at"),          // 保持历史时序，不写 CURRENT_TIMESTAMP
                "H3YUN",
                externalId,
                clip(sourceNo, MAX_OUTBOUND_NO),
                clip(str(head.get("status")), MAX_STATUS_RAW),
                clip(str(head.get("warehouse")), MAX_WAREHOUSE_NAME),
                date(head.get("ship_date")),
                salesAmount,
                clip(pending.reason(), MAX_PENDING));

        Long outboundId = jdbc.queryForObject(
                "SELECT id FROM erp_sales_outbounds WHERE external_source='H3YUN' AND external_id=?",
                Long.class, externalId);
        if (outboundId == null) throw new IllegalStateException("镜像出库单插入后未能取回 id");

        // 行号：源 sequence_no 实测同单内唯一且无空，直接沿用（保住与氚云台账的行序对应）。
        // 但仍做去重兜底 —— uk_esoi_line(outbound_id, line_no) 是唯一索引，一旦撞号整单插入失败。
        Set<Integer> used = new HashSet<>();
        int next = 1;
        for (Map<String, Object> line : lines) {
            Integer seq = line.get("sequence_no") == null ? null : ((Number) line.get("sequence_no")).intValue();
            int no;
            if (seq != null && seq > 0 && used.add(seq)) {
                no = seq;
            } else {
                while (!used.add(next)) next++;
                no = next;
            }
            next = Math.max(next, no + 1);
            Long productId = line.get("sales_product_id") == null
                    ? null : ((Number) line.get("sales_product_id")).longValue();
            String productName = str(line.get("product_name"));
            jdbc.update("""
                INSERT INTO erp_sales_outbound_items(outbound_id,line_no,line_type,external_id,source_category,
                  sales_product_id,canonical_product_id,product_code_snapshot,product_name_snapshot,
                  specification_snapshot,unit,quantity,unit_price,amount,remark)
                VALUES(?,?,?,?,?,?,(
                    SELECT COALESCE(sp.canonical_product_id, sp.id) FROM sales_products sp WHERE sp.id=?
                  ),?,?,?,?,?,?,?,?)
                """,
                    outboundId, no,
                    clip(defaultIfBlank(str(line.get("line_type")), "PRODUCT"), 20),
                    str(line.get("external_id")),
                    clip(str(line.get("category")), MAX_CATEGORY),
                    productId, productId,
                    clip(str(line.get("product_code")), 100),
                    clip(productName != null ? productName : BLANK_PRODUCT, MAX_PRODUCT_NAME),
                    clip(str(line.get("specification")), MAX_SPEC),
                    clip(str(line.get("unit")), MAX_SPEC_UNIT),
                    dec(line.get("quantity")),
                    dec(line.get("sales_unit_price")),
                    dec(line.get("sales_total")),
                    clip(str(line.get("remark")), MAX_LINE_REMARK));
        }

        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", outboundId, "INSERT",
                ErpChangeLogWriter.changes(
                        "outboundNo", sourceNo, "sourceOutboundNo", sourceNo, "status", "DRAFT",
                        "customerNameSnapshot", customerName, "warehouseId", warehouseId,
                        "salespersonId", salespersonId, "outboundDate", outboundDate,
                        "lineCount", lines.size(), "totalAmount", salesAmount,
                        "pendingReason", pending.reason()),
                null, "氚云销售出库单镜像为 ERP 出库单（草稿）", null, null));

        return new MirrorResult(outboundId, sourceNo, pending.reason() != null, pending.reason());
    }

    /** 待补原因累计器：把同一张单上的多处缺失合并成一条可读原因。 */
    private record PendingSum(String reason) {}

    /**
     * 判定一张单的「待补原因」。
     *
     * <p>纪律：<b>源缺什么就记什么，不造假值、也不阻断镜像</b>。这些缺失是业务事实
     * （氚云的运费行本来就不挂产品、草稿单本来就没有业务员），挡在门外反而看不见问题。
     */
    private PendingSum evaluatePending(String customerName, Long warehouseId, Long salespersonId,
                                       List<Map<String, Object>> lines) {
        List<String> reasons = new ArrayList<>();
        if (customerName == null) reasons.add("氚云未填客户");
        if (warehouseId == null) reasons.add("氚云未填仓库");
        if (salespersonId == null) reasons.add("氚云未填业务员");
        int noProduct = 0, negative = 0, noQty = 0;
        for (Map<String, Object> line : lines) {
            String type = str(line.get("line_type"));
            BigDecimal qty = dec(line.get("quantity"));
            if ("PRODUCT".equals(type) && line.get("sales_product_id") == null) noProduct++;
            if (qty != null && qty.signum() < 0) negative++;
            if (qty == null) noQty++;
        }
        if (noProduct > 0) reasons.add(noProduct + " 行商品未绑产品");
        if (negative > 0) reasons.add(negative + " 行数量为负");
        if (noQty > 0) reasons.add(noQty + " 行数量为空");
        if (reasons.isEmpty()) return new PendingSum(null);
        return new PendingSum(String.join("；", reasons));
    }

    /** 单头备注：氚云「订单备注」+「收款备注」合并；都空则为 null。 */
    private static String joinRemark(Map<String, Object> head) {
        String order = str(head.get("order_remark"));
        String collection = str(head.get("collection_remark"));
        if (order == null) return collection;
        if (collection == null) return order;
        return order + "\n收款：" + collection;
    }

    // ---- 小工具 ----

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String defaultIfBlank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    /** 按字符数截断（ERP 表列宽比源 TEXT 窄时必需，否则插入直接报错）。 */
    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static BigDecimal dec(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.sql.Date date(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d;
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime());
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try {
            return java.sql.Date.valueOf(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
