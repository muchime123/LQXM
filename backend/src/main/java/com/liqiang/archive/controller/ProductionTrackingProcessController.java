package com.liqiang.archive.controller;

import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductionTrackingProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manufacturing/tracking-processes")
@RequiredArgsConstructor
public class ProductionTrackingProcessController {
    private final ProductionTrackingProcessService processes;
    private final com.liqiang.archive.service.ProductionTrackingService tracking;
    private final ActorResolver actors;

    @GetMapping("/products/{productConfigId}/versions")
    @RequirePermission({"manufacturing.template.manage","manufacturing.baseline.manage"})
    public List<Map<String,Object>> versions(@PathVariable long productConfigId) {
        return processes.versions(productConfigId);
    }

    @GetMapping("/versions/{templateId}")
    @RequirePermission({"manufacturing.template.manage","manufacturing.baseline.manage"})
    public Map<String,Object> version(@PathVariable long templateId) {
        return processes.version(templateId);
    }

    @GetMapping("/versions/{templateId}/duration-samples")
    @RequirePermission("manufacturing.baseline.manage")
    public Map<String,Object> versionDurationSamples(@PathVariable long templateId) {
        return processes.versionDurationSamples(templateId);
    }

    @GetMapping("/orders/{orderId}/snapshot")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> snapshot(@PathVariable long orderId) {
        tracking.visibleOrderNo(orderId); // 与跟单详情共用任务可见性检查。
        return processes.snapshot(orderId);
    }

    @PostMapping("/orders/{orderId}/legacy-snapshot")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> captureLegacy(@PathVariable long orderId) {
        tracking.visibleOrderNo(orderId);
        processes.captureLegacyOrder(orderId,actors.current().name());
        return processes.snapshot(orderId);
    }

    @GetMapping("/legacy-preview")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> legacyPreview(@RequestParam(defaultValue="100") int limit) {
        return processes.legacyPreview(limit);
    }

    @GetMapping("/orders/{orderId}/legacy-preview")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> legacyOrderPreview(@PathVariable long orderId) {
        tracking.visibleOrderNo(orderId);
        return processes.legacyOrderPreview(orderId);
    }

    @PostMapping("/legacy-batch")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> captureLegacyBatch(@RequestBody ProductionTrackingProcessService.LegacyBatch request) {
        if(request!=null&&request.orderIds()!=null)
            for(Long id:request.orderIds())if(id!=null)tracking.visibleOrderNo(id);
        return processes.captureLegacyBatch(request,actors.current().name());
    }

    @PostMapping("/orders/{orderId}/revision")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Integer> revise(@PathVariable long orderId,
            @RequestBody ProductionTrackingProcessService.Revision request) {
        tracking.visibleOrderNo(orderId);
        return Map.of("revisionNo",processes.revise(orderId,request,actors.current().name()));
    }

    @PostMapping("/products/{productConfigId}/versions")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Long> create(@PathVariable long productConfigId,
            @RequestBody ProductionTrackingProcessService.Draft draft) {
        return Map.of("id",processes.createDraft(productConfigId,draft,actors.current().name()));
    }

    @PostMapping("/versions/{templateId}/publish")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Long> publish(@PathVariable long templateId) {
        processes.publish(templateId,actors.current().name());
        return Map.of("id",templateId);
    }
}
