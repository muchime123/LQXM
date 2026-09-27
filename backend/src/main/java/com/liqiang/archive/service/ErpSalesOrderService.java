package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 新销售订单的需求源头。历史 sales_contracts 保持只读。 */
@Service
public class ErpSalesOrderService {
    public record Line(Long id, String productName, String specification, Long salesProductId, String supplyMode,
                       String unit, BigDecimal quantity, BigDecimal unitPrice, LocalDate requestedDeliveryDate,
                       String remark, ProductionConfig productionConfig) {}
    public record ProductionConfig(Long workshopId, String productName, String boxSize, String boxHeight,
                                   String moldArrangement, String boxShape, String boxMaterial, String voltage,
                                   String plug, String brand, List<String> optionalConfigs, String configNote,
                                   Boolean extraMold, String boxLengthMm, String boxWidthMm,
                                   String boxDiameterMm, String boxShapeNote, String boxSizeNote) {}
    public record Request(Long customerId, String customerName, LocalDate orderDate,
                          LocalDate requestedDeliveryDate, String remark, List<Line> lines) {}

    private final JdbcTemplate jdbc;
    private final ErpActorResolver actors;
    private final DataScopeSupport scope;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter log;
    private final SalesOutboundService outbounds;
    private final ObjectMapper mapper;
    private final ManufacturingService manufacturing;

    public ErpSalesOrderService(JdbcTemplate jdbc, ErpActorResolver actors, DataScopeSupport scope,
                               @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                               @Qualifier("archiveAuditWriter") ErpChangeLogWriter log,
                               SalesOutboundService outbounds, ObjectMapper mapper,
                               ManufacturingService manufacturing) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.scope = scope;
        this.idem = idem;
        this.log = log;
        this.outbounds = outbounds;
        this.mapper = mapper;
        this.manufacturing = manufacturing;
    }

    public Map<String, Object> list(String keyword, String status, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(1, page);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (o.order_no LIKE ? OR o.customer_name_snapshot LIKE ? OR EXISTS"
                    + "(SELECT 1 FROM erp_sales_order_items i WHERE i.order_id=o.id AND i.product_name_snapshot LIKE ?))");
            String term = "%" + keyword.trim() + "%";
            args.add(term); args.add(term); args.add(term);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND o.status=?"); args.add(status);
        }
        scope.appendByName(where, args, "o.salesperson_name_snapshot", FeatureCode.SALES_OUTBOUND);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sales_orders o" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        paged.add(safeSize); paged.add((safePage - 1) * safeSize);
        var rows = jdbc.queryForList("SELECT o.id,o.order_no orderNo,o.status,o.customer_id customerId,"
                + "o.customer_name_snapshot customerName,o.salesperson_name_snapshot salespersonName,"
                + "o.order_date orderDate,o.requested_delivery_date requestedDeliveryDate,o.total_amount totalAmount,"
                + "o.created_at createdAt,(SELECT COUNT(*) FROM erp_sales_order_items i WHERE i.order_id=o.id) itemCount"
                + " FROM erp_sales_orders o" + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?", paged.toArray());
        return Map.of("items", rows, "total", total == null ? 0 : total, "page", safePage, "size", safeSize);
    }

    public List<Map<String, Object>> products(String keyword) {
        if (keyword == null || keyword.trim().length() < 2) return List.of();
        String term = "%" + keyword.trim() + "%";
        return jdbc.queryForList("SELECT id,product_code productCode,product_name productName,"
                + "specification,unit FROM sales_products WHERE deleted_at IS NULL"
                + " AND (product_code LIKE ? OR product_name LIKE ?) ORDER BY id DESC LIMIT 30", term, term);
    }

    /** 销售订单和生产任务单共用同一产线/产品配置主数据。 */
    public List<Map<String, Object>> productionWorkshops() {
        return jdbc.queryForList("SELECT id,workshop_name workshopName FROM manufacturing_workshops"
                + " WHERE enabled=1 ORDER BY workshop_name");
    }

    public Map<String, Object> productionOptions(long workshopId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_workshops WHERE id=? AND enabled=1",
                Long.class, workshopId);
        if (count == null || count == 0) throw new IllegalArgumentException("所选产线不存在或已停用");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productNames", jdbc.queryForList("SELECT DISTINCT config_name FROM manufacturing_task_config_items"
                + " WHERE workshop_id=? AND config_type='PRODUCT_NAME' AND active=1 ORDER BY config_name",
                String.class, workshopId));
        var products = jdbc.queryForList("SELECT c.config_name configName,"
                + "COALESCE(c.process_template_id,w.default_process_template_id) processTemplateId,"
                + "CASE WHEN c.process_template_id IS NOT NULL THEN COALESCE(pt.template_name,'已停用制程')"
                + " ELSE COALESCE(wt.template_name,'无需报工') END processTemplateName"
                + " FROM manufacturing_task_config_items c JOIN manufacturing_workshops w ON w.id=c.workshop_id"
                + " LEFT JOIN manufacturing_process_templates pt ON pt.id=c.process_template_id"
                + " LEFT JOIN manufacturing_process_templates wt ON wt.id=w.default_process_template_id"
                + " WHERE c.workshop_id=? AND c.config_type='PRODUCT_NAME' AND c.active=1"
                + " ORDER BY c.config_name", workshopId);
        var templateIds = new LinkedHashMap<String, Object>();
        var templateNames = new LinkedHashMap<String, String>();
        products.forEach(row -> {
            String name = String.valueOf(row.get("configName"));
            templateIds.put(name, row.get("processTemplateId"));
            templateNames.put(name, String.valueOf(row.get("processTemplateName")));
        });
        result.put("productTemplates", templateIds);
        result.put("productTemplateNames", templateNames);
        result.put("optionalConfigs", jdbc.queryForList("SELECT DISTINCT config_name FROM manufacturing_task_config_items"
                + " WHERE workshop_id=? AND config_type='OPTIONAL_CONFIG' AND active=1 ORDER BY config_name",
                String.class, workshopId));
        return result;
    }

    /** 车间待评估的销售自产需求；已接单行不再重复出现。 */
    public List<Map<String, Object>> pendingProductionDemands() {
        return jdbc.queryForList("SELECT i.id itemId,i.order_id orderId,o.order_no orderNo,"
                + "o.customer_name_snapshot customerName,o.salesperson_name_snapshot salespersonName,"
                + "i.product_name_snapshot productName,i.specification_snapshot specification,"
                + "i.production_workshop_id workshopId,w.workshop_name workshopName,"
                + "CAST(i.production_config_json AS CHAR CHARACTER SET utf8mb4) productionConfigJson,i.quantity,i.unit,"
                + "COALESCE(i.requested_delivery_date,o.requested_delivery_date) dueDate,"
                + "i.remark FROM erp_sales_order_items i JOIN erp_sales_orders o ON o.id=i.order_id"
                + " LEFT JOIN manufacturing_workshops w ON w.id=i.production_workshop_id"
                + " WHERE o.status='CONFIRMED' AND i.supply_mode='MAKE'"
                + " AND NOT EXISTS(SELECT 1 FROM manufacturing_orders m WHERE m.sales_order_item_id=i.id)"
                + " ORDER BY COALESCE(i.requested_delivery_date,o.requested_delivery_date),i.id LIMIT 200");
    }

    /** 车间核对需求后接单；复用生产任务建单服务，不重新录产品配置。 */
    public long acceptProductionDemand(long orderId, long itemId, String key) {
        ErpActor actor = actors.current();
        return Long.parseLong(idem.execute(actor, key, "sales.order.production-accept",
                Map.of("orderId", orderId, "itemId", itemId), () -> {
                    var heads = jdbc.queryForList("SELECT id,order_no orderNo,status,customer_id customerId,"
                            + "customer_name_snapshot customerName,salesperson_id salespersonId,"
                            + "salesperson_name_snapshot salespersonName,requested_delivery_date dueDate"
                            + " FROM erp_sales_orders WHERE id=? FOR UPDATE", orderId);
                    if (heads.isEmpty() || !"CONFIRMED".equals(heads.getFirst().get("status")))
                        throw new IllegalStateException("只有已确认订单可以下达生产需求");
                    var lines = jdbc.queryForList("SELECT id,supply_mode supplyMode,product_name_snapshot productName,"
                            + "specification_snapshot specification,production_workshop_id workshopId,"
                            + "production_config_json productionConfigJson,quantity,unit,remark,"
                            + "requested_delivery_date dueDate FROM erp_sales_order_items"
                            + " WHERE id=? AND order_id=? FOR UPDATE", itemId, orderId);
                    if (lines.isEmpty() || !"MAKE".equals(lines.getFirst().get("supplyMode")))
                        throw new IllegalArgumentException("订单自产明细不存在");
                    var line = lines.getFirst();
                    if (line.get("workshopId") == null || line.get("productionConfigJson") == null)
                        throw new IllegalStateException("订单明细缺少已确认的生产产品配置");
                    Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_orders"
                            + " WHERE sales_order_item_id=?", Long.class, itemId);
                    if (existing != null && existing > 0)
                        throw new IllegalStateException("该订单明细已由车间接单，请查看关联生产任务");
                    Map<String, Object> config;
                    try { config = mapper.readValue(jsonText(line.get("productionConfigJson")),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}); }
                    catch (JsonProcessingException e) { throw new IllegalStateException("订单产品配置损坏，无法接单", e); }
                    String childKey = UUID.nameUUIDFromBytes(("sales-order-manufacturing:" + key)
                            .getBytes(StandardCharsets.UTF_8)).toString();
                    var head = heads.getFirst();
                    long manufacturingId = manufacturing.create(new ManufacturingService.CreateOrder(
                            String.valueOf(head.get("orderNo")), (Long) head.get("customerId"),
                            String.valueOf(head.get("customerName")), String.valueOf(line.get("productName")),
                            null, String.valueOf(line.get("unit")), (String) line.get("specification"),
                            (String) config.get("configNote"), (BigDecimal) line.get("quantity"),
                            line.get("dueDate") == null ? localDate(head.get("dueDate")) : localDate(line.get("dueDate")),
                            (String) line.get("remark"), "SALES_ORDER", null,
                            ((Number) line.get("workshopId")).longValue(), null, config, itemId), childKey);
                    jdbc.update("UPDATE manufacturing_orders SET sales_order_item_id=?,salesperson_id=?,"
                            + "salesperson_name_snapshot=? WHERE id=?", itemId, head.get("salespersonId"),
                            head.get("salespersonName"), manufacturingId);
                    log.write(ErpChangeLogWriter.of(actor, "product_archive.manufacturing_orders", manufacturingId,
                            "UPDATE", ErpChangeLogWriter.changes("salesOrderItemId", itemId),
                            null, "车间评估接单，引用销售订单明细", key, null));
                    return String.valueOf(manufacturingId);
                }));
    }

    public Map<String, Object> detail(long id) {
        StringBuilder where = new StringBuilder(" WHERE o.id=?");
        List<Object> args = new ArrayList<>(); args.add(id);
        scope.appendByName(where, args, "o.salesperson_name_snapshot", FeatureCode.SALES_OUTBOUND);
        var heads = jdbc.queryForList("SELECT o.id,o.order_no orderNo,o.status,o.customer_id customerId,"
                + "o.customer_name_snapshot customerName,o.salesperson_id salespersonId,"
                + "o.salesperson_name_snapshot salespersonName,o.order_date orderDate,"
                + "o.requested_delivery_date requestedDeliveryDate,o.remark,o.total_amount totalAmount,"
                + "o.confirmed_at confirmedAt,o.created_at createdAt FROM erp_sales_orders o" + where, args.toArray());
        if (heads.isEmpty()) throw new IllegalArgumentException("销售订单不存在或无权查看");
        var lines = jdbc.queryForList("SELECT id,line_no lineNo,product_name_snapshot productName,"
                + "specification_snapshot specification,sales_product_id salesProductId,supply_mode supplyMode,"
                + "unit,quantity,unit_price unitPrice,amount,requested_delivery_date requestedDeliveryDate,remark,"
                + "production_workshop_id productionWorkshopId,"
                + "CAST(production_config_json AS CHAR CHARACTER SET utf8mb4) productionConfigJson,"
                + "(SELECT m.id FROM manufacturing_orders m WHERE m.sales_order_item_id=erp_sales_order_items.id"
                + " LIMIT 1) manufacturingOrderId,"
                + "(SELECT COALESCE(SUM(oi.quantity),0) FROM erp_sales_outbound_items oi"
                + " JOIN erp_sales_outbounds o ON o.id=oi.outbound_id"
                + " WHERE oi.sales_order_item_id=erp_sales_order_items.id AND o.status IN ('DRAFT','POSTED')) allocatedQuantity,"
                + "(SELECT COALESCE(SUM(oi.quantity),0) FROM erp_sales_outbound_items oi"
                + " JOIN erp_sales_outbounds o ON o.id=oi.outbound_id"
                + " WHERE oi.sales_order_item_id=erp_sales_order_items.id AND o.status='POSTED') shippedQuantity"
                + " FROM erp_sales_order_items WHERE order_id=? ORDER BY line_no", id);
        return Map.of("head", heads.getFirst(), "items", lines);
    }

    public long create(Request request, String key) {
        ErpActor actor = actors.current();
        return Long.parseLong(idem.execute(actor, key, "sales.order.create", request,
                () -> String.valueOf(insert(request, actor, key))));
    }

    public long update(long id, Request request, String key) {
        ErpActor actor = actors.current();
        requireVisible(id);
        return Long.parseLong(idem.execute(actor, key, "sales.order.update", Map.of("id", id, "request", request),
                () -> String.valueOf(replace(id, request, actor, key))));
    }

    public void confirm(long id, String key) {
        ErpActor actor = actors.current();
        requireVisible(id);
        idem.execute(actor, key, "sales.order.confirm", Map.of("id", id), () -> {
            var heads = jdbc.queryForList("SELECT status FROM erp_sales_orders WHERE id=? FOR UPDATE", id);
            if (heads.isEmpty() || !"DRAFT".equals(heads.getFirst().get("status")))
                throw new IllegalStateException("只有草稿订单可以确认");
            var requirements = jdbc.queryForList("SELECT id,supply_mode supplyMode,production_config_json productionConfigJson"
                    + " FROM erp_sales_order_items WHERE order_id=?", id);
            for (var line : requirements) {
                if ("UNDECIDED".equals(line.get("supplyMode")))
                    throw new IllegalStateException("明细 " + line.get("id") + " 尚未确定供货方式");
                if ("MAKE".equals(line.get("supplyMode"))) {
                    if (line.get("productionConfigJson") == null)
                        throw new IllegalStateException("自产明细 " + line.get("id") + " 尚未完成产品配置");
                    try {
                        validateProductionConfig(mapper.readValue(jsonText(line.get("productionConfigJson")),
                                ProductionConfig.class));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("自产明细 " + line.get("id") + " 的产品配置无法读取", e);
                    }
                }
            }
            jdbc.update("UPDATE erp_sales_orders SET status='CONFIRMED',confirmed_at=NOW(),"
                    + "confirmed_by_employee_id=? WHERE id=?", actor.employeeId(), id);
            log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_orders", id, "UPDATE",
                    ErpChangeLogWriter.changes("status", "CONFIRMED"), null, "确认销售需求", key, null));
            return String.valueOf(id);
        });
    }

    /** 现货明细一键生成可复核的出库草稿；不扣库存，过账仍由仓库流程完成。 */
    public long createOutboundDraft(long orderId, long itemId, long warehouseId, BigDecimal quantity, String key) {
        ErpActor actor = actors.current();
        requireVisible(orderId);
        return Long.parseLong(idem.execute(actor, key, "sales.order.outbound-draft",
                Map.of("orderId", orderId, "itemId", itemId, "warehouseId", warehouseId,
                        "quantity", quantity == null ? "remaining" : quantity), () -> {
                    var head = jdbc.queryForList("SELECT order_no orderNo,status,customer_id customerId,"
                            + "customer_name_snapshot customerName FROM erp_sales_orders WHERE id=? FOR UPDATE", orderId);
                    if (head.isEmpty() || !"CONFIRMED".equals(head.getFirst().get("status")))
                        throw new IllegalStateException("只有已确认销售订单可以生成出库草稿");
                    var items = jdbc.queryForList("SELECT sales_product_id salesProductId,supply_mode supplyMode,"
                            + "quantity,unit_price unitPrice,remark FROM erp_sales_order_items WHERE id=? AND order_id=?",
                            itemId, orderId);
                    if (items.isEmpty()) throw new IllegalArgumentException("订单明细不存在");
                    var line = items.getFirst();
                    String supplyMode = String.valueOf(line.get("supplyMode"));
                    if (!List.of("STOCK", "BUY").contains(supplyMode) || line.get("salesProductId") == null)
                        throw new IllegalStateException("只有已选产品档案的现货或外采明细可以生成出库草稿");
                    // 外采的出库口子在**本地库存**上：只有「到货入库型」的采购订单、且货已经进过仓，
                    // 本地才有这批货可扣。代发（DROP_SHIP）的货由供应商直发客户，从不进本地仓，
                    // 它的出库单必须从采购订单侧生成（ErpPurchaseOrderService），不能走这里。
                    if ("BUY".equals(supplyMode)) {
                        var purchases = jdbc.queryForList("SELECT fulfillment_mode fulfillmentMode,status "
                                + "FROM erp_purchase_orders WHERE sales_order_item_id=? AND status<>'CANCELLED'"
                                + " ORDER BY id DESC LIMIT 1", itemId);
                        if (purchases.isEmpty())
                            throw new IllegalStateException("该外采明细还没有采购订单，请先在采购订单页安排外采");
                        var purchase = purchases.getFirst();
                        if ("DROP_SHIP".equals(purchase.get("fulfillmentMode")))
                            throw new IllegalStateException("该外采明细是供应商直发客户（代发），"
                                    + "请从采购订单生成代发出库单，不走本地库存出库");
                        if (!List.of("RECEIVING", "CLOSED").contains(String.valueOf(purchase.get("status"))))
                            throw new IllegalStateException("该外采明细尚未到货入库，本地仓库还没有这批货，"
                                    + "请先在采购订单页办理到货入库");
                    }
                    BigDecimal allocated = jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM erp_sales_outbound_items i"
                            + " JOIN erp_sales_outbounds o ON o.id=i.outbound_id"
                            + " WHERE i.sales_order_item_id=? AND o.status IN ('DRAFT','POSTED')",
                            BigDecimal.class, itemId);
                    BigDecimal remaining = ((BigDecimal) line.get("quantity")).subtract(
                            allocated == null ? BigDecimal.ZERO : allocated);
                    BigDecimal shipQuantity = quantity == null ? remaining : quantity;
                    if (shipQuantity.signum() <= 0 || shipQuantity.compareTo(remaining) > 0 || shipQuantity.scale() > 6)
                        throw new IllegalArgumentException("本次出库数量必须大于 0，且不能超过订单明细剩余量 " + remaining);
                    String childKey = UUID.nameUUIDFromBytes(("sales-order-outbound:" + key)
                            .getBytes(StandardCharsets.UTF_8)).toString();
                    long outboundId = outbounds.create(new SalesOutboundService.OutboundRequest(
                            (Long) head.getFirst().get("customerId"), String.valueOf(head.getFirst().get("customerName")),
                            warehouseId, String.valueOf(head.getFirst().get("orderNo")), LocalDate.now(),
                            "从销售订单明细生成", List.of(new SalesOutboundService.OutboundLine(
                                    ((Number) line.get("salesProductId")).longValue(),
                                    shipQuantity, (BigDecimal) line.get("unitPrice"),
                                    (String) line.get("remark")))), childKey);
                    jdbc.update("UPDATE erp_sales_outbounds SET sales_order_id=? WHERE id=?", orderId, outboundId);
                    jdbc.update("UPDATE erp_sales_outbound_items SET sales_order_item_id=? WHERE outbound_id=?",
                            itemId, outboundId);
                    log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_outbounds", outboundId,
                            "UPDATE", ErpChangeLogWriter.changes("salesOrderId", orderId,
                                    "salesOrderItemId", itemId), null, "从订单明细生成出库草稿", key, null));
                    return String.valueOf(outboundId);
                }));
    }

    private long insert(Request request, ErpActor actor, String key) {
        var lines = validated(request);
        String pending = "PENDING-" + UUID.randomUUID();
        jdbc.update("INSERT INTO erp_sales_orders(order_no,status,customer_id,customer_name_snapshot,"
                        + "salesperson_id,salesperson_name_snapshot,order_date,requested_delivery_date,remark,total_amount)"
                        + " VALUES(?,'DRAFT',?,?,?,?,?,?,?,?)",
                pending, request.customerId(), required(request.customerName(), "客户", 300), actor.employeeId(),
                actor.name(), request.orderDate() == null ? LocalDate.now() : request.orderDate(),
                request.requestedDeliveryDate(), clean(request.remark()), total(lines));
        long id = jdbc.queryForObject("SELECT id FROM erp_sales_orders WHERE order_no=?", Long.class, pending);
        String no = "XSDD" + String.format("%08d", id);
        jdbc.update("UPDATE erp_sales_orders SET order_no=? WHERE id=?", no, id);
        writeLines(id, lines);
        log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_orders", id, "INSERT",
                ErpChangeLogWriter.changes("orderNo", no, "customerName", request.customerName(),
                        "lineCount", lines.size()), null, "创建销售订单草稿", key, null));
        return id;
    }

    private long replace(long id, Request request, ErpActor actor, String key) {
        var heads = jdbc.queryForList("SELECT status FROM erp_sales_orders WHERE id=? FOR UPDATE", id);
        if (heads.isEmpty() || !"DRAFT".equals(heads.getFirst().get("status")))
            throw new IllegalStateException("已确认订单不可直接修改，需走需求变更流程");
        var lines = validated(request);
        jdbc.update("UPDATE erp_sales_orders SET customer_id=?,customer_name_snapshot=?,order_date=?,"
                        + "requested_delivery_date=?,remark=?,total_amount=? WHERE id=?",
                request.customerId(), required(request.customerName(), "客户", 300),
                request.orderDate() == null ? LocalDate.now() : request.orderDate(),
                request.requestedDeliveryDate(), clean(request.remark()), total(lines), id);
        var existingRows = jdbc.queryForList("SELECT id FROM erp_sales_order_items WHERE order_id=? FOR UPDATE", id);
        var existingIds = existingRows.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        var retainedIds = lines.stream().map(Line::id).filter(java.util.Objects::nonNull).toList();
        if (retainedIds.size() != retainedIds.stream().distinct().count())
            throw new IllegalArgumentException("订单明细 ID 不能重复");
        if (!existingIds.containsAll(retainedIds))
            throw new IllegalArgumentException("订单明细不属于当前订单");
        for (Long oldId : existingIds) {
            if (retainedIds.contains(oldId)) continue;
            Long refs = jdbc.queryForObject("SELECT "
                    + "(SELECT COUNT(*) FROM manufacturing_orders WHERE sales_order_item_id=?) + "
                    + "(SELECT COUNT(*) FROM erp_purchase_orders WHERE sales_order_item_id=?) + "
                    + "(SELECT COUNT(*) FROM erp_sales_outbound_items WHERE sales_order_item_id=?)",
                    Long.class, oldId, oldId, oldId);
            if (refs != null && refs > 0)
                throw new IllegalStateException("明细 " + oldId + " 已关联后续单据，不能从草稿删除");
            jdbc.update("DELETE FROM erp_sales_order_items WHERE id=?", oldId);
        }
        // 先腾出旧行号，再写新顺序；原位更新保留稳定的明细 ID 和下游关联。
        for (int n = 0; n < retainedIds.size(); n++)
            jdbc.update("UPDATE erp_sales_order_items SET line_no=? WHERE id=?", -(n + 1), retainedIds.get(n));
        for (int n = 0; n < lines.size(); n++) {
            Line line = lines.get(n);
            if (line.id() == null) insertLine(id, n + 1, line);
            else updateLine(line.id(), n + 1, line);
        }
        log.write(ErpChangeLogWriter.of(actor, "product_archive.erp_sales_orders", id, "UPDATE",
                ErpChangeLogWriter.changes("lineCount", lines.size(), "customerName", request.customerName()),
                null, "修改销售订单草稿", key, null));
        return id;
    }

    private List<Line> validated(Request request) {
        if (request == null) throw new IllegalArgumentException("订单内容不能为空");
        required(request.customerName(), "客户", 300);
        if (request.lines() == null || request.lines().isEmpty() || request.lines().size() > 200)
            throw new IllegalArgumentException("订单明细必须为 1 至 200 行");
        for (Line line : request.lines()) {
            if (line == null) throw new IllegalArgumentException("订单明细不能为空");
            required(line.productName(), "产品名称", 500);
            required(line.unit(), "单位", 40);
            if (line.supplyMode() == null || !List.of("STOCK", "MAKE", "BUY", "UNDECIDED").contains(line.supplyMode()))
                throw new IllegalArgumentException("供货方式不正确");
            if (line.specification() != null && line.specification().length() > 1000)
                throw new IllegalArgumentException("规格配置不能超过 1000 字");
            if (line.remark() != null && line.remark().length() > 500)
                throw new IllegalArgumentException("明细备注不能超过 500 字");
            if (line.quantity() == null || line.quantity().signum() <= 0 || line.quantity().scale() > 6)
                throw new IllegalArgumentException("订单数量必须大于 0，最多 6 位小数");
            if (line.unitPrice() != null && (line.unitPrice().signum() < 0 || line.unitPrice().scale() > 4))
                throw new IllegalArgumentException("单价必须大于等于 0，最多 4 位小数");
            if (line.salesProductId() != null) {
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_products WHERE id=? AND deleted_at IS NULL",
                        Long.class, line.salesProductId());
                if (count == null || count == 0) throw new IllegalArgumentException("所选产品档案不存在");
            }
            if ("MAKE".equals(line.supplyMode()) && line.productionConfig() != null) {
                validateProductionConfig(line.productionConfig());
            } else if (line.productionConfig() != null) {
                throw new IllegalArgumentException("只有自产明细可以填写生产产品配置");
            }
        }
        return request.lines();
    }

    private void writeLines(long id, List<Line> lines) {
        for (int n = 0; n < lines.size(); n++) {
            insertLine(id, n + 1, lines.get(n));
        }
    }

    private void insertLine(long orderId, int lineNo, Line line) {
            if (line.id() != null) throw new IllegalArgumentException("新建订单明细不能指定已有 ID");
            ProductionConfig config = line.productionConfig();
            jdbc.update("INSERT INTO erp_sales_order_items(order_id,line_no,product_name_snapshot,"
                            + "specification_snapshot,sales_product_id,supply_mode,unit,quantity,unit_price,amount,"
                            + "requested_delivery_date,remark,production_workshop_id,production_config_json)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    orderId, lineNo, productName(line), clean(line.specification()), line.salesProductId(),
                    line.supplyMode(), line.unit().trim(), line.quantity(), line.unitPrice(), amount(line),
                    line.requestedDeliveryDate(), clean(line.remark()), config == null ? null : config.workshopId(),
                    config == null ? null : json(config));
    }

    private void updateLine(long id, int lineNo, Line line) {
        ProductionConfig config = line.productionConfig();
        jdbc.update("UPDATE erp_sales_order_items SET line_no=?,product_name_snapshot=?,"
                        + "specification_snapshot=?,sales_product_id=?,supply_mode=?,unit=?,quantity=?,"
                        + "unit_price=?,amount=?,requested_delivery_date=?,remark=?,production_workshop_id=?,"
                        + "production_config_json=? WHERE id=?",
                lineNo, productName(line), clean(line.specification()), line.salesProductId(), line.supplyMode(),
                line.unit().trim(), line.quantity(), line.unitPrice(), amount(line), line.requestedDeliveryDate(),
                clean(line.remark()), config == null ? null : config.workshopId(),
                config == null ? null : json(config), id);
    }

    private String productName(Line line) {
        return line.productionConfig() == null ? line.productName().trim() : line.productionConfig().productName().trim();
    }

    private BigDecimal amount(Line line) {
        return line.unitPrice() == null ? null
                : line.unitPrice().multiply(line.quantity()).setScale(4, RoundingMode.HALF_UP);
    }

    void validateProductionConfig(ProductionConfig config) {
        if (config.workshopId() == null) throw new IllegalArgumentException("自产产品必须选择产线");
        var options = productionOptions(config.workshopId());
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) options.get("productNames");
        @SuppressWarnings("unchecked")
        List<String> optionals = (List<String>) options.get("optionalConfigs");
        if (config.productName() == null || !names.contains(config.productName().trim()))
            throw new IllegalArgumentException("产品名称必须来自所选产线的可选产品");
        if (List.of(nullToEmpty(config.boxHeight()), nullToEmpty(config.moldArrangement()), nullToEmpty(config.boxShape()),
                nullToEmpty(config.boxMaterial()), nullToEmpty(config.voltage()),
                nullToEmpty(config.plug()), nullToEmpty(config.brand()))
                .stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("请补齐自产产品的高度、模具排列、材质、形状、电压、插头和品牌");
        if ("OTHER".equals(config.boxShape())) {
            if (nullToEmpty(config.boxShapeNote()).isBlank() || nullToEmpty(config.boxSizeNote()).isBlank())
                throw new IllegalArgumentException("异形盒子必须填写形状说明和尺寸描述");
        } else if (nullToEmpty(config.boxSize()).isBlank()) {
            throw new IllegalArgumentException("请补齐自产产品尺寸");
        }
        if (config.optionalConfigs() != null && (config.optionalConfigs().size() > 50
                || config.optionalConfigs().stream().anyMatch(value -> !optionals.contains(value))))
            throw new IllegalArgumentException("可选配置与所选产线不符");
        if (config.configNote() != null && config.configNote().length() > 500)
            throw new IllegalArgumentException("配置说明不能超过 500 字");
        for (String value : List.of(nullToEmpty(config.boxSize()), nullToEmpty(config.boxHeight()),
                nullToEmpty(config.moldArrangement()), nullToEmpty(config.boxShape()),
                nullToEmpty(config.boxMaterial()), nullToEmpty(config.voltage()),
                nullToEmpty(config.plug()), nullToEmpty(config.brand()))) {
            if (value.length() > 100) throw new IllegalArgumentException("生产配置字段不能超过 100 字");
        }
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
    private String jsonText(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }
    private LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("产品配置无法保存", e); }
    }

    private BigDecimal total(List<Line> lines) {
        if (lines.stream().anyMatch(l -> l.unitPrice() == null)) return null;
        return lines.stream().map(l -> l.unitPrice().multiply(l.quantity()).setScale(4, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void requireVisible(long id) { detail(id); }
    private String clean(String s) { return s == null ? null : s.trim(); }
    private String required(String s, String name, int max) {
        if (s == null || s.isBlank() || s.trim().length() > max)
            throw new IllegalArgumentException(name + "不能为空且不能超过 " + max + " 字");
        return s.trim();
    }
}
