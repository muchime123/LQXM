package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ErpSalesOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/erp-sales-orders")
@RequiredArgsConstructor
public class ErpSalesOrderController {
    private final ErpSalesOrderService service;

    @GetMapping("/products")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public java.util.List<Map<String, Object>> products(@RequestParam(defaultValue = "") String keyword) {
        return service.products(keyword);
    }

    @GetMapping("/production-workshops")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public java.util.List<Map<String, Object>> productionWorkshops() { return service.productionWorkshops(); }

    @GetMapping("/production-options")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public Map<String, Object> productionOptions(@RequestParam long workshopId) {
        return service.productionOptions(workshopId);
    }

    @GetMapping("/production-demands")
    @RequirePermission("manufacturing.manage")
    public java.util.List<Map<String, Object>> pendingProductionDemands() {
        return service.pendingProductionDemands();
    }

    @PostMapping("/{id}/items/{itemId}/accept-production")
    @RequirePermission("manufacturing.manage")
    public long acceptProductionDemand(@PathVariable long id, @PathVariable long itemId,
                                       @RequestHeader(value = "X-Request-Key", required = false) String key) {
        return service.acceptProductionDemand(id, itemId, key);
    }

    @GetMapping
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public Map<String, Object> list(@RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(defaultValue = "") String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, status, page, size);
    }

    @GetMapping("/{id}")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public Map<String, Object> detail(@PathVariable long id) { return service.detail(id); }

    @PostMapping
    @RequirePermission("sales_order.manage")
    public long create(@RequestBody ErpSalesOrderService.Request request,
                       @RequestHeader(value = "X-Request-Key", required = false) String key) {
        return service.create(request, key);
    }

    @PutMapping("/{id}")
    @RequirePermission("sales_order.manage")
    public long update(@PathVariable long id, @RequestBody ErpSalesOrderService.Request request,
                       @RequestHeader(value = "X-Request-Key", required = false) String key) {
        return service.update(id, request, key);
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission("sales_order.manage")
    public void confirm(@PathVariable long id,
                        @RequestHeader(value = "X-Request-Key", required = false) String key) {
        service.confirm(id, key);
    }

    @PostMapping("/{id}/items/{itemId}/outbound-draft")
    @RequirePermission("sales_order.manage")
    public long createOutboundDraft(@PathVariable long id, @PathVariable long itemId,
                                    @RequestBody Map<String, Object> request,
                                    @RequestHeader(value = "X-Request-Key", required = false) String key) {
        Long warehouseId = request.get("warehouseId") instanceof Number value ? value.longValue() : null;
        if (warehouseId == null) throw new IllegalArgumentException("请选择发货仓库");
        java.math.BigDecimal quantity = request.get("quantity") == null ? null
                : new java.math.BigDecimal(String.valueOf(request.get("quantity")));
        return service.createOutboundDraft(id, itemId, warehouseId, quantity, key);
    }
}
