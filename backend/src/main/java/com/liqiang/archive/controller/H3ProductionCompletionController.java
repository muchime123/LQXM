package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3ProductionCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/h3-production-completions")
@RequiredArgsConstructor
@RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all",
        "manufacturing.report","manufacturing.report.override","manufacturing.followup","manufacturing.inspect"})
public class H3ProductionCompletionController {
    private final H3ProductionCompletionService service;

    @GetMapping("/warehouses")
    public List<Map<String,Object>> warehouses() { return service.warehouses(); }

    @GetMapping("/products")
    public List<Map<String,Object>> products(@RequestParam(defaultValue="") String keyword) {
        return service.productOptions(keyword);
    }

    @GetMapping("/{orderId}")
    public Map<String,Object> current(@PathVariable long orderId) { return service.current(orderId); }

    @PostMapping("/{orderId}")
    @RequirePermission("manufacturing.inspect")
    public Map<String,Long> complete(@PathVariable long orderId,
            @RequestBody H3ProductionCompletionService.CompleteRequest request,
            @RequestHeader("X-Request-Key") String key) {
        return Map.of("id",service.complete(orderId,request,key));
    }

    @PostMapping("/{orderId}/{completionId}/reverse")
    @RequirePermission("manufacturing.inspect")
    public Map<String,Long> reverse(@PathVariable long orderId,@PathVariable long completionId,
            @RequestBody H3ProductionCompletionService.ReverseRequest request,
            @RequestHeader("X-Request-Key") String key) {
        service.reverse(orderId,completionId,request,key);
        return Map.of("id",completionId);
    }
}
