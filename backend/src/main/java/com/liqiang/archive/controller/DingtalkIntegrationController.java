package com.liqiang.archive.controller;

import com.liqiang.archive.dto.ExpenseDtos;
import com.liqiang.archive.dto.ExpenseDtos.SyncRunDetail;
import com.liqiang.archive.dto.ExpenseDtos.SyncTriggered;
import com.liqiang.archive.service.DingtalkNotEnabledException;
import com.liqiang.archive.service.DingtalkSyncService;
import com.liqiang.archive.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * P6.1 钉钉集成管理 API。
 * <p>
 * 安全原则（ChatGPT 总体设计 §十 & §二十一）：
 * <ul>
 *   <li>AppSecret 等凭证永远只放后端；本控制器绝不返回任何凭证字段。</li>
 *   <li>GET /status 返回 AppKey 掩码后的几位数字 + enabled/configured 布尔，供前端「集成卡片」展示连接状态。</li>
 *   <li>同步端点 /integrations/dingtalk/sync/reimbursements 前端仅发触发指令，不参与签名。</li>
 *   <li>未启用或未配置凭证时：返回 HTTP 503 Service Unavailable + 明确中文提示（引导管理员配置）。</li>
 * </ul>
 * 建议权限（P8 权限系统落地前先通过菜单隐藏控制）：integration.dingtalk.read / manage。
 */
@RestController
@RequestMapping("/api/integrations/dingtalk")
@RequiredArgsConstructor
@RequirePermission("integration.dingtalk")
public class DingtalkIntegrationController {

    private final DingtalkSyncService service;

    /**
     * 连接状态。前端「钉钉集成」卡片渲染。
     * 绝不返回 Secret，只返回掩码 AppKey + 布尔状态。
     */
    @GetMapping("/status")
    public ExpenseDtos.DingtalkStatus status() {
        return service.getStatus();
    }

    /**
     * 手动触发：同步日常报销单据。
     *
     * @return runId 与中文提示；前端可跳同步记录查看错误明细。
     * @throws ResponseStatusException HTTP 503 — 后端未启用或未配置钉钉凭证
     */
    @PostMapping("/sync/reimbursements")
    public SyncTriggered syncReimbursements(
            @RequestParam(value = "daysBack", required = false) Integer daysBack,
            @RequestHeader(value = "X-User-Name", required = false) String userName) {
        try {
            return service.triggerReimbursementSync(userName, daysBack);
        } catch (DingtalkNotEnabledException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getReason());
        }
    }

    /** 同步记录分页（按时间倒序）。 */
    @GetMapping("/sync-runs")
    public Map<String, Object> syncRuns(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<ExpenseDtos.SyncRunRow> p = service.listRuns(page, size);
        Map<String, Object> m = new HashMap<>();
        m.put("items", p.getContent());
        m.put("total", p.getTotalElements());
        m.put("page", p.getNumber() + 1);
        m.put("size", p.getSize());
        return m;
    }

    /** 单次同步详情（含完整错误明细）。 */
    @GetMapping("/sync-runs/{id}")
    public SyncRunDetail syncRunDetail(@PathVariable Long id) {
        SyncRunDetail d = service.getRunDetail(id);
        if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "同步记录不存在（id=" + id + "）");
        return d;
    }
}
