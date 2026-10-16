package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3ProductCategoryGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/h3-product-category-groups")
@RequiredArgsConstructor
public class H3ProductCategoryGroupController {
    private final H3ProductCategoryGroupService service;

    @GetMapping
    @RequirePermission({"product_inventory.read","integration.h3"})
    public List<Map<String,Object>> categories(){return service.categories();}

    public record Assignment(String sourceCategory,String productGroup) {}

    @PutMapping
    @RequirePermission("integration.h3")
    public void assign(@RequestBody Assignment request){
        if(request==null)throw new IllegalArgumentException("请选择产品类目");
        service.assign(request.sourceCategory(),request.productGroup());
    }

    public record BatchAssignment(List<String> sourceCategories,String productGroup) {}

    @PutMapping("/batch")
    @RequirePermission("integration.h3")
    public Map<String,Integer> assignBatch(@RequestBody BatchAssignment request){
        if(request==null)throw new IllegalArgumentException("请先选择产品类目");
        return Map.of("updated",service.assignBatch(request.sourceCategories(),request.productGroup()));
    }
}
