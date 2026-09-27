package com.liqiang.archive.service;

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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 氚云来源任务的质检合格与真实库存入账；来源工单及其仓库快照始终只读。 */
@Service
public class H3ProductionCompletionService {
    private final JdbcTemplate jdbc;
    private final DataScopeSupport scope;
    private final CurrentUserService users;
    private final ErpActorResolver actors;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter audit;
    private final ProductInventoryService inventory;
    private final H3ProductionExecutionService execution;

    public H3ProductionCompletionService(JdbcTemplate jdbc, DataScopeSupport scope, CurrentUserService users,
            ErpActorResolver actors, @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
            @Qualifier("archiveAuditWriter") ErpChangeLogWriter audit, ProductInventoryService inventory,
            H3ProductionExecutionService execution) {
        this.jdbc=jdbc;this.scope=scope;this.users=users;this.actors=actors;this.idem=idem;this.audit=audit;
        this.inventory=inventory;this.execution=execution;
    }

    public record Item(Long salesProductId, BigDecimal quantity, boolean primary) {}
    public record CompleteRequest(Long warehouseId, List<Item> items, String remark) {}
    public record ReverseRequest(String reason) {}

    public List<Map<String,Object>> warehouses() {
        return jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName "
                + "FROM warehouses WHERE active=1 ORDER BY id");
    }

    public List<Map<String,Object>> productOptions(String keyword) {
        if (keyword == null || keyword.trim().length() < 2) return List.of();
        String term = "%" + keyword.trim() + "%";
        return jdbc.queryForList("""
                SELECT id,product_code productCode,product_name productName,specification,unit
                  FROM sales_products WHERE deleted_at IS NULL AND external_id IS NOT NULL
                   AND (product_code LIKE ? OR product_name LIKE ? OR COALESCE(specification,'') LIKE ?)
                 ORDER BY product_name,product_code LIMIT 50
                """, term,term,term);
    }

    public Map<String,Object> current(long orderId) {
        var order = visibleOrder(orderId);
        var rows = jdbc.queryForList("""
                SELECT c.id,c.work_order_no_snapshot workOrderNo,c.status,c.remark,c.inspected_by_name inspectedByName,
                       c.inspected_at inspectedAt,c.reversed_by_name reversedByName,c.reversed_at reversedAt,
                       c.reversal_reason reversalReason,c.warehouse_id warehouseId,w.warehouse_name warehouseName
                  FROM production_execution_completions c JOIN warehouses w ON w.id=c.warehouse_id
                 WHERE BINARY c.order_external_id=BINARY ? ORDER BY c.id DESC
                """, order.get("external_id"));
        var result = new LinkedHashMap<String,Object>();
        result.put("completions", rows);
        if (!rows.isEmpty()) {
            var ids = rows.stream().map(r -> ((Number)r.get("id")).longValue()).toList();
            String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
            result.put("items", jdbc.queryForList("SELECT i.completion_id completionId,i.line_no lineNo,"
                    + "i.is_primary isPrimary,i.product_code_snapshot productCode,"
                    + "i.product_name_snapshot productName,i.specification_snapshot specification,"
                    + "i.unit,i.quantity,t.transaction_no transactionNo,t.balance_after balanceAfter "
                    + "FROM production_execution_completion_items i "
                    + "LEFT JOIN product_inventory_transactions t ON t.source_type='H3_PRODUCTION_COMPLETION' "
                    + "AND t.source_id=i.id WHERE i.completion_id IN ("
                    + placeholders + ") ORDER BY i.completion_id DESC,i.line_no", ids.toArray()));
        } else result.put("items", List.of());
        return result;
    }

    public long complete(long orderId, CompleteRequest request, String requestKey) {
        if (!users.can("manufacturing.inspect")) throw new SecurityException("没有完工检验权限");
        var visible = visibleOrder(orderId);
        if (request == null || request.warehouseId() == null || request.items() == null || request.items().isEmpty())
            throw new IllegalArgumentException("请选择入库仓库和实际产出产品");
        String remark = clean(request.remark(), 1000, "检验说明");
        var actor = actors.current();
        var payload = ErpChangeLogWriter.changes("orderId", orderId, "warehouseId", request.warehouseId(),
                "items", request.items(), "remark", remark);
        return Long.parseLong(idem.execute(actor, requestKey, "h3.production.completion.post", payload, () -> {
            var order = lockOrder(orderId, visible);
            if ("已取消".equals(order.get("status")) || "CANCELLED".equals(order.get("status")))
                throw new IllegalStateException("已取消工单不能完工入库");
            if (quantity(order.get("warehoused_quantity")).signum() > 0
                    || Boolean.TRUE.equals(order.get("warehousing_completed"))
                    || order.get("warehousing_completed") instanceof Number n && n.intValue() == 1)
                throw new IllegalStateException("氚云来源已记入库，不能再次入库");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_completions "
                    + "WHERE BINARY order_external_id=BINARY ? AND status='POSTED'", Long.class,
                    order.get("external_id")) > 0) throw new IllegalStateException("该工单已完成本地入库");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM warehouses WHERE id=? AND active=1", Long.class,
                    request.warehouseId()) != 1) throw new IllegalArgumentException("请选择有效仓库");
            var activeReworks = jdbc.queryForList("SELECT id,status,component_external_id FROM production_execution_reworks "
                    + "WHERE BINARY order_external_id=BINARY ? AND status IN ('REWORKING','PENDING_REINSPECTION') FOR UPDATE",
                    order.get("external_id"));
            if (!activeReworks.isEmpty() && !"PENDING_REINSPECTION".equals(activeReworks.getFirst().get("status")))
                throw new IllegalStateException("返工尚未报工完成，不能办理复检入库");
            if (!activeReworks.isEmpty()) {
                long matched=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM production_order_components "
                        + "WHERE production_order_id=? AND BINARY external_id=BINARY ?",Long.class,
                        orderId,activeReworks.getFirst().get("component_external_id")));
                if (matched!=1) throw new IllegalStateException("返工制程的氚云来源标识已变化，不能复检入库");
            }
            var detail = execution.detail(orderId);
            long count = ((Number)detail.get("componentCount")).longValue();
            long done = ((Number)detail.get("completedComponentCount")).longValue();
            if (count == 0 || done != count) throw new IllegalStateException("还有未完成制程，不能提交完工检验");
            BigDecimal required = quantity(order.get("required_quantity"));
            if (required.signum() <= 0) throw new IllegalStateException("来源工单需求数量无效，不能入库");
            var lines = validateItems(request.items(), required);
            jdbc.update("INSERT INTO production_execution_completions(order_external_id,work_order_no_snapshot,"
                    + "warehouse_id,remark,inspected_by_employee_id,inspected_by_name) VALUES(?,?,?,?,?,?)",
                    order.get("external_id"),order.get("work_order_no"),request.warehouseId(),remark,
                    actor.employeeId(),actor.name());
            long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
            String no = "HPC" + String.format("%08d", id);
            int lineNo = 1;
            for (var line : lines) {
                jdbc.update("INSERT INTO production_execution_completion_items(completion_id,line_no,is_primary,"
                        + "sales_product_id,canonical_product_id,product_code_snapshot,product_name_snapshot,"
                        + "specification_snapshot,unit,quantity) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        id,lineNo++,line.primary()?1:0,line.productId(),line.canonicalId(),line.code(),
                        line.name(),line.specification(),line.unit(),line.quantity());
                long itemId = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
                inventory.post(request.warehouseId(),line.productId(),null,line.name(),line.specification(),
                        line.unit(),"PRODUCTION_RECEIPT",line.quantity(),"H3_PRODUCTION_COMPLETION",itemId,
                        no,LocalDateTime.now(),remark);
            }
            if (!activeReworks.isEmpty()) jdbc.update("UPDATE production_execution_reworks "
                    + "SET status='RESOLVED',closed_at=NOW() WHERE id=?",activeReworks.getFirst().get("id"));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_execution_completions",id,"INSERT",
                    ErpChangeLogWriter.changes("workOrderNo",order.get("work_order_no"),"warehouseId",request.warehouseId(),
                            "itemCount",lines.size(),"status","POSTED"),null,remark,requestKey,null));
            return String.valueOf(id);
        }));
    }

    public void reverse(long orderId, long completionId, ReverseRequest request, String requestKey) {
        if (!users.can("manufacturing.inspect")) throw new SecurityException("没有完工入库冲正权限");
        var visible = visibleOrder(orderId);
        String reason = clean(request == null ? null : request.reason(), 1000, "冲正原因");
        if (reason.isBlank()) throw new IllegalArgumentException("请填写冲正原因");
        var actor = actors.current();
        var payload = Map.of("orderId",orderId,"completionId",completionId,"reason",reason);
        idem.execute(actor,requestKey,"h3.production.completion.reverse",payload,() -> {
            var order = lockOrder(orderId, visible);
            var rows = jdbc.queryForList("SELECT * FROM production_execution_completions WHERE id=? "
                    + "AND BINARY order_external_id=BINARY ? FOR UPDATE", completionId,order.get("external_id"));
            if (rows.isEmpty()) throw new IllegalArgumentException("完工入库记录不存在");
            var completion = rows.getFirst();
            if (!"POSTED".equals(completion.get("status"))) throw new IllegalStateException("该入库已冲正");
            var items = jdbc.queryForList("SELECT * FROM production_execution_completion_items "
                    + "WHERE completion_id=? ORDER BY line_no",completionId);
            String no = "HPC" + String.format("%08d",completionId);
            for (var item : items) {
                inventory.post(((Number)completion.get("warehouse_id")).longValue(),
                        ((Number)item.get("sales_product_id")).longValue(),null,
                        String.valueOf(item.get("product_name_snapshot")),(String)item.get("specification_snapshot"),
                        String.valueOf(item.get("unit")),"PRODUCTION_RECEIPT_REVERSAL",
                        ((BigDecimal)item.get("quantity")).negate(),"H3_PRODUCTION_COMPLETION_REVERSAL",
                        ((Number)item.get("id")).longValue(),no,LocalDateTime.now(),reason);
            }
            jdbc.update("UPDATE production_execution_completions SET status='REVERSED',reversal_reason=?,"
                    + "reversed_by_employee_id=?,reversed_by_name=?,reversed_at=NOW() WHERE id=?",
                    reason,actor.employeeId(),actor.name(),completionId);
            var resolved = jdbc.queryForList("SELECT id FROM production_execution_reworks WHERE "
                    + "BINARY order_external_id=BINARY ? AND status='RESOLVED' ORDER BY id DESC LIMIT 1 FOR UPDATE",
                    order.get("external_id"));
            if (!resolved.isEmpty()) jdbc.update("UPDATE production_execution_reworks "
                    + "SET status='PENDING_REINSPECTION',closed_at=NULL WHERE id=?",resolved.getFirst().get("id"));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_execution_completions",
                    completionId,"UPDATE",ErpChangeLogWriter.changes("status","POSTED→REVERSED",
                            "reversalReason",reason),"REVERSAL",reason,requestKey,null));
            return String.valueOf(completionId);
        });
    }

    private record Line(long productId,long canonicalId,String code,String name,String specification,
                        String unit,BigDecimal quantity,boolean primary) {}

    private List<Line> validateItems(List<Item> items, BigDecimal required) {
        if (items.size() > 30) throw new IllegalArgumentException("产出明细最多30行");
        var seen = new HashSet<Long>();
        var result = new ArrayList<Line>();
        int primaryCount = 0;
        for (var item : items) {
            if (item == null || item.salesProductId() == null || !seen.add(item.salesProductId()))
                throw new IllegalArgumentException("产出产品不可为空或重复");
            if (item.quantity() == null || item.quantity().signum() <= 0 || item.quantity().scale() > 6)
                throw new IllegalArgumentException("产出数量必须大于0且最多6位小数");
            var matches = jdbc.queryForList("SELECT id,canonical_product_id,product_code,product_name,"
                    + "specification,unit FROM sales_products WHERE id=? AND deleted_at IS NULL "
                    + "AND external_id IS NOT NULL", item.salesProductId());
            if (matches.size() != 1) throw new IllegalArgumentException("请选择有效的氚云标准产品 SKU");
            var product = matches.getFirst();
            String unit = String.valueOf(product.get("unit"));
            if (unit.isBlank() || "null".equals(unit)) throw new IllegalArgumentException("产品缺少单位，不能入库");
            if (item.primary()) {
                primaryCount++;
                if (item.quantity().compareTo(required) != 0)
                    throw new IllegalArgumentException("主产品入库数量必须等于工单需求数量");
            }
            result.add(new Line(item.salesProductId(),
                    product.get("canonical_product_id") instanceof Number n ? n.longValue() : item.salesProductId(),
                    (String)product.get("product_code"),String.valueOf(product.get("product_name")),
                    (String)product.get("specification"),unit,item.quantity(),item.primary()));
        }
        if (primaryCount != 1) throw new IllegalArgumentException("必须且只能有一行主产品");
        return result;
    }

    private Map<String,Object> visibleOrder(long id) {
        var params = new ArrayList<Object>();
        var where = new StringBuilder(" WHERE p.id=? AND p.external_source='H3YUN'"); params.add(id);
        scope.appendByEmployeeId(where,params,"p.employee_id","e.department",FeatureCode.MANUFACTURING);
        var rows = jdbc.queryForList("SELECT p.id,p.external_id,p.work_order_no,p.status,p.required_quantity,"
                + "p.warehoused_quantity,p.warehousing_completed FROM production_orders p "
                + "LEFT JOIN employees e ON e.id=p.employee_id " + where,params.toArray());
        if (rows.isEmpty()) throw new IllegalArgumentException("生产任务单不存在或无权操作");
        return rows.getFirst();
    }

    private Map<String,Object> lockOrder(long id,Map<String,Object> visible) {
        var rows = jdbc.queryForList("SELECT id,external_id,work_order_no,status,required_quantity,"
                + "warehoused_quantity,warehousing_completed FROM production_orders "
                + "WHERE id=? AND external_source='H3YUN' AND BINARY external_id=BINARY ? FOR UPDATE",
                id,visible.get("external_id"));
        if (rows.isEmpty()) throw new IllegalStateException("来源工单已变化，请刷新后重试");
        return rows.getFirst();
    }

    private static BigDecimal quantity(Object value) { return value instanceof BigDecimal n ? n : BigDecimal.ZERO; }
    private static String clean(String value,int max,String label) {
        String text=value==null?"":value.trim();
        if(text.length()>max)throw new IllegalArgumentException(label+"最多"+max+"字");
        return text;
    }
}
