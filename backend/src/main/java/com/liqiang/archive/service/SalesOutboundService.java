package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ERP 正式销售出库单（P2：第一张 ERP 自建业务单据）。
 *
 * <p><b>它解决什么</b>：LQ 的业务主线是「有货的产品业务员直接销售出库，没有现货的先下生产
 * 任务」。这条链此前断在「ERP 没有自己的出库单」——能出的只有氚云台账（只读历史）。
 * 本类是 ERP 侧第一个**能录单、能扣库存、能追溯**的销售动作。
 *
 * <p><b>三件事必须原子</b>：出库单写入、库存余额扣减、库存流水。三者同库同事务——
 * 故本类绑 {@code archiveIdempotencyService} / {@code archiveAuditWriter}（主库绑定，
 * 幂等表以 {@code erp.idem_request} 限定名走同一条连接）。**选错绑定不报错、只静默失效**
 * （见 {@code ErpContractConfig} 的绑定纪律）。
 *
 * <p><b>状态词表</b>（双轨制之后，一张表里同时容两类单）：
 * <ul>
 *   <li><b>库存态（只属于 ERP 自建单）</b>：{@code DRAFT}（草稿，不动库存）
 *       → {@code POSTED}（已过账，扣库存 + 写流水）→ {@code REVERSED}（已冲销，回补库存，终态）。
 *       不提供「反冲销」——冲销后要重发就新建一张，避免单号复用与流水口径歧义。</li>
 *   <li><b>非库存态（氚云镜像单的原状态）</b>：{@code CONFIRMED}（已生效）/ {@code PROCESSING}（进行中）/
 *       {@code CANCELLED}（已取消）/ 源草稿的 {@code DRAFT}。它们如实反映氚云里的状态，
 *       <b>不做任何库存动作、也不代表已过账</b>。词表直接复用台账侧的归一器
 *       （{@code SalesOutboundSettlementService.normalizeOutboundStatus}），列注释见 V326。</li>
 * </ul>
 * ⚠️ <b>镜像单不参与过账</b>（{@link #doPost} 显式拒绝）：历史单扣的是当前库存，
 * 两者关系尚未定案（待商榷），宁可显式拒绝也不顺手扣。
 *
 * <p><b>库存口径</b>：过账按 {@code canonical_product_id} 扣减（#28 Q1）——
 * 这样 {@code MFG-<工单号>} 产出的货与标准 SKU 的分录累加在同一余额行，业务员看到的
 * 是「这件货总共还有多少」，而不是「猜哪个单号还有货」。
 */
@Service
public class SalesOutboundService {

    private static final String ACTION_CREATE = "sales.outbound.create";
    private static final String ACTION_UPDATE = "sales.outbound.update";
    private static final String ACTION_POST = "sales.outbound.post";
    private static final String ACTION_REVERSE = "sales.outbound.reverse";

    /** 库存流水来源标识：正向出库 / 冲销回补。 */
    private static final String SOURCE_OUTBOUND = "ERP_SALES_OUTBOUND";
    private static final String SOURCE_REVERSAL = "ERP_SALES_OUTBOUND_REVERSAL";

    /** 单行最大行数：一张出库单手录明细的现实上限，防前端异常提交。 */
    private static final int MAX_LINES = 200;

    /**
     * <p><b>本类不写 {@code audit_logs}（勿加回 {@code AuditLogService}）</b>：
     * 审计统一走 {@link ErpChangeLogWriter}，身份 = {@code target_table}
     * （{@code product_archive.erp_sales_outbounds}，schema 限定表名 = 物理唯一）。
     *
     * <p>为什么不用 {@code audit_logs}：它的身份字段 {@code entity_type} 是**自由字符串**，
     * 一个字段要同时表达「哪张表」和「什么业务对象」——两个模块对同名概念用同一个词就会撞，
     * 且撞了**不报错**。2026-09-21 实测该表 48 个 {@code entity_type} 里确有真撞名：
     * {@code SalesOutbound} 早被氚云侧治理动作占用（{@code H3_OUTBOUND_RELATION_AUTO} /
     * {@code HISTORICAL_ORGANIZATION_ASSIGN}，660 行，{@code entity_id} 是台账行 id 量级 10 万+），
     * 而 ERP 自建出库单的 id 从 1 起 → 同一实体名下两套 id 空间。
     *
     * <p>当时靠改名为 {@code ErpSalesOutbound} 规避，但**改名只是治标**：根因是同一动作
     * 写了两份审计（强身份 {@code row_change_log} + 弱身份 {@code audit_logs}）。
     * 正解是**不写后者**。详见 {@code docs/审计实体身份治理与撞名防治-20260921.md}。
     */
    private final JdbcTemplate jdbc;
    private final ProductInventoryService productInventory;
    private final CurrentUserService users;
    private final DataScopeSupport scope;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;

    public SalesOutboundService(JdbcTemplate jdbc,
                                ProductInventoryService productInventory,
                                CurrentUserService users,
                                DataScopeSupport scope,
                                @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                                @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                ErpActorResolver actors) {
        this.jdbc = jdbc;
        this.productInventory = productInventory;
        this.users = users;
        this.scope = scope;
        this.idem = idem;
        this.changeLog = changeLog;
        this.actors = actors;
    }

    /** 出库明细行入参：产品身份 + 数量（+ 可选单价/备注）。 */
    public record OutboundLine(Long productId, BigDecimal quantity, BigDecimal unitPrice, String remark) {}

    /** 建单/改单入参。 */
    public record OutboundRequest(Long customerId, String customerName, Long warehouseId, String salesOrderRef,
                                  LocalDate outboundDate, String remark, List<OutboundLine> lines) {}

    // ==================== 读 ====================

    public List<Map<String, Object>> warehouses() {
        return jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName "
                + "FROM warehouses WHERE active=1 ORDER BY id");
    }

    /** 可售库存（canonical 口径，数量&gt;0）：业务员在此选货；选不到就是「该下生产任务」的信号。 */
    public List<Map<String, Object>> availableStock(String keyword, Long warehouseId, int limit) {
        return productInventory.availableStock(keyword, warehouseId, limit);
    }

    /**
     * 出库单列表（行级数据范围按业务员）。
     *
     * <p>数据范围复用 {@link FeatureCode#SALES_OUTBOUND}——与制造域
     * {@code FeatureCode.MANUFACTURING} 同款机制，不另造一套。
     *
     * <p>{@code source} 是**双轨制**留下的区分维度：{@code H3YUN}=氚云镜像进来的历史单，
     * {@code LOCAL}=ERP 自建单（{@code external_source IS NULL}）。不传则两者混排——
     * 这是刻意的：用户要在一张列表里同时看到「历史怎么发生的」和「新单怎么办」。
     *
     * @param pendingOnly 只看有客观缺失、待业务侧补齐的单（氚云镜像单才有此标记）
     */
    public Map<String, Object> list(String keyword, String status, Long warehouseId, Long customerId,
                                    String dateFrom, String dateTo, int page, int size,
                                    String source, Boolean pendingOnly) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页范围无效");
        var where = new StringBuilder(" WHERE 1=1");
        var params = new ArrayList<>();
        scope.appendByEmployeeId(where, params, "o.salesperson_id", "e.department", FeatureCode.SALES_OUTBOUND);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.outbound_no LIKE ? OR o.customer_name_snapshot LIKE ? "
                    + "OR o.sales_order_ref LIKE ? OR EXISTS (SELECT 1 FROM erp_sales_outbound_items i "
                    + "WHERE i.outbound_id=o.id AND i.product_name_snapshot LIKE ?))");
            String k = "%" + keyword.trim() + "%";
            for (int i = 0; i < 4; i++) params.add(k);
        }
        if (status != null && !status.isBlank()) { where.append(" AND o.status=?"); params.add(status.trim()); }
        if (warehouseId != null) { where.append(" AND o.warehouse_id=?"); params.add(warehouseId); }
        if (customerId != null) { where.append(" AND o.customer_id=?"); params.add(customerId); }
        if (dateFrom != null && !dateFrom.isBlank()) { where.append(" AND o.outbound_date>=?"); params.add(dateFrom.trim()); }
        if (dateTo != null && !dateTo.isBlank()) { where.append(" AND o.outbound_date<=?"); params.add(dateTo.trim()); }
        if ("H3YUN".equals(source)) where.append(" AND o.external_source='H3YUN'");
        else if ("LOCAL".equals(source)) where.append(" AND o.external_source IS NULL");
        if (Boolean.TRUE.equals(pendingOnly)) where.append(" AND o.source_mirror_pending IS NOT NULL");

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sales_outbounds o "
                + "LEFT JOIN employees e ON e.id=o.salesperson_id" + where, Long.class, params.toArray());
        params.add(size);
        params.add((page - 1) * size);
        var items = jdbc.queryForList(
                "SELECT o.id,o.outbound_no outboundNo,o.status,o.customer_id customerId,"
                        + " o.customer_name_snapshot customerName,o.salesperson_name_snapshot salespersonName,"
                        + " o.warehouse_id warehouseId,w.warehouse_name warehouseName,"
                        + " o.sales_order_ref salesOrderRef,o.outbound_date outboundDate,o.total_amount totalAmount,"
                        + " o.remark,o.posted_at postedAt,o.reversed_at reversedAt,o.reversal_reason reversalReason,"
                        + " o.created_by_name createdByName,o.created_at createdAt,"
                        + " o.external_source externalSource,o.source_outbound_no sourceOutboundNo,"
                        + " o.source_status_raw sourceStatusRaw,o.source_mirror_pending sourceMirrorPending,"
                        + " (SELECT COUNT(*) FROM erp_sales_outbound_items i WHERE i.outbound_id=o.id) itemCount"
                        + " FROM erp_sales_outbounds o"
                        + " LEFT JOIN employees e ON e.id=o.salesperson_id"
                        + " LEFT JOIN warehouses w ON w.id=o.warehouse_id" + where
                        + " ORDER BY o.id DESC LIMIT ? OFFSET ?", params.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0L : total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    /**
     * 出库单详情（含明细）。无权限或不存在统一抛「不存在或无权查看」，不泄露单号是否存在。
     *
     * <p>明细里的 {@code line_type} 与来源列是**双轨制的必要信息**：氚云镜像单的明细含运费/服务费行
     * （没有 SKU、没有单位），且可能带待补原因。不返回这些字段，前端就无法区分
     * 「这行是缺数据」还是「这行本来就不是商品」。
     */
    public Map<String, Object> detail(long id) {
        requireVisible(id);
        var head = jdbc.queryForList(
                "SELECT o.id,o.outbound_no outboundNo,o.status,o.customer_id customerId,"
                        + " o.customer_name_snapshot customerName,o.salesperson_id salespersonId,"
                        + " o.salesperson_name_snapshot salespersonName,o.warehouse_id warehouseId,"
                        + " w.warehouse_name warehouseName,o.sales_order_ref salesOrderRef,"
                        + " o.outbound_date outboundDate,o.total_amount totalAmount,o.remark,"
                        + " o.posted_at postedAt,o.posted_by_name postedByName,"
                        + " o.reversed_at reversedAt,o.reversed_by_name reversedByName,"
                        + " o.reversal_reason reversalReason,o.created_by_name createdByName,o.created_at createdAt,"
                        + " o.external_source externalSource,o.source_outbound_no sourceOutboundNo,"
                        + " o.source_status_raw sourceStatusRaw,o.source_warehouse_name sourceWarehouseName,"
                        + " o.source_ship_date sourceShipDate,o.source_sales_amount sourceSalesAmount,"
                        + " o.source_mirror_pending sourceMirrorPending"
                        + " FROM erp_sales_outbounds o LEFT JOIN warehouses w ON w.id=o.warehouse_id WHERE o.id=?",
                id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("销售出库单不存在或无权查看"));
        var items = jdbc.queryForList(
                "SELECT id,line_no lineNo,line_type lineType,external_id externalId,source_category sourceCategory,"
                        + " sales_product_id salesProductId,canonical_product_id canonicalProductId,"
                        + " product_code_snapshot productCode,product_name_snapshot productName,"
                        + " specification_snapshot specification,unit,quantity,unit_price unitPrice,amount,remark"
                        + " FROM erp_sales_outbound_items WHERE outbound_id=? ORDER BY line_no", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", head);
        result.put("items", items);
        return result;
    }

    /** 行级可见性校验（数据范围）：不可见与不存在返回同一句提示。 */
    public void requireVisible(long id) {
        var where = new StringBuilder(" WHERE o.id=?");
        var params = new ArrayList<>();
        params.add(id);
        scope.appendByEmployeeId(where, params, "o.salesperson_id", "e.department", FeatureCode.SALES_OUTBOUND);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sales_outbounds o "
                + "LEFT JOIN employees e ON e.id=o.salesperson_id" + where, Long.class, params.toArray());
        if (n == null || n == 0) throw new IllegalArgumentException("销售出库单不存在或无权查看");
    }

    // ==================== 写（M0 契约：幂等 + 行级审计） ====================

    /** 建单（草稿）：不碰库存。 */
    public long create(OutboundRequest request, String requestKey) {
        ErpActor actor = actors.current();
        Map<String, Object> payload = ErpChangeLogWriter.changes(
                "customerId", request.customerId(), "customerName", clean(request.customerName()),
                "warehouseId", request.warehouseId(), "outboundDate", request.outboundDate(),
                "salesOrderRef", clean(request.salesOrderRef()), "lines", describeLines(request.lines()));
        return Long.parseLong(idem.execute(actor, requestKey, ACTION_CREATE, payload,
                () -> String.valueOf(doCreate(request, actor, requestKey))));
    }

    /** 改单：仅草稿可改，整体替换明细（避免逐行 diff 的复杂度与漏改）。 */
    public long update(long id, OutboundRequest request, String requestKey) {
        ErpActor actor = actors.current();
        Map<String, Object> payload = ErpChangeLogWriter.changes(
                "id", id, "customerId", request.customerId(), "customerName", clean(request.customerName()),
                "warehouseId", request.warehouseId(), "outboundDate", request.outboundDate(),
                "salesOrderRef", clean(request.salesOrderRef()), "lines", describeLines(request.lines()));
        return Long.parseLong(idem.execute(actor, requestKey, ACTION_UPDATE, payload,
                () -> String.valueOf(doUpdate(id, request, actor, requestKey))));
    }

    /** 过账：扣库存 + 写流水。库存不足整体回滚（不会出现「单过了、货没扣」）。 */
    public void post(long id, String requestKey) {
        ErpActor actor = actors.current();
        Map<String, Object> payload = ErpChangeLogWriter.changes("id", id);
        idem.execute(actor, requestKey, ACTION_POST, payload,
                () -> { doPost(id, actor, requestKey); return String.valueOf(id); });
    }

    /** 冲销：回补库存 + 写反向流水。终态，不可再冲销。 */
    public void reverse(long id, String reason, String requestKey) {
        ErpActor actor = actors.current();
        Map<String, Object> payload = ErpChangeLogWriter.changes("id", id, "reason", clean(reason));
        idem.execute(actor, requestKey, ACTION_REVERSE, payload,
                () -> { doReverse(id, reason, actor, requestKey); return String.valueOf(id); });
    }

    // ==================== 写实现 ====================

    private long doCreate(OutboundRequest request, ErpActor actor, String requestKey) {
        String customer = required(request.customerName(), "客户名称", 300);
        Long warehouseId = requireWarehouse(request.warehouseId());
        var lines = validateLines(request.lines());
        LocalDate date = request.outboundDate() == null ? LocalDate.now() : request.outboundDate();
        var user = users.current();
        String tmp = "PENDING-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO erp_sales_outbounds(outbound_no,status,customer_id,customer_name_snapshot,
                  salesperson_id,salesperson_name_snapshot,warehouse_id,sales_order_ref,outbound_date,remark,
                  total_amount,created_by_employee_id,created_by_name)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tmp, "DRAFT", request.customerId(), customer, user.employeeId(), user.name(),
                warehouseId, clean(request.salesOrderRef()), date, clean(request.remark()),
                totalOf(lines), user.employeeId(), user.name());
        long id = jdbc.queryForObject("SELECT id FROM erp_sales_outbounds WHERE outbound_no=?", Long.class, tmp);
        String no = "XSCK" + String.format("%08d", id);
        jdbc.update("UPDATE erp_sales_outbounds SET outbound_no=? WHERE id=?", no, id);
        writeLines(id, lines);
        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", id, "INSERT",
                ErpChangeLogWriter.changes("outboundNo", no, "status", "DRAFT", "customerNameSnapshot", customer,
                        "warehouseId", warehouseId, "outboundDate", date, "lineCount", lines.size(),
                        "totalAmount", totalOf(lines)),
                null, "创建销售出库单", requestKey, null));
        return id;
    }

    private long doUpdate(long id, OutboundRequest request, ErpActor actor, String requestKey) {
        var head = lockHead(id);
        if (!"DRAFT".equals(String.valueOf(head.get("status"))))
            throw new IllegalStateException("只有草稿状态的销售出库单可以修改");
        String customer = required(request.customerName(), "客户名称", 300);
        Long warehouseId = requireWarehouse(request.warehouseId());
        var lines = validateLines(request.lines());
        LocalDate date = request.outboundDate() == null ? LocalDate.now() : request.outboundDate();
        jdbc.update("UPDATE erp_sales_outbounds SET customer_id=?,customer_name_snapshot=?,warehouse_id=?,"
                        + "sales_order_ref=?,outbound_date=?,remark=?,total_amount=? WHERE id=?",
                request.customerId(), customer, warehouseId, clean(request.salesOrderRef()), date,
                clean(request.remark()), totalOf(lines), id);
        jdbc.update("DELETE FROM erp_sales_outbound_items WHERE outbound_id=?", id);
        writeLines(id, lines);
        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", id, "UPDATE",
                ErpChangeLogWriter.changes("status", "DRAFT", "customerNameSnapshot", customer,
                        "warehouseId", warehouseId, "outboundDate", date, "lineCount", lines.size(),
                        "totalAmount", totalOf(lines)),
                null, "修改销售出库单", requestKey, null));
        return id;
    }

    /**
     * 过账实现：逐行扣库存。
     *
     * <p>扣减前先做一次**可读性预检**，把「哪个产品在哪个仓差多少」讲清楚——直接靠
     * {@code post()} 抛「统一产品库存不足」业务员看不出是哪一行的问题。
     * 预检不加锁，真正的并发正确性由 {@code post()} 内的 {@code SELECT … FOR UPDATE} 保证。
     */
    private void doPost(long id, ErpActor actor, String requestKey) {
        var head = lockHead(id);
        String status = String.valueOf(head.get("status"));
        if (!"DRAFT".equals(status))
            throw new IllegalStateException("REVERSED".equals(status) ? "已冲销的出库单不能重新过账，请新建一张"
                    : "该出库单已过账，不能重复过账");
        // ⭐ 氚云镜像的历史单**不参与过账**（LQ 2026-09-22 口径）：
        // 过账的语义是「扣减**当前**库存」，而这批单是 2025-11~2026-09 已发生的事实。
        // 两者的关系（历史单要不要补扣库存）属于**另一套设计，待商榷** ——
        // 在此之前宁可显式拒绝，也不能让镜像动作顺手扣掉当前库存。
        // 前端也不会给镜像单出过账按钮，这里挡的是绕过 UI 的直接调用。
        if ("H3YUN".equals(head.get("externalSource")))
            throw new IllegalStateException("这是氚云镜像进来的历史出库单，不参与过账（它只如实反映氚云原状态）。"
                    + "如需按当前库存出库，请新建一张出库单。");
        long warehouseId = requireWarehouseForPost(head);
        String no = String.valueOf(head.get("outboundNo"));
        var lines = jdbc.queryForList("SELECT id,line_no lineNo,sales_product_id salesProductId,"
                + " product_name_snapshot productName,specification_snapshot specification,unit,quantity,remark"
                + " FROM erp_sales_outbound_items WHERE outbound_id=? ORDER BY line_no", id);
        if (lines.isEmpty()) throw new IllegalStateException("出库单没有明细，不能过账");

        assertLinesDeductible(id);

        String warehouseName = jdbc.queryForObject("SELECT warehouse_name FROM warehouses WHERE id=?", String.class, warehouseId);
        for (var line : lines) {
            BigDecimal qty = (BigDecimal) line.get("quantity");
            BigDecimal available = availableOf(warehouseId, ((Number) line.get("salesProductId")).longValue(), String.valueOf(line.get("unit")));
            if (available.compareTo(qty) < 0)
                throw new IllegalStateException("产品「" + line.get("productName") + "」在" + warehouseName
                        + "库存不足：可用 " + strip(available) + "，本次出库 " + strip(qty) + " " + line.get("unit"));
        }
        var user = users.current();
        LocalDateTime at = LocalDateTime.now();
        for (var line : lines) {
            productInventory.post(warehouseId, ((Number) line.get("salesProductId")).longValue(), null,
                    String.valueOf(line.get("productName")), (String) line.get("specification"),
                    String.valueOf(line.get("unit")), "SALES_OUTBOUND", ((BigDecimal) line.get("quantity")).negate(),
                    SOURCE_OUTBOUND, id, no, at, (String) line.get("remark"));
        }
        jdbc.update("UPDATE erp_sales_outbounds SET status='POSTED',posted_at=?,posted_by_employee_id=?,"
                + "posted_by_name=? WHERE id=?", at, user.employeeId(), user.name(), id);
        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", id, "UPDATE",
                ErpChangeLogWriter.changes("status", "DRAFT→POSTED", "postedAt", at, "warehouseId", warehouseId,
                        "lineCount", lines.size()),
                null, "销售出库过账", requestKey, null));
    }

    private void doReverse(long id, String reason, ErpActor actor, String requestKey) {
        String r = clean(reason);
        if (r == null || r.isBlank()) throw new IllegalArgumentException("请填写冲销原因");
        if (r.length() > 500) throw new IllegalArgumentException("冲销原因不能超过500字");
        var head = lockHead(id);
        if (!"POSTED".equals(String.valueOf(head.get("status"))))
            throw new IllegalStateException("只有已过账的销售出库单可以冲销");
        long warehouseId = requireWarehouseForPost(head);
        String no = String.valueOf(head.get("outboundNo"));
        var lines = jdbc.queryForList("SELECT line_no lineNo,sales_product_id salesProductId,"
                + " product_name_snapshot productName,specification_snapshot specification,unit,quantity"
                + " FROM erp_sales_outbound_items WHERE outbound_id=? ORDER BY line_no", id);
        var user = users.current();
        LocalDateTime at = LocalDateTime.now();
        for (var line : lines) {
            productInventory.post(warehouseId, ((Number) line.get("salesProductId")).longValue(), null,
                    String.valueOf(line.get("productName")), (String) line.get("specification"),
                    String.valueOf(line.get("unit")), "SALES_OUTBOUND_REVERSAL", (BigDecimal) line.get("quantity"),
                    SOURCE_REVERSAL, id, no, at, r);
        }
        jdbc.update("UPDATE erp_sales_outbounds SET status='REVERSED',reversed_at=?,reversed_by_employee_id=?,"
                        + "reversed_by_name=?,reversal_reason=? WHERE id=?",
                at, user.employeeId(), user.name(), r, id);
        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", id, "UPDATE",
                ErpChangeLogWriter.changes("status", "POSTED→REVERSED", "reversalReason", r, "reversedAt", at),
                "REVERSAL", r, requestKey, null));
    }

    // ==================== 内部工具 ====================

    /**
     * 过账前的「明细可扣减性」预检。
     *
     * <p>V324 / V325 放开了业务表明细的若干 NOT NULL（为了让氚云源数据如实镜像进来：
     * 运费/服务费行没有产品身份、57 行没有数量、4,295 行没有单位……）。
     * 代价是**原来安全的解箱写法会 NPE**——业务员只会看到「系统错误」，不知道哪一行缺什么。
     *
     * <p>所以把校验集中在这里：**一行不能扣，整单就拒绝过账**，并把每一行的缺项讲清楚。
     * 这也是双轨制的必然要求——历史镜像单本来就带着客观缺失进来，
     * 能不能过账要在业务动作发生前回答，而不是在扣库存的半路上炸掉。
     */
    private void assertLinesDeductible(long id) {
        var bad = jdbc.queryForList(
                "SELECT line_no lineNo,line_type lineType,product_name_snapshot productName,"
                        + " canonical_product_id canonicalProductId,unit,quantity"
                        + " FROM erp_sales_outbound_items WHERE outbound_id=? AND ("
                        + "   canonical_product_id IS NULL OR quantity IS NULL OR quantity <= 0 OR unit IS NULL"
                        + " ) ORDER BY line_no LIMIT 5", id);
        if (bad.isEmpty()) return;
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sales_outbound_items WHERE outbound_id=? AND ("
                        + " canonical_product_id IS NULL OR quantity IS NULL OR quantity <= 0 OR unit IS NULL)",
                Long.class, id);
        StringBuilder b = new StringBuilder("以下明细无法扣减库存，不能过账");
        if (total != null && total > 5) b.append("（共 ").append(total).append(" 行，列出前 5 行）");
        b.append("：");
        for (var r : bad) {
            Object pn = r.get("productName");
            b.append("\n第 ").append(r.get("lineNo")).append(" 行「")
                    .append(pn == null ? "无名称" : pn).append("」");
            List<String> why = new ArrayList<>();
            if (r.get("canonicalProductId") == null) why.add("没有产品身份");
            if (r.get("unit") == null) why.add("没有单位");
            if (r.get("quantity") == null) why.add("没有数量");
            else if (((BigDecimal) r.get("quantity")).signum() <= 0)
                why.add("数量 " + strip((BigDecimal) r.get("quantity")) + " 不是正数");
            b.append("：").append(String.join("、", why));
        }
        b.append("\n请在编辑本单时补齐或删除这些行，再过账。");
        throw new IllegalStateException(b.toString());
    }

    /**
     * 取单头仓库并保证非空。
     *
     * <p>{@code warehouse_id} 在 V324 之前是 NOT NULL，解箱写法是安全的；放开之后
     * 氚云镜像单有 2 张没有仓库，解箱会直接 NPE（业务员只看到「系统错误」，不知道是缺仓库）。
     * 所以统一走这里，把「缺仓库」变成一句能读懂的话。
     */
    private long requireWarehouseForPost(Map<String, Object> head) {
        Object whId = head.get("warehouseId");
        if (whId == null)
            throw new IllegalStateException("该出库单没有发货仓库，不能过账。请先编辑补齐仓库。");
        return ((Number) whId).longValue();
    }

    /** 锁定并读取单头（并发下过账/冲销串行化）。字段名统一转 camelCase 以便下游按名取值。 */
    private Map<String, Object> lockHead(long id) {
        var rows = jdbc.queryForList("SELECT id,outbound_no outboundNo,status,warehouse_id warehouseId,"
                + "external_source externalSource "
                + "FROM erp_sales_outbounds WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("销售出库单不存在");
        return rows.getFirst();
    }

    /** 校验并规整明细；返回带快照的完整行（快照在建单时固定，不随主数据改名而变）。 */    private List<Map<String, Object>> validateLines(List<OutboundLine> input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("请至少添加一行出库明细");
        if (input.size() > MAX_LINES) throw new IllegalArgumentException("出库明细不能超过 " + MAX_LINES + " 行");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (OutboundLine line : input) {
            if (line == null || line.productId() == null) throw new IllegalArgumentException("出库明细的产品不能为空");
            BigDecimal qty = line.quantity();
            if (qty == null || qty.signum() <= 0 || qty.scale() > 6)
                throw new IllegalArgumentException("出库数量必须大于0且最多6位小数");
            if (!seen.add(line.productId()))
                throw new IllegalArgumentException("同一产品在明细中重复出现，请合并为一行");
            var product = jdbc.queryForList("SELECT id,product_code productCode,product_name productName,"
                            + " specification,unit FROM sales_products WHERE id=? AND deleted_at IS NULL", line.productId())
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("出库明细的产品不存在：" + line.productId()));
            BigDecimal price = line.unitPrice();
            if (price != null && (price.signum() < 0 || price.scale() > 4))
                throw new IllegalArgumentException("单价必须≥0且最多4位小数");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", product.get("id"));
            row.put("productCode", product.get("productCode"));
            row.put("productName", product.get("productName"));
            row.put("specification", product.get("specification"));
            row.put("unit", product.get("unit"));
            row.put("quantity", qty);
            row.put("unitPrice", price);
            row.put("remark", clean(line.remark()));
            result.add(row);
        }
        return result;
    }

    private void writeLines(long outboundId, List<Map<String, Object>> lines) {
        int lineNo = 1;
        for (var row : lines) {
            BigDecimal qty = (BigDecimal) row.get("quantity");
            BigDecimal price = (BigDecimal) row.get("unitPrice");
            BigDecimal amount = price == null ? null : price.multiply(qty).setScale(4, java.math.RoundingMode.HALF_UP);
            jdbc.update("INSERT INTO erp_sales_outbound_items(outbound_id,line_no,sales_product_id,"
                            + "canonical_product_id,product_code_snapshot,product_name_snapshot,specification_snapshot,"
                            + "unit,quantity,unit_price,amount,remark)"
                            + " VALUES(?,?,?,(SELECT COALESCE(canonical_product_id,id) FROM sales_products WHERE id=?),?,?,?,?,?,?,?,?)",
                    outboundId, lineNo++, row.get("productId"), row.get("productId"), row.get("productCode"),
                    row.get("productName"), row.get("specification"), row.get("unit"), qty, price, amount, row.get("remark"));
        }
    }

    private BigDecimal totalOf(List<Map<String, Object>> lines) {
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (var row : lines) {
            BigDecimal price = (BigDecimal) row.get("unitPrice");
            if (price == null) continue;
            any = true;
            total = total.add(price.multiply((BigDecimal) row.get("quantity")));
        }
        return any ? total : null;
    }

    /** 当前可用库存（canonical 口径）。查不到按 0——「没有这行」与「数量为 0」对出库而言等价。 */
    private BigDecimal availableOf(long warehouseId, long productId, String unit) {
        var rows = jdbc.queryForList("SELECT b.quantity FROM product_inventory_balances b"
                        + " WHERE b.warehouse_id=? AND b.unit=?"
                        + " AND b.canonical_product_id=(SELECT COALESCE(canonical_product_id,id) FROM sales_products WHERE id=?)",
                warehouseId, unit, productId);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("quantity");
    }

    private Long requireWarehouse(Long warehouseId) {
        if (warehouseId == null
                || jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE id=? AND active=1", Integer.class, warehouseId) != 1)
            throw new IllegalArgumentException("请选择有效仓库");
        return warehouseId;
    }

    private static String describeLines(List<OutboundLine> lines) {
        if (lines == null) return "(无)";
        StringBuilder b = new StringBuilder();
        for (OutboundLine l : lines) {
            if (l == null) continue;
            if (b.length() > 0) b.append(';');
            b.append(l.productId()).append('x').append(l.quantity());
        }
        return b.toString();
    }

    /** 去掉小数末尾无意义的 0（错误提示里 10.000000 读起来像精度问题）。 */
    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String required(String s, String label, int max) {
        String v = clean(s);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        if (v.length() > max) throw new IllegalArgumentException(label + "不能超过" + max + "字");
        return v;
    }

    private static String clean(String s) { return s == null ? null : s.trim(); }
}
