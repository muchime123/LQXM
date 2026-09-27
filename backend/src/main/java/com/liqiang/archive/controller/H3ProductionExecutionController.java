package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3ProductionExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/h3-production-execution")
@RequiredArgsConstructor
@RequirePermission({"manufacturing.read.self", "manufacturing.read.department", "manufacturing.read.all", "manufacturing.report", "manufacturing.report.override", "manufacturing.inspect", "manufacturing.followup"})
public class H3ProductionExecutionController {
    private final H3ProductionExecutionService service;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String state,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(keyword, state, page, size);
    }

    /** 看板统计卡：与列表同一数据范围、同一阶段口径；卡片点击筛选不影响统计分母。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "") String keyword) {
        return service.summary(keyword);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/components/{componentExternalId}/reports")
    @RequirePermission("manufacturing.report")
    public Map<String, Long> report(@PathVariable long id, @PathVariable String componentExternalId,
            @RequestBody H3ProductionExecutionService.ReportRequest request,
            @RequestHeader("X-Request-Key") String requestKey) {
        return Map.of("id", service.report(id, componentExternalId, request, requestKey));
    }

    @PostMapping("/{id}/reports/{reportId}/reverse")
    @RequirePermission("manufacturing.report.override")
    public Map<String, Long> reverse(@PathVariable long id, @PathVariable long reportId,
            @RequestBody H3ProductionExecutionService.ReverseRequest request,
            @RequestHeader("X-Request-Key") String requestKey) {
        service.reverse(id, reportId, request, requestKey);
        return Map.of("id", reportId);
    }
}
