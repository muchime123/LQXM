package com.liqiang.archive.controller;

import com.liqiang.archive.dto.H3FactImportDtos.BatchProgress;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3BusinessQueryService;
import com.liqiang.archive.service.H3FactImportService;
import com.liqiang.archive.service.H3LedgerRowHistoryService;
import com.liqiang.archive.service.H3ResetService;
import com.liqiang.archive.service.ImportKindDescriptor;
import com.liqiang.archive.service.ProductInventoryLedgerImportService;
import com.liqiang.archive.service.ProductInventoryMergeService;
import com.liqiang.archive.service.SalesOutboundNatureService;
import com.liqiang.archive.service.OutboundRelationGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 氚云导入中心统一读端点（阶段 2 批次 + 阶段 3 台账，2026-09）。
 *
 * <p>背景：批次表 h3_import_batches 为 A（四类业务事实）/ B（产品信息/仓库明细台账）
 * 的单一事实源，但旧端点按域拆分在两处前缀（/api/h3-facts、/api/product-inventory），
 * 各自携带不同读权限（integration.h3 / product_inventory.read），造成同一张表的
 * 隐式权限耦合。本控制器按设计文档将读端点统一收口：
 * <ul>
 *   <li>阶段 2：批次「列表/进度/预览」收口到 {@code /api/h3-imports/batches/**}，
 *       列表天然 A+B 混列，progress/preview 按 data_type 路由到对应域服务；</li>
 *   <li>阶段 3：历史台账六类读（outbound/production/receipt/settlement/product/stock）
 *       收口到 {@code /api/h3-imports/ledger/{kind}}，按 kind 路由到 A/B 域服务，
 *       已同步下线 A 域 /api/h3-facts/ledger/* 与 B 域 /api/product-inventory/{catalog,stock}；</li>
 *   <li>读权限为 integration.h3 或 product_inventory.read 任一（二选一即放行，避免破坏
 *       B 域既有只读调用；写操作仍维持各域 manage 权限，本控制器不含写端点）；</li>
 *   <li>阶段 4：旧前缀重复读端点已下线（/api/h3-facts/import-batches 列表、
 *       /import-batches/{id}/progress|preview、/api/product-inventory/ledger-batches/{id}/progress|preview），
 *       前端已全部切换本控制器统一前缀；各域写端点（stage/confirm）与事实域专有读端点
 *       （orchestration/lineage，权限为 integration.h3）继续保留原前缀。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/h3-imports")
@RequiredArgsConstructor
public class H3ImportBatchController {
    private final H3BusinessQueryService queryService;
    private final H3FactImportService factService;
    private final ProductInventoryLedgerImportService ledgerService;
    private final ProductInventoryMergeService mergeService;
    private final SalesOutboundNatureService outboundNatureService;
    private final OutboundRelationGovernanceService relationGovernanceService;
    private final H3LedgerRowHistoryService rowHistoryService;
    private final H3ResetService resetService;

    /** 全量重导前只读影响预检；返回保护项、清理项和禁止执行原因。 */
    @GetMapping("/reset/preview")
    @RequirePermission("integration.h3")
    public Map<String, Object> resetPreview() {
        return resetService.preview();
    }

    /** 重置前保存稳定业务键和人工治理结论；不执行删除。 */
    @PostMapping("/reset/prepare")
    @RequirePermission("integration.h3")
    public Map<String, Object> prepareReset(@RequestBody(required = false) Map<String, Object> body) {
        return resetService.prepare(body == null ? null : String.valueOf(body.getOrDefault("mode", "")));
    }

    /** 使用保护快照令牌执行事务化来源重置。 */
    @PostMapping("/reset/execute")
    @RequirePermission("integration.h3")
    public Map<String, Object> executeReset(@RequestBody Map<String, Object> body) {
        return resetService.execute(String.valueOf(body.getOrDefault("resetToken", "")));
    }

    /** 作废尚未执行的快照令牌，允许重新选择身份策略。 */
    @PostMapping("/reset/cancel")
    @RequirePermission("integration.h3")
    public Map<String, Object> cancelReset(@RequestBody Map<String, Object> body) {
        return resetService.cancel(String.valueOf(body.getOrDefault("resetToken", "")));
    }

    /** 批次列表（A+B 混列，type 支持 data_type 原值或前端 key，NORMAL 归一在查询层唯一处理）。 */
    @GetMapping("/batches")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> batches(@RequestParam(required = false) String type,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return queryService.batches(type, page, Math.min(size, 100));
    }

    /** 批次进度（A/B 同形 BatchProgress；按 data_type 路由到各自服务，异常语义与旧端点一致）。 */
    @GetMapping("/batches/{id}/progress")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public BatchProgress progress(@PathVariable Long id) {
        String dataType = queryService.importBatchDataType(id);
        BatchProgress p = isLedgerDataType(dataType)
                ? ledgerService.batchProgress(id)
                : factService.getBatchProgress(id);
        if (p == null) throw new NoSuchElementException("批次不存在：id=" + id);
        return p;
    }

    /**
     * 批次预览：A 域返回 {@code StagingPreview}（事实暂存行），B 域返回 {@code LedgerImportPreview}
     * （台账比对行，扁平结构）；按 data_type 路由，两种负载均与旧端点 JSON 逐字节一致。
     */
    @GetMapping("/batches/{id}/preview")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Object preview(@PathVariable Long id) {
        String dataType = queryService.importBatchDataType(id);
        // 合并入库批次的 preview 来自 mergeService（事务性写入，无 staging）
        if ("INVENTORY_MERGE".equals(dataType)) return mergeService.mergePreviewOf(id);
        return isLedgerDataType(dataType)
                ? ledgerService.previewOf(id)
                : factService.getPreviewFromStaging(id);
    }

    /**
     * 历史台账统一读端点（阶段 3）：九类台账按 kind 路由到 A/B 域只读服务。
     * kind ∈ outbound / production / receipt / settlement / purchase / product / stock / dealer / supplier / otherIn / otherOut / transfer / invoiceProfile / invoiceRequest / productionReport
     * / otherIn / otherOut / transfer（V306/V307，2026-09-17）/ productionReport（V311，2026-09-17 第 15 类）。
     * production 的 filter 参数仅为旧调用兼容；生产台账当前不与正式生产任务建立关联。
     */
    @GetMapping("/ledger/{kind}")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> ledger(@PathVariable String kind,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String filter,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return switch (kind) {
            case "outbound" -> queryService.outboundLedger(keyword, page, Math.min(size, 100));
            case "production" -> queryService.productionLedger(keyword, filter, page, Math.min(size, 100));
            case "receipt" -> queryService.receiptLedger(false, keyword, page, Math.min(size, 100));
            case "settlement" -> queryService.receiptLedger(true, keyword, page, Math.min(size, 100));
            case "product" -> mergeService.productCatalogLedger(page, size, keyword);
            case "stock" -> mergeService.warehouseStockLedger(page, size, keyword);
            case "dealer" -> mergeService.dealerLedger(page, size, keyword);
            case "supplier" -> queryService.supplierLedger(keyword, page, Math.min(size, 100));
            case "purchase" -> queryService.purchaseLedger(keyword, page, Math.min(size, 100));
            case "otherIn" -> queryService.otherMovementLedger(true, keyword, page, Math.min(size, 100));
            case "otherOut" -> queryService.otherMovementLedger(false, keyword, page, Math.min(size, 100));
            case "transfer" -> queryService.transferLedger(keyword, page, Math.min(size, 100));
            case "invoiceProfile" -> queryService.invoiceProfileLedger(keyword, page, Math.min(size, 100));
            case "invoiceRequest" -> queryService.invoiceRequestLedger(keyword, page, Math.min(size, 100));
            case "productionReport" -> queryService.productionReportLedger(keyword, page, Math.min(size, 100));
            case "invoiceInput" -> queryService.invoiceInputLedger(keyword, page, Math.min(size, 100));
            case "invoiceSales" -> queryService.invoiceSalesLedger(keyword, page, Math.min(size, 100));
            case "supplierPayable" -> queryService.supplierPayableLedger(keyword, page, Math.min(size, 100));
            case "cashflowJournal" -> queryService.cashflowJournalLedger(keyword, page, Math.min(size, 100));
            case "cashierLog" -> queryService.cashierLogLedger(keyword, page, Math.min(size, 100));
            default -> throw new IllegalArgumentException("未知台账类型：" + kind);
        };
    }

    /**
     * 历史台账单行详情（阶段 3 扩展）：{@code /ledger/{kind}} 列表只能展示摘要列，
     * 此处返回主记录全字段 + 子表明细，供详情抽屉核对完整原始数据。
     * 响应统一结构 {@code { kind, row, rows? }}：
     * <ul>
     *   <li>outbound：row=出库主单，rows=sales_outbound_items 产品明细；</li>
     *   <li>production：row=工单主表，rows=production_order_components 制程明细；</li>
     *   <li>receipt / settlement：row=receipts 主单，settlement 额外 rows=覆盖出库单链接；</li>
     *   <li>product / stock：row=台账行（无子表）。</li>
     * </ul>
     * 主单只读镜像，external_source='H3YUN'；未命中抛 404 语义异常。
     */
    @GetMapping("/ledger/{kind}/{id}")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> ledgerDetail(@PathVariable String kind, @PathVariable Long id) {
        return switch (kind) {
            case "outbound" -> queryService.outboundDetail(id);
            case "production" -> queryService.productionDetail(id);
            case "receipt" -> queryService.receiptDetail(false, id);
            case "settlement" -> queryService.receiptDetail(true, id);
            case "product" -> mergeService.productCatalogDetail(id);
            case "stock" -> mergeService.warehouseStockDetail(id);
            case "dealer" -> mergeService.dealerDetail(id);
            case "supplier" -> queryService.supplierDetail(id);
            case "purchase" -> queryService.purchaseDetail(id);
            case "otherIn", "otherOut" -> queryService.otherMovementDetail(id);
            case "transfer" -> queryService.transferDetail(id);
            case "invoiceProfile" -> queryService.invoiceProfileDetail(id);
            case "invoiceRequest" -> queryService.invoiceRequestDetail(id);
            case "productionReport" -> queryService.productionReportDetail(id);
            case "invoiceInput" -> queryService.invoiceInputDetail(id);
            case "invoiceSales" -> queryService.invoiceSalesDetail(id);
            case "supplierPayable" -> queryService.supplierPayableDetail(id);
            case "cashflowJournal" -> queryService.cashflowJournalDetail(id);
            case "cashierLog" -> queryService.cashierLogDetail(id);
            default -> throw new IllegalArgumentException("未知台账类型：" + kind);
        };
    }

    /**
     * 历史台账行级「更新历史」：按业务键回放该行在历次导入批次中的 CREATE/UPDATE 记录
     * （含旧值 → 新值差异，数据源为各批次保留的 preview_json）。详见 {@link H3LedgerRowHistoryService}。
     */
    @GetMapping("/ledger/{kind}/{id}/history")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> ledgerRowHistory(@PathVariable String kind, @PathVariable Long id) {
        return rowHistoryService.history(kind, id);
    }

    /** 销售出库关联工作台：按业务单查看客户、员工、产品与仓库的项目内关联完整度。 */
    @GetMapping("/outbound-relations")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundRelations(@RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "all") String relation,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return queryService.outboundRelations(keyword, relation, Math.max(1,page), Math.min(Math.max(1,size),100));
    }

    /** 唯一精确匹配预览：业务员姓名→员工、来源仓库名称→仓库。 */
    @GetMapping("/outbound-relations/exact-preview")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundRelationExactPreview() {
        return relationGovernanceService.preview();
    }

    /** 执行唯一精确匹配并保存重导可复用规则；不处理重名和模糊候选。 */
    @PostMapping("/outbound-relations/exact-apply")
    @RequirePermission("integration.h3")
    public Map<String,Object> applyOutboundRelationExact() {
        return relationGovernanceService.applyExact();
    }

    @GetMapping("/outbound-relations/customer-preview")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundRelationCustomerPreview() { return relationGovernanceService.customerPreview(); }

    @PostMapping("/outbound-relations/customer-apply")
    @RequirePermission("integration.h3")
    public Map<String,Object> applyOutboundRelationCustomer() { return relationGovernanceService.applyCustomerSafe(); }

    @GetMapping("/outbound-relations/new-customer-preview")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundNewCustomerPreview() { return relationGovernanceService.newCustomerClusterPreview(); }

    @PostMapping("/outbound-relations/new-customer-apply")
    @RequirePermission({"integration.h3", "customer.manage"})
    public Map<String,Object> applyOutboundNewCustomers() { return relationGovernanceService.applyNewCustomerClusters(); }

    @GetMapping("/outbound-relations/customer-exceptions")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundCustomerExceptions() { return relationGovernanceService.customerExceptionPreview(); }

    @GetMapping("/outbound-relations/multi-phone-preview")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundMultiPhonePreview() { return relationGovernanceService.multiPhonePreview(); }

    @PostMapping("/outbound-relations/multi-phone-apply")
    @RequirePermission({"integration.h3", "customer.manage"})
    public Map<String,Object> applyOutboundMultiPhone() { return relationGovernanceService.applyMultiPhone(); }

    /** 产品归类建议：按来源产品编码优先、名称规格单位辅助，只对唯一命中给出建议。 */
    @GetMapping("/outbound-relations/product-preview")
    @RequirePermission("integration.h3")
    public Map<String,Object> outboundRelationProductPreview() {
        return relationGovernanceService.productPreview();
    }

    /** 执行产品归类并保存重导可复用规则；多候选与无候选不处理。 */
    @PostMapping("/outbound-relations/product-apply")
    @RequirePermission("integration.h3")
    public Map<String,Object> applyOutboundRelationProduct() {
        return relationGovernanceService.applyProductExact();
    }

    /** 导入后自动编排的人工重试入口；只执行可重复、确定性的关联规则。 */
    @PostMapping("/outbound-relations/auto-organize")
    @RequirePermission({"integration.h3", "customer.manage"})
    public Map<String,Object> autoOrganizeOutboundRelations() {
        return relationGovernanceService.applyAllDeterministic();
    }

    /** 疑似退换货可选原销售单：同一客户、当前单日期以前的已生效出库。 */
    @GetMapping("/ledger/outbound/{id}/original-candidates")
    @RequirePermission("integration.h3")
    public java.util.List<Map<String, Object>> outboundOriginalCandidates(@PathVariable Long id) {
        return outboundNatureService.originalCandidates(id);
    }

    /** 人工确认销售/退货/换货类型；氚云重导不会覆盖该结论。 */
    @PutMapping("/ledger/outbound/{id}/business-type")
    @RequirePermission("integration.h3")
    public Map<String, Object> confirmOutboundBusinessType(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        Object original = body.get("originalOutboundId");
        Long originalId = original instanceof Number n ? n.longValue() : null;
        return outboundNatureService.confirm(id, String.valueOf(body.getOrDefault("businessType", "")), originalId);
    }

    private static boolean isLedgerDataType(String dataType) {
        return ImportKindDescriptor.isLedgerDbType(dataType);
    }
}
