package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductionFollowUpService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/production-followups")
@RequiredArgsConstructor
@RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all","manufacturing.followup"})
public class ProductionFollowUpController {
    private final ProductionFollowUpService service;
    @GetMapping
    public Map<String,Object> list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String workshop,
            @RequestParam(defaultValue="all") String status,@RequestParam(defaultValue="false") boolean overdue,
            @RequestParam(defaultValue="newest") String orderBy,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int size) {
        return service.list(keyword,workshop,status,overdue,orderBy,page,size);
    }
    @GetMapping("/workshops") public List<String> workshops(){return service.workshops();}
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}
    @PutMapping("/{id}") @RequirePermission("manufacturing.followup")
    public Map<String,Long> save(@PathVariable long id,@RequestBody ProductionFollowUpService.SaveRequest request,
            @RequestHeader("X-Request-Key") String key){return Map.of("version",service.save(id,request,key));}
}
