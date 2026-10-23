package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CashflowEntityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资金归属主体与账户管理（P2-A，2026-10-23）。
 * 读端点挂 cashflow.read，写端点挂 cashflow.manage（LQ：财务数据不默认全员可见，
 * 且不凭 integration.h3 放行）。
 */
@RestController
@RequestMapping("/api/cashflow")
@RequiredArgsConstructor
public class CashflowEntityController {

    private final CashflowEntityService service;

    @GetMapping("/entities")
    @RequirePermission("cashflow.read")
    public Map<String, Object> entities() {
        return service.entities();
    }

    @PostMapping("/entities")
    @RequirePermission("cashflow.manage")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(str(body.get("name")), str(body.get("entityType")), str(body.get("note")));
    }

    @PutMapping("/entities/{id}")
    @RequirePermission("cashflow.manage")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        service.update(id, str(body.get("name")), str(body.get("entityType")),
                str(body.get("status")), str(body.get("note")));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("updated", id);
        return out;
    }

    /** 删除主体：仅空主体（无账户认领）可删；409=仍有账户。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/entities/{id}")
    @RequirePermission("cashflow.manage")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        try {
            service.delete(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 账户简单列表（含所属主体与状态），供认领弹窗与按主体筛选使用。 */
    @GetMapping("/accounts")
    @RequirePermission("cashflow.read")
    public java.util.List<java.util.Map<String, Object>> accounts() {
        return service.accounts();
    }

    /** 建议初值：只建议不落库（D1——15 组前缀仅作界面预填，财务确认后才成为正式主体）。 */
    @GetMapping("/entities/suggest")
    @RequirePermission("cashflow.read")
    public Map<String, Object> suggest() {
        return service.suggest();
    }

    /** 批量认领：[{accountId, entityId|null}]；entityId=null=撤销认领。 */
    @PostMapping("/accounts/assign-entity")
    @RequirePermission("cashflow.manage")
    public Map<String, Object> assign(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignments = (List<Map<String, Object>>) body.get("assignments");
        if (assignments == null) throw new IllegalArgumentException("assignments 不能为空");
        return service.assignEntities(assignments);
    }

    @PutMapping("/accounts/{id}/status")
    @RequirePermission("cashflow.manage")
    public ResponseEntity<Map<String, Object>> setStatus(@PathVariable long id,
                                                         @RequestBody Map<String, Object> body) {
        service.setAccountStatus(id, str(body.get("status")));
        return ResponseEntity.ok(Map.of("updated", id, "status", str(body.get("status"))));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
