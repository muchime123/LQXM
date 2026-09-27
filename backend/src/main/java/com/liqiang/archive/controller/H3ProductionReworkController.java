package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3ProductionReworkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/h3-production-reworks")
@RequiredArgsConstructor
@RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all",
        "manufacturing.report","manufacturing.report.override","manufacturing.inspect","manufacturing.followup"})
public class H3ProductionReworkController {
    private final H3ProductionReworkService service;

    @GetMapping("/{orderId}")
    public Map<String,Object> current(@PathVariable long orderId) { return service.current(orderId); }

    @PostMapping("/{orderId}/fail")
    @RequirePermission("manufacturing.inspect")
    public Map<String,Long> fail(@PathVariable long orderId,
            @RequestBody H3ProductionReworkService.FailRequest request,
            @RequestHeader("X-Request-Key") String key) {
        return Map.of("id",service.fail(orderId,request,key));
    }

    @PostMapping("/{orderId}/{reworkId}/reports")
    @RequirePermission("manufacturing.report")
    public Map<String,Long> report(@PathVariable long orderId,@PathVariable long reworkId,
            @RequestBody H3ProductionReworkService.ReportRequest request,
            @RequestHeader("X-Request-Key") String key) {
        return Map.of("id",service.report(orderId,reworkId,request,key));
    }

    @PostMapping("/{orderId}/{reworkId}/reports/{reportId}/reverse")
    @RequirePermission("manufacturing.report.override")
    public Map<String,Long> reverse(@PathVariable long orderId,@PathVariable long reworkId,
            @PathVariable long reportId,@RequestBody H3ProductionReworkService.ReverseRequest request,
            @RequestHeader("X-Request-Key") String key) {
        service.reverse(orderId,reworkId,reportId,request,key);
        return Map.of("id",reportId);
    }
}
