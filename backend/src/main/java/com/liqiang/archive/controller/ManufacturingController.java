package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ManufacturingExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Production process and operation master data. */
@RestController
@RequestMapping("/api/manufacturing")
@RequiredArgsConstructor
public class ManufacturingController {
    private final ManufacturingExecutionService execution;
    @GetMapping("/master/operations") @RequirePermission("manufacturing.template.manage")
    public List<Map<String,Object>> operationCatalog(){return execution.operationCatalog();}
    @PostMapping("/master/operations") @RequirePermission("manufacturing.template.manage")
    public long createOperation(@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.OperationMasterRequest request){return execution.saveOperation(null,request);}
    @PutMapping("/master/operations/{id}") @RequirePermission("manufacturing.template.manage")
    public long updateOperation(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.OperationMasterRequest request){return execution.saveOperation(id,request);}
    @GetMapping("/master/templates") @RequirePermission("manufacturing.template.manage")
    public List<Map<String,Object>> templateCatalog(){return execution.templateCatalog();}
    @GetMapping("/master/templates/{id}") @RequirePermission("manufacturing.template.manage")
    public Map<String,Object> templateDetail(@PathVariable long id){return execution.templateDetail(id);}
    @PostMapping("/master/templates") @RequirePermission("manufacturing.template.manage")
    public long createTemplate(@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.TemplateMasterRequest request){return execution.saveTemplate(null,request);}
    @PutMapping("/master/templates/{id}") @RequirePermission("manufacturing.template.manage")
    public long updateTemplate(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.TemplateMasterRequest request){return execution.saveTemplate(id,request);}
    @PutMapping("/master/templates/{id}/operations") @RequirePermission("manufacturing.template.manage")
    public void replaceTemplateOperations(@PathVariable long id,@RequestBody List<com.liqiang.archive.service.ManufacturingExecutionService.TemplateOperationRequest> rows){execution.replaceTemplateOperations(id,rows);}
    @GetMapping("/master/templates/{id}/rules") @RequirePermission("manufacturing.template.manage")
    public List<Map<String,Object>> templateRules(@PathVariable long id){return execution.templateRules(id);}
    @PostMapping("/master/templates/{id}/rules") @RequirePermission("manufacturing.template.manage")
    public long createTemplateRule(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.TemplateRuleRequest request){return execution.saveTemplateRule(id,null,request);}
    @PutMapping("/master/templates/{id}/rules/{ruleId}") @RequirePermission("manufacturing.template.manage")
    public long updateTemplateRule(@PathVariable long id,@PathVariable long ruleId,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.TemplateRuleRequest request){return execution.saveTemplateRule(id,ruleId,request);}
    @DeleteMapping("/master/templates/{id}/rules/{ruleId}") @RequirePermission("manufacturing.template.manage")
    public void deleteTemplateRule(@PathVariable long id,@PathVariable long ruleId){execution.deleteTemplateRule(id,ruleId);}
}
