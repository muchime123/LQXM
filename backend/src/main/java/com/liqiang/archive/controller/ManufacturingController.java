package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ManufacturingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/manufacturing")
@RequiredArgsConstructor
public class ManufacturingController {
    private final ManufacturingService service;
    private final com.liqiang.archive.service.ManufacturingExecutionService execution;
    private final com.liqiang.archive.service.ManufacturingMaterialService materials;
    @GetMapping("/warehouses") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> warehouses(){return service.warehouses();}
    /** 建单弹窗用：启用中的车间 + 默认制程（读权限即可，勿并入 /master/workshops——那是模板管理权限）。 */
    @GetMapping("/workshop-options") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> workshopOptions(){return service.workshopOptions();}
    @GetMapping("/orders") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> orders(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String status,
            @RequestParam(defaultValue="")String priority,@RequestParam(defaultValue="")String scheduled,
            @RequestParam(defaultValue="")String team,@RequestParam(required=false)Long responsible,
            @RequestParam(defaultValue="")String workshop,@RequestParam(defaultValue="")String overdue,
            @RequestParam(defaultValue="")String orderBy,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
        return service.list(keyword,status,priority,scheduled,team,responsible,workshop,overdue,orderBy,page,size);
    }
    @GetMapping("/schedule-summary") @RequirePermission("manufacturing.schedule")
    public Map<String,Object> scheduleSummary(){return service.scheduleSummary();}
    @GetMapping("/orders/{id}") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}
    @GetMapping("/orders/{id}/materials") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all","manufacturing.material.manage"}) public Map<String,Object> materials(@PathVariable long id){service.requireVisible(id);return materials.detail(id);}
    @PostMapping("/orders/{id}/materials") @RequirePermission("manufacturing.material.manage") public long createMaterial(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingMaterialService.RequirementRequest request){service.requireVisible(id);return materials.saveRequirement(id,null,request);}
    @PutMapping("/orders/{id}/materials/{materialId}") @RequirePermission("manufacturing.material.manage") public long updateMaterial(@PathVariable long id,@PathVariable long materialId,@RequestBody com.liqiang.archive.service.ManufacturingMaterialService.RequirementRequest request){service.requireVisible(id);return materials.saveRequirement(id,materialId,request);}
    @PostMapping("/orders/{id}/material-issues") @RequirePermission("manufacturing.material.manage") public long issueMaterial(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingMaterialService.MovementRequest request){service.requireVisible(id);return materials.move(id,"ISSUE",request);}
    @PostMapping("/orders/{id}/material-returns") @RequirePermission("manufacturing.material.manage") public long returnMaterial(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingMaterialService.MovementRequest request){service.requireVisible(id);return materials.move(id,"RETURN",request);}
    @GetMapping("/process-templates") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> templates(){return execution.templates();}
    @GetMapping("/execution-board") @RequirePermission("manufacturing.report")
    public Map<String,Object> executionBoard(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String workshop,
            @RequestParam(defaultValue="")String state,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
        return execution.executionBoard(keyword,workshop,state,page,size);
    }
    @GetMapping("/my-work-reports") @RequirePermission("manufacturing.report")
    public Map<String,Object> myReports(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return execution.myReports(page,size);}
    @GetMapping("/work-reports") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all","manufacturing.report.override"})
    public Map<String,Object> workReports(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String reporterName,
            @RequestParam(defaultValue="")String workshop,@RequestParam(defaultValue="")String operationName,
            @RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String dateFrom,@RequestParam(defaultValue="")String dateTo,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
        return execution.allReports(keyword,reporterName,workshop,operationName,status,dateFrom,dateTo,page,size);
    }
    @GetMapping("/inspections/pending") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all","manufacturing.inspect"})
    public Map<String,Object> pendingInspections(@RequestParam(defaultValue="")String keyword,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return execution.pendingInspections(keyword,page,size);}
    @GetMapping("/assignees") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> assignees(){return execution.assignees();}
    @PutMapping("/orders/{id}/schedule") @RequirePermission("manufacturing.schedule")
    public void schedule(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.ScheduleRequest request){execution.schedule(id,request);}
    @PutMapping("/orders/{orderId}/operations/{operationId}/assignment") @RequirePermission("manufacturing.schedule")
    public void assign(@PathVariable long orderId,@PathVariable long operationId,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.AssignmentRequest request){execution.assign(orderId,operationId,request);}
    @PostMapping("/orders/{id}/issues") @RequirePermission("manufacturing.issue.report")
    public long issue(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.IssueRequest request){return execution.reportIssue(id,request);}
    @PostMapping("/issues/{id}/resolve") @RequirePermission("manufacturing.issue.manage")
    public void resolveIssue(@PathVariable long id,@RequestBody Map<String,String> request){execution.resolveIssue(id,request.get("resolution"));}
    @GetMapping("/statistics") @RequirePermission({"manufacturing.read.department","manufacturing.read.all","manufacturing.manage"})
    public Map<String,Object> statistics(){return execution.statistics();}
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
    @PostMapping("/orders/{id}/operations/generate") @RequirePermission("manufacturing.manage")
    public void generate(@PathVariable long id,@RequestBody Map<String,Long> request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){execution.generateOperations(id,request.get("templateId"),requestKey);}
    // ---------- M0 契约写动作：全部要求 X-Request-Key（决策 #12「无键不直通」） ----------
    // 前端每次提交生成一个新键；同一次提交的重试必须复用同一个键，否则会被判为两次新动作。
    @PostMapping("/orders/{orderId}/operations/{operationId}/work-reports") @RequirePermission("manufacturing.report")
    public long report(@PathVariable long orderId,@PathVariable long operationId,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.WorkReportRequest request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){return execution.report(orderId,operationId,request,requestKey);}
    @PostMapping("/work-reports/{id}/reverse") @RequirePermission("manufacturing.report.override")
    public void reverse(@PathVariable long id,@RequestBody Map<String,String> request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){execution.reverse(id,request.get("reason"),requestKey);}
    @PostMapping("/orders/{id}/inspections") @RequirePermission("manufacturing.inspect")
    public long inspect(@PathVariable long id,@RequestBody com.liqiang.archive.service.ManufacturingExecutionService.InspectionRequest request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){return execution.inspect(id,request,requestKey);}
    @PostMapping("/orders") @RequirePermission("manufacturing.manage")
    public long create(@RequestBody ManufacturingService.CreateOrder request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){return service.create(request,requestKey);}
    @PostMapping("/orders/{id}/completion-receipts") @RequirePermission("manufacturing.receive")
    public long complete(@PathVariable long id,@RequestBody ManufacturingService.CompleteOrder request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){return service.complete(id,request,requestKey);}
    @GetMapping("/completion-receipts") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all","manufacturing.receive","manufacturing.receive.reverse"})
    public Map<String,Object> completionReceipts(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String status,
            @RequestParam(required=false)Long warehouseId,@RequestParam(defaultValue="")String operatorName,
            @RequestParam(defaultValue="")String dateFrom,@RequestParam(defaultValue="")String dateTo,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){
        return service.allReceipts(keyword,status,warehouseId,operatorName,dateFrom,dateTo,page,size);
    }
    @GetMapping("/completion-receipts/{id}") @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all","manufacturing.receive","manufacturing.receive.reverse"})
    public Map<String,Object> receiptDetail(@PathVariable long id){return service.receiptDetail(id);}
    @PostMapping("/completion-receipts/{id}/reverse") @RequirePermission("manufacturing.receive.reverse")
    public void reverseReceipt(@PathVariable long id,@RequestBody Map<String,String> request,
            @RequestHeader(value="X-Request-Key",required=false) String requestKey){service.reverseReceipt(id,request.get("reason"),requestKey);}
}
