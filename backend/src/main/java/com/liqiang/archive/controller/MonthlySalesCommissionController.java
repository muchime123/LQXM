package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CommissionCalculationService;
import com.liqiang.archive.service.MonthlySalesCommissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 月度提成核算 REST API（现行简化口径，2026-09-01 起核算期间支持任意日期范围）。
 *
 * <p>核算公式：经销商判定 = 出库单经销商非空，或毛利率低于 15%（防漏填）；
 * 提成金额 = (期间经销商回款 − 上期经销商运杂费) × 经销商比例 + (期间零售回款 − 上期零售运杂费) × 零售比例；
 * 期间为 [startDate, endDate]；上期运杂费扣减窗口默认 = 结束日所在月的前一个完整自然月（财务口径：运杂费按整月，
 * 不随核算时间段切分；期间恰为自然月时 = 上月运杂费）。P2（G4）起支持三模式：
 * PREVIOUS_CALENDAR_MONTH（默认）/ PREVIOUS_EQUAL_PERIOD（前一个等长周期）/ CUSTOM（自定义起止，须附运费范围原因）。
 * 只读取销售出库事实，不依赖钉钉、不写业务事实数据（比例配置除外）。
 *
 * <p>核算版本（2026-08-31 财务口径）：导入时自动跟踪「已生效出库单」的收款/运杂费修正变化（单据级），
 * 财务手动「核算结账」按所选日期范围生成核算版本 = 期间正常提成 + 修正提成，版本间可逐业务员对比差额。
 * 旧「导入前后总额做差」（delta-batches）已废弃删除。
 *
 * <p>读接口需 monthly_commission.read.*（本人/部门/全部任一个）；
 * 核算/比例配置写接口需 monthly_commission.manage（ADMIN / BOSS / FINANCE）。
 */
@RestController
@RequestMapping("/api/monthly-commissions")
@RequiredArgsConstructor
@RequirePermission({"monthly_commission.read.self", "monthly_commission.read.department", "monthly_commission.read.all"})
public class MonthlySalesCommissionController {

    private final MonthlySalesCommissionService service;
    private final CommissionCalculationService calculationService;

    /** 核算汇总（实时口径，供参考）：顶部指标 + 业务员汇总行 + 数据异常提示，日期范围 [startDate, endDate]。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam String startDate,
                                       @RequestParam String endDate,
                                       @RequestParam(required = false) String freightPeriodMode,
                                       @RequestParam(required = false) String freightStartDate,
                                       @RequestParam(required = false) String freightEndDate) {
        return service.summary(parseDate(startDate), parseDate(endDate),
                freightPeriodMode, parseDateOrNull(freightStartDate), parseDateOrNull(freightEndDate));
    }

    /** 指定业务员某日期范围的核算明细（期间收款明细 + 上期运杂费明细）。 */
    @GetMapping("/{employeeId}")
    public Map<String, Object> employeeDetail(@PathVariable Long employeeId,
                                              @RequestParam String startDate,
                                              @RequestParam String endDate,
                                              @RequestParam(required = false) String freightPeriodMode,
                                              @RequestParam(required = false) String freightStartDate,
                                              @RequestParam(required = false) String freightEndDate) {
        return service.employeeDetail(employeeId, parseDate(startDate), parseDate(endDate),
                freightPeriodMode, parseDateOrNull(freightStartDate), parseDateOrNull(freightEndDate));
    }

    // ==================== 核算版本（2026-08-31 财务口径） ====================

    /** 核算版本列表（全部版本，按核算时间倒序），附每版本汇总与核算日期范围。 */
    @GetMapping("/versions")
    public List<Map<String, Object>> versions() {
        return calculationService.listVersions();
    }

    /** 结账前预览：范围内总量、已核算量与本次待核算来源。 */
    @GetMapping("/versions/preview")
    @RequirePermission("monthly_commission.manage")
    public Map<String, Object> settlementPreview(@RequestParam String startDate,
                                                 @RequestParam String endDate,
                                                 @RequestParam(required = false) String freightPeriodMode,
                                                 @RequestParam(required = false) String freightStartDate,
                                                 @RequestParam(required = false) String freightEndDate,
                                                 @RequestParam(required = false) String freightReason) {
        return calculationService.settlementPreview(parseDate(startDate), parseDate(endDate),
                freightPeriodMode, parseDateOrNull(freightStartDate), parseDateOrNull(freightEndDate), freightReason);
    }

    /** 核算版本明细（业务员行 + 版本汇总）。 */
    @GetMapping("/versions/{id}")
    public Map<String, Object> versionDetail(@PathVariable Long id) {
        return calculationService.versionDetail(id);
    }

    /** 两个核算版本逐业务员对比（diff = newer − older；参数顺序由前端保证新版本在前）。 */
    @GetMapping("/versions/compare")
    public Map<String, Object> compareVersions(@RequestParam Long newer, @RequestParam Long older) {
        return calculationService.compareVersions(newer, older);
    }

    // ==================== 版本导出（P4 G8；读权限即可，用于财务核对 Excel） ====================

    /** 提成发放汇总导出（每业务员一行：正常 + 修正 + 应发）。 */
    @GetMapping("/versions/{id}/export/items.csv")
    public ResponseEntity<byte[]> exportVersionItems(@PathVariable Long id) {
        return csvResponse("monthly-commission-version-" + id + "-items.csv",
                calculationService.exportVersionItems(id));
    }

    /** 核算来源明细导出（来源行粒度，含渠道来源与导入批次线索）。 */
    @GetMapping("/versions/{id}/export/lines.csv")
    public ResponseEntity<byte[]> exportVersionLines(@PathVariable Long id) {
        return csvResponse("monthly-commission-version-" + id + "-lines.csv",
                calculationService.exportVersionLines(id));
    }

    /** 修正（调整）明细导出（版本已并入的变化记录）。 */
    @GetMapping("/versions/{id}/export/adjustments.csv")
    public ResponseEntity<byte[]> exportVersionAdjustments(@PathVariable Long id) {
        return csvResponse("monthly-commission-version-" + id + "-adjustments.csv",
                calculationService.exportVersionAdjustments(id));
    }

    private ResponseEntity<byte[]> csvResponse(String fileName, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }

    /**
     * 财务手动「核算结账」：按所选日期范围生成核算版本 = 期间正常提成 + 未处理修正变化。
     * @param body 字段：startDate/endDate/remark 必填或可选；intent=RECALCULATION 表示作废后重算（审计标记）
     */
    @PostMapping("/versions")
    @RequirePermission("monthly_commission.manage")
    public Map<String, Object> calculateVersion(@RequestBody Map<String, Object> body) {
        return calculationService.calculateVersion(
                parseDate(body.get("startDate")),
                parseDate(body.get("endDate")),
                str(body.get("remark")),
                str(body.get("intent")),
                str(body.get("freightPeriodMode")),
                parseDateOrNull(str(body.get("freightStartDate"))),
                parseDateOrNull(str(body.get("freightEndDate"))),
                str(body.get("freightReason")));
    }

    /** 作废核算版本：释放该版本占用的来源明细并回滚修正；原因必填，已发放版本不可作废。 */
    @PostMapping("/versions/{id}/void")
    @RequirePermission("monthly_commission.manage")
    public Map<String, Object> voidVersion(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return calculationService.voidVersion(id,
                body == null ? null : str(body.get("reason")));
    }

    /** 标记核算版本提成已发放：已发放版本不可再作废（审计留痕）。 */
    @PostMapping("/versions/{id}/mark-paid")
    @RequirePermission("monthly_commission.manage")
    public Map<String, Object> markVersionPaid(@PathVariable Long id) {
        return calculationService.markVersionPaid(id);
    }

    /** 已生效出库单变化明细（可选按状态/批次过滤，支持 limit/offset 分页），单据级，财务可下钻核对。 */
    @GetMapping("/changes")
    public List<Map<String, Object>> changes(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long batchId,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) Long offset) {
        return calculationService.listChanges(status, batchId, limit, offset);
    }

    /** 变化数量（可选按状态过滤）：前端待核算徽标用，避免拉全量明细。 */
    @GetMapping("/changes/count")
    public int changesCount(@RequestParam(required = false) String status) {
        return calculationService.countChanges(status);
    }

    // ==================== 比例配置（monthly_commission.manage） ====================

    /** 提成比例配置列表（默认规则在前）。 */
    @GetMapping("/rates")
    public List<Map<String, Object>> rates() {
        return service.listRates();
    }

    /** 新增业务员提成比例配置。 */
    @PostMapping("/rates")
    @RequirePermission("monthly_commission.manage")
    public Map<String, Object> createRate(@RequestBody Map<String, Object> body) {
        return service.createRate(
                number(body.get("employeeId")),
                decimal(body.get("dealerRate")),
                decimal(body.get("retailRate")),
                str(body.get("remark")));
    }

    /** 修改提成比例配置（含默认规则行）。 */
    @PutMapping("/rates/{id}")
    @RequirePermission("monthly_commission.manage")
    public Map<String, Object> updateRate(@PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        return service.updateRate(
                id,
                decimal(body.get("dealerRate")),
                decimal(body.get("retailRate")),
                str(body.get("remark")));
    }

    /** 删除业务员提成比例配置（默认规则行不可删除）。 */
    @DeleteMapping("/rates/{id}")
    @RequirePermission("monthly_commission.manage")
    public void deleteRate(@PathVariable Long id) {
        service.deleteRate(id);
    }

    // ==================== 参数解析 ====================

    private LocalDate parseDate(Object v) {
        if (v == null) throw new IllegalArgumentException("请选择核算日期范围");
        try {
            return LocalDate.parse(String.valueOf(v).trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD");
        }
    }

    /** 可选日期参数：空/缺失返回 null（运费自定义窗口等场景）。 */
    private LocalDate parseDateOrNull(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD");
        }
    }

    private Long number(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("业务员参数无效");
        }
    }

    private BigDecimal decimal(Object v) {
        if (v == null) return null;
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("提成比例参数无效");
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
