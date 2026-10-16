package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3SalesProductAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/h3-sales-product-analysis")
@RequiredArgsConstructor
@RequirePermission("integration.h3")
public class H3SalesProductAnalysisController {
    private final H3SalesProductAnalysisService service;

    @GetMapping
    public Map<String,Object> analyze(@RequestParam(defaultValue="") String keyword,
                                      @RequestParam(defaultValue="ALL") String view,
                                      @RequestParam(defaultValue="") String category,
                                      @RequestParam(defaultValue="") String productGroup,
                                      @RequestParam(defaultValue="false") boolean onlyInStock,
                                      @RequestParam(defaultValue="") String sort,
                                      @RequestParam(defaultValue="") String direction,
                                      @RequestParam(defaultValue="") String dateFrom,
                                      @RequestParam(defaultValue="") String dateTo,
                                      @RequestParam(defaultValue="1") int page,
                                      @RequestParam(defaultValue="30") int size) {
        return service.analyze(keyword,view,category,productGroup,onlyInStock,sort,direction,
                dateFrom,dateTo,page,size);
    }
}
