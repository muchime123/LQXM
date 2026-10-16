package com.liqiang.archive.controller;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/product-configurations") @RequiredArgsConstructor
public class ProductConfigurationController {
    private final ProductConfigurationService service;
    @GetMapping("/products")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> products(@RequestParam long workshopId){return service.directory(workshopId);}
    @GetMapping("/products/{id}/rule")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> rule(@PathVariable long id,@RequestParam(required=false)Long ruleId){return service.rule(id,ruleId,false);}
    @PostMapping("/preview")
    @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> preview(@RequestBody ProductConfigurationService.Preview request){return service.preview(request,false);}
    @GetMapping("/products/{id}/rules") @RequirePermission("manufacturing.template.manage")
    public List<Map<String,Object>> history(@PathVariable long id){return service.history(id);}
    @PostMapping("/products/{id}/rules") @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> draft(@PathVariable long id){return service.createDraft(id);}
    @PutMapping("/products/{id}/rules/{ruleId}") @RequirePermission("manufacturing.template.manage")
    public void save(@PathVariable long id,@PathVariable long ruleId,@RequestBody ProductConfigurationService.Save request){service.save(id,ruleId,request);}
    @PostMapping("/products/{id}/rules/{ruleId}/publish") @RequirePermission("manufacturing.template.manage")
    public void publish(@PathVariable long id,@PathVariable long ruleId,@RequestBody Map<String,Integer> request){
        if(request.get("revision")==null)throw new IllegalArgumentException("缺少草稿修订号");service.publish(id,ruleId,request.get("revision"));}
    @PostMapping("/products/{id}/rules/{ruleId}/preview") @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> draftPreview(@PathVariable long id,@PathVariable long ruleId,@RequestBody Map<String,Object> config){return service.preview(new ProductConfigurationService.Preview(id,ruleId,config),true);}
}
