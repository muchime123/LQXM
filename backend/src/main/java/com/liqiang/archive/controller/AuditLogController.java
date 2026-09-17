package com.liqiang.archive.controller;

import com.liqiang.archive.entity.AuditLog;
import com.liqiang.archive.repository.AuditLogRepository;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * 操作日志查询端点（系统管理 → 操作日志）。
 * 写入走 AuditLogService.log/logBatch（由各业务 Service 调用），这里只读。
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@RequirePermission("system.employee.manage")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public Page<AuditLog> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditLogService.listFiltered(entityType, action,
                parseDate(dateFrom, false), parseDate(dateTo, true), keyword,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    /** 筛选候选：实体类型 / 操作动作 */
    @GetMapping("/facets")
    public Map<String, Object> facets() {
        return Map.of(
                "entityTypes", auditLogRepository.findDistinctEntityTypes(),
                "actions", auditLogRepository.findDistinctActions()
        );
    }

    private static LocalDateTime parseDate(String s, boolean endOfDay) {
        if (s == null || s.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(s.trim());
            return endOfDay ? d.atTime(LocalTime.MAX) : d.atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }
}
