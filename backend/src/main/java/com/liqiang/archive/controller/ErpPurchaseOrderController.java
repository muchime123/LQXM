package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ErpPurchaseOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 外采采购订单（BUY 履约）。
 *
 * <p>与 {@code ErpSalesOrderController} 对称：读端点要三档读权限之一，
 * 写端点统一要 {@code purchase_order.manage}。行级数据范围在 Service 内按建单人过滤
 * （{@code DataScopeSupport.appendByEmployeeId}，featureCode=PURCHASE_ORDER）——
 * 拦截器管「有没有这个功能」，Service 管「能看到哪几条」，两者都要过。
 */
@RestController
@RequestMapping("/api/erp-purchase-orders")
@RequiredArgsConstructor
public class ErpPurchaseOrderController {
    private final ErpPurchaseOrderService service;

    @GetMapping
    @RequirePermission({"purchase_order.read.self", "purchase_order.read.department", "purchase_order.read.all"})
    public Map<String, Object> list(@RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(defaultValue = "") String status,
                                    @RequestParam(defaultValue = "") String fulfillmentMode,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, status, fulfillmentMode, page, size);
    }

    /** 待采购需求：已确认销售订单里 BUY 明细且尚未挂有效采购订单。 */
    @GetMapping("/demands")
    @RequirePermission({"purchase_order.read.self", "purchase_order.read.department", "purchase_order.read.all"})
    public List<Map<String, Object>> demands() {
        return service.pendingPurchaseDemands();
    }

    /** 供应商联想（h3_supplier_catalog 只读）。 */
    @GetMapping("/suppliers")
    @RequirePermission({"purchase_order.read.self", "purchase_order.read.department", "purchase_order.read.all"})
    public List<Map<String, Object>> suppliers(@RequestParam(defaultValue = "") String keyword) {
        return service.suppliers(keyword);
    }

    /** 有效仓库（到货入库落点）。 */
    @GetMapping("/warehouses")
    @RequirePermission({"purchase_order.read.self", "purchase_order.read.department", "purchase_order.read.all"})
    public List<Map<String, Object>> warehouses() {
        return service.warehouses();
    }

    @GetMapping("/{id}")
    @RequirePermission({"purchase_order.read.self", "purchase_order.read.department", "purchase_order.read.all"})
    public Map<String, Object> detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping
    @RequirePermission("purchase_order.manage")
    public long create(@RequestBody ErpPurchaseOrderService.Request request,
                       @RequestHeader(value = "X-Request-Key", required = false) String key) {
        return service.create(request, key);
    }

    @PostMapping("/{id}/confirm")
    @RequirePermission("purchase_order.manage")
    public void confirm(@PathVariable long id,
                        @RequestHeader(value = "X-Request-Key", required = false) String key) {
        service.confirm(id, key);
    }

    @PostMapping("/{id}/cancel")
    @RequirePermission("purchase_order.manage")
    public void cancel(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body,
                       @RequestHeader(value = "X-Request-Key", required = false) String key) {
        Object reason = body == null ? null : body.get("reason");
        service.cancel(id, reason == null ? null : String.valueOf(reason), key);
    }

    /** 到货入库（仅 WAREHOUSE 入库型）。 */
    @PostMapping("/{id}/receipts")
    @RequirePermission("purchase_order.manage")
    public long receive(@PathVariable long id, @RequestBody ErpPurchaseOrderService.ReceiveRequest request,
                        @RequestHeader(value = "X-Request-Key", required = false) String key) {
        return service.receive(id, request, key);
    }

    /** 到货冲正（反向过账，回退库存与累计到货量）。 */
    @PostMapping("/{id}/receipts/{receiptId}/reverse")
    @RequirePermission("purchase_order.manage")
    public void reverseReceipt(@PathVariable long id, @PathVariable long receiptId,
                               @RequestBody(required = false) Map<String, Object> body,
                               @RequestHeader(value = "X-Request-Key", required = false) String key) {
        Object reason = body == null ? null : body.get("reason");
        service.reverseReceipt(id, receiptId, reason == null ? null : String.valueOf(reason), key);
    }

    /** 生成代发出库单草稿（仅 DROP_SHIP 代发型；过账时豁免扣库存）。 */
    @PostMapping("/{id}/drop-ship-outbound")
    @RequirePermission("purchase_order.manage")
    public long dropShipOutbound(@PathVariable long id,
                                 @RequestHeader(value = "X-Request-Key", required = false) String key) {
        return service.createDropShipOutbound(id, key);
    }
}
