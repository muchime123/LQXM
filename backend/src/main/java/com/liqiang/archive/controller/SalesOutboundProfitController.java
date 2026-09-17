package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.SalesOutboundProfitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 销售出库订单利润 REST API（方案：docs/current-sales-profit-design.md §12）。
 */
@RestController
@RequestMapping("/api/sales-outbound-profits")
@RequiredArgsConstructor
@RequirePermission({"order_profit.read.self", "order_profit.read.department", "order_profit.read.all"})
public class SalesOutboundProfitController {

    private final SalesOutboundProfitService service;

    /** 分页查询订单利润。无核算期概念，覆盖全部发货期间的已确认出库。 */
    @GetMapping
    public Map<String, Object> page(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long customerId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.pageOutboundProfits(keyword, status, page, Math.min(size, 100), customerId);
    }

    /** 单张销售出库利润详情（含调整、费用、提成）。 */
    @GetMapping("/{outboundId:\\d+}")
    public Map<String, Object> detail(@PathVariable Long outboundId) {
        return service.outboundProfitDetail(outboundId);
    }

    /** 单笔重算。 */
    @PostMapping("/{outboundId:\\d+}/recalculate")
    @RequirePermission("order_profit.manage")
    public Map<String, Object> recalculate(@PathVariable Long outboundId) {
        return service.recalculate(outboundId, null);
    }

    /** 新增利润调整。 */
    @PostMapping("/adjustments")
    @RequirePermission("order_profit.manage")
    public Map<String, Object> addAdjustment(@RequestBody Map<String, Object> body) {
        Long outboundId = ((Number) body.get("salesOutboundId")).longValue();
        String type = (String) body.get("adjustmentType");
        BigDecimal amount = new BigDecimal(String.valueOf(body.get("amount")));
        boolean plus = body.get("direction") == null || "PLUS".equals(body.get("direction"));
        boolean affectsCommission = body.get("affectsCommission") == null
                || Boolean.parseBoolean(String.valueOf(body.get("affectsCommission")));
        String reason = (String) body.get("reason");
        return service.addAdjustment(outboundId, type, amount, plus, affectsCommission, reason);
    }

    /** 撤销利润调整。 */
    @PostMapping("/adjustments/{id}/reverse")
    @RequirePermission("order_profit.manage")
    public Map<String, Object> reverseAdjustment(@PathVariable Long id) {
        return service.reverseAdjustment(id);
    }
}
