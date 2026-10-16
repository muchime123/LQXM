package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.SalesOutboundProfitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 销售出库订单利润 REST API。
 *
 * <p>2026-10-07 收敛：仅保留分页查询（客户档案「业务」列表使用）。
 * 详情、单笔重算、利润调整/撤销等人工写端点已下线——利润重算仅由氚云导入流程内部触发，
 * 调整链路随旧业务流一并废弃。
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
}
