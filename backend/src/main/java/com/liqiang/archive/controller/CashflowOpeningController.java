package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CashflowOpeningService;
import com.liqiang.archive.service.CashflowOpeningConfirmationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;

/** P2-B-3 年度期初管理入口。 */
@RestController
@RequestMapping("/api/cashflow/accounts")
@RequiredArgsConstructor
public class CashflowOpeningController {
    private final CashflowOpeningService service;
    private final CashflowOpeningConfirmationService confirmationService;

    @GetMapping("/{accountId}/opening/{year}")
    @RequirePermission("cashflow.read")
    public Map<String, Object> get(@PathVariable long accountId, @PathVariable int year) {
        return service.get(accountId, year);
    }

    public record ManualOpening(long accountId, int fiscalYear, BigDecimal amount, String basis, String note) {}

    @PostMapping("/opening/manual")
    @RequirePermission("cashflow.manage")
    public Map<String, Object> manual(@RequestBody ManualOpening request,
                                      @RequestHeader("X-Request-Key") String requestKey) {
        return service.createManual(request.accountId(), request.fiscalYear(), request.amount(),
                request.basis(), request.note(), requestKey);
    }

    @PostMapping("/opening/{id}/finance-confirm")
    @RequirePermission("cashflow.opening.confirm")
    public Map<String, Object> financeConfirm(@PathVariable long id,
                                              @RequestBody CashflowOpeningConfirmationService.Request request,
                                              @RequestHeader("X-Request-Key") String requestKey) {
        return confirmationService.confirm(id, request, requestKey);
    }

    @GetMapping("/opening/{id}/confirmations")
    @RequirePermission("cashflow.read")
    public List<Map<String, Object>> confirmationHistory(@PathVariable long id) {
        return confirmationService.history(id);
    }
}
