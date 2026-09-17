package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.SalesOutboundCommissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 销售出库订单提成 REST API（方案：docs/current-sales-profit-design.md §12）。
 */
@RestController
@RequestMapping("/api/sales-outbound-commissions")
@RequiredArgsConstructor
@RequirePermission({"order_commission.read.self", "order_commission.read.department", "order_commission.read.all"})
public class SalesOutboundCommissionController {

    private final SalesOutboundCommissionService service;

    /** 分页查询订单提成。 */
    @GetMapping
    public Map<String, Object> page(@RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.pageCommissions(keyword, page, Math.min(size, 100));
    }

    /** 单张销售出库提成详情。 */
    @GetMapping("/{outboundId}")
    public Map<String, Object> detail(@PathVariable Long outboundId) {
        return service.commissionDetail(outboundId);
    }

    /** 单笔重算提成（需先有利润结果）。 */
    @PostMapping("/{outboundId}/recalculate")
    @RequirePermission("order_commission.manage")
    public Map<String, Object> recalculate(@PathVariable Long outboundId) {
        Map<String, Object> detail = service.commissionDetail(outboundId);
        Long profitId = detail.get("profitId") == null ? null : ((Number) detail.get("profitId")).longValue();
        java.math.BigDecimal commissionable = detail.get("commissionableProfit") == null
                ? java.math.BigDecimal.ZERO : (java.math.BigDecimal) detail.get("commissionableProfit");
        service.recalculate(outboundId, profitId, commissionable, null);
        return service.commissionDetail(outboundId);
    }
}
