package com.liqiang.archive.controller;
import com.liqiang.archive.dto.H3FactImportDtos.Result;
import com.liqiang.archive.dto.H3FactImportDtos.StageAccepted;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductInventoryLedgerImportService;
import com.liqiang.archive.service.ProductInventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/product-inventory")
@RequirePermission("product_inventory.read")
@RequiredArgsConstructor
public class ProductInventoryController {
    private final ProductInventoryService service;
    private final ProductInventoryLedgerImportService importService;

    @GetMapping("/balances")
    public Map<String, Object> balances(@RequestParam(defaultValue = "") String keyword,
                                        @RequestParam(required = false) Long warehouseId,
                                        @RequestParam(defaultValue = "") String category,
                                        @RequestParam(defaultValue = "false") boolean onlyInStock,
                                        @RequestParam(defaultValue = "ALL") String shelfStatus,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return service.balances(keyword, warehouseId, category, onlyInStock, shelfStatus, page, size);
    }

    /**
     * 库存单行详情：本行快照 + 两份来源台账行（产品信息 / 仓库明细）+ 同编号跨仓库分布。
     *
     * <p>定位键为 (仓库, 编号, 单位)：仓库为空表示该行只在产品信息台账、仓库明细无此产品；
     * 「更新明细」不在本端点返回，前端按来源行 id 调
     * {@code GET /api/h3-imports/ledger/{product|stock}/{id}/history} 获取。
     */
    @GetMapping("/detail")
    public Map<String, Object> rowDetail(@RequestParam(required = false) Long warehouseId,
                                        @RequestParam String productCode,
                                        @RequestParam(required = false) String unit) {
        return service.rowDetail(warehouseId, productCode, unit);
    }

    /** 快照元信息（来源批次/导出时间差）+ 缺价/缺产品异常清单（已按影响金额分层、并扣除已处置） */
    @GetMapping("/snapshot-info")
    public Map<String, Object> snapshotInfo() {
        return service.snapshotInfo();
    }

    // ===== 异常处置（只记录人的判断，不改台账；采购价唯一来源仍是氚云） =====

    /**
     * 异常全量明细（未处置），供导出 CSV。
     *
     * <p>抽屉里每类只回显前 200 行，307 行这种规模人工核不完；导出走本端点拿全量。
     *
     * @param issueType 可选：MISSING_PRODUCT 缺产品 / MISSING_PRICE 缺价；不传则两类都要
     */
    @GetMapping("/issues")
    public List<Map<String, Object>> issues(@RequestParam(required = false) String issueType) {
        return service.issueRowsAll(issueType);
    }

    /**
     * 标记 / 更新一条异常处置结论（按 仓库+编号+单位 幂等 upsert，重复标记即改判）。
     *
     * <p>结论取值：PENDING_H3 氚云待补 / DATA_RESIDUE 历史残留 / NO_PRICE_NEEDED 无需计价。
     */
    @PostMapping("/issues/dispositions")
    @RequirePermission("product_inventory.manage")
    public Map<String, Object> disposeIssue(@RequestBody IssueDispositionRequest req) {
        return service.disposeIssue(req.warehouseName(), req.productCode(), req.unit(),
                req.issueType(), req.status(), req.remark());
    }

    /** 撤销一条处置结论（业务键定位）：判错了，或氚云补完数据后该行已不再是异常。 */
    @DeleteMapping("/issues/dispositions")
    @RequirePermission("product_inventory.manage")
    public Map<String, Object> undoIssueDisposition(@RequestParam String warehouseName,
                                                    @RequestParam String productCode,
                                                    @RequestParam(defaultValue = "") String unit) {
        return Map.of("removed", service.undoIssueDisposition(warehouseName, productCode, unit));
    }

    record IssueDispositionRequest(String warehouseName, String productCode, String unit,
                                   String issueType, String status, String remark) {}

    // ===== 产品生命周期状态（V304：产品永不物理删除，「删除/停用」表达为 在用/下架） =====

    /**
     * 批量设置产品生命周期状态（按编号）。
     *
     * <p>只写本地管理列，导入 upsert 不覆盖；下架是标注不是删除——库存金额照算
     * （货还在仓里），覆盖报告也不再提示已下架的行。
     *
     * @param status ON_SHELF 在用 / OFF_SHELF 下架
     */
    @PostMapping("/catalog/shelf-status")
    @RequirePermission("product_inventory.manage")
    public Map<String, Object> setShelfStatus(@RequestBody ShelfStatusRequest req) {
        return Map.of("updated", service.setShelfStatus(req.productCodes(), req.status()));
    }

    record ShelfStatusRequest(List<String> productCodes, String status) {}

    // ===== 台账批次化导入（产品信息/仓库明细，与销售出库相同：登记批次→解析→预览→确认） =====

    /** 上传并登记批次：解析 Excel → 落暂存 → 与当前台账比对（新增/更新/忽略）返回预览 */
    @PostMapping("/{kind}/stage")
    @RequirePermission("product_inventory.manage")
    public StageAccepted stageLedger(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
        return importService.stage(kind, file);
    }

    /**
     * 批次预览/进度（GET /ledger-batches/{id}/preview|progress）已统一收口到
     * {@code /api/h3-imports/batches/**}（H3ImportBatchController），旧前缀读端点于阶段 4 下线；
     * 此处仅保留写端点（stage/confirm）。
     */
    /** 确认批次：与当前台账 diff 后增量写入（幂等，已确认批次直接返回统计） */
    @PostMapping("/ledger-batches/{id}/confirm")
    @RequirePermission("product_inventory.manage")
    public Result confirmLedger(@PathVariable Long id) {
        return importService.confirm(id);
    }
}
