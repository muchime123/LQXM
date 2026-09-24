package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ErpApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通用审批（决策登记 #35）。三条链（销售订单 / 生产任务单 / 采购订单）共用。
 *
 * <p>这里只放**跨单据类型**的两个端点（待办清单、历史查询）；
 * 「提交 / 通过 / 驳回」放在各自单据的 Controller 上 —— 那样权限码能精确到单据族
 * （{@code purchase_order.approve} 而不是笼统的 {@code approval.approve}），
 * 也避免审计动作名与业务单据脱节。
 *
 * <p>准入 = 任意一个 {@code *.approve} 权限。⚠️ 不做行级可见性过滤：审批人通常不是建单人。
 */
@RestController
@RequestMapping("/api/erp-approvals")
@RequiredArgsConstructor
public class ErpApprovalController {

    private final ErpApprovalService service;

    /** 待审批清单（审批人的待办）：三张表里所有处于待审批状态的单据。 */
    @GetMapping("/pending")
    @RequirePermission({"sales_order.approve", "manufacturing.approve", "purchase_order.approve"})
    public List<Map<String, Object>> pending() {
        return service.pending();
    }

    /** 某张单据的完整审批历史（含驳回）。 */
    @GetMapping("/{docType}/{docId}")
    @RequirePermission({"sales_order.approve", "manufacturing.approve", "purchase_order.approve"})
    public List<Map<String, Object>> history(@PathVariable String docType, @PathVariable long docId) {
        return service.history(ErpApprovalService.DocType.of(docType), docId);
    }
}
