package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 外采采购订单（BUY 履约）。与 ErpSalesOrderService 对称：
 * 本地动作单（DRAFT→PENDING_APPROVAL→CONFIRMED→RECEIVING→CLOSED/CANCELLED），与只读氚云台账
 * purchase_receipts 隔离。一行销售需求对应一个采购订单（1:1，仿 manufacturing_orders）。
 *
 * <p><b>审批关口（#35）</b>：「确认」不再是单步动作，而是**提交审批 → 老板通过**两步
 * （{@link ErpApprovalService}）。所以 {@code /confirm} 端点已由 {@code /submit} + {@code /approve}
 * 取代 —— 保留一个能直接从草稿跳到 {@code CONFIRMED} 的入口等于**绕过审批**，故不保留。
 * 到货入库必须有 {@code CONFIRMED} 起的状态，因此未审批的单在库存侧天然不可执行。
 *
 * <p>履约模式（V344/V345）：
 * <ul>
 *   <li>{@code WAREHOUSE} 入库型：到货即写 {@code product_inventory_balances} + 库存流水
 *       （{@link #receive}），与自产完工入库**同套余额表、同口径**；出库走标准扣减。</li>
 *   <li>{@code DROP_SHIP} 代发型：供应商直发客户，**不写本地库存账**；生成销售出库草稿
 *       （{@link #createDropShipOutbound}），过账由 {@code SalesOutboundService} 按
 *       {@code fulfillment_source} 豁免扣库存。</li>
 * </ul>
 *
 * <p>{@code RECEIVING} 对本域的统一语义是「履行中」：入库型指已开始到货（未结案），
 * 代发型指已生成代发出库单。两者都不代表完成——完成由 {@code CLOSED} 表达。
 *
 * <p>⚠️ 与 SalesOutboundService 同纪律：<b>本类不写 audit_logs</b>，审计统一走
 * {@link ErpChangeLogWriter}（身份 = {@code product_archive.<物理表名>}）；
 * 幂等/审计必须与业务写入同一 DataSource 与事务管理器，故注入
 * {@code archiveIdempotencyService} / {@code archiveAuditWriter}。
 */
@Service
public class ErpPurchaseOrderService {

    /** 库存流水来源标识：外采到货入库 / 冲正（与 H3_PRODUCTION_COMPLETION 同款命名）。 */
    private static final String SOURCE_RECEIPT = "ERP_PURCHASE_RECEIPT";
    private static final String SOURCE_RECEIPT_REVERSAL = "ERP_PURCHASE_RECEIPT_REVERSAL";

    public record Line(String productName, String specification, Long salesProductId,
                       String unit, BigDecimal quantity, BigDecimal purchasePrice, String remark) {}
    public record Request(Long supplierId, String supplierName, Long warehouseId, String fulfillmentMode,
                          Long salesOrderItemId, LocalDate expectedArrivalDate, String remark, List<Line> lines) {}
    /** 到货入库明细：按采购明细行给本次到货数量（允许部分到货）。 */
    public record ReceiveItem(Long poItemId, BigDecimal quantity) {}
    public record ReceiveRequest(Long warehouseId, List<ReceiveItem> items, String remark) {}

    private final JdbcTemplate jdbc;
    private final ErpActorResolver actors;
    private final DataScopeSupport scope;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter log;
    private final ProductInventoryService inventory;
    private final SalesOutboundService outbounds;
    private final ErpApprovalService approvals;

    public ErpPurchaseOrderService(JdbcTemplate jdbc, ErpActorResolver actors, DataScopeSupport scope,
                                   @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                                   @Qualifier("archiveAuditWriter") ErpChangeLogWriter log,
                                   ProductInventoryService inventory, SalesOutboundService outbounds,
                                   ErpApprovalService approvals) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.scope = scope;
        this.idem = idem;
        this.log = log;
        this.inventory = inventory;
        this.outbounds = outbounds;
        this.approvals = approvals;
    }

    public Map<String, Object> list(String keyword, String status, String fulfillmentMode, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (p.po_no LIKE ? OR p.supplier_name_snapshot LIKE ? OR EXISTS"
                    + "(SELECT 1 FROM erp_purchase_order_items pi WHERE pi.po_id=p.id AND pi.product_name_snapshot LIKE ?))");
            String term = "%" + keyword.trim() + "%";
            args.add(term); args.add(term); args.add(term);
        }
        if (status != null && !status.isBlank()) { where.append(" AND p.status=?"); args.add(status); }
        if (fulfillmentMode != null && !fulfillmentMode.isBlank()) {
            where.append(" AND p.fulfillment_mode=?"); args.add(fulfillmentMode);
        }
        // 行级数据范围按**建单人**：采购单的负责人是录单人，而采购单上没有业务员列
        // （销售订单才按 salesperson_name_snapshot 过滤）。SELF/DEPARTMENT 都落到这一列。
        scope.appendByEmployeeId(where, args, "p.created_by_employee_id", "e.department", FeatureCode.PURCHASE_ORDER);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM erp_purchase_orders p"
                + " LEFT JOIN employees e ON e.id=p.created_by_employee_id" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        paged.add(safeSize); paged.add((safePage - 1) * safeSize);
        var rows = jdbc.queryForList("SELECT p.id,p.po_no poNo,p.status,p.approval_status approvalStatus,"
                + "p.supplier_id supplierId,"
                + "p.supplier_name_snapshot supplierName,p.warehouse_id warehouseId,p.fulfillment_mode fulfillmentMode,"
                + "p.sales_order_item_id salesOrderItemId,p.expected_arrival_date expectedArrivalDate,"
                + "p.total_amount totalAmount,p.created_by_name createdByName,p.created_at createdAt,"
                + "(SELECT COUNT(*) FROM erp_purchase_order_items pi WHERE pi.po_id=p.id) itemCount,"
                + "(SELECT COALESCE(SUM(pi.received_quantity),0) FROM erp_purchase_order_items pi WHERE pi.po_id=p.id)"
                + " receivedQuantity,"
                + "(SELECT COALESCE(SUM(pi.quantity),0) FROM erp_purchase_order_items pi WHERE pi.po_id=p.id)"
                + " orderedQuantity,"
                + "(SELECT o.order_no FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                + " WHERE i.id=p.sales_order_item_id) salesOrderNo"
                + " FROM erp_purchase_orders p LEFT JOIN employees e ON e.id=p.created_by_employee_id"
                + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?", paged.toArray());
        return Map.of("items", rows, "total", total == null ? 0 : total, "page", safePage, "size", safeSize);
    }

    /** 供应商主档选择（h3_supplier_catalog，只读）。 */
    public List<Map<String, Object>> suppliers(String keyword) {
        if (keyword == null || keyword.trim().length() < 2) return List.of();
        String term = "%" + keyword.trim() + "%";
        return jdbc.queryForList("SELECT id,name FROM h3_supplier_catalog"
                + " WHERE (supplier_status IS NULL OR supplier_status<>'合作终止')"
                + " AND name LIKE ? ORDER BY name LIMIT 30", term);
    }

    /** 销售订单已确认的 BUY 明细且未挂有效采购订单（CANCELLED 不占坑）。 */
    public List<Map<String, Object>> pendingPurchaseDemands() {
        return jdbc.queryForList("SELECT i.id itemId,o.id orderId,o.order_no orderNo,"
                + "o.customer_name_snapshot customerName,o.salesperson_name_snapshot salespersonName,"
                + "i.product_name_snapshot productName,i.specification_snapshot specification,"
                + "i.unit,i.quantity,i.remark,"
                + "COALESCE(i.requested_delivery_date,o.requested_delivery_date) dueDate"
                + " FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                + " WHERE o.status='CONFIRMED' AND i.supply_mode='BUY'"
                + " AND NOT EXISTS(SELECT 1 FROM erp_purchase_orders p WHERE p.sales_order_item_id=i.id AND p.status<>'CANCELLED')"
                + " ORDER BY COALESCE(i.requested_delivery_date,o.requested_delivery_date),i.id LIMIT 200");
    }

    public Map<String, Object> detail(long id) {
        var where = new StringBuilder(" WHERE p.id=?");
        List<Object> args = new ArrayList<>(); args.add(id);
        scope.appendByEmployeeId(where, args, "p.created_by_employee_id", "e.department", FeatureCode.PURCHASE_ORDER);
        var heads = jdbc.queryForList("SELECT p.id,p.po_no poNo,p.status,p.approval_status approvalStatus,"
                + "p.approval_submitted_at approvalSubmittedAt,p.approval_submitted_by_employee_id approvalSubmittedByEmployeeId,"
                + "p.approval_decided_at approvalDecidedAt,p.approval_decided_by_employee_id approvalDecidedByEmployeeId,"
                + "p.supplier_id supplierId,"
                + "p.supplier_name_snapshot supplierName,p.warehouse_id warehouseId,w.warehouse_name warehouseName,"
                + "p.fulfillment_mode fulfillmentMode,"
                + "p.sales_order_item_id salesOrderItemId,p.expected_arrival_date expectedArrivalDate,p.remark,"
                + "p.total_amount totalAmount,p.confirmed_at confirmedAt,p.created_by_name createdByName,p.created_at createdAt,"
                + "(SELECT o.id FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                + " WHERE i.id=p.sales_order_item_id) salesOrderId,"
                + "(SELECT o.order_no FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                + " WHERE i.id=p.sales_order_item_id) salesOrderNo,"
                + "(SELECT o.customer_name_snapshot FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                + " WHERE i.id=p.sales_order_item_id) customerName"
                + " FROM erp_purchase_orders p LEFT JOIN employees e ON e.id=p.created_by_employee_id "
                + " LEFT JOIN warehouses w ON w.id=p.warehouse_id" + where, args.toArray());
        if (heads.isEmpty()) throw new IllegalArgumentException("采购订单不存在或无权查看");
        var items = jdbc.queryForList("SELECT id,line_no lineNo,sales_product_id salesProductId,"
                + "product_name_snapshot productName,specification_snapshot specification,unit,quantity,"
                + "purchase_price purchasePrice,amount,received_quantity receivedQuantity,remark,"
                + "(quantity-received_quantity) pendingQuantity"
                + " FROM erp_purchase_order_items WHERE po_id=? ORDER BY line_no", id);
        // 代发出库单：过账口径由单据自身标记（fulfillment_source），这里只是给个跳转入口。
        // ⚠️ 销售明细的关联列在**明细表**（erp_sales_outbound_items.sales_order_item_id），
        // 主表只有 sales_order_id —— 与 ErpSalesOrderService.createOutboundDraft 同款约定。
        var outbound = jdbc.queryForList("SELECT o.id,o.outbound_no outboundNo,o.status FROM erp_sales_outbounds o"
                + " JOIN erp_sales_outbound_items oi ON oi.outbound_id=o.id"
                + " WHERE o.fulfillment_source='DROP_SHIP' AND o.status<>'CANCELLED'"
                + " AND oi.sales_order_item_id=(SELECT sales_order_item_id FROM erp_purchase_orders WHERE id=?)"
                + " GROUP BY o.id,o.outbound_no,o.status ORDER BY o.id DESC LIMIT 1", id);
        var receipts = jdbc.queryForList("SELECT r.id,r.receipt_no receiptNo,r.status,r.received_at receivedAt,"
                + "r.received_by_name receivedByName,r.remark,r.reversed_at reversedAt,"
                + "r.reversed_by_name reversedByName,r.reversal_reason reversalReason,r.warehouse_id warehouseId,"
                + "w.warehouse_name warehouseName FROM erp_purchase_receipts r"
                + " LEFT JOIN warehouses w ON w.id=r.warehouse_id WHERE r.po_id=? ORDER BY r.id DESC", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", heads.getFirst());
        result.put("items", items);
        result.put("receipts", receipts);
        result.put("approvals", approvals.history(ErpApprovalService.DocType.PURCHASE_ORDER, id));
        result.put("dropShipOutbound", outbound.isEmpty() ? null : outbound.getFirst());
        result.put("warehouses", warehouses());
        return result;
    }

    /** 有效仓库（到货入库落点）。 */
    public List<Map<String, Object>> warehouses() {
        return jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName "
                + "FROM warehouses WHERE active=1 ORDER BY id");
    }

    /** 行级可见性校验：不可见与不存在返回同一句提示（不泄露单号是否存在）。 */
    public void requireVisible(long id) {
        var where = new StringBuilder(" WHERE p.id=?");
        List<Object> args = new ArrayList<>(); args.add(id);
        scope.appendByEmployeeId(where, args, "p.created_by_employee_id", "e.department", FeatureCode.PURCHASE_ORDER);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_purchase_orders p"
                + " LEFT JOIN employees e ON e.id=p.created_by_employee_id" + where, Long.class, args.toArray());
        if (n == null || n == 0) throw new IllegalArgumentException("采购订单不存在或无权查看");
    }

    public long create(Request request, String key) {
        ErpActor actor = actors.current();
        return Long.parseLong(idem.execute(actor, key, "purchase.order.create", request,
                () -> String.valueOf(insert(request, actor, key))));
    }

    /**
     * 提交审批（草稿 → 待审批）。提交人须对该单可见（谁开的单谁提交）。
     *
     * <p>「必须有明细」的业务前置校验通过 {@code precondition} 传进审批服务的**同一事务**，
     * 避免「校验过了但流转没做 / 流转做了但校验没跑」这种分家。
     */
    public void submit(long id, String key) {
        requireVisible(id);
        approvals.submit(ErpApprovalService.DocType.PURCHASE_ORDER, id, key, () -> {
            Long lineCount = jdbc.queryForObject("SELECT COUNT(*) FROM erp_purchase_order_items WHERE po_id=?",
                    Long.class, id);
            if (lineCount == null || lineCount == 0)
                throw new IllegalStateException("采购订单没有明细，不能提交审批");
        });
    }

    /**
     * 审批通过（待审批 → 已确认）。
     *
     * <p>⚠️ 这里**不做行级可见性校验** —— 审批人（老板）通常不是建单人，按建单人/部门过滤
     * 会让审批入口对审批人永久 403。准入只由 {@code purchase_order.approve} 权限决定。
     */
    public void approve(long id, String comment, String key) {
        approvals.approve(ErpApprovalService.DocType.PURCHASE_ORDER, id, comment, key);
    }

    /** 驳回（待审批 → 草稿，原因必填）。 */
    public void reject(long id, String comment, String key) {
        approvals.reject(ErpApprovalService.DocType.PURCHASE_ORDER, id, comment, key);
    }

    /** 该采购订单的完整审批历史（含驳回）。 */
    public List<Map<String, Object>> approvalHistory(long id) {
        return approvals.history(ErpApprovalService.DocType.PURCHASE_ORDER, id);
    }

    public void cancel(long id, String reason, String key) {
        ErpActor actor = actors.current();
        idem.execute(actor, key, "purchase.order.cancel", Map.of("id", id, "reason", reason), () -> {
            var heads = jdbc.queryForList("SELECT status FROM erp_purchase_orders WHERE id=? FOR UPDATE", id);
            if (heads.isEmpty())
                throw new IllegalArgumentException("采购订单不存在");
            String status = String.valueOf(heads.getFirst().get("status"));
            if ("RECEIVING".equals(status) || "CLOSED".equals(status))
                throw new IllegalStateException("已到货或已结案的采购订单不能取消");
            jdbc.update("UPDATE erp_purchase_orders SET status='CANCELLED' WHERE id=?", id);
            log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_purchase_orders", id, "UPDATE",
                    ErpChangeLogWriter.changes("status", status + "->CANCELLED", "reason", reason),
                    null, "取消采购订单", key, null));
            return String.valueOf(id);
        });
    }

    // ==================== 履约一：到货入库（WAREHOUSE 入库型，P2） ====================

    /**
     * 到货入库：把供应商送到的货写进**本地库存账**（余额 + 不可变流水），并累计采购明细到货量。
     *
     * <p>与自产完工入库（{@code H3ProductionCompletionService}）走同一个
     * {@link ProductInventoryService#post}，因此余额口径天然一致：同一件货不管来自生产还是外采，
     * 都累加在 (仓库, canonical_product_id, 单位) 这一行上，出库只需一套扣减逻辑。
     *
     * <p>为什么「明细没挂产品档案」直接拒绝而不是跳过该行：库存余额的键里含产品身份，
     * 没有身份就没有余额行可写。临时外采品要么先建档，要么改走供应商直发（代发）——
     * 静默少入一批货会让账面与实际悄悄分叉。
     */
    public long receive(long poId, ReceiveRequest request, String key) {
        ErpActor actor = actors.current();
        requireVisible(poId);
        Map<String, Object> payload = ErpChangeLogWriter.changes("poId", poId,
                "warehouseId", request == null ? null : request.warehouseId(),
                "items", request == null ? "(无)" : describeReceiptItems(request.items()),
                "remark", request == null ? null : clean(request.remark()));
        return Long.parseLong(idem.execute(actor, key, "purchase.order.receive", payload,
                () -> String.valueOf(doReceive(poId, request, actor, key))));
    }

    private long doReceive(long poId, ReceiveRequest request, ErpActor actor, String key) {
        if (request == null || request.warehouseId() == null || request.items() == null || request.items().isEmpty())
            throw new IllegalArgumentException("请选择入库仓库并填写本次到货数量");
        var head = lockHead(poId);
        if ("DROP_SHIP".equals(head.get("fulfillmentMode")))
            throw new IllegalStateException("这是供应商直发客户（代发）的采购订单，不办理到货入库；请生成代发出库单");
        if (!List.of("CONFIRMED", "RECEIVING").contains(String.valueOf(head.get("status"))))
            throw new IllegalStateException("采购订单尚未审批通过（当前状态「" + head.get("status")
                    + "」），不能办理到货入库");
        if (!Long.valueOf(1L).equals(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouses WHERE id=? AND active=1", Long.class, request.warehouseId())))
            throw new IllegalArgumentException("请选择有效仓库");
        String remark = clean(request.remark());
        if (remark != null && remark.length() > 1000) throw new IllegalArgumentException("到货说明不能超过 1000 字");

        var prepared = new ArrayList<Map<String, Object>>();
        var seen = new java.util.HashSet<Long>();
        for (var item : request.items()) {
            if (item == null || item.poItemId() == null || !seen.add(item.poItemId()))
                throw new IllegalArgumentException("到货明细不能为空或重复");
            BigDecimal qty = item.quantity();
            if (qty == null || qty.signum() <= 0 || qty.scale() > 6)
                throw new IllegalArgumentException("到货数量必须大于 0，最多 6 位小数");
            var rows = jdbc.queryForList("SELECT id,sales_product_id salesProductId,product_name_snapshot productName,"
                    + "specification_snapshot specification,unit,quantity,received_quantity receivedQuantity "
                    + "FROM erp_purchase_order_items WHERE id=? AND po_id=? FOR UPDATE", item.poItemId(), poId);
            if (rows.isEmpty()) throw new IllegalArgumentException("到货明细行不属于该采购订单");
            var row = rows.getFirst();
            if (row.get("salesProductId") == null)
                throw new IllegalStateException("采购明细「" + row.get("productName") + "」还没有关联产品档案，不能入本地库存账。"
                        + "请先在产品档案建立该产品，或把该单改为供应商直发（代发）。");
            BigDecimal pending = ((BigDecimal) row.get("quantity")).subtract((BigDecimal) row.get("receivedQuantity"));
            if (qty.compareTo(pending) > 0)
                throw new IllegalStateException("采购明细「" + row.get("productName") + "」本次到货 "
                        + qty.stripTrailingZeros().toPlainString() + " 超过未到货数量 "
                        + pending.stripTrailingZeros().toPlainString());
            var ready = new LinkedHashMap<String, Object>();
            ready.put("poItemId", row.get("id"));
            ready.put("productId", row.get("salesProductId"));
            ready.put("productName", row.get("productName"));
            ready.put("specification", row.get("specification"));
            ready.put("unit", row.get("unit"));
            ready.put("quantity", qty);
            prepared.add(ready);
        }

        LocalDateTime at = LocalDateTime.now();
        jdbc.update("INSERT INTO erp_purchase_receipts(receipt_no,po_id,warehouse_id,status,remark,"
                        + "received_by_employee_id,received_by_name) VALUES(?,?,?,'POSTED',?,?,?)",
                "PENDING-" + UUID.randomUUID(), poId, request.warehouseId(), remark, actor.employeeId(), actor.name());
        long receiptId = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        String receiptNo = "CGDH" + String.format("%08d", receiptId);
        jdbc.update("UPDATE erp_purchase_receipts SET receipt_no=? WHERE id=?", receiptNo, receiptId);

        int lineNo = 1;
        for (var row : prepared) {
            var product = jdbc.queryForList("SELECT product_code productCode,"
                    + "COALESCE(canonical_product_id,id) canonicalId FROM sales_products WHERE id=? AND deleted_at IS NULL",
                    row.get("productId"));
            if (product.isEmpty())
                throw new IllegalStateException("采购明细关联的产品档案已失效，请重新关联后再办理到货");
            var p = product.getFirst();
            jdbc.update("INSERT INTO erp_purchase_receipt_items(receipt_id,line_no,po_item_id,sales_product_id,"
                            + "canonical_product_id,product_code_snapshot,product_name_snapshot,specification_snapshot,"
                            + "unit,quantity) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    receiptId, lineNo++, row.get("poItemId"), row.get("productId"), p.get("canonicalId"),
                    p.get("productCode"), row.get("productName"), row.get("specification"), row.get("unit"),
                    row.get("quantity"));
            long receiptItemId = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
            inventory.post(request.warehouseId(), ((Number) row.get("productId")).longValue(), null,
                    String.valueOf(row.get("productName")), (String) row.get("specification"),
                    String.valueOf(row.get("unit")), "PURCHASE_RECEIPT", (BigDecimal) row.get("quantity"),
                    SOURCE_RECEIPT, receiptItemId, receiptNo, at, remark);
            jdbc.update("UPDATE erp_purchase_order_items SET received_quantity=received_quantity+? WHERE id=?",
                    row.get("quantity"), row.get("poItemId"));
        }
        Long pendingLines = jdbc.queryForObject("SELECT COUNT(*) FROM erp_purchase_order_items "
                + "WHERE po_id=? AND received_quantity<quantity", Long.class, poId);
        String next = (pendingLines == null || pendingLines == 0) ? "CLOSED" : "RECEIVING";
        jdbc.update("UPDATE erp_purchase_orders SET status=? WHERE id=?", next, poId);
        log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_purchase_receipts", receiptId, "INSERT",
                ErpChangeLogWriter.changes("receiptNo", receiptNo, "poNo", head.get("poNo"),
                        "warehouseId", request.warehouseId(), "lineCount", prepared.size(),
                        "poStatus", next, "inventoryDeducted", "receipt"),
                null, "外采到货入库", key, null));
        return receiptId;
    }

    /**
     * 到货冲正：反向过账（余额回退 + 反向流水）并回退累计到货量，到货单置 REVERSED。
     *
     * <p>本动作**不加业务闸门以外的额外判断**：若这批货已经被销售出库过，
     * {@code ProductInventoryService.post} 会因余额不足直接抛错并整笔回滚——
     * 这正是想要的行为（货已经发走了就不该被冲回来），且不需要另写一套「是否已出库」的检查。
     */
    public void reverseReceipt(long poId, long receiptId, String reason, String key) {
        ErpActor actor = actors.current();
        requireVisible(poId);
        String r = clean(reason);
        if (r == null || r.isBlank()) throw new IllegalArgumentException("请填写冲正原因");
        if (r.length() > 500) throw new IllegalArgumentException("冲正原因不能超过 500 字");
        Map<String, Object> payload = ErpChangeLogWriter.changes("poId", poId, "receiptId", receiptId, "reason", r);
        idem.execute(actor, key, "purchase.order.receive-reverse", payload, () -> {
            lockHead(poId);
            var rows = jdbc.queryForList("SELECT * FROM erp_purchase_receipts WHERE id=? AND po_id=? FOR UPDATE",
                    receiptId, poId);
            if (rows.isEmpty()) throw new IllegalArgumentException("到货单不存在");
            var receipt = rows.getFirst();
            if (!"POSTED".equals(receipt.get("status"))) throw new IllegalStateException("该到货单已冲正");
            long warehouseId = ((Number) receipt.get("warehouse_id")).longValue();
            String receiptNo = String.valueOf(receipt.get("receipt_no"));
            var items = jdbc.queryForList("SELECT * FROM erp_purchase_receipt_items WHERE receipt_id=? ORDER BY line_no",
                    receiptId);
            var at = LocalDateTime.now();
            for (var item : items) {
                inventory.post(warehouseId, ((Number) item.get("sales_product_id")).longValue(), null,
                        String.valueOf(item.get("product_name_snapshot")), (String) item.get("specification_snapshot"),
                        String.valueOf(item.get("unit")), "PURCHASE_RECEIPT_REVERSAL",
                        ((BigDecimal) item.get("quantity")).negate(), SOURCE_RECEIPT_REVERSAL,
                        ((Number) item.get("id")).longValue(), receiptNo, at, r);
                jdbc.update("UPDATE erp_purchase_order_items SET received_quantity=GREATEST(received_quantity-?,0) WHERE id=?",
                        item.get("quantity"), item.get("po_item_id"));
            }
            jdbc.update("UPDATE erp_purchase_receipts SET status='REVERSED',reversed_at=?,reversed_by_employee_id=?,"
                            + "reversed_by_name=?,reversal_reason=? WHERE id=?",
                    at, actor.employeeId(), actor.name(), r, receiptId);
            Long left = jdbc.queryForObject("SELECT COUNT(*) FROM erp_purchase_receipts "
                    + "WHERE po_id=? AND status='POSTED'", Long.class, poId);
            jdbc.update("UPDATE erp_purchase_orders SET status=? WHERE id=?",
                    (left == null || left == 0) ? "CONFIRMED" : "RECEIVING", poId);
            log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_purchase_receipts", receiptId, "UPDATE",
                    ErpChangeLogWriter.changes("status", "POSTED→REVERSED", "receiptNo", receiptNo, "reversalReason", r),
                    "REVERSAL", r, key, null));
            return String.valueOf(receiptId);
        });
    }

    // ==================== 履约二：代发出库（DROP_SHIP，P3） ====================

    /**
     * 生成代发出库单草稿：供应商直发客户，**不进本地库存**。
     *
     * <p>出库单本身仍由 {@link SalesOutboundService} 创建（单据只有一个来源，不另写一套建单逻辑），
     * 只是走 {@code createDropShip} 这个入口：允许无仓库、允许明细无产品档案，
     * 并在单头打上 {@code fulfillment_source=DROP_SHIP}，供过账时豁免扣库存。
     *
     * <p>金额口径：采购单价是**成本**，不能当销售单价写进出库单。只有当采购单恰好一行、
     * 且能对上销售明细单价时才带入销售单价；多行时留空（前端显示「待定」），
     * 由业务员在出库单上补——宁可让金额空着，也不要拿成本冒充满。
     */
    public long createDropShipOutbound(long poId, String key) {
        ErpActor actor = actors.current();
        requireVisible(poId);
        return Long.parseLong(idem.execute(actor, key, "purchase.order.dropship-outbound",
                ErpChangeLogWriter.changes("poId", poId),
                () -> String.valueOf(doCreateDropShipOutbound(poId, actor, key))));
    }

    private long doCreateDropShipOutbound(long poId, ErpActor actor, String key) {
        var head = lockHead(poId);
        if (!"DROP_SHIP".equals(head.get("fulfillmentMode")))
            throw new IllegalStateException("只有供应商直发客户（代发）的采购订单可以生成代发出库单");
        if (!"CONFIRMED".equals(String.valueOf(head.get("status"))))
            throw new IllegalStateException("只有已确认的采购订单可以生成代发出库单（已生成过的不再重复生成）");
        Object salesItemId = head.get("salesOrderItemId");
        if (salesItemId == null)
            throw new IllegalStateException("代发必须关联销售订单明细才能确定客户，请先重新建单并关联销售明细");
        var orders = jdbc.queryForList("SELECT o.id orderId,o.order_no orderNo,o.customer_id customerId,"
                + "o.customer_name_snapshot customerName,o.salesperson_id salespersonId,"
                + "o.salesperson_name_snapshot salespersonName,i.unit_price unitPrice "
                + "FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id WHERE i.id=?", salesItemId);
        if (orders.isEmpty()) throw new IllegalStateException("关联的销售订单明细已不存在");
        var order = orders.getFirst();
        var lines = jdbc.queryForList("SELECT sales_product_id salesProductId,product_name_snapshot productName,"
                + "specification_snapshot specification,unit,quantity,remark "
                + "FROM erp_purchase_order_items WHERE po_id=? ORDER BY line_no", poId);
        if (lines.isEmpty()) throw new IllegalStateException("采购订单没有明细，不能生成代发出库单");
        BigDecimal salesPrice = lines.size() == 1 && order.get("unitPrice") instanceof BigDecimal price
                ? price : null;
        var shipLines = new ArrayList<SalesOutboundService.DropShipLine>();
        for (var line : lines) {
            shipLines.add(new SalesOutboundService.DropShipLine(
                    line.get("salesProductId") instanceof Number n ? n.longValue() : null,
                    String.valueOf(line.get("productName")), (String) line.get("specification"),
                    String.valueOf(line.get("unit")), (BigDecimal) line.get("quantity"),
                    salesPrice, (String) line.get("remark")));
        }
        String childKey = UUID.nameUUIDFromBytes(("purchase-order-dropship:" + key)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        long outboundId = outbounds.createDropShip(new SalesOutboundService.DropShipRequest(
                order.get("customerId") instanceof Number n ? n.longValue() : null,
                String.valueOf(order.get("customerName")),
                order.get("salespersonId") instanceof Number n ? n.longValue() : null,
                (String) order.get("salespersonName"),
                String.valueOf(order.get("orderNo")), LocalDate.now(),
                "供应商直发客户（代发），由采购订单 " + head.get("poNo") + " 生成", shipLines), childKey);
        // 关联落点与既有约定一致：主表记销售订单、**明细行**记销售明细
        // （erp_sales_outbounds 没有 sales_order_item_id 列；见 ErpSalesOrderService 同款写法）。
        jdbc.update("UPDATE erp_sales_outbounds SET sales_order_id=? WHERE id=?", order.get("orderId"), outboundId);
        jdbc.update("UPDATE erp_sales_outbound_items SET sales_order_item_id=? WHERE outbound_id=?",
                salesItemId, outboundId);
        jdbc.update("UPDATE erp_purchase_orders SET status='RECEIVING' WHERE id=?", poId);
        log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_purchase_orders", poId, "UPDATE",
                ErpChangeLogWriter.changes("status", "CONFIRMED→RECEIVING", "dropShipOutboundId", outboundId,
                        "inventoryDeducted", false),
                null, "生成代发出库单草稿", key, null));
        return outboundId;
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> lockHead(long poId) {
        var rows = jdbc.queryForList("SELECT id,po_no poNo,status,fulfillment_mode fulfillmentMode,"
                + "sales_order_item_id salesOrderItemId,warehouse_id warehouseId "
                + "FROM erp_purchase_orders WHERE id=? FOR UPDATE", poId);
        if (rows.isEmpty()) throw new IllegalArgumentException("采购订单不存在");
        return rows.getFirst();
    }

    private static String describeReceiptItems(List<ReceiveItem> items) {
        if (items == null) return "(无)";
        StringBuilder b = new StringBuilder();
        for (ReceiveItem i : items) {
            if (i == null) continue;
            if (b.length() > 0) b.append(';');
            b.append(i.poItemId()).append('x').append(i.quantity());
        }
        return b.toString();
    }

    private long insert(Request request, ErpActor actor, String key) {
        var lines = validated(request);
        if (request.salesOrderItemId() != null) {
            var demand = jdbc.queryForList("SELECT i.id,i.supply_mode supplyMode,o.status orderStatus"
                    + " FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                    + " WHERE i.id=? FOR UPDATE", request.salesOrderItemId());
            if (demand.isEmpty())
                throw new IllegalArgumentException("关联的销售订单明细不存在");
            var row = demand.getFirst();
            if (!"CONFIRMED".equals(row.get("orderStatus")))
                throw new IllegalStateException("关联销售订单未确认，不能生成采购订单");
            if (!"BUY".equals(row.get("supplyMode")))
                throw new IllegalArgumentException("只有外采（BUY）明细可以生成采购订单");
            Long existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_purchase_orders WHERE sales_order_item_id=? AND status<>'CANCELLED'",
                    Long.class, request.salesOrderItemId());
            if (existing != null && existing > 0)
                throw new IllegalStateException("该销售明细已有关联采购订单，请查看原单或先取消");
        }
        if (request.supplierId() != null) {
            Long sc = jdbc.queryForObject("SELECT COUNT(*) FROM h3_supplier_catalog WHERE id=?",
                    Long.class, request.supplierId());
            if (sc == null || sc == 0) throw new IllegalArgumentException("所选供应商不存在");
        }
        String pending = "PENDING-" + UUID.randomUUID();
        jdbc.update("INSERT INTO erp_purchase_orders(po_no,status,supplier_id,supplier_name_snapshot,"
                + "warehouse_id,fulfillment_mode,sales_order_item_id,expected_arrival_date,remark,total_amount,"
                + "created_by_employee_id,created_by_name)"
                + " VALUES(?,'DRAFT',?,?,?,?,?,?,?,?,?,?)",
                pending, request.supplierId(), required(request.supplierName(), "供应商", 190),
                request.warehouseId(),
                request.fulfillmentMode() == null ? "WAREHOUSE" : request.fulfillmentMode(),
                request.salesOrderItemId(), request.expectedArrivalDate(), clean(request.remark()),
                total(lines), actor.employeeId(), actor.name());
        long id = jdbc.queryForObject("SELECT id FROM erp_purchase_orders WHERE po_no=?", Long.class, pending);
        String no = "CGDD" + String.format("%08d", id);
        jdbc.update("UPDATE erp_purchase_orders SET po_no=? WHERE id=?", no, id);
        writeItems(id, lines);
        log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_purchase_orders", id, "INSERT",
                ErpChangeLogWriter.changes("poNo", no, "supplierName", request.supplierName(),
                        "fulfillmentMode", request.fulfillmentMode(), "lineCount", lines.size()),
                null, "创建采购订单草稿", key, null));
        return id;
    }

    private List<Line> validated(Request request) {
        if (request == null) throw new IllegalArgumentException("采购订单内容不能为空");
        required(request.supplierName(), "供应商", 190);
        String mode = request.fulfillmentMode() == null ? "WAREHOUSE" : request.fulfillmentMode();
        if (!List.of("WAREHOUSE", "DROP_SHIP").contains(mode))
            throw new IllegalArgumentException("履约方式不正确");
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 200)
            throw new IllegalArgumentException("采购明细必须为 1 至 200 行");
        for (Line line : request.lines()) {
            if (line == null) throw new IllegalArgumentException("采购明细不能为空");
            required(line.productName(), "产品名称", 500);
            required(line.unit(), "单位", 40);
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().scale() > 6)
                throw new IllegalArgumentException("采购数量必须大于 0，最多 6 位小数");
            if (line.purchasePrice() != null && (line.purchasePrice().signum() < 0 || line.purchasePrice().scale() > 4))
                throw new IllegalArgumentException("采购单价必须大于等于 0，最多 4 位小数");
            if (line.specification() != null && line.specification().length() > 1000)
                throw new IllegalArgumentException("规格不能超过 1000 字");
            if (line.remark() != null && line.remark().length() > 500)
                throw new IllegalArgumentException("明细备注不能超过 500 字");
            if (line.salesProductId() != null) {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_products WHERE id=? AND deleted_at IS NULL",
                        Long.class, line.salesProductId());
                if (count == null || count == 0) throw new IllegalArgumentException("所选产品档案不存在");
            }
        }
        return request.lines();
    }

    private void writeItems(long id, List<Line> lines) {
        for (int n = 0; n < lines.size(); n++) {
            Line l = lines.get(n);
            BigDecimal amount = l.purchasePrice() == null ? null
                    : l.purchasePrice().multiply(l.quantity()).setScale(4, RoundingMode.HALF_UP);
            jdbc.update("INSERT INTO erp_purchase_order_items(po_id,line_no,sales_product_id,"
                    + "product_name_snapshot,specification_snapshot,unit,quantity,purchase_price,amount,remark)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, n + 1, l.salesProductId(), l.productName().trim(), clean(l.specification()),
                    l.unit().trim(), l.quantity(), l.purchasePrice(), amount, clean(l.remark()));
        }
    }

    private BigDecimal total(List<Line> lines) {
        if (lines.stream().anyMatch(l -> l.purchasePrice() == null)) return null;
        return lines.stream().map(l -> l.purchasePrice().multiply(l.quantity()).setScale(4, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String clean(String s) { return s == null ? null : s.trim(); }
    private String required(String s, String name, int max) {
        if (s == null || s.isBlank() || s.trim().length() > max)
            throw new IllegalArgumentException(name + "不能为空且不能超过 " + max + " 字");
        return s.trim();
    }
}
