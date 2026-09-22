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
 * 氚云生产任务单 → ERP 业务工单「全量镜像」（双轨制的生产侧接线）。
 *
 * <h3>为什么要有这个类</h3>
 * 项目里长期并行着两套生产数据，互不相通：
 * <ul>
 *   <li><b>源轨道</b> {@code production_orders}：氚云导入的 <b>5,691 张真实工单</b>，
 *       字段丰富（品类/数量/进度/箱型/模具布局/产线/接单员…），但<b>只读</b>，
 *       只作「氚云导入中心」的台账展示。</li>
 *   <li><b>业务轨道</b> {@code manufacturing_orders}：制造域（报工/完工/收货/检验）
 *       全部能力都挂在这张表上，但只有 1 张测试单。</li>
 * </ul>
 * 后果是：<b>真实的 5,691 张工单在系统里没有任何入口能看到</b>，而能操作的那套流程
 * 底下是空的。本类把源轨道的每一张工单镜像成一张业务工单，让制造域的能力
 * 第一次作用在真实数据上。
 *
 * <h3>双轨纪律（不可违反）</h3>
 * <ul>
 *   <li>镜像<b>只读源表、只写业务表</b>；{@code production_orders} 的内容一个字都不改
 *       （除回写 {@code linked_manufacturing_order_id/no} 这个反向指针）。</li>
 *   <li>镜像<b>不反向同步</b>：业务侧补的 SKU、业务侧的状态流转，绝不回写氚云台账。
 *       下次氚云重导只更新源轨道，业务改动不丢。</li>
 *   <li>幂等靠两重保险：{@code uk_manufacturing_external(external_source,external_id)}
 *       唯一索引 + 「源表左连业务表，只取连不上的」选取条件。</li>
 * </ul>
 *
 * <h3>⭐ 实测得出的关键前提（这个类能成立的原因）</h3>
 * 氚云工单的 {@code product_name} 是<b>品类级</b>（"手动片膜"/"模具"/"LQ260气调机卷膜"…），
 * 而制程解析 {@link ManufacturingTaskTemplateResolver} 正是按品类名精确匹配
 * {@code manufacturing_task_config_items.config_name}。实测
 * <b>5,688/5,688 = 100% 命中</b> —— 所以镜像后工序能完整展开，报工/完工/收货全流程可用。
 *
 * <p>对比：拿 SKU 名去反推品类则完全不可行（4,916 个 SKU 里 74.5% 零命中）。
 * 两条路线的差别就在这里 —— 氚云工单走的是品类，天然对得上。
 *
 * <h3>已知的不完整（这是业务事实，不是缺陷）</h3>
 * 氚云工单没有产品 SKU、没有单位，客户名只有 64% 有值。镜像**不造假值**——
 * 该空的空着，落 {@code source_mirror_pending} 说明待补原因，由业务侧「补数据」补齐。
 */
@Service
public class H3ManufacturingMirrorService {

    /**
     * 氚云产线名 → 本地车间 id。
     *
     * <p>定车间优先靠品类唯一命中（45/47 个品类只在一个车间配置）；只有「模具」「配件」
     * 两个品类同时存在于两个车间（实测 538 张工单），才用产线名消歧。
     */
    private static final Map<String, Long> LINE_TO_WORKSHOP = Map.of(
            "封口机车间", 3L,
            "半自动气调机车间", 2L,
            "全自动气调机车间", 4L,
            "称重机车间", 1L);

    /** 氚云工单里被标记为不需要报工的，不展开工序（实测 180 张）。 */
    private static final String NO_REPORT_MARK = "无需报工";

    /**
     * 氚云确有工单没填产品名（实测 3 张）。业务表的 {@code product_name_snapshot} 是 NOT NULL，
     * 落这个显式占位而不是放宽约束 —— 页面上要能一眼看出「氚云未填」，而不是看到一个空白。
     */
    private static final String H3_BLANK_PRODUCT = "（氚云未填产品名）";

    private final JdbcTemplate jdbc;
    private final ManufacturingTaskTemplateResolver taskTemplateResolver;
    private final ManufacturingExecutionService execution;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;
    private final TransactionTemplate tx;

    public H3ManufacturingMirrorService(JdbcTemplate jdbc,
                                        ManufacturingTaskTemplateResolver taskTemplateResolver,
                                        ManufacturingExecutionService execution,
                                        @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                        ErpActorResolver actors,
                                        PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.taskTemplateResolver = taskTemplateResolver;
        this.execution = execution;
        this.changeLog = changeLog;
        this.actors = actors;
        // 显式构造而不是注入 TransactionTemplate：本项目是双数据源，业务表在主库，
        // 必须钉死用主库事务管理器（@Primary 的那个），不能靠自动配置碰运气。
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 一张工单的镜像结果，用于批次统计。 */
    public record MirrorResult(long manufacturingOrderId, String orderNo, boolean pendingData, String pendingReason) {}

    /** 定车间的结果：{@code pendingReason} 非空表示没能定到车间，工序不展开。 */
    private record WorkshopChoice(Long workshopId, String pendingReason) {}

    /**
     * 全量镜像入口：把尚未镜像的氚云工单逐张转成业务工单。
     *
     * <p>每张工单<b>独立事务</b>——一张失败不影响其余，失败明细随返回值带出。
     * 可反复调用：已镜像的天然被跳过，因此「点几次」和「点一次全量」结果一致。
     *
     * @param limit 本批最多处理多少张（0 = 不限，建议页面上分批点，便于观察）
     * @return 统计：{@code scanned/created/skipped/pendingData/errors}
     */
    public Map<String, Object> mirrorProductionOrders(int limit) {
        List<Map<String, Object>> rows = pendingSourceRows(limit);
        int created = 0, pendingData = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        List<String> pendingSamples = new ArrayList<>();

        for (Map<String, Object> po : rows) {
            String orderNo = str(po.get("work_order_no"));
            try {
                MirrorResult r = tx.execute(s -> doMirrorOne(po));
                if (r == null) { failed++; errors.add(orderNo + ": 事务未返回结果"); continue; }
                created++;
                if (r.pendingData()) {
                    pendingData++;
                    if (pendingSamples.size() < 10) pendingSamples.add(orderNo + " → " + r.pendingReason());
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < 20) errors.add(orderNo + ": " + rootMessage(e));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", rows.size());
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
                "SELECT COUNT(*) FROM production_orders", Long.class));
        out.put("mirrored", jdbc.queryForObject(
                "SELECT COUNT(*) FROM manufacturing_orders WHERE external_source='H3YUN'", Long.class));
        out.put("remaining", remainingSourceCount());
        out.put("pendingData", jdbc.queryForObject(
                "SELECT COUNT(*) FROM manufacturing_orders WHERE external_source='H3YUN'"
                        + " AND source_mirror_pending IS NOT NULL", Long.class));
        out.put("retryable", retryableCount());
        return out;
    }

    /**
     * 重试「已镜像但工序没展开」的工单 —— 补完制程配置后的收口入口。
     *
     * <p><b>为什么必须有这个入口</b>：首次镜像时制程配置可能缺失（实测 633 张落在
     * 「制程未配置」），而镜像的幂等选取条件是「源表左连业务表、只取连不上的」——
     * 已镜像的单<b>永远不会被重扫</b>。所以补完配置（给车间设默认制程 / 给品类绑模板）后，
     * 这 633 张不会自己好起来，会成为死数据。这个入口就是让它们活过来。
     *
     * <p><b>两条安全边界</b>：
     * <ul>
     *   <li>只补工序，<b>不重建工单</b>，也不重写产出清单；</li>
     *   <li>只处理<b>还没有任何工序行</b>的单 —— 已经进入业务流程（报工过）的单，
     *       状态归流程管，重试不得覆盖。</li>
     * </ul>
     */
    public Map<String, Object> retryPendingOperations(int limit) {
        String sql = """
            SELECT po.*, mo.id AS mo_id FROM production_orders po
            JOIN manufacturing_orders mo
              ON mo.external_source='H3YUN' AND mo.external_id=po.external_id
            WHERE mo.source_mirror_pending LIKE '制程未配置%'
              AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations o
                              WHERE o.manufacturing_order_id=mo.id)
            ORDER BY po.application_date, po.id
            """;
        if (limit > 0) sql = sql + " LIMIT " + limit;
        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        int fixed = 0, still = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        List<String> stillSamples = new ArrayList<>();
        for (Map<String, Object> po : rows) {
            String orderNo = str(po.get("work_order_no"));
            long moId = ((Number) po.get("mo_id")).longValue();
            try {
                String remain = tx.execute(s -> doRetryOne(po, moId));
                if (remain == null) fixed++;
                else {
                    still++;
                    if (stillSamples.size() < 10) stillSamples.add(orderNo + " → " + remain);
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < 20) errors.add(orderNo + ": " + rootMessage(e));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", rows.size());
        out.put("fixed", fixed);
        out.put("stillPending", still);
        out.put("failed", failed);
        out.put("stillSamples", stillSamples);
        out.put("errors", errors);
        out.put("retryable", retryableCount());
        return out;
    }

    /**
     * 还有多少张**可能因配置缺失而修复**的单。
     *
     * <p>刻意只统计 {@code 制程未配置%} —— 待补里另有两类**重试也不会变**的：
     * 「氚云标记『无需报工』」（180 张，属业务事实，本就不展开工序）与
     * 「氚云工单没有产品名」（3 张，源里就没有）。把它们算进「可重试」会让按钮上的数字骗人
     * （点完发现只修好一部分）。
     */
    private long retryableCount() {
        Long n = jdbc.queryForObject("""
            SELECT COUNT(*) FROM manufacturing_orders mo
            WHERE mo.external_source='H3YUN' AND mo.source_mirror_pending LIKE '制程未配置%'
              AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations o
                              WHERE o.manufacturing_order_id=mo.id)
            """, Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 重试单张（独立事务）。返回 {@code null} 表示已补全；非 null 表示仍缺的原因。
     *
     * <p>与首次镜像共用 {@code resolveWorkshop} / {@code mapStatus}，保证口径不分叉。
     * {@code doGenerateOperations} 会把状态改成 RELEASED，因此目标状态必须在它之后写回。
     */
    private String doRetryOne(Map<String, Object> po, long moId) {
        ErpActor actor = actors.current();
        String productName = str(po.get("product_name"));
        String h3Status = str(po.get("status"));
        WorkshopChoice choice = resolveWorkshop(productName, str(po.get("production_line")),
                str(po.get("process_name")));
        String pending = choice.pendingReason();
        if (choice.workshopId() != null) {
            try {
                var selection = taskTemplateResolver.resolve(choice.workshopId(), productName);
                execution.doGenerateOperations(moId, selection.templateId(), actor, null);
                jdbc.update("UPDATE manufacturing_orders SET workshop_type=? WHERE id=?",
                        selection.workshopName(), moId);
            } catch (IllegalArgumentException ex) {
                if (pending == null) pending = "制程未配置：" + ex.getMessage();
            }
        }
        jdbc.update("UPDATE manufacturing_orders SET status=?,source_mirror_pending=? WHERE id=?",
                mapStatus(h3Status, str(po.get("production_progress")), str(po.get("warehousing_progress"))),
                pending, moId);
        if (pending == null) {
            changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.manufacturing_orders", moId, "UPDATE",
                    ErpChangeLogWriter.changes("pendingReason", "(已清空)", "workshopId", choice.workshopId()
                            , "note", "补制程配置后重试展开工序成功"),
                    null, "重试展开工序（氚云镜像单）", null, null));
        }
        return pending;
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    /**
     * 选取待镜像的氚云工单。
     *
     * <p>用「左连业务表取连不上的」而不是只看 {@code linked_manufacturing_order_id}——
     * 后者可能在「业务单已建但回写指针失败」的中间态下失真，那时若只信指针会重复建单。
     * 以业务表实际存在与否为准，才是真正幂等。
     */
    private List<Map<String, Object>> pendingSourceRows(int limit) {
        String sql = """
            SELECT po.* FROM production_orders po
            LEFT JOIN manufacturing_orders mo
              ON mo.external_source='H3YUN' AND mo.external_id=po.external_id
            WHERE mo.id IS NULL
            ORDER BY po.application_date, po.id
            """;
        if (limit > 0) sql = sql + " LIMIT " + limit;
        return jdbc.queryForList(sql);
    }

    private long remainingSourceCount() {
        Long n = jdbc.queryForObject("""
            SELECT COUNT(*) FROM production_orders po
            LEFT JOIN manufacturing_orders mo
              ON mo.external_source='H3YUN' AND mo.external_id=po.external_id
            WHERE mo.id IS NULL
            """, Long.class);
        return n == null ? 0L : n;
    }

    /** 镜像单张工单（在一个独立事务内）。 */
    private MirrorResult doMirrorOne(Map<String, Object> po) {
        ErpActor actor = actors.current();
        String productName = str(po.get("product_name"));
        // 入库用显式占位（满足 NOT NULL，且页面可读）；定车间仍用原值（null → 走「没有产品名」分支）。
        String productNameForStorage = productName != null ? productName : H3_BLANK_PRODUCT;
        String h3No = str(po.get("work_order_no"));
        String h3Status = str(po.get("status"));
        String processName = str(po.get("process_name"));
        String productionLine = str(po.get("production_line"));

        BigDecimal planned = dec(po.get("required_quantity"));
        BigDecimal completed = dec(po.get("completed_quantity"));
        BigDecimal warehoused = dec(po.get("warehoused_quantity"));

        WorkshopChoice choice = resolveWorkshop(productName, productionLine, processName);

        String orderNo = (h3No == null || h3No.isBlank()) ? "MO-H3-" + po.get("id") : h3No;

        jdbc.update("""
            INSERT INTO manufacturing_orders(order_no,task_type,external_source,external_id,h3_work_order_no,
              priority,planned_start_date,planned_finish_date,production_team_name,
              responsible_employee_name,sales_order_ref,customer_id,customer_name_snapshot,
              salesperson_id,salesperson_name_snapshot,product_name_snapshot,
              specification_snapshot,tooling_requirement,custom_requirement,
              planned_quantity,completed_quantity,unit,due_date,remark,
              status,created_by_name,
              source_status_raw,source_progress_raw,source_warehoused_quantity,source_mirror_pending)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                orderNo,
                "SALES_ORDER",
                "H3YUN",
                str(po.get("external_id")),
                h3No,
                "NORMAL",
                date(po.get("application_date")),
                date(po.get("expected_completion_date")),
                processName,
                str(po.get("order_receiver")),
                str(po.get("linked_sales_order_no")),
                po.get("customer_id"),
                str(po.get("customer_name")),
                po.get("employee_id") != null ? po.get("employee_id") : po.get("salesperson_id"),
                str(po.get("salesperson_name")),
                productNameForStorage,
                str(po.get("box_size")),
                str(po.get("mold_layout")),
                joinText(str(po.get("production_requirement")), str(po.get("optional_configuration"))),
                planned,
                completed,
                null,                                   // 单位：氚云工单没有，待补
                date(po.get("required_completion_date")),
                str(po.get("plan_type")),
                mapStatus(h3Status, str(po.get("production_progress")), str(po.get("warehousing_progress"))),
                str(po.get("source_created_by")),
                h3Status,
                str(po.get("production_progress")),
                warehoused,
                choice.pendingReason());

        Long orderId = jdbc.queryForObject(
                "SELECT id FROM manufacturing_orders WHERE external_source='H3YUN' AND external_id=?",
                Long.class, str(po.get("external_id")));
        if (orderId == null) throw new IllegalStateException("镜像工单插入后未能取回 id");

        // 产出清单：主产品行。SKU 留空待补（氚云工单没有 SKU，这是既定事实）。
        jdbc.update("""
            INSERT INTO manufacturing_order_outputs(manufacturing_order_id,line_no,is_primary,sales_product_id,
              product_code_snapshot,product_name_snapshot,specification_snapshot,unit,
              planned_quantity,is_mold,remark)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,
                orderId, 1, 1, null,
                null, productNameForStorage, str(po.get("box_size")), null,
                planned, "模具".equals(productName) ? 1 : 0,
                "氚云工单镜像，产品身份待补");

        // 展开工序（定到车间才做）。
        //
        // ⚠️ 制程配置缺失**不能阻断镜像**（实测踩到）：生产环境里 2 号（半自动气调机）与
        // 4 号（全自动气调机）车间的 default_process_template_id 是 NULL，部分品类的
        // process_template_id 也没绑 → resolve 会抛「产品未关联制程，且车间未设置默认制程」。
        // 最初让异常冒泡，结果整个事务回滚、工单进不来、下一批又重试再失败（每批上百条）。
        // 正确姿势是「单据照进、工序留空、落待补原因」—— 让人在业务侧补配置，
        // 而不是把源数据挡在门外（那违背镜像的初衷）。
        //
        // 注意 doGenerateOperations 会把 status 改成 RELEASED，因此目标状态必须在它之后写回。
        String pending = choice.pendingReason();
        if (choice.workshopId() != null) {
            try {
                var selection = taskTemplateResolver.resolve(choice.workshopId(), productName);
                execution.doGenerateOperations(orderId, selection.templateId(), actor, null);
                jdbc.update("UPDATE manufacturing_orders SET workshop_type=? WHERE id=?",
                        selection.workshopName(), orderId);
            } catch (IllegalArgumentException ex) {
                if (pending == null) pending = "制程未配置：" + ex.getMessage();
            }
        }
        jdbc.update("UPDATE manufacturing_orders SET status=?,source_mirror_pending=? WHERE id=?",
                mapStatus(h3Status, str(po.get("production_progress")), str(po.get("warehousing_progress"))),
                pending, orderId);

        // 反向指针（只写这一处，源表其余内容不动）
        jdbc.update("UPDATE production_orders SET linked_manufacturing_order_id=?,linked_manufacturing_order_no=?"
                        + " WHERE id=?",
                orderId, orderNo, po.get("id"));

        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.manufacturing_orders", orderId, "INSERT",
                ErpChangeLogWriter.changes(
                        "orderNo", orderNo, "h3WorkOrderNo", h3No, "productNameSnapshot", productName,
                        "plannedQuantity", planned, "workshopId", choice.workshopId(),
                        "sourceStatusRaw", h3Status, "pendingReason", pending),
                null, "氚云生产任务单镜像为业务工单", null, null));

        return new MirrorResult(orderId, orderNo, pending != null, pending);
    }

    /**
     * 定车间：品类唯一命中优先，多命中用产线名消歧，零命中落待补。
     *
     * <p>实测分布：45 个品类只在一个车间配置、2 个（模具/配件）在两处；
     * 按工单算是 5,150 张唯一命中 / 538 张需消歧。
     */
    private WorkshopChoice resolveWorkshop(String productName, String productionLine, String processName) {
        if (processName != null && processName.contains(NO_REPORT_MARK)) {
            return new WorkshopChoice(null, "氚云标记「无需报工」，不展开工序");
        }
        if (productName == null || productName.isBlank()) {
            return new WorkshopChoice(null, "氚云工单没有产品名");
        }
        List<Long> candidates = jdbc.queryForList("""
            SELECT DISTINCT workshop_id FROM manufacturing_task_config_items
            WHERE config_type='PRODUCT_NAME' AND active=1 AND config_name=?
            """, Long.class, productName);
        if (candidates.isEmpty()) {
            return new WorkshopChoice(null, "品类「" + productName + "」未配置制程");
        }
        if (candidates.size() == 1) {
            return new WorkshopChoice(candidates.get(0), null);
        }
        Long byLine = productionLine == null ? null : LINE_TO_WORKSHOP.get(productionLine.trim());
        if (byLine != null && candidates.contains(byLine)) {
            return new WorkshopChoice(byLine, null);
        }
        Long first = candidates.get(0);
        return new WorkshopChoice(first,
                "品类「" + productName + "」在多个车间配置，暂取车间 " + first + "（需人工确认）");
    }

    /**
     * 氚云状态 → 业务状态。
     *
     * <p>业务侧状态机取值（前端有中文映射）：DRAFT / RELEASED / IN_PRODUCTION /
     * PENDING_RECEIPT / PARTIALLY_RECEIVED / COMPLETED / REWORK / CANCELLED。
     */
    private String mapStatus(String h3Status, String productionProgress, String warehousingProgress) {
        String s = h3Status == null ? "" : h3Status.trim();
        if (s.contains("草稿")) return "DRAFT";
        if (s.contains("已取消")) return "CANCELLED";
        if (s.contains("进行中")) return "IN_PRODUCTION";
        boolean prodDone = percentAtLeast(productionProgress, 100);
        boolean whDone = percentAtLeast(warehousingProgress, 100);
        if (prodDone && whDone) return "COMPLETED";
        if (prodDone) return "PENDING_RECEIPT";
        return "RELEASED";
    }

    /** {@code "100.00%"} → {@code >= threshold}；解析不出来一律当作未达标。 */
    private boolean percentAtLeast(String raw, double threshold) {
        if (raw == null) return false;
        String t = raw.trim().replace("%", "");
        try {
            return Double.parseDouble(t) >= threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- 小工具 ----

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
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

    private static String joinText(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "\n" + b;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
