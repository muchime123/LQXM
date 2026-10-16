package com.liqiang.archive.controller;

import com.liqiang.archive.dto.ExpenseDtos;
import com.liqiang.archive.dto.ExpenseDtos.*;
import com.liqiang.archive.dto.ExpenseImportDtos;
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
 * 日常报销 REST API（报销台账 + 钉钉同步）。
 *
 * <p>2026-10-07 收敛：仅保留报销单的查询、批量编辑与钉钉导入。
 * 费用归单/分配（allocation）链路随旧业务流一并下线——
 * 报销数据仅作台账留档，不再归集到销售出库等业务单据。
 */
@RestController
@RequestMapping("/api/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService service;
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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ClaimRow> p = service.searchClaims(new ExpenseDtos.SearchParams(
                keyword, employeeId, status, category, dateFrom, dateTo, page, size));
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

    /** 批量编辑报销单字段（白名单 + 类型转换 + 审计）。 */
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
