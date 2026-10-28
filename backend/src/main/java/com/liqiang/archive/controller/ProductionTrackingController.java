package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductionTrackingService;
import com.liqiang.archive.service.ProductionTrackingAnalyticsService;
import com.liqiang.archive.service.ProductionTrackingWeeklyReviewService;
import com.liqiang.archive.service.ProductionOutboundTraceService;
import com.liqiang.archive.service.H3BusinessQueryService;
import com.liqiang.archive.service.ProductionTrackingRoundTripService;
import com.liqiang.archive.service.ProductionTrackingWorkbookImportService;
import com.liqiang.archive.service.ProductionTrackingWorkbookReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/manufacturing/followups")
@RequiredArgsConstructor
public class ProductionTrackingController {
    private final ProductionTrackingService service;
    private final ProductionTrackingAnalyticsService analytics;
    private final ProductionTrackingWeeklyReviewService weekly;
    private final ProductionOutboundTraceService outboundTrace;
    private final H3BusinessQueryService queryService;
    private final ProductionTrackingWorkbookImportService workbookImport;
    private final ProductionTrackingWorkbookReviewService workbookReview;
    private final ProductionTrackingRoundTripService roundTrip;

    @GetMapping
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> list(@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="") String moldLayout,
            @RequestParam(defaultValue="") String workshop,
            @RequestParam(defaultValue="all") String trackingStatus,
            @RequestParam(defaultValue="all") String reminder,
            @RequestParam(defaultValue="") String sourceStatus,
            @RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="") String assignee,
            @RequestParam(defaultValue="") String month,
            @RequestParam(defaultValue="all") String action,
            @RequestParam(defaultValue="false") boolean urgent,
            @RequestParam(defaultValue="") String countdown,
            @RequestParam(defaultValue="") String salesperson,
            @RequestParam(defaultValue="") String deliveryResult,
            @RequestParam(defaultValue="") String createdFrom,
            @RequestParam(defaultValue="") String createdTo,
            @RequestParam(defaultValue="") String applicationFrom,
            @RequestParam(defaultValue="") String applicationTo,
            @RequestParam(defaultValue="false") boolean applicationMissing,
            @RequestParam(defaultValue="") String scheduleFrom,
            @RequestParam(defaultValue="") String scheduleTo,
            @RequestParam(defaultValue="false") boolean scheduleMissing,
            @RequestParam(defaultValue="") String expectedFrom,
            @RequestParam(defaultValue="") String expectedTo,
            @RequestParam(defaultValue="false") boolean expectedMissing,
            @RequestParam(defaultValue="") String optionalStage,
            @RequestParam(defaultValue="") String moldStage,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="30") int size){
        var result=service.list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,salesperson,deliveryResult,
                new ProductionTrackingService.DateRangeFilter(applicationFrom,applicationTo,applicationMissing),
                new ProductionTrackingService.DateRangeFilter(scheduleFrom,scheduleTo,scheduleMissing),
                new ProductionTrackingService.DateRangeFilter(expectedFrom,expectedTo,expectedMissing),urgent,moldLayout,
                new ProductionTrackingService.DateRangeFilter(createdFrom,createdTo,false),optionalStage,moldStage,page,size);
        @SuppressWarnings("unchecked")
        var items=(List<Map<String,Object>>)result.get("items");
        outboundTrace.attachSummaries(items);
        return result;
    }

    /** 搜索栏下拉选项（车间 / 跟单员 / 未分配计数），与列表同一可见性口径。 */
    @GetMapping("/filter-options")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> filterOptions(){return service.filterOptions();}

    /**
     * 导出当前筛选条件下的**全部**行（不分页，上限见 EXPORT_MAX_ROWS）。
     * 与列表同一套筛选、可见性、取数列与排序；权限与列表相同 —— 跟单员导出到的仍只是本人可见的任务。
     * 返回 JSON 由前端组装 Excel（中文文案口径只在前端，避免两处各写一份）。
     */
    @GetMapping("/export")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> export(@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="") String moldLayout,
            @RequestParam(defaultValue="") String workshop,
            @RequestParam(defaultValue="all") String trackingStatus,
            @RequestParam(defaultValue="all") String reminder,
            @RequestParam(defaultValue="") String sourceStatus,
            @RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="") String assignee,
            @RequestParam(defaultValue="") String month,
            @RequestParam(defaultValue="all") String action,
            @RequestParam(defaultValue="false") boolean urgent,
            @RequestParam(defaultValue="") String countdown,
            @RequestParam(defaultValue="") String salesperson,
            @RequestParam(defaultValue="") String deliveryResult,
            @RequestParam(defaultValue="") String createdFrom,
            @RequestParam(defaultValue="") String createdTo,
            @RequestParam(defaultValue="") String applicationFrom,
            @RequestParam(defaultValue="") String applicationTo,
            @RequestParam(defaultValue="false") boolean applicationMissing,
            @RequestParam(defaultValue="") String scheduleFrom,
            @RequestParam(defaultValue="") String scheduleTo,
            @RequestParam(defaultValue="false") boolean scheduleMissing,
            @RequestParam(defaultValue="") String expectedFrom,
            @RequestParam(defaultValue="") String expectedTo,
            @RequestParam(defaultValue="false") boolean expectedMissing,
            @RequestParam(defaultValue="") String optionalStage,
            @RequestParam(defaultValue="") String moldStage,
            @RequestParam(defaultValue="") String ids){
        return service.exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,salesperson,deliveryResult,
                new ProductionTrackingService.DateRangeFilter(applicationFrom,applicationTo,applicationMissing),
                new ProductionTrackingService.DateRangeFilter(scheduleFrom,scheduleTo,scheduleMissing),
                new ProductionTrackingService.DateRangeFilter(expectedFrom,expectedTo,expectedMissing),urgent,moldLayout,
                new ProductionTrackingService.DateRangeFilter(createdFrom,createdTo,false),optionalStage,moldStage,ids);}

    @GetMapping("/summary")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> summary(){return service.summary();}

    /** 车间生产概览；与跟单列表使用同一可见范围。 */
    @GetMapping("/dashboard")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> dashboard(){return service.dashboard();}

    /** Submitted-order date range analysis; shares the list's visibility and delivery definitions. */
    @GetMapping("/analytics")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> analytics(@RequestParam(defaultValue="") String fromDate,
            @RequestParam(defaultValue="") String toDate,@RequestParam(defaultValue="") String workshop,
            @RequestParam(defaultValue="baseline") String dateBasis){
        return analytics.analyze(fromDate,toDate,workshop,dateBasis);
    }

    /** Drilldown of an analysis metric; returns only identifiers and production dates needed to open tracking. */
    @GetMapping("/analytics/orders")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> analyticsOrders(@RequestParam(defaultValue="") String fromDate,
            @RequestParam(defaultValue="") String toDate,@RequestParam(defaultValue="") String workshop,
            @RequestParam(defaultValue="baseline") String dateBasis,
            @RequestParam(defaultValue="") String month,@RequestParam String bucket,
            @RequestParam(defaultValue="") String reason,
            @RequestParam(defaultValue="") String reasonCategory,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="30") int size){
        return analytics.orders(fromDate,toDate,workshop,dateBasis,month,bucket,reason,reasonCategory,page,size);
    }

    /**
     * 跟单周复盘看板：把交期达成分析的同一批数字按周报的写法重组（含原因归类）。
     * 周期由前端算好传 fromDate/toDate（自然周或 10.01-10.11 这类月初分段都支持）。
     */
    @GetMapping("/weekly-review")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> weeklyReview(@RequestParam(defaultValue="") String fromDate,
            @RequestParam(defaultValue="") String toDate,@RequestParam(defaultValue="") String workshop){
        return weekly.review(fromDate,toDate,workshop);
    }

    /**
     * 已固化的历史周期快照列表（趋势看板用）。过去周期首次查看时固化，
     * 交期调整不再改变其周报数字；当前周期仍实时现算、不在此列。
     */
    @GetMapping("/weekly-review/history")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> weeklyReviewHistory(@RequestParam(defaultValue="") String workshop){
        return analytics.listSnapshots(workshop);
    }

    /**
     * 节点完成率分析视图（按车间 × 动态节点 + 排产等待分布与异常单）。
     * 只统计跟单覆盖的单，可见性随 MANUFACTURING 数据范围收敛；不依赖氚云报工/入库时间。
     */
    @GetMapping("/analytics/nodes")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> nodeAnalytics(){
        return analytics.nodeAnalyticsView();
    }

    /**
     * 节点分析下钻：点某节点 / 某等待天数时返回对应工单清单（分页）。
     * 与生产分析、周复盘同一个交互模型——先看聚合，点了才展开明细。
     */
    @GetMapping("/analytics/nodes/orders")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> nodeAnalyticsOrders(
            @RequestParam(defaultValue="") String workshop,
            @RequestParam(defaultValue="") String stage,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(required=false) Integer waitDays,
            @RequestParam(required=false) Integer waitDaysMin,
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="30") int size){
        return analytics.nodeAnalyticsOrders(workshop,stage,status,waitDays,waitDaysMin,page,size);
    }

    @GetMapping("/{id}")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}

    @GetMapping("/{id}/purchase-receipts")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> purchaseReceipts(@PathVariable long id){return service.purchaseReceipts(id);}

    @GetMapping("/{id}/purchase-receipts/{receiptId}")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> purchaseReceiptDetail(@PathVariable long id,@PathVariable long receiptId){
        boolean linked=service.purchaseReceipts(id).stream()
                .anyMatch(receipt->((Number)receipt.get("id")).longValue()==receiptId);
        if(!linked) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,"该生产工单未关联此采购入库单");
        var orderNo=service.visibleOrderNo(id);
        var source=queryService.purchaseDetail(receiptId);
        @SuppressWarnings("unchecked")
        var lines=(List<Map<String,Object>>)source.get("rows");
        var receiptLines=lines.stream()
                .map(line->safeFields(line,"lineNo","productionOrderNo","productCode","productName","spec","unit","quantity"))
                .toList();
        var row=safeFields(castRow(source),"id","sourceNo","dataStatus","purchaseDate","warehouseName");
        row.put("trackingOrderNo",orderNo);
        return Map.of("kind","purchase",
                "row",row,"rows",receiptLines);
    }

    @GetMapping("/{id}/sales-trace")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> salesTrace(@PathVariable long id){return outboundTrace.trace(id);}

    @GetMapping("/{id}/sales-trace/receipts/{itemId}/candidates")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> salesTraceCandidates(@PathVariable long id,@PathVariable long itemId,
            @RequestParam(defaultValue="") String keyword){
        return outboundTrace.candidates(id,itemId,keyword);
    }

    @GetMapping("/{id}/sales-trace/outbounds/{outboundId}")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> salesTraceOutbound(@PathVariable long id,@PathVariable long outboundId){
        outboundTrace.requireCandidateOutbound(id,outboundId);
        var trace=outboundTrace.trace(id);
        @SuppressWarnings("unchecked")
        var receipts=(List<Map<String,Object>>)trace.get("receipts");
        Set<String> codes=receipts.stream().map(row->Objects.toString(row.get("productCode"),"").trim())
                .filter(code->!code.isEmpty()).collect(Collectors.toSet());
        var source=queryService.outboundDetail(outboundId);
        @SuppressWarnings("unchecked")
        var lines=(List<Map<String,Object>>)source.get("rows");
        var matchingLines=lines.stream().filter(line->codes.contains(Objects.toString(line.get("productCode"),"").trim()))
                .map(line->safeFields(line,"sequenceNo","productCode","productName","specification","unit","quantity"))
                .toList();
        return Map.of("kind","outbound",
                "row",safeFields(castRow(source),"id","outboundNo","status","shipDate","businessDate","warehouse"),
                "rows",matchingLines);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> castRow(Map<String,Object> source){return (Map<String,Object>)source.get("row");}

    /** Follow-up endpoints return only operational fields, even when a ledger source contains financial data. */
    private static Map<String,Object> safeFields(Map<String,Object> source,String... fields){
        var safe=new LinkedHashMap<String,Object>();
        for(String field:fields)if(source.containsKey(field))safe.put(field,source.get(field));
        return safe;
    }

    @PostMapping("/{id}/sales-trace/allocations")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> confirmSalesTrace(@PathVariable long id,
            @RequestBody ProductionOutboundTraceService.Confirmation body,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("id",outboundTrace.confirm(id,body,key));
    }

    @PostMapping("/{id}/sales-trace/allocations/{allocationId}/revoke")
    @RequirePermission("manufacturing.followup")
    public Map<String,Boolean> revokeSalesTrace(@PathVariable long id,@PathVariable long allocationId,
            @RequestBody ProductionOutboundTraceService.Revocation body){
        outboundTrace.revoke(id,allocationId,body);
        return Map.of("revoked",true);
    }

    @PostMapping("/{id}/time-reviews")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> addTimeReview(@PathVariable long id,
            @RequestBody ProductionTrackingService.TimeReviewRequest body,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("id",service.addTimeReview(id,body,key));
    }

    /** 修改某类时间核实的最新一条记录（写错原因/补漏细节）；不新增历史，审计留改动前后原文。 */
    @PutMapping("/{id}/time-reviews/{noteId}")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> updateTimeReview(@PathVariable long id,@PathVariable long noteId,
            @RequestBody ProductionTrackingService.TimeReviewRequest body,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("id",service.updateTimeReview(id,noteId,body,key));
    }

    @GetMapping("/candidates")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> candidates(@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) Long workbookRowId){return service.candidates(keyword,workbookRowId);}

    @PostMapping("/enroll/{id}")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> enroll(@PathVariable long id,@RequestHeader("X-Request-Key") String key){
        return Map.of("id",service.enroll(id,key));
    }

    @GetMapping("/workbook-rows")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> workbookRows(@RequestParam(defaultValue="false") boolean history){
        return service.workbookRows(history);
    }

    /** 按钮计数专用轻量端点：只回各类状态条数，不拉待处理明细。 */
    @GetMapping("/workbook-rows/summary")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> workbookSummary(){return service.workbookSummary();}

    /** 关联工单窗口的智能候选：按车间/产品/交期/订单号/数量评分排序，含命中与冲突字段。 */
    @GetMapping("/workbook-rows/{rowId}/candidates")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> workbookCandidates(@PathVariable long rowId){
        return service.workbookCandidates(rowId);
    }

    /** 归档规则：命中后导入时直接归为已归档，不再进入待处理。 */
    @GetMapping("/workbook-rows/archive-rules")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public List<Map<String,Object>> workbookArchiveRules(){return service.workbookArchiveRules();}

    public record WorkbookArchiveRequest(long rowId,String reason) {}

    @PostMapping("/workbook-rows/archive")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> archiveWorkbookRow(@RequestBody WorkbookArchiveRequest body,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("id",service.archiveWorkbookRow(body.rowId(),body.reason(),key));
    }

    public record WorkbookBatchRequest(List<Long> rowIds,String action) {}

    @PostMapping("/workbook-rows/batch")
    @RequirePermission("manufacturing.followup")
    public Map<String,Object> batchWorkbookRows(@RequestBody WorkbookBatchRequest body,
            @RequestHeader("X-Request-Key") String key){
        return service.batchResolveWorkbookRows(body.rowIds(),body.action(),key);
    }

    public record WorkbookArchiveRevokeRequest(boolean restoreRows) {}

    @PostMapping("/workbook-rows/archive-rules/{ruleId}/revoke")
    @RequirePermission("manufacturing.followup")
    public Map<String,Object> revokeWorkbookArchiveRule(@PathVariable long ruleId,
            @RequestBody(required=false) WorkbookArchiveRevokeRequest body,
            @RequestHeader("X-Request-Key") String key){
        return service.revokeWorkbookArchiveRule(ruleId,body!=null&&body.restoreRows(),key);
    }

    @PostMapping("/workbook/preview")
    @RequirePermission("manufacturing.followup")
    public ProductionTrackingWorkbookImportService.Preview previewWorkbook(@RequestParam("file") MultipartFile file)
            throws java.io.IOException {service.requireTrackingSupervisor();return workbookImport.preview(file);}

    @PostMapping("/workbook/apply")
    @RequirePermission("manufacturing.followup")
    public ProductionTrackingWorkbookImportService.ApplyResult applyWorkbook(@RequestParam("file") MultipartFile file,
            @RequestParam("expectedHash") String expectedHash,@RequestParam("expectedPlanHash") String expectedPlanHash,
            @RequestHeader("X-Request-Key") String key)
            throws java.io.IOException {service.requireTrackingSupervisor();return workbookImport.apply(file,expectedHash,expectedPlanHash,key);}

    /**
     * 导回预检：解析**本系统导出的 Excel**（导出 → 线下改 → 导回），逐行给出「能写什么、为什么跳过」。零写入。
     * 只回写跟单员维护的四类字段（跟单备注 / 车间预计完成＝延期至 / 延期原因 / 各节点完成日期），空白＝不修改。
     */
    @PostMapping("/round-trip/preview")
    @RequirePermission("manufacturing.followup")
    public ProductionTrackingRoundTripService.Preview previewRoundTrip(@RequestParam("file") MultipartFile file)
            throws java.io.IOException {return roundTrip.preview(file);}

    /** 导回应用：整批一个事务；文件哈希与计划指纹都要与预检一致，否则要求重新预检。 */
    @PostMapping("/round-trip/apply")
    @RequirePermission("manufacturing.followup")
    public ProductionTrackingRoundTripService.ApplyResult applyRoundTrip(@RequestParam("file") MultipartFile file,
            @RequestParam("expectedHash") String expectedHash,@RequestParam("expectedPlanHash") String expectedPlanHash,
            @RequestHeader("X-Request-Key") String key)
            throws java.io.IOException {return roundTrip.apply(file,expectedHash,expectedPlanHash,key);}

    public record WorkbookResolution(Long productionOrderId,boolean skip) {}
    @PostMapping("/workbook-rows/{rowId}/resolve")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> resolveWorkbookRow(@PathVariable long rowId,@RequestBody WorkbookResolution body,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("id",service.resolveWorkbookRow(rowId,body.productionOrderId(),body.skip(),key));
    }

    @GetMapping("/workbook-rows/{rowId}/review")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public ProductionTrackingWorkbookReviewService.Review reviewWorkbookRow(@PathVariable long rowId){
        return workbookReview.review(rowId);
    }

    public record WorkbookReviewDecision(long expectedVersion,List<String> selectedKeys) {}

    @PostMapping("/workbook-rows/{rowId}/apply-review")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> applyWorkbookReview(@PathVariable long rowId,@RequestBody WorkbookReviewDecision body,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("id",workbookReview.apply(rowId,body.expectedVersion(),body.selectedKeys(),key));
    }

    @PostMapping("/workbook-rows/{rowId}/ignore-review")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> ignoreWorkbookReview(@PathVariable long rowId,@RequestHeader("X-Request-Key") String key){
        return Map.of("id",workbookReview.ignore(rowId,key));
    }

    @PostMapping("/{id}/entries")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> add(@PathVariable long id,@RequestBody ProductionTrackingService.EntryRequest request,
            @RequestHeader("X-Request-Key") String key){return Map.of("version",service.add(id,request,key));}

    @PostMapping("/{id}/disposition")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> changeDisposition(@PathVariable long id,
            @RequestBody ProductionTrackingService.DispositionRequest request,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("version",service.changeDisposition(id,request,key));
    }

    /**
     * 跟单结案确认 / 撤销：与「停止」互斥的本地处置（不修改氚云生产任务单）；
     * 状态按「结案优先」覆盖为「跟单已完成」，与节点是否齐全解耦。
     */
    @PostMapping("/{id}/closure")
    @RequirePermission("manufacturing.followup")
    public Map<String,Long> changeClosure(@PathVariable long id,
            @RequestBody ProductionTrackingService.ClosureRequest request,
            @RequestHeader("X-Request-Key") String key){
        return Map.of("version",service.changeClosure(id,request,key));
    }

    /**
     * 节点参考基准配置（仅系统管理员）：读矩阵 / 批量保存天数。
     * 独立权限 manufacturing.baseline.manage——跟单员能推节点，但不该改「一般要几天」的判据。
     */
    @GetMapping("/node-benchmarks")
    @RequirePermission("manufacturing.baseline.manage")
    public Map<String,Object> nodeBenchmarks(){return service.nodeBenchmarkMatrix();}

    @PutMapping("/node-benchmarks")
    @RequirePermission("manufacturing.baseline.manage")
    public Map<String,Object> saveNodeBenchmarks(@RequestBody List<ProductionTrackingService.BenchmarkSave> rows){
        return service.saveNodeBenchmarks(rows);
    }

    @GetMapping("/product-node-benchmarks")
    @RequirePermission("manufacturing.baseline.manage")
    public List<Map<String,Object>> productNodeBenchmarks(){return service.productBenchmarkMatrix();}

    @PutMapping("/product-node-benchmarks")
    @RequirePermission("manufacturing.baseline.manage")
    public void saveProductNodeBenchmark(@RequestBody ProductionTrackingService.ProductBenchmarkSave row){
        service.saveProductBenchmark(row);
    }

    @PostMapping("/product-node-benchmarks/{taskConfigId}/refresh-candidates")
    @RequirePermission("manufacturing.baseline.manage")
    public List<Map<String,Object>> refreshProductBenchmarkCandidates(@PathVariable long taskConfigId){
        return service.refreshProductBenchmarkCandidates(taskConfigId);
    }

    /** 候选 P50 批量转发布值（界面确认后触发；已发布节点不动）。 */
    @PostMapping("/product-node-benchmarks/{taskConfigId}/apply-p50")
    @RequirePermission("manufacturing.baseline.manage")
    public List<Map<String,Object>> applyProductBenchmarkP50(@PathVariable long taskConfigId){
        return service.applyProductBenchmarkP50(taskConfigId);
    }

    /** 样本明细（只读）：候选是怎么算出来的，用于发布前抽查。
     *  与 productOrderHistory/productDuration 同取 any-of：维护参考间隔或任务单字典的人都该能看候选来源。 */
    @GetMapping("/product-benchmark-samples/{taskConfigId}")
    @RequirePermission({"manufacturing.baseline.manage","manufacturing.template.manage"})
    public Map<String,Object> productBenchmarkSamples(@PathVariable long taskConfigId){
        return service.productBenchmarkSamples(taskConfigId);
    }

    /**
     * 产品的历史生产任务明细（只读）：配置页「历史任务单 / 跟单记录」两个数字的来源。
     *
     * <p>权限取 any-of：维护参考间隔的人（baseline.manage）与维护任务单字典的人（template.manage）
     * 都该看得见自己那个数字是怎么来的；只给其中一个，会出现「有数字、点不开」。
     */
    @GetMapping("/product-orders/{taskConfigId}")
    @RequirePermission({"manufacturing.baseline.manage","manufacturing.template.manage"})
    public Map<String,Object> productOrderHistory(@PathVariable long taskConfigId,
            @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="200") int limit){
        return service.productOrderHistory(taskConfigId,status,limit);
    }

    /** 从同车间另一产品复制已发布间隔（只补未发布节点）。 */
    @PostMapping("/product-node-benchmarks/{taskConfigId}/copy-from/{sourceTaskConfigId}")
    @RequirePermission("manufacturing.baseline.manage")
    public Map<String,Object> copyProductBenchmarks(@PathVariable long taskConfigId,@PathVariable long sourceTaskConfigId){
        return service.copyProductBenchmarks(taskConfigId,sourceTaskConfigId);
    }

    /**
     * 产品生产工期（只读）：各必经节点生效参考值之和 + 历史实测「排产 → 入库」P50/P75。
     *
     * <p>权限与 {@link #productOrderHistory} 同口径（any-of）：维护参考间隔的人与维护任务单字典的人
     * 都该看见这个数字，否则会出现「配置页有工期、某个角色点不开」。
     */
    @GetMapping("/product-duration/{taskConfigId}")
    @RequirePermission({"manufacturing.baseline.manage","manufacturing.template.manage"})
    public Map<String,Object> productDuration(@PathVariable long taskConfigId){
        return service.productDuration(taskConfigId);
    }

    /**
     * 列表「默认可见列」的全站配置（2026-10-12）。
     * <p>读用页面读权限——全站默认列要**对所有人生效**，员工必须能读到；写用独立权限码
     * manufacturing.listcolumns.manage（只绑 ADMIN）：看得到列表 ≠ 有权决定全公司看到哪些列。
     */
    @GetMapping("/list-column-defaults")
    @RequirePermission({"manufacturing.read.self","manufacturing.read.department","manufacturing.read.all"})
    public Map<String,Object> listColumnDefaults(){return service.columnDefaultMatrix();}

    @PutMapping("/list-column-defaults")
    @RequirePermission("manufacturing.listcolumns.manage")
    public Map<String,Object> saveListColumnDefaults(
            @RequestBody List<ProductionTrackingService.ColumnDefaultSave> rows){
        return service.saveColumnDefaults(rows);
    }
}
