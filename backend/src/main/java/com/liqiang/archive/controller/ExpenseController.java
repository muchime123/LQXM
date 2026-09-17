package com.liqiang.archive.controller;

import com.liqiang.archive.dto.ExpenseDtos;
import com.liqiang.archive.dto.ExpenseDtos.*;
import com.liqiang.archive.dto.ExpenseImportDtos;
import com.liqiang.archive.service.ExpenseAllocationService;
import com.liqiang.archive.service.ExpenseImportService;
import com.liqiang.archive.service.ExpenseService;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P6.1 + P6.2 费用中心 REST API。
 * <p>
 * 端点：
 * <ul>
 *   <li>GET  /api/expenses/stats — 顶部指标卡统计（含待归集真实计数）</li>
 *   <li>GET  /api/expenses — 分页搜索</li>
 *   <li>GET  /api/expenses/{id} — 详情（含明细）</li>
 *   <li>GET  /api/expenses/{id}/allocations/summary — 分配合计概览（核定 / 已分配 / 剩余 / 撤销历史）</li>
 *   <li>GET  /api/expenses/{id}/allocations — 分配行列表（含 includeReversed）</li>
 *   <li>POST /api/expenses/allocations/{allocationId}/reverse — 软撤销单条分配</li>
 *   <li>POST /api/expenses/allocations/{allocationId}/confirm — 订单费用确认（PENDING/DRAFT → CONFIRMED）</li>
 *   <li>GET  /api/expenses/allocations/by-target — 反向查询（某业务/部门/公司/对公付款的归集费用）</li>
 *   <li>GET  /api/expenses/allocations/pending-outbound — 待财务裁决的销售出库费用分页</li>
 *   <li>GET  /api/expenses/allocations/targets/search — 销售出库目标候选搜索</li>
 * </ul>
 * <p>旧自动归集端点（auto-allocate / allocation-rules CRUD / runs / legacy-migration / batch-save）已于 2026-09 下线。</p>
 */
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService service;
    private final ExpenseAllocationService allocService;
    private final ExpenseImportService expenseImportService;

    @GetMapping("/stats")
    @RequirePermission({"expense.read.self","expense.manage"})
    public ExpenseStats stats() {
        return service.getStats();
    }

    @GetMapping
    @RequirePermission({"expense.read.self","expense.manage"})
    public Map<String, Object> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "false") boolean unallocatedOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ClaimRow> p = service.searchClaims(new ExpenseDtos.SearchParams(
                keyword, employeeId, status, category, dateFrom, dateTo, page, size, unallocatedOnly));
        Map<String, Object> m = new HashMap<>();
        m.put("items", p.getContent());
        m.put("total", p.getTotalElements());
        m.put("page", p.getNumber() + 1);
        m.put("size", p.getSize());
        return m;
    }

    @GetMapping("/{id}")
    @RequirePermission({"expense.read.self","expense.manage"})
    public ClaimDetail detail(@PathVariable Long id) {
        ClaimDetail d = service.getDetail(id);
        if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报销单不存在（id=" + id + "）");
        return d;
    }

    /** P1-D 批量编辑报销单字段（白名单 + 类型转换 + 审计）。 */
    @PutMapping("/batch")
    @RequirePermission("expense.manage")
    public Map<String, Integer> batchUpdateFields(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Number> idNumbers = (List<Number>) body.get("ids");
            List<Long> ids = idNumbers.stream().map(Number::longValue).toList();
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) body.get("fields");
            int count = service.batchUpdateFields(ids, fields);
            return Map.of("affected", count);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        }
    }

    // ==================== P6.2 费用归集 ====================

    @GetMapping("/{id}/allocations/summary")
    @RequirePermission({"expense.read.self","expense.manage"})
    public AllocationSummary allocationsSummary(@PathVariable Long id) {
        try {
            return allocService.getSummary(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/{id}/allocations")
    @RequirePermission({"expense.read.self","expense.manage"})
    public Map<String, Object> allocations(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includeReversed) {
        List<AllocationRow> list = allocService.listAllocations(id, includeReversed);
        Map<String, Object> m = new HashMap<>();
        m.put("items", list);
        m.put("total", list.size());
        return m;
    }


    @PostMapping("/allocations/{allocationId}/reverse")
    @RequirePermission("expense.manage")
    public AllocationRow allocationReverse(
            @PathVariable Long allocationId,
            @RequestParam(required = false) String reason,
            @RequestHeader(value = "X-User-Name", required = false) String operator) {
        try {
            return allocService.reverseOne(allocationId, reason, operator);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /** 历史订单费用确认：仅更新归集审计状态，不参与销售出库利润或月度提成。 */
    @PostMapping("/allocations/{allocationId}/confirm")
    @RequirePermission({"expense.manage", "order_expense.manage"})
    public AllocationRow allocationConfirm(
            @PathVariable Long allocationId,
            @RequestHeader(value = "X-User-Name", required = false) String operator) {
        try {
            return allocService.confirmOne(allocationId, operator);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/allocations/by-target")
    @RequirePermission("expense.manage")
    public AllocationForTarget allocationsByTarget(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        try {
            return allocService.listByTarget(targetType, targetId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/allocations/pending-outbound")
    @RequirePermission({"expense.manage", "order_expense.manage"})
    public Map<String, Object> pendingOutboundAllocations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return allocService.pendingOutboundAllocations(page, size);
    }

    @GetMapping("/allocations/targets/search")
    @RequirePermission("expense.manage")
    public Map<String, Object> targetSearch(
            @RequestParam String targetType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Page<TargetCandidate> p = allocService.searchTargetCandidates(targetType, keyword, page, size);
            Map<String, Object> m = new HashMap<>();
            m.put("items", p.getContent());
            m.put("total", p.getTotalElements());
            m.put("page", p.getNumber() + 1);
            m.put("size", p.getSize());
            return m;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    // ==================== 钉钉报销 Excel 导入 ====================

    /**
     * 解析钉钉「日常报销」导出 Excel，返回分组预览（不落库）。
     * 上传成功后前端展示分组统计与明细，确认后调 /import/confirm。
     */
    @PostMapping("/import/preview")
    @RequirePermission("expense.manage")
    public ExpenseImportDtos.ImportPreview importPreview(@RequestParam("file") MultipartFile file) {
        try {
            return expenseImportService.preview(file);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * 确认导入：重新解析 Excel，按审批编号幂等落库。
     * approvalNos 为表单多值参数，省略时导入文件内全部单。
     */
    @PostMapping("/import/confirm")
    @RequirePermission("expense.manage")
    public ExpenseImportDtos.ImportResult importConfirm(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "approvalNos", required = false) List<String> approvalNos) {
        try {
            return expenseImportService.confirm(file, approvalNos);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

}
