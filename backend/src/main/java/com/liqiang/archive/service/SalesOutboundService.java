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
 * <p><b>状态机</b>：{@code DRAFT}（草稿，不动库存）→ {@code POSTED}（已过账，扣库存+写流水）
 * → {@code REVERSED}（已冲销，回补库存）。{@code REVERSED} 是终态：冲销后要重发就新建一张，
 * 不提供「反冲销」，避免单号复用与流水口径歧义。
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
     * 操作审计的实体类型。
     *
     * <p><b>必须带 {@code Erp} 前缀（勿改回 {@code SalesOutbound}）</b>：
     * {@code SalesOutbound} 这个实体名早就被氚云侧的治理动作占用了
     * （{@code OutboundRelationGovernanceService} 的产品归类/客户关联、
     * {@code SalesOutboundNatureService} 的退换货类型确认），那些行的
     * {@code entity_id} 是**氚云销售出库台账行 id**（量级 10 万+）。
     * 本类是 ERP 自建单据，{@code entity_id} 是 {@code erp_sales_outbounds.id}（从 1 起）。
     *
     * <p>若沿用同名，操作日志页按「实体类型 = SalesOutbound」筛选会同时命中
     * 「氚云台账行的归类动作」和「ERP 出库单的建单/过账/冲销」，而 {@code entity_id}
     * 在两套 id 空间里含义不同——审计时无法区分，且随着两套 id 增长存在数值撞车可能。
     * 2026-09-21 端到端验证后清理残留时实测发现（当时库内 660 行是氚云侧、5 行是本次 ERP 单）。
     *
     * <p>命名规则：**ERP 自建单据 → {@code Erp*} 实体名 + {@code erp_*} 表名**，
     * 与制造域 {@code Manufacturing*} 同款「领域前缀」写法。
     */
    private static final String AUDIT_ENTITY = "ErpSalesOutbound";

    private final JdbcTemplate jdbc;
    private final ProductInventoryService productInventory;
    private final CurrentUserService users;
    private final DataScopeSupport scope;
    private final AuditLogService audit;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter changeLog;
    private final ErpActorResolver actors;

    public SalesOutboundService(JdbcTemplate jdbc,
                                ProductInventoryService productInventory,
                                CurrentUserService users,
                                DataScopeSupport scope,
                                AuditLogService audit,
                                @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                                @Qualifier("archiveAuditWriter") ErpChangeLogWriter changeLog,
                                ErpActorResolver actors) {
        this.jdbc = jdbc;
        this.productInventory = productInventory;
        this.users = users;
        this.scope = scope;
        this.audit = audit;
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
     */
    public Map<String, Object> list(String keyword, String status, Long warehouseId, Long customerId,
                                    String dateFrom, String dateTo, int page, int size) {
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

    /** 出库单详情（含明细）。无权限或不存在统一抛「不存在或无权查看」，不泄露单号是否存在。 */
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
                        + " o.reversal_reason reversalReason,o.created_by_name createdByName,o.created_at createdAt"
                        + " FROM erp_sales_outbounds o LEFT JOIN warehouses w ON w.id=o.warehouse_id WHERE o.id=?",
                id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("销售出库单不存在或无权查看"));
        var items = jdbc.queryForList(
                "SELECT id,line_no lineNo,sales_product_id salesProductId,canonical_product_id canonicalProductId,"
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
        audit.log(AUDIT_ENTITY, id, no, "CREATE",
                "创建销售出库单（草稿）；客户=" + customer + "；明细 " + lines.size() + " 行");
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
        audit.log(AUDIT_ENTITY, id, String.valueOf(head.get("outboundNo")), "UPDATE",
                "修改销售出库单（草稿）；明细 " + lines.size() + " 行");
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
        long warehouseId = ((Number) head.get("warehouseId")).longValue();
        String no = String.valueOf(head.get("outboundNo"));
        var lines = jdbc.queryForList("SELECT id,line_no lineNo,sales_product_id salesProductId,"
                + " product_name_snapshot productName,specification_snapshot specification,unit,quantity,remark"
                + " FROM erp_sales_outbound_items WHERE outbound_id=? ORDER BY line_no", id);
        if (lines.isEmpty()) throw new IllegalStateException("出库单没有明细，不能过账");

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
        audit.log(AUDIT_ENTITY, id, no, "POST", "销售出库过账；仓库=" + warehouseName + "；明细 " + lines.size() + " 行");
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
        long warehouseId = ((Number) head.get("warehouseId")).longValue();
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
        audit.log(AUDIT_ENTITY, id, no, "REVERSE", "销售出库冲销；原因=" + r);
        changeLog.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", id, "UPDATE",
                ErpChangeLogWriter.changes("status", "POSTED→REVERSED", "reversalReason", r, "reversedAt", at),
                "REVERSAL", r, requestKey, null));
    }

    // ==================== 内部工具 ====================

    /** 锁定并读取单头（并发下过账/冲销串行化）。字段名统一转 camelCase 以便下游按名取值。 */
    private Map<String, Object> lockHead(long id) {
        var rows = jdbc.queryForList("SELECT id,outbound_no outboundNo,status,warehouse_id warehouseId "
                + "FROM erp_sales_outbounds WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("销售出库单不存在");
        return rows.getFirst();
    }

    /** 校验并规整明细；返回带快照的完整行（快照在建单时固定，不随主数据改名而变）。 */
    private List<Map<String, Object>> validateLines(List<OutboundLine> input) {
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
