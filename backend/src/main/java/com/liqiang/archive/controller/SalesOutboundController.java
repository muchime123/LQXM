package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.SalesOutboundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ERP 正式销售出库单接口（P2）。
 *
 * <p>权限复用既有的 {@code sales_order.*}（当前项目里销售出库单就是销售单据本身）。
 * ⚠️ 注意读取接口**必须同时列出三个 read 变体**：{@code @RequirePermission} 是「任一满足」语义，
 * 只写 {@code read.all} 会让只有 {@code read.self} 的业务员被挡在门外。
 *
 * <p>写动作（建单 / 改单 / 过账 / 冲销）全部要求 {@code X-Request-Key}——
 * 决策 #12「无键不直通」：缺键由幂等模板直接拒绝，不会退化成「无保护直写」。
 */
@RestController
@RequestMapping("/api/sales-outbounds")
@RequiredArgsConstructor
public class SalesOutboundController {

    private final SalesOutboundService service;

    @GetMapping("/warehouses")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public List<Map<String, Object>> warehouses() {
        return service.warehouses();
    }

    /** 可售库存：建单时选货用。返回空列表 = 没有现货，是「该下生产任务」的业务信号而非错误。 */
    @GetMapping("/available-stock")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public List<Map<String, Object>> availableStock(@RequestParam(defaultValue = "") String keyword,
                                                    @RequestParam(required = false) Long warehouseId,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return service.availableStock(keyword, warehouseId, limit);
    }

    @GetMapping
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public Map<String, Object> list(@RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(defaultValue = "") String status,
                                    @RequestParam(required = false) Long warehouseId,
                                    @RequestParam(required = false) Long customerId,
                                    @RequestParam(defaultValue = "") String dateFrom,
                                    @RequestParam(defaultValue = "") String dateTo,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, status, warehouseId, customerId, dateFrom, dateTo, page, size);
    }

    @GetMapping("/{id}")
    @RequirePermission({"sales_order.read.self", "sales_order.read.department", "sales_order.read.all"})
    public Map<String, Object> detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping
    @RequirePermission("sales_order.manage")
    public long create(@RequestBody SalesOutboundService.OutboundRequest request,
                       @RequestHeader(value = "X-Request-Key", required = false) String requestKey) {
        return service.create(request, requestKey);
    }

    @PutMapping("/{id}")
    @RequirePermission("sales_order.manage")
    public long update(@PathVariable long id, @RequestBody SalesOutboundService.OutboundRequest request,
                       @RequestHeader(value = "X-Request-Key", required = false) String requestKey) {
        return service.update(id, request, requestKey);
    }

    /** 过账：扣库存 + 写库存流水。库存不足时整体回滚。 */
    @PostMapping("/{id}/post")
    @RequirePermission("sales_order.manage")
    public void post(@PathVariable long id,
                     @RequestHeader(value = "X-Request-Key", required = false) String requestKey) {
        service.post(id, requestKey);
    }

    /** 冲销：回补库存。终态，冲销后要重发请新建一张。 */
    @PostMapping("/{id}/reverse")
    @RequirePermission("sales_order.manage")
    public void reverse(@PathVariable long id, @RequestBody Map<String, String> request,
                        @RequestHeader(value = "X-Request-Key", required = false) String requestKey) {
        service.reverse(id, request.get("reason"), requestKey);
    }
}
