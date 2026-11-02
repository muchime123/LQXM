package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductionTrackingProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/manufacturing/tracking-processes")
@RequiredArgsConstructor
public class ProductionTrackingProcessController {
    private final ProductionTrackingProcessService processes;
    private final com.liqiang.archive.service.ProductionTrackingService tracking;

    @GetMapping("/orders/{orderId}/snapshot")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> snapshot(@PathVariable long orderId) {
        tracking.visibleOrderNo(orderId); // 与跟单详情共用任务可见性检查。
        return processes.snapshot(orderId);
    }

}
