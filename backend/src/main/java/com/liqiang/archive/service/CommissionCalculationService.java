package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 销售出库提成核算：单据级变化跟踪 + 手动核算版本（2026-08-31 财务确认口径）。
 *
 * <p>替代已被否定的「导入前后总额做差」方案（{@code CommissionDeltaService} 已废弃）。
 * 核心机制：
 * <pre>
 *   1) 变化跟踪（导入时自动，单据级）：
 *      每张已生效（CONFIRMED）出库单在 {@code commission_outbound_baselines} 中维护提成基线
 *      （该单收款合计 + 运杂费，按经销/零售拆分）。导入后逐单对比「当前值 vs 基线」：
 *      - 有变化 → 写 {@code commission_outbound_changes} 变化明细（单据级，财务可下钻核对）并更新基线；
 *      - 无变化 → 仅刷新基线（天然幂等，重复导入不重复记录）；
 *      - 首次生效 → 只建基线、不记变化（首次正常计入当月提成，不属于修正）；
 *      - 生效后退回/取消（非 CONFIRMED）且基线存在 → 记 Δ=0−基线（追回已结算提成）并删基线。
 *
 *   2) 核算版本（财务手动触发）：
 *      calculateVersion 用「本期正常提成（复用 {@link MonthlySalesCommissionService#summary} 口径）
 *      + 未处理变化明细的修正提成（按业务员汇总）」生成一个核算版本，
 *      变化明细随之标记为 PROCESSED（并入下次结算）。版本间可逐业务员对比差额。
 * </pre>
 *
 * <p>经销商判定口径与月度提成一致：{@link CommissionPolicy#DEALER_CASE}。
 * 统计口径与期间正常提成统一（2026-09-02 财务确认）：(回款 − 运杂费) × 比例；
 * 变化提成 = (Δ经销回款 − Δ经销运杂费) × 经销比例 + (Δ零售回款 − Δ零售运杂费) × 零售比例。
 * 比例按「归属期间」追溯（{@link CommissionPolicy#rateAt}，比例调整在 monthly_commission_rate_history 留痕）；
 * 归属日期缺失（无法定位期间）时取识别时点最新配置。
 */
@Service
public class CommissionCalculationService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int IN_CHUNK = 500;
    private static final String CHANGE_ENTITY = "CommissionOutboundChange";
    private static final String VERSION_ENTITY = "CommissionCalculationVersion";
    private static final String H3_SOURCE_FILTER =
            "o.external_source IN ('H3YUN','H3YUN_RECOVERED','H3_HISTORICAL_RECOVERED')";
    /** 结算类型：正常核算 / 补充核算 / 作废后重新核算（2026-09-04 起，V259）。 */
    private static final String SETTLEMENT_NORMAL = "NORMAL";
    private static final String SETTLEMENT_SUPPLEMENT = "SUPPLEMENT";
    private static final String SETTLEMENT_RECALCULATION = "RECALCULATION";
    /** 运费范围模式：默认 = 结束日所在月的上一个完整自然月（财务确认维持现行口径）。 */
    private static final String FREIGHT_MODE_DEFAULT = CommissionPolicy.FREIGHT_MODE_PREVIOUS_CALENDAR_MONTH;

    private record SettlementSource(String lineType, Long sourceId, Long outboundId, String outboundNo,
                                    String customerName, Long employeeId, String employeeName,
                                    LocalDate occurrenceDate, boolean dealer, String channelSource,
                                    BigDecimal amount) {
    }

    private final JdbcTemplate jdbc;
    private final MonthlySalesCommissionService monthlyService;
    private final CurrentUserService currentUsers;
    private final AuditLogService auditLog;

    public CommissionCalculationService(JdbcTemplate jdbc,
                                        MonthlySalesCommissionService monthlyService,
                                        CurrentUserService currentUsers,
                                        AuditLogService auditLog) {
        this.jdbc = jdbc;
        this.monthlyService = monthlyService;
        this.currentUsers = currentUsers;
        this.auditLog = auditLog;
    }

    /**
     * 单个维度的金额聚合（收款/运杂费各自拆经销/零售），含基线时点业务员归属。
     * employeeId/employeeName 仅对「基线」有业务含义；minus 结果（Δ）中无意义。
     */
    public record Base(BigDecimal totalReceipt, BigDecimal dealerReceipt, BigDecimal retailReceipt,
                       BigDecimal totalFreight, BigDecimal dealerFreight, BigDecimal retailFreight,
                       Long employeeId, String employeeName) {

        static Base zero() {
            return new Base(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, null, null);
        }

        /** 本 − o，得到变化量。 */
        Base minus(Base o) {
            return new Base(
                    totalReceipt.subtract(o.totalReceipt),
                    dealerReceipt.subtract(o.dealerReceipt),
                    retailReceipt.subtract(o.retailReceipt),
                    totalFreight.subtract(o.totalFreight),
                    dealerFreight.subtract(o.dealerFreight),
                    retailFreight.subtract(o.retailFreight),
                    null, null);
        }

        boolean isZero() {
            return totalReceipt.signum() == 0 && totalFreight.signum() == 0;
        }
    }

    /** 某出库单当前导入后的核算口径值（含期间归属所需的收款/发货日期）。 */
    private record OutboundValue(Long outboundId, String outboundNo, String customerName,
                                 Long employeeId, String employeeName, String status,
                                 BigDecimal freight, BigDecimal dealerFreight, BigDecimal retailFreight,
                                 BigDecimal receiptTotal, BigDecimal receiptDealer, BigDecimal receiptRetail,
                                 LocalDate receiptDate, LocalDate shipDate) {

        Base toBase() {
            return new Base(receiptTotal, receiptDealer, receiptRetail, freight, dealerFreight, retailFreight,
                    employeeId, employeeName);
        }

        OutboundValue withReceipt(BigDecimal total, BigDecimal dealer, BigDecimal retail, LocalDate receiptDate) {
            return new OutboundValue(outboundId, outboundNo, customerName, employeeId, employeeName, status,
                    freight, dealerFreight, retailFreight, total, dealer, retail, receiptDate, shipDate);
        }
    }

    // ==================== 1. 变化跟踪（导入后调用，单据级） ====================

    /**
     * 对本批次涉及的全部出库单执行「当前值 vs 基线」对比，识别已生效单的收款/运杂费变化。
     * 幂等：同批次已生成变化记录则直接跳过；重复导入时基线已刷新，不会重复记录。
     *
     * @param batchId    导入批次 h3_import_batches.id
     * @param outboundIds 本次导入涉及的出库单 id 集合（含 UNCHANGED）
     * @return 新增变化记录数
     */
    @Transactional
    public int trackOutboundChanges(Long batchId, Collection<Long> outboundIds, String remark) {
        if (batchId == null || outboundIds == null || outboundIds.isEmpty()) return 0;
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_outbound_changes WHERE batch_id=?", Integer.class, batchId);
        if (existing != null && existing > 0) return 0;

        Set<Long> ids = new LinkedHashSet<>(outboundIds);
        Map<Long, OutboundValue> current = currentValues(ids);
        Map<Long, Base> baselines = loadBaselines(ids);
        Map<Long, List<CommissionPolicy.RateEntry>> rateHistory = CommissionPolicy.loadRateHistory(jdbc);

        LocalDateTime now = LocalDateTime.now();
        String cleanedRemark = clean(remark);
        int rows = 0;
        for (Long outboundId : ids) {
            OutboundValue cur = current.get(outboundId);
            Base baseline = baselines.get(outboundId);
            if (cur == null || !"CONFIRMED".equals(cur.status())) {
                // 非已生效（或数据缺失）：若基线存在则视为取消生效，追回已结算提成
                if (baseline == null) continue;
                rows += insertChange(batchId, outboundId, cur, baseline.employeeId(), baseline.employeeName(),
                        null, null, baseline, Base.zero(), rateHistory, now, cleanedRemark);
                jdbc.update("DELETE FROM commission_outbound_baselines WHERE outbound_id=?", outboundId);
                continue;
            }
            Base currentBase = cur.toBase();
            Long curEmp = validEmployeeId(cur.employeeId());
            Long baseEmp = validEmployeeId(baseline.employeeId());
            if (baseline == null) {
                // 取消生效后重新生效（该单历史上曾记「取消追回」且其后无「补给」）：补记补给修正，
                // 否则已结账期间的提成会随「取消→重新生效」悄悄丢失；纯首次生效只建基线、不记变化
                if (curEmp != null && hasRecoverableCancel(outboundId)) {
                    rows += insertChange(batchId, outboundId, cur, curEmp, currentName(cur),
                            null, null, Base.zero(), currentBase, rateHistory, now, cleanedRemark);
                }
                insertBaseline(outboundId, cur, currentBase);
                continue;
            }
            // 业务员变更感知：换人/清空时生成「原业务员追回 + 新业务员补给」两条修正（金额不变也记录），
            // 否则已结账期间的提成会随当前归属悄悄易主、无法下钻核对。
            if (!Objects.equals(curEmp, baseEmp)) {
                if (baseEmp != null) {
                    rows += insertChange(batchId, outboundId, cur, baseEmp, baseline.employeeName(),
                            null, null, baseline, Base.zero(), rateHistory, now, cleanedRemark);
                }
                if (curEmp != null) {
                    rows += insertChange(batchId, outboundId, cur, curEmp, currentName(cur),
                            baseEmp, baseline.employeeName(), Base.zero(), currentBase, rateHistory, now, cleanedRemark);
                }
                insertBaseline(outboundId, cur, currentBase);
                continue;
            }
            Base delta = currentBase.minus(baseline);
            if (delta.isZero()) {
                touchBaseline(outboundId);
                continue;
            }
            if (curEmp == null) {
                // 业务员无效（清空/缺失）且金额变化：无法归属修正，保留基线不更新，
                // 待业务员恢复后按「补给」统一处理，避免修正永久丢失
                continue;
            }
            rows += insertChange(batchId, outboundId, cur, curEmp, currentName(cur),
                    null, null, baseline, currentBase, rateHistory, now, cleanedRemark);
            updateBaseline(outboundId, cur, currentBase);
        }
        if (rows > 0) {
            auditLog.log(CHANGE_ENTITY, batchId, "批次#" + batchId, "TRACK",
                    "已生效出库单变化跟踪：识别 " + rows + " 笔修正变化（收款/运杂费变化，待下次核算）");
        }
        return rows;
    }

    /** 聚合本次涉及出库单的当前值：主表信息（含运杂费拆分）+ 收款子表合计（拆分）。 */
    private Map<Long, OutboundValue> currentValues(Collection<Long> outboundIds) {
        Map<Long, OutboundValue> result = new HashMap<>();
        String dealer = CommissionPolicy.DEALER_CASE;
        forEachChunk(outboundIds, chunk -> {
            String in = inClause(chunk.size());
            jdbc.query("""
                SELECT o.id outbound_id, o.outbound_no, COALESCE(o.customer_name,'') customer_name,
                       o.employee_id,
                       COALESCE(o.salesperson_name,
                               (SELECT e.name FROM employees e WHERE e.id=o.employee_id),'') employee_name,
                       o.outbound_status,
                       COALESCE(o.freight,0) freight,
                       o.attribution_date ship_date,
                       CASE WHEN (%s) THEN COALESCE(o.freight,0) ELSE 0 END dealer_freight,
                       CASE WHEN NOT (%s) THEN COALESCE(o.freight,0) ELSE 0 END retail_freight
                FROM sales_outbounds o
                WHERE o.id IN (%s)
                """.formatted(dealer, dealer, in),
                    (rs, n) -> result.put(rs.getLong("outbound_id"), rowValue(rs)),
                    chunk.toArray());
            // 收款合计（仅已生效子表；非 CONFIRMED 单自然为 0/无行），最近收款日期用于期间归属
            jdbc.query("""
                SELECT ps.sales_outbound_id outbound_id,
                       COALESCE(SUM(ps.received_amount),0) total_receipt,
                       COALESCE(SUM(CASE WHEN (%s) THEN ps.received_amount ELSE 0 END),0) dealer_receipt,
                       COALESCE(SUM(CASE WHEN NOT (%s) THEN ps.received_amount ELSE 0 END),0) retail_receipt,
                       MAX(ps.receipt_date) receipt_date
                FROM sales_outbound_payment_snapshots ps
                JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
                WHERE ps.sales_outbound_id IN (%s)
                  AND ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
                GROUP BY ps.sales_outbound_id
                """.formatted(dealer, dealer, in),
                    (RowCallbackHandler) rs -> {
                        Long id = rs.getLong("outbound_id");
                        OutboundValue v = result.get(id);
                        if (v != null) {
                            java.sql.Date d = rs.getDate("receipt_date");
                            result.put(id, v.withReceipt(bd(rs, "total_receipt"),
                                    bd(rs, "dealer_receipt"), bd(rs, "retail_receipt"),
                                    d == null ? null : d.toLocalDate()));
                        }
                    },
                    chunk.toArray());
        });
        return result;
    }

    private OutboundValue rowValue(ResultSet rs) throws SQLException {
        java.sql.Date ship = rs.getDate("ship_date");
        return new OutboundValue(rs.getLong("outbound_id"), rs.getString("outbound_no"),
                rs.getString("customer_name"), rs.getLong("employee_id"), rs.getString("employee_name"),
                rs.getString("outbound_status"), bd(rs, "freight"), bd(rs, "dealer_freight"),
                bd(rs, "retail_freight"), ZERO, ZERO, ZERO,
                null, ship == null ? null : ship.toLocalDate());
    }

    private Map<Long, Base> loadBaselines(Collection<Long> outboundIds) {
        Map<Long, Base> result = new HashMap<>();
        forEachChunk(outboundIds, chunk -> jdbc.query("""
            SELECT outbound_id, employee_id, employee_name,
                   receipt_total, receipt_dealer, receipt_retail,
                   freight_total, freight_dealer, freight_retail
            FROM commission_outbound_baselines
            WHERE outbound_id IN (%s)
            """.formatted(inClause(chunk.size())),
                (RowCallbackHandler) rs -> result.put(rs.getLong("outbound_id"),
                        new Base(bd(rs, "receipt_total"), bd(rs, "receipt_dealer"), bd(rs, "receipt_retail"),
                                bd(rs, "freight_total"), bd(rs, "freight_dealer"), bd(rs, "freight_retail"),
                                rs.getLong("employee_id"), rs.getString("employee_name"))),
                chunk.toArray()));
        return result;
    }

    private void insertBaseline(Long outboundId, OutboundValue v, Base base) {
        jdbc.update("""
            INSERT INTO commission_outbound_baselines(
                outbound_id, employee_id, employee_name,
                receipt_total, receipt_dealer, receipt_retail,
                freight_total, freight_dealer, freight_retail, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,NOW())
            ON DUPLICATE KEY UPDATE employee_id=VALUES(employee_id), employee_name=VALUES(employee_name),
                receipt_total=VALUES(receipt_total), receipt_dealer=VALUES(receipt_dealer),
                receipt_retail=VALUES(receipt_retail), freight_total=VALUES(freight_total),
                freight_dealer=VALUES(freight_dealer), freight_retail=VALUES(freight_retail), updated_at=NOW()
            """, outboundId, v.employeeId() != null ? v.employeeId() : 0,
                v.employeeName() == null || v.employeeName().isBlank() ? "业务员#" + v.employeeId() : v.employeeName(),
                base.totalReceipt(), base.dealerReceipt(), base.retailReceipt(),
                base.totalFreight(), base.dealerFreight(), base.retailFreight());
    }

    private void updateBaseline(Long outboundId, OutboundValue v, Base base) {
        insertBaseline(outboundId, v, base);
    }

    private void touchBaseline(Long outboundId) {
        jdbc.update("UPDATE commission_outbound_baselines SET updated_at=NOW() WHERE outbound_id=?", outboundId);
    }

    /**
     * 该单是否存在「取消追回（before 有值 / after 全 0）」且其后无「补给（before 全 0 / after 有值）」记录。
     * 用于「取消生效 → 重新生效」场景补记补给修正：取消时已追回旧基线提成，重新生效若无补给，
     * 已结账期间的提成会永久丢失（重生效按首次生效只建基线、不记变化）。
     */
    private boolean hasRecoverableCancel(Long outboundId) {
        Integer c = jdbc.queryForObject("""
            SELECT COUNT(*) FROM commission_outbound_changes t
            WHERE t.outbound_id=? AND (
                (t.before_receipt_dealer<>0 OR t.before_receipt_retail<>0
                 OR t.before_freight_dealer<>0 OR t.before_freight_retail<>0)
                AND t.after_receipt_dealer=0 AND t.after_receipt_retail=0
                AND t.after_freight_dealer=0 AND t.after_freight_retail=0
            ) AND NOT EXISTS (
                SELECT 1 FROM commission_outbound_changes g
                WHERE g.outbound_id=t.outbound_id AND g.id>t.id
                  AND g.before_receipt_dealer=0 AND g.before_receipt_retail=0
                  AND g.before_freight_dealer=0 AND g.before_freight_retail=0
                  AND (g.after_receipt_dealer<>0 OR g.after_receipt_retail<>0
                       OR g.after_freight_dealer<>0 OR g.after_freight_retail<>0)
            )
            """, Integer.class, outboundId);
        return c != null && c > 0;
    }

    /**
     * 写一条单据级变化明细（INSERT IGNORE 幂等：同批次同单只记一次）。
     * <p>归属期间随明细落库：回款变化按该单最近收款日期（receiptDate）、运杂费变化按发货日期（shipDate），
     * 结账时按「期间回款 + 上期运杂费窗口」口径过滤（与实时汇总 summary 一致）。
     * before/after 可传入 {@link Base#zero()} 构造「追回/补给」语义（业务员变更/取消生效）。
     */
    private int insertChange(Long batchId, Long outboundId, OutboundValue cur,
                             Long employeeId, String employeeName,
                             Long prevEmployeeId, String prevEmployeeName,
                             Base before, Base after,
                             Map<Long, List<CommissionPolicy.RateEntry>> rateHistory,
                             LocalDateTime now, String remark) {
        if (employeeId == null || employeeId <= 0) return 0;
        Base d = after.minus(before);
        // 比例按归属期间追溯（原期间比例）：回款变化按收款日期、运杂费变化按发货日期；
        // 日期缺失（无法定位期间，如历史存量/取消单）时取该员工当前最新配置
        LocalDate periodDate = cur != null
                ? (cur.receiptDate() != null ? cur.receiptDate() : cur.shipDate()) : null;
        CommissionPolicy.Rate rate = CommissionPolicy.rateAt(rateHistory, employeeId, periodDate);
        // 口径与期间正常提成统一：(Δ回款 − Δ运杂费) × 对应比例（运杂费同样乘比例，2026-09-02 财务确认）
        BigDecimal changeCommission = money(
                d.dealerReceipt().subtract(d.dealerFreight()).multiply(rate.dealerRate())
                        .add(d.retailReceipt().subtract(d.retailFreight()).multiply(rate.retailRate())));
        String outboundNo = cur != null && cur.outboundNo() != null ? cur.outboundNo()
                : (outboundId != null ? String.valueOf(outboundId) : "");
        String customerName = cur != null ? cur.customerName() : null;
        java.sql.Date receiptDate = cur != null && cur.receiptDate() != null
                ? java.sql.Date.valueOf(cur.receiptDate()) : null;
        java.sql.Date shipDate = cur != null && cur.shipDate() != null
                ? java.sql.Date.valueOf(cur.shipDate()) : null;
        int updated = jdbc.update("""
            INSERT IGNORE INTO commission_outbound_changes(
                batch_id, outbound_id, outbound_no, customer_name,
                receipt_date, ship_date,
                employee_id, employee_name, prev_employee_id, prev_employee_name,
                recorded_at, source,
                before_receipt_dealer, after_receipt_dealer, before_receipt_retail, after_receipt_retail,
                before_freight_dealer, after_freight_dealer, before_freight_retail, after_freight_retail,
                dealer_rate, retail_rate, change_commission, remark, status, version_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'IMPORT',?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',NULL)
            """,
                batchId, outboundId, outboundNo, customerName,
                receiptDate, shipDate,
                employeeId, employeeName, prevEmployeeId, prevEmployeeName, now,
                before.dealerReceipt(), after.dealerReceipt(), before.retailReceipt(), after.retailReceipt(),
                before.dealerFreight(), after.dealerFreight(), before.retailFreight(), after.retailFreight(),
                rate.dealerRate(), rate.retailRate(), changeCommission, remark);
        return updated;
    }

    /** 业务员 id 归一：null / <=0 视为无效（未归属），返回 null。 */
    private Long validEmployeeId(Long id) {
        return id != null && id > 0 ? id : null;
    }

    /** 业务员显示名：优先出库单业务员名，无则回退「业务员#id」。 */
    private String currentName(OutboundValue v) {
        return v != null && v.employeeName() != null && !v.employeeName().isBlank()
                ? v.employeeName() : "业务员#" + v.employeeId();
    }

    // ==================== 2. 核算版本（财务手动触发） ====================

    /**
     * 生成一个核算版本：期间正常提成（复用 {@link MonthlySalesCommissionService#summary} 口径）
     * + 所有未处理变化明细的修正提成（按业务员汇总）。生成后变化明细标记为 PROCESSED。
     * 核算期间为任意日期范围 [start,end]（2026-09-01 财务反馈：非整月核算）。
     */
    @Transactional
    public Map<String, Object> calculateVersion(LocalDate start, LocalDate end, String remark, String intent) {
        // 兼容旧调用：缺省运费模式 = 现行口径（结束日所在月的上一个完整自然月）
        return calculateVersion(start, end, remark, intent, FREIGHT_MODE_DEFAULT, null, null, null);
    }

    /**
     * 完整入参：核算范围 + 结算类型审计标记（intent）+ 运费范围模式与自定义窗口（G4，P2）。
     * 运费模式仅决定「本次扣减的运杂费窗口」与修正归属过滤，不影响回款窗口 [start, end]。
     */
    @Transactional
    public Map<String, Object> calculateVersion(LocalDate start, LocalDate end, String remark, String intent,
                                                String freightMode, LocalDate freightStart, LocalDate freightEnd,
                                                String freightReason) {
        validateRange(start, end);
        CommissionPolicy.FreightWindow fw = CommissionPolicy.resolveFreightWindow(
                start, end, freightMode, freightStart, freightEnd);
        CommissionPolicy.requireFreightCustomReason(fw.mode(), freightReason);
        String actualMode = fw.mode();
        LocalDate prevStart = fw.startInclusive();
        LocalDate prevEnd = fw.endExclusive();
        LocalDate freightEndDate = fw.endInclusive();
        // G7：base 正常提成改为按「来源行发生日」追溯当时比例；修正变化行内已存归属时比例。
        Map<Long, List<CommissionPolicy.RateEntry>> rateHistory = CommissionPolicy.loadRateHistory(jdbc);

        // 锁定并只读取尚未被有效版本占用的氚云收款/运杂费来源。日期范围可以重叠，
        // 防重以来源明细为准；唯一键负责拦截极端并发下的二次占用。
        List<SettlementSource> baseSources = loadUnsettledSources(start, end.plusDays(1), prevStart, prevEnd);
        List<Map<String, Object>> baseItems = summarizeSources(baseSources, rateHistory);
        Map<Long, Map<String, Object>> assessmentByEmp = loadAssessmentSnapshots(start, end.plusDays(1));

        // 修正提成 = 按「归属期间」过滤的 PENDING 变化聚合。关键：与 summary 的 base 避免重叠——
        //   · 该期间已有核算版本（已结账）：base 取版本快照，不随实时数据变化，归属任何 ≤ 结束日期间
        //     （回款按收款日期 < end+1、运杂费按发货日期 < 上月窗口下月1日）的修正都应并入（单算）；
        //   · 该期间尚未结账（首次结账）：base 为实时值，已含期间内收款/运杂费变化，若再并入归属本期间的
        //     修正会「双算」→ 金额只并归属「期间之前」的修正（回款 < start、运杂费 < 上月窗口起点），
        //     归属本期间的修正不参与金额，但结账时一并标记 PROCESSED（其影响已进 base，避免后续重复并入）。
        //   · 日期缺失（NULL）无法归属的变化：并入当次结账兜底，避免永久滞留（前端待核算徽标永不消）。
        // FOR UPDATE 锁定匹配变化行，防止并发结账时同一批修正被两个版本重复并入。
        LocalDate curStart = start;
        LocalDate curEnd = end.plusDays(1);   // 半开区间
        String deltaReceipt = "(COALESCE(after_receipt_dealer,0)-COALESCE(before_receipt_dealer,0)"
                + "+COALESCE(after_receipt_retail,0)-COALESCE(before_receipt_retail,0)) <> 0";
        String deltaFreight = "(COALESCE(after_freight_dealer,0)-COALESCE(before_freight_dealer,0)"
                + "+COALESCE(after_freight_retail,0)-COALESCE(before_freight_retail,0)) <> 0";
        // 期间是否存在「已结账（FINAL）」版本（作废 VOID 不计：作废后重结账按首次结账口径，避免双算）
        boolean alreadySettled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_calculation_versions WHERE start_date=? AND end_date=? AND status='FINAL'",
                Integer.class, start, end) > 0;
        // 计入修正提成金额的过滤：已结账并入「回款日期 < 下月1日 或 运杂费日期 < 上月窗口下月1日」；
        // 未结账仅并入「回款日期 < start 或 运杂费日期 < 上月窗口起点」（本期间变化已在实时 base 中）
        String adjFilter = " AND (" + deltaReceipt + " AND (receipt_date IS NULL OR receipt_date < ?)"
                + " OR " + deltaFreight + " AND (ship_date IS NULL OR ship_date < ?))";
        // 标记 PROCESSED 的过滤：未结账时除金额并入外，归属本期间的变化也标记（影响已进 base）
        String markFilter = alreadySettled ? adjFilter
                : " AND (" + deltaReceipt + " AND (receipt_date IS NULL OR receipt_date < ?)"
                        + " OR " + deltaFreight + " AND (ship_date IS NULL OR ship_date < ?))";
        Object[] adjParams = alreadySettled ? new Object[]{curEnd, prevEnd} : new Object[]{start, prevStart};
        Object[] markParams = alreadySettled ? adjParams : new Object[]{curEnd, prevEnd};
        Map<Long, Map<String, Object>> adjByEmp = new LinkedHashMap<>();
        List<Map<String, Object>> adjustmentRows = jdbc.query("""
            SELECT employee_id, MAX(employee_name) employee_name,
                   COALESCE(SUM(after_receipt_dealer - before_receipt_dealer),0) adj_dealer_receipt,
                   COALESCE(SUM(after_receipt_retail - before_receipt_retail),0) adj_retail_receipt,
                   COALESCE(SUM(after_freight_dealer - before_freight_dealer),0) adj_dealer_freight,
                   COALESCE(SUM(after_freight_retail - before_freight_retail),0) adj_retail_freight,
                   COALESCE(SUM(change_commission),0) adj_commission,
                   COUNT(*) change_count
            FROM commission_outbound_changes
            WHERE status='PENDING' """ + adjFilter + """
            GROUP BY employee_id
            FOR UPDATE
            """, this::row, adjParams);
        for (Map<String, Object> adjustment : adjustmentRows) {
            adjByEmp.put(((Number) adjustment.get("employeeId")).longValue(), adjustment);
        }
        // 同一次补充核算中，新增收款/首次出现的运杂费既会成为未占用来源，也可能被单据级
        // 基线识别为变化。来源金额已经进入 base，需从 adjustment 扣除，避免同一金额算两次；
        // 已占用来源的修改/删除没有 base 行，仍完整保留为差额调整。
        Set<String> adjustedOutbounds = new LinkedHashSet<>();
        List<Map<String, Object>> adjustedKeys = jdbc.query("""
            SELECT DISTINCT outbound_id, employee_id
            FROM commission_outbound_changes
            WHERE status='PENDING' """ + adjFilter + " FOR UPDATE", this::row, adjParams);
        for (Map<String, Object> key : adjustedKeys) {
            adjustedOutbounds.add(key.get("employeeId") + ":" + key.get("outboundId"));
        }
        for (SettlementSource source : baseSources) {
            if (!adjustedOutbounds.contains(source.employeeId() + ":" + source.outboundId())) continue;
            Map<String, Object> adjustment = adjByEmp.get(source.employeeId());
            if (adjustment == null) continue;
            String amountKey = source.dealer()
                    ? ("RECEIPT".equals(source.lineType()) ? "adjDealerReceipt" : "adjDealerFreight")
                    : ("RECEIPT".equals(source.lineType()) ? "adjRetailReceipt" : "adjRetailFreight");
            adjustment.put(amountKey, decimal(adjustment, amountKey).subtract(source.amount()));
            // G7：抵扣口径须与 base 的追溯口径一致（按该来源发生日的当时比例），不能用核算时点比例
            CommissionPolicy.Rate rate = CommissionPolicy.rateAt(rateHistory, source.employeeId(), source.occurrenceDate());
            BigDecimal contribution = source.amount().multiply(source.dealer() ? rate.dealerRate() : rate.retailRate());
            if ("FREIGHT".equals(source.lineType())) contribution = contribution.negate();
            adjustment.put("adjCommission", decimal(adjustment, "adjCommission").subtract(contribution));
        }

        // 合并业务员：正常提成行 + 修正提成
        Set<Long> empIds = new LinkedHashSet<>();
        Map<Long, Map<String, Object>> baseByEmp = new LinkedHashMap<>();
        for (Map<String, Object> b : baseItems) {
            Long empId = ((Number) b.get("employeeId")).longValue();
            baseByEmp.put(empId, b);
            empIds.add(empId);
        }
        empIds.addAll(adjByEmp.keySet());
        empIds.addAll(assessmentByEmp.keySet());

        if (baseSources.isEmpty() && adjByEmp.values().stream().allMatch(a -> decimal(a, "adjCommission").signum() == 0)) {
            throw new IllegalArgumentException("所选范围没有尚未核算的收款、运杂费或修正明细");
        }

        LocalDateTime now = LocalDateTime.now();
        Long operatorId = currentUsers.current().employeeId();
        String operatorName = currentUsers.current().name();
        String periodLabel = start + "~" + end;
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_calculation_versions WHERE start_date=? AND end_date=?",
                Integer.class, start, end);
        String versionNo = periodLabel + "-" + ((versionCount == null ? 0 : versionCount) + 1);

        // 结算类型：同期间已有 FINAL 且本次并入新增来源/修正 → SUPPLEMENT（补充核算）；
        // 作废后重算（期间无 FINAL 但存在 VOID）由前端带 intent=RECALCULATION 审计标记。
        String settlementType = alreadySettled ? SETTLEMENT_SUPPLEMENT : SETTLEMENT_NORMAL;
        if (SETTLEMENT_RECALCULATION.equalsIgnoreCase(intent) && !alreadySettled) {
            Integer voids = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM commission_calculation_versions WHERE start_date=? AND end_date=? AND status='VOID'",
                    Integer.class, start, end);
            if (voids != null && voids > 0) settlementType = SETTLEMENT_RECALCULATION;
        }

        // P3 结账门禁：结账前检查的阻断项任一中招即拒绝核算（预览弹窗展示同口径阻断清单）
        Map<String, Object> gateChecks = runSettlementChecks(start, end, actualMode, prevStart, prevEnd,
                baseSources, baseItems);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gateBlocking = (List<Map<String, Object>>) gateChecks.get("blocking");
        if (gateBlocking != null && !gateBlocking.isEmpty()) {
            throw new IllegalArgumentException(gateBlockMessage(gateBlocking));
        }

        Long versionId = insertVersion(start, end, periodLabel, versionNo, clean(remark), now, operatorId, operatorName,
                settlementType, prevStart, freightEndDate, actualMode);
        captureOutboundAssessmentLines(versionId, start, end.plusDays(1));

        int claimedSources = claimSources(versionId, baseSources, rateHistory);
        int claimedAdjustments = claimAdjustments(versionId, adjFilter, adjParams, claimedSourcePairs(baseSources));
        BigDecimal totalBase = ZERO, totalAdj = ZERO, totalCommission = ZERO;
        int itemRows = 0;
        for (Long empId : empIds) {
            Map<String, Object> b = baseByEmp.get(empId);
            Map<String, Object> a = adjByEmp.get(empId);
            Map<String, Object> assessment = assessmentByEmp.get(empId);
            String name = b != null ? String.valueOf(b.get("employeeName"))
                    : a != null ? String.valueOf(a.get("employeeName"))
                    : assessment != null ? String.valueOf(assessment.get("employeeName")) : "业务员#" + empId;
            BigDecimal baseReceiptDealer = decimal(b, "dealerReceiptAmount");
            BigDecimal baseReceiptRetail = decimal(b, "retailReceiptAmount");
            BigDecimal baseFreightDealer = decimal(b, "dealerFreightAmount");
            BigDecimal baseFreightRetail = decimal(b, "retailFreightAmount");
            BigDecimal baseCommission = decimal(b, "commissionAmount");
            BigDecimal adjReceiptDealer = decimal(a, "adjDealerReceipt");
            BigDecimal adjReceiptRetail = decimal(a, "adjRetailReceipt");
            BigDecimal adjFreightDealer = decimal(a, "adjDealerFreight");
            BigDecimal adjFreightRetail = decimal(a, "adjRetailFreight");
            BigDecimal adjCommission = decimal(a, "adjCommission");
            BigDecimal total = money(baseCommission.add(adjCommission));
            BigDecimal salesSnapshot = decimal(assessment, "salesAmountSnapshot");
            BigDecimal receivedSnapshot = decimal(assessment, "receivedAmountSnapshot");
            BigDecimal outstandingSnapshot = decimal(assessment, "outstandingAmountSnapshot");
            BigDecimal receivableSnapshot = receivedSnapshot.add(outstandingSnapshot);
            BigDecimal collectionRateSnapshot = receivableSnapshot.signum() <= 0 ? BigDecimal.ONE
                    : receivedSnapshot.divide(receivableSnapshot, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            BigDecimal dealerRate = b != null && b.get("dealerRate") instanceof Number n
                    ? new BigDecimal(n.toString()) : CommissionPolicy.rateAt(rateHistory, empId, null).dealerRate();
            BigDecimal retailRate = b != null && b.get("retailRate") instanceof Number n
                    ? new BigDecimal(n.toString()) : CommissionPolicy.rateAt(rateHistory, empId, null).retailRate();

            jdbc.update("""
                INSERT INTO commission_calculation_items(
                    version_id, employee_id, employee_name, dealer_rate, retail_rate,
                    base_receipt_dealer, base_receipt_retail, base_freight_dealer, base_freight_retail, base_commission,
                    adj_receipt_dealer, adj_receipt_retail, adj_freight_dealer, adj_freight_retail, adj_commission,
                    total_commission, sales_amount_snapshot, received_amount_snapshot,
                    outstanding_amount_snapshot, collection_rate_snapshot)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, versionId, empId, name, dealerRate, retailRate,
                baseReceiptDealer, baseReceiptRetail, baseFreightDealer, baseFreightRetail, baseCommission,
                adjReceiptDealer, adjReceiptRetail, adjFreightDealer, adjFreightRetail, adjCommission, total,
                salesSnapshot, receivedSnapshot, outstandingSnapshot, collectionRateSnapshot);
            totalBase = totalBase.add(baseCommission);
            totalAdj = totalAdj.add(adjCommission);
            totalCommission = totalCommission.add(total);
            itemRows++;
        }

        // 标记 PROCESSED：未结账时归属本期间的变化也一并标记（其影响已进 base 实时值）；
        // 归属未来期间的 PENDING 保持未核算，留待对应期间结账
        Object[] markArgs = new Object[markParams.length + 1];
        markArgs[0] = versionId;
        System.arraycopy(markParams, 0, markArgs, 1, markParams.length);
        int processed = jdbc.update("""
            UPDATE commission_outbound_changes SET status='PROCESSED', version_id=?
             WHERE status='PENDING' """ + markFilter,
                markArgs);
        jdbc.update("""
            UPDATE commission_calculation_versions
               SET status='FINAL', remark=COALESCE(remark,?)
             WHERE id=?
            """, clean(remark), versionId);

        String freightReasonClean = clean(freightReason);
        String windowNote = "运费范围 " + actualMode + "（" + prevStart + "~" + freightEndDate + "）"
                + (freightReasonClean != null ? "；范围原因：" + freightReasonClean : "");
        auditLog.log(VERSION_ENTITY, versionId, versionNo, "CALCULATE",
                periodLabel + " 核算结账（" + windowNote + "）：业务员 " + itemRows + " 人，正常提成 ¥" + money(totalBase)
                        + "，修正提成 ¥" + money(totalAdj) + "，应发合计 ¥" + money(totalCommission)
                        + "（并入修正变化 " + processed + " 笔）");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        result.put("versionNo", versionNo);
        result.put("period", periodLabel);
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("calculatedAt", now.toString());
        result.put("employeeCount", itemRows);
        result.put("totalBaseCommission", money(totalBase));
        result.put("totalAdjCommission", money(totalAdj));
        result.put("totalCommission", money(totalCommission));
        result.put("settlementType", settlementType);
        result.put("freightPeriodMode", actualMode);
        result.put("freightStartDate", prevStart.toString());
        result.put("freightEndDate", freightEndDate.toString());
        String freightReasonClean2 = clean(freightReason);
        if (freightReasonClean2 != null) result.put("freightReason", freightReasonClean2);
        result.put("processedChanges", processed);
        result.put("settledSourceLines", claimedSources);
        result.put("settledAdjustmentLines", claimedAdjustments);
        result.put("settledReceiptLines", baseSources.stream().filter(s -> "RECEIPT".equals(s.lineType())).count());
        result.put("settledFreightLines", baseSources.stream().filter(s -> "FREIGHT".equals(s.lineType())).count());
        return result;
    }

    /** 结账前预览：缺省运费范围 = 现行口径（结束日所在月的上一个完整自然月）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> settlementPreview(LocalDate start, LocalDate end) {
        return settlementPreview(start, end, FREIGHT_MODE_DEFAULT, null, null, null);
    }

    /**
     * 结账前预览（G4）：按来源明细展示范围内总量、已占用量和本次待核算量；
     * 运费范围支持三模式，与核算结账同口径。自定义范围必填原因（与结账一致，避免预览与落库不一致）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> settlementPreview(LocalDate start, LocalDate end,
                                                 String freightMode, LocalDate freightStart, LocalDate freightEnd,
                                                 String freightReason) {
        validateRange(start, end);
        CommissionPolicy.FreightWindow fw = CommissionPolicy.resolveFreightWindow(
                start, end, freightMode, freightStart, freightEnd);
        CommissionPolicy.requireFreightCustomReason(fw.mode(), freightReason);
        String actualMode = fw.mode();
        LocalDate prevStart = fw.startInclusive();
        LocalDate prevEnd = fw.endExclusive();
        Map<Long, List<CommissionPolicy.RateEntry>> rateHistory = CommissionPolicy.loadRateHistory(jdbc);
        List<SettlementSource> pending = loadUnsettledSources(start, end.plusDays(1), prevStart, prevEnd, false);

        long pendingReceipts = pending.stream().filter(s -> "RECEIPT".equals(s.lineType())).count();
        long pendingFreights = pending.size() - pendingReceipts;
        BigDecimal pendingReceiptAmount = pending.stream().filter(s -> "RECEIPT".equals(s.lineType()))
                .map(SettlementSource::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal pendingFreightAmount = pending.stream().filter(s -> "FREIGHT".equals(s.lineType()))
                .map(SettlementSource::amount).reduce(ZERO, BigDecimal::add);
        List<Map<String, Object>> previewItems = summarizeSources(pending, rateHistory);
        Map<Long, Map<String, Object>> assessmentByEmp = loadAssessmentSnapshots(start, end.plusDays(1));
        Map<Long, Map<String, Object>> adjustments = previewAdjustments(
                start, end, prevStart, prevEnd, pending, rateHistory);
        Map<Long, Map<String, Object>> previewByEmployee = new LinkedHashMap<>();
        for (Map<String, Object> item : previewItems) {
            Long employeeId = ((Number) item.get("employeeId")).longValue();
            item.put("baseCommission", decimal(item, "commissionAmount"));
            item.put("adjustmentCommission", ZERO);
            previewByEmployee.put(employeeId, item);
        }
        for (Map.Entry<Long, Map<String, Object>> entry : adjustments.entrySet()) {
            Map<String, Object> item = previewByEmployee.computeIfAbsent(entry.getKey(), id -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("employeeId", id);
                created.put("employeeName", entry.getValue().get("employeeName"));
                created.put("baseCommission", ZERO);
                created.put("commissionAmount", ZERO);
                previewItems.add(created);
                return created;
            });
            BigDecimal adjustment = decimal(entry.getValue(), "adjCommission");
            item.put("adjustmentCommission", money(adjustment));
            item.put("commissionAmount", money(decimal(item, "baseCommission").add(adjustment)));
        }
        for (Map.Entry<Long, Map<String, Object>> entry : assessmentByEmp.entrySet()) {
            Map<String, Object> item = previewByEmployee.computeIfAbsent(entry.getKey(), id -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("employeeId", id);
                created.put("employeeName", entry.getValue().get("employeeName"));
                created.put("baseCommission", ZERO);
                created.put("adjustmentCommission", ZERO);
                created.put("commissionAmount", ZERO);
                previewItems.add(created);
                return created;
            });
            BigDecimal received = decimal(entry.getValue(), "receivedAmountSnapshot");
            BigDecimal outstanding = decimal(entry.getValue(), "outstandingAmountSnapshot");
            BigDecimal receivable = received.add(outstanding);
            item.put("salesAmountSnapshot", decimal(entry.getValue(), "salesAmountSnapshot"));
            item.put("receivedAmountSnapshot", received);
            item.put("outstandingAmountSnapshot", outstanding);
            item.put("collectionRateSnapshot", receivable.signum() <= 0 ? BigDecimal.ONE
                    : received.divide(receivable, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE));
        }
        BigDecimal pendingBaseCommission = previewItems.stream().map(i -> decimal(i, "baseCommission"))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal pendingAdjustmentCommission = previewItems.stream().map(i -> decimal(i, "adjustmentCommission"))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal pendingCommission = pendingBaseCommission.add(pendingAdjustmentCommission);

        // P3 结账前检查：阻断 + 警告（口径与本期核算一致，随预览一并返回供弹窗展示）
        Map<String, Object> checks = runSettlementChecks(start, end, actualMode, prevStart, prevEnd,
                pending, previewItems);

        Map<String, Object> receiptTotal = oneOrNull("""
            SELECT COUNT(*) cnt, COALESCE(SUM(ps.received_amount),0) amt
            FROM sales_outbound_payment_snapshots ps
            JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
            WHERE ps.receipt_date>=? AND ps.receipt_date<?
              AND ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
              AND o.employee_id IS NOT NULL AND %s
            """.formatted(H3_SOURCE_FILTER), start, end.plusDays(1));
        Map<String, Object> freightTotal = oneOrNull("""
            SELECT COUNT(*) cnt, COALESCE(SUM(COALESCE(o.freight,0)),0) amt
            FROM sales_outbounds o
            WHERE o.attribution_date>=? AND o.attribution_date<?
              AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
              AND COALESCE(o.freight,0)<>0 AND %s
            """.formatted(H3_SOURCE_FILTER), prevStart, prevEnd);

        long totalReceipts = countValue(receiptTotal);
        long totalFreights = countValue(freightTotal);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start);
        result.put("endDate", end);
        result.put("freightPeriodMode", actualMode);
        result.put("freightStartDate", prevStart);
        result.put("freightEndDate", fw.endInclusive());
        String reasonClean = clean(freightReason);
        if (reasonClean != null) result.put("freightReason", reasonClean);
        result.put("totalReceiptLines", totalReceipts);
        result.put("settledReceiptLines", totalReceipts - pendingReceipts);
        result.put("pendingReceiptLines", pendingReceipts);
        result.put("totalReceiptAmount", amountValue(receiptTotal));
        result.put("pendingReceiptAmount", money(pendingReceiptAmount));
        result.put("totalFreightLines", totalFreights);
        result.put("settledFreightLines", totalFreights - pendingFreights);
        result.put("pendingFreightLines", pendingFreights);
        result.put("totalFreightAmount", amountValue(freightTotal));
        result.put("pendingFreightAmount", money(pendingFreightAmount));
        result.put("pendingBaseCommission", money(pendingBaseCommission));
        result.put("pendingAdjustmentCommission", money(pendingAdjustmentCommission));
        result.put("pendingCommission", money(pendingCommission));
        result.put("salesAmountSnapshot", previewItems.stream()
                .map(i -> decimal(i, "salesAmountSnapshot")).reduce(ZERO, BigDecimal::add));
        result.put("receivedAmountSnapshot", previewItems.stream()
                .map(i -> decimal(i, "receivedAmountSnapshot")).reduce(ZERO, BigDecimal::add));
        result.put("outstandingAmountSnapshot", previewItems.stream()
                .map(i -> decimal(i, "outstandingAmountSnapshot")).reduce(ZERO, BigDecimal::add));
        result.put("employeeCount", previewItems.size());
        result.put("items", previewItems);
        result.put("blocking", checks.get("blocking"));
        result.put("warnings", checks.get("warnings"));
        return result;
    }

    /** 与正式结账一致的待处理修正预览；同时扣除本次新增基础来源，避免重复计算。 */
    private Map<Long, Map<String, Object>> previewAdjustments(
            LocalDate start, LocalDate end, LocalDate freightStart, LocalDate freightEnd,
            List<SettlementSource> baseSources, Map<Long, List<CommissionPolicy.RateEntry>> rateHistory) {
        boolean alreadySettled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_calculation_versions WHERE start_date=? AND end_date=? AND status='FINAL'",
                Integer.class, start, end) > 0;
        String deltaReceipt = "(COALESCE(after_receipt_dealer,0)-COALESCE(before_receipt_dealer,0)"
                + "+COALESCE(after_receipt_retail,0)-COALESCE(before_receipt_retail,0)) <> 0";
        String deltaFreight = "(COALESCE(after_freight_dealer,0)-COALESCE(before_freight_dealer,0)"
                + "+COALESCE(after_freight_retail,0)-COALESCE(before_freight_retail,0)) <> 0";
        String filter = " AND (" + deltaReceipt + " AND (receipt_date IS NULL OR receipt_date < ?)"
                + " OR " + deltaFreight + " AND (ship_date IS NULL OR ship_date < ?))";
        Object[] args = alreadySettled
                ? new Object[]{end.plusDays(1), freightEnd}
                : new Object[]{start, freightStart};
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.query("""
            SELECT employee_id, MAX(employee_name) employee_name,
                   COALESCE(SUM(after_receipt_dealer-before_receipt_dealer),0) adj_dealer_receipt,
                   COALESCE(SUM(after_receipt_retail-before_receipt_retail),0) adj_retail_receipt,
                   COALESCE(SUM(after_freight_dealer-before_freight_dealer),0) adj_dealer_freight,
                   COALESCE(SUM(after_freight_retail-before_freight_retail),0) adj_retail_freight,
                   COALESCE(SUM(change_commission),0) adj_commission
            FROM commission_outbound_changes WHERE status='PENDING' """ + filter +
                " GROUP BY employee_id", this::row, args)) {
            result.put(((Number) row.get("employeeId")).longValue(), row);
        }
        Set<String> changed = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbc.query("SELECT DISTINCT outbound_id, employee_id FROM commission_outbound_changes "
                + "WHERE status='PENDING' " + filter, this::row, args)) {
            changed.add(row.get("employeeId") + ":" + row.get("outboundId"));
        }
        for (SettlementSource source : baseSources) {
            if (!changed.contains(source.employeeId() + ":" + source.outboundId())) continue;
            Map<String, Object> adjustment = result.get(source.employeeId());
            if (adjustment == null) continue;
            CommissionPolicy.Rate rate = CommissionPolicy.rateAt(rateHistory, source.employeeId(), source.occurrenceDate());
            BigDecimal contribution = source.amount().multiply(source.dealer() ? rate.dealerRate() : rate.retailRate());
            if ("FREIGHT".equals(source.lineType())) contribution = contribution.negate();
            adjustment.put("adjCommission", decimal(adjustment, "adjCommission").subtract(contribution));
        }
        return result;
    }

    /**
     * P3 结账前检查引擎：返回 { blocking, warnings }，每项 { code, message, count, amount }。
     * blocking 在结账（calculateVersion）时作为门禁——任一项命中即拒绝核算；
     * warnings 仅供结账预览提示，不阻断。
     * 口径与本期核算一致：回款窗口 [start,end)、运杂费窗口 [prevStart,prevEnd)；
     * 「缺业务员/缺日期」为数据质量问题，优先报告最小集合避免与月度汇总问题重复刷屏。
     * 并发占用/金额解析等异常由 UNIQUE 索引与 DECIMAL 列天然兜底，不重复造阻断。
     */
    private Map<String, Object> runSettlementChecks(LocalDate start, LocalDate end,
                                                    String actualMode, LocalDate prevStart, LocalDate prevEnd,
                                                    List<SettlementSource> pending,
                                                    List<Map<String, Object>> items) {
        List<Map<String, Object>> blocking = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        // 文本块以 `AND """ + h3` 结尾时，行尾空格会被 text block 规范化删除，导致粘连成 "ANDo.external_source"；
        // 故此处给 h3 加前导空格（本函数内仅用于这几处文本块行尾拼接）。
        String h3 = " " + H3_SOURCE_FILTER;

        // 阻断：未收款是提成考核核心指标，氚云主单三项金额必须能自洽。
        addBlock(blocking, "OUTBOUND_AMOUNT_MISMATCH",
                "核算期间存在销售金额不等于已收款加未收款的销售出库，请先在氚云核对金额后重新导入",
                oneOrNull("""
                    SELECT COUNT(*) cnt,
                           COALESCE(SUM(ABS(COALESCE(o.sales_amount,0)
                               - COALESCE(o.source_received_amount,o.received_amount,0)
                               - COALESCE(o.outstanding_amount,0))),0) amt
                    FROM sales_outbounds o
                    WHERE o.attribution_date>=? AND o.attribution_date<?
                      AND o.outbound_status='CONFIRMED' AND """ + h3 + """
                      AND ABS(COALESCE(o.sales_amount,0)
                          - COALESCE(o.source_received_amount,o.received_amount,0)
                          - COALESCE(o.outstanding_amount,0))>0.02
                    """, start, end.plusDays(1)));

        addBlock(blocking, "OUTBOUND_PAYMENT_DETAIL_MISMATCH",
                "存在销售出库主单已收款与收款详细子表合计不一致，请先在氚云核对并重新导入",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(x.source_received-x.detail_received)),0) amt
                    FROM (
                        SELECT o.id,
                               COALESCE(o.source_received_amount,o.received_amount,0) source_received,
                               COALESCE(SUM(CASE WHEN ps.legacy_status='CONFIRMED' THEN ps.received_amount ELSE 0 END),0) detail_received
                        FROM sales_outbounds o
                        LEFT JOIN sales_outbound_payment_snapshots ps ON ps.sales_outbound_id=o.id
                        WHERE o.outbound_status='CONFIRMED' AND """ + h3 + """
                          AND (o.attribution_date>=? AND o.attribution_date<?
                               OR EXISTS (SELECT 1 FROM sales_outbound_payment_snapshots rp
                                          WHERE rp.sales_outbound_id=o.id AND rp.legacy_status='CONFIRMED'
                                            AND rp.receipt_date>=? AND rp.receipt_date<?))
                        GROUP BY o.id,o.source_received_amount,o.received_amount
                        HAVING ABS(source_received-detail_received)>0.02
                    ) x
                    """, start, end.plusDays(1), start, end.plusDays(1)));

        addWarn(warnings, "OUTBOUND_NEGATIVE_OUTSTANDING",
                "核算期间存在氚云未收款金额为负数的销售出库，可能是预收或超收，请核对",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(o.outstanding_amount)),0) amt
                    FROM sales_outbounds o
                    WHERE o.attribution_date>=? AND o.attribution_date<?
                      AND o.outbound_status='CONFIRMED' AND o.outstanding_amount<0 AND """ + h3,
                        start, end.plusDays(1)));

        // 阻断 1：回款窗口内未归属业务员的已生效收款（不会计入本次提成）
        addBlock(blocking, "RECEIPT_WITHOUT_EMPLOYEE",
                "回款范围内存在未归属业务员的已生效收款，不会计入本次提成，请先在氚云补齐出库单业务员后重试",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ps.received_amount),0) amt
                    FROM sales_outbound_payment_snapshots ps
                    JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
                    WHERE ps.receipt_date>=? AND ps.receipt_date<? AND ps.legacy_status='CONFIRMED'
                      AND o.outbound_status='CONFIRMED' AND o.employee_id IS NULL AND """ + h3,
                        start, end.plusDays(1)));

        // 阻断 2：运杂费窗口内未归属业务员的已生效出库运杂费（不会参与扣减）
        addBlock(blocking, "FREIGHT_WITHOUT_EMPLOYEE",
                "运杂费窗口内存在未归属业务员的已生效出库单运杂费，无法参与抵扣，请先在氚云补齐出库单业务员后重试",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(COALESCE(o.freight,0))),0) amt
                    FROM sales_outbounds o
                    WHERE o.attribution_date>=? AND o.attribution_date<?
                      AND o.outbound_status='CONFIRMED' AND o.employee_id IS NULL
                      AND COALESCE(o.freight,0)<>0 AND """ + h3,
                        prevStart, prevEnd));

        // 阻断 3：缺收款日期的已生效收款（无法归属任何期间；仅报有业务员的，避免与阻断 1 重叠）
        addBlock(blocking, "RECEIPT_WITHOUT_DATE",
                "存在有收款金额但缺收款日期的已生效收款，无法归属核算期间，请先在氚云补填收款日期后重试",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ps.received_amount),0) amt
                    FROM sales_outbound_payment_snapshots ps
                    JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
                    WHERE ps.receipt_date IS NULL AND ps.legacy_status='CONFIRMED'
                      AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL AND """ + h3));

        // 阻断 4：缺发货/业务日期的出库运杂费（无法按窗口归属；仅报有业务员的）
        addBlock(blocking, "FREIGHT_WITHOUT_DATE",
                "存在有运杂费但缺发货/业务日期的已生效出库单，无法归属运杂费窗口，请先在氚云补录日期后重试",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(COALESCE(o.freight,0))),0) amt
                    FROM sales_outbounds o
                    WHERE o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
                      AND COALESCE(o.freight,0)<>0 AND o.ship_date IS NULL AND o.business_date IS NULL
                      AND """ + h3));

        // 阻断 5：比例配置缺失——默认规则被删且员工无独立配置时才触发（内置 4% 兜底非常态）
        if (pending != null && !pending.isEmpty()) {
            List<Long> configuredList = jdbc.queryForList(
                    "SELECT employee_id FROM monthly_commission_rates WHERE employee_id IS NOT NULL", Long.class);
            Set<Long> configured = new LinkedHashSet<>(configuredList);
            Integer defaultRules = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM monthly_commission_rates WHERE employee_id IS NULL", Integer.class);
            if (defaultRules == null || defaultRules == 0) {
                Set<Long> missing = new LinkedHashSet<>();
                for (SettlementSource s : pending) {
                    if (!configured.contains(s.employeeId())) missing.add(s.employeeId());
                }
                if (!missing.isEmpty()) {
                    BigDecimal amt = ZERO;
                    for (SettlementSource s : pending) {
                        if (missing.contains(s.employeeId())) amt = amt.add(s.amount().abs());
                    }
                    addCheckItem(blocking, "RATE_MISSING",
                            "存在既未单独配置、默认规则也已缺失的业务员，将按内置 4% 兜底核算，请先补充比例配置",
                            missing.size(), amt);
                }
            }
        }

        // 阻断 6：本期间相关待核算修正所属导入批次未完成（防止导入进行中即结账导致金额口径不稳；
        // 只统计回款/运杂费窗口内会并入本次核算的变化，避免未来期间在途导入误阻断）
        addBlock(blocking, "IMPORT_BATCH_UNFINISHED",
                "存在并入本次核算、但所属导入批次尚未完成的修正变化，请先完成或处理对应导入批次后重试",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(c.change_commission)),0) amt
                    FROM commission_outbound_changes c
                    JOIN h3_import_batches b ON b.id=c.batch_id
                    WHERE c.status='PENDING' AND b.status NOT IN ('COMPLETED')
                      AND ((c.receipt_date>=? AND c.receipt_date<?) OR (c.ship_date>=? AND c.ship_date<?))""",
                        start, end.plusDays(1), prevStart, prevEnd));

        // 警告 1：来源渠道按毛利兜底推断为经销商（G5 口径，疑似漏填经销商）
        if (pending != null) {
            long fallbackCount = 0;
            BigDecimal fallbackAmt = ZERO;
            for (SettlementSource s : pending) {
                if ("MARGIN_FALLBACK".equals(s.channelSource())) {
                    fallbackCount++;
                    fallbackAmt = fallbackAmt.add(s.amount().abs());
                }
            }
            if (fallbackCount > 0) {
                addCheckItem(warnings, "MARGIN_FALLBACK",
                        "本次待核算明细中有按「毛利率兜底」判为经销商的来源，请留意是否漏填经销商",
                        fallbackCount, fallbackAmt);
            }
        }

        // 警告 2：回款窗口内收款合计超过订单销售金额（可能跨单收款/误录）
        addWarn(warnings, "RECEIPT_EXCEEDS_SALES",
                "回款范围内存在收款合计超过订单销售金额的出库单，请核对是否含跨单收款或误录",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(x.over_amount),0) amt
                    FROM (
                        SELECT o.id, SUM(ps.received_amount)-COALESCE(o.sales_amount,0) over_amount
                        FROM sales_outbounds o
                        JOIN sales_outbound_payment_snapshots ps ON ps.sales_outbound_id=o.id
                        WHERE o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
                          AND ps.legacy_status='CONFIRMED' AND ps.receipt_date>=? AND ps.receipt_date<?
                          AND """ + h3 + """
                        GROUP BY o.id, o.sales_amount
                        HAVING SUM(ps.received_amount) > COALESCE(o.sales_amount,0)
                    ) x""",
                        start, end.plusDays(1)));

        // 警告 3：运杂费为负（按负数抵扣提成）
        addWarn(warnings, "NEGATIVE_FREIGHT",
                "运杂费窗口内存在运杂费为负数的出库单，将按负数抵扣提成，请核对",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(COALESCE(o.freight,0))),0) amt
                    FROM sales_outbounds o
                    WHERE o.attribution_date>=? AND o.attribution_date<?
                      AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
                      AND COALESCE(o.freight,0)<0 AND """ + h3,
                        prevStart, prevEnd));

        // 警告 4：本次基础提成为负（回款不足以抵扣上期运杂费）
        if (items != null) {
            long negativeCount = 0;
            BigDecimal negativeAmt = ZERO;
            for (Map<String, Object> item : items) {
                BigDecimal contribution = decimal(item, "commissionAmount");
                if (contribution.signum() < 0) {
                    negativeCount++;
                    negativeAmt = negativeAmt.add(contribution.abs());
                }
            }
            if (negativeCount > 0) {
                addCheckItem(warnings, "NEGATIVE_BASE",
                        "存在本次待核算基础提成为负的业务员（回款不足以抵扣上期运杂费），请核对是否需调整",
                        negativeCount, negativeAmt);
            }
        }

        // 警告 5：范围内业务员变更补给修正（金额按现归属并入）
        addWarn(warnings, "EMPLOYEE_CHANGED",
                "范围内存在业务员变更补给修正（金额按现归属并入本次核算），请留意归属是否符合预期",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(ABS(c.change_commission)),0) amt
                    FROM commission_outbound_changes c
                    WHERE c.status='PENDING' AND c.prev_employee_id IS NOT NULL
                      AND ((c.receipt_date>=? AND c.receipt_date<?) OR (c.ship_date>=? AND c.ship_date<?))""",
                        start, end.plusDays(1), prevStart, prevEnd));

        // 警告 6：同一出库单收款横跨多个自然月（按收款日期分属不同核算期间）
        addWarn(warnings, "RECEIPT_CROSS_PERIODS",
                "回款范围内存在同一出库单收款横跨多个自然月，将按收款日期分属不同核算期间，请留意",
                oneOrNull("""
                    SELECT COUNT(*) cnt, COALESCE(SUM(x.amt),0) amt
                    FROM (
                        SELECT o.id, SUM(ps.received_amount) amt
                        FROM sales_outbounds o
                        JOIN sales_outbound_payment_snapshots ps ON ps.sales_outbound_id=o.id
                        WHERE o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
                          AND ps.legacy_status='CONFIRMED' AND ps.receipt_date>=? AND ps.receipt_date<?
                          AND """ + h3 + """
                        GROUP BY o.id
                        HAVING COUNT(DISTINCT DATE_FORMAT(ps.receipt_date,'%Y-%m')) > 1
                    ) x""",
                        start, end.plusDays(1)));

        // 警告 7：与最近一次已结账总额差异超过 30%（仅对「本范围首次结账」有意义）
        Integer settledSameRange = jdbc.queryForObject(
                "SELECT COUNT(*) FROM commission_calculation_versions WHERE start_date=? AND end_date=? AND status='FINAL'",
                Integer.class, start, end);
        if ((settledSameRange == null || settledSameRange == 0) && items != null) {
            BigDecimal currentTotal = ZERO;
            for (Map<String, Object> item : items) {
                currentTotal = currentTotal.add(decimal(item, "commissionAmount"));
            }
            Map<String, Object> previous = oneOrNull("""
                SELECT COALESCE(SUM(i.total_commission),0) amt
                FROM commission_calculation_versions v
                LEFT JOIN commission_calculation_items i ON i.version_id=v.id
                WHERE v.status='FINAL' AND v.end_date<? AND v.end_date>=?
                GROUP BY v.id ORDER BY v.end_date DESC, v.id DESC LIMIT 1""",
                    start, start.minusMonths(6));
            BigDecimal oldTotal = amountValue(previous);
            if (oldTotal.signum() > 0) {
                BigDecimal diff = currentTotal.subtract(oldTotal).abs();
                BigDecimal ratio = diff.divide(oldTotal, 4, RoundingMode.HALF_UP);
                if (ratio.compareTo(new BigDecimal("0.30")) > 0) {
                    addCheckItem(warnings, "FLUCTUATION_GT_30",
                            "本次待核算提成与最近一次已结账总额差异超过 30%，请核对是否有大批调整或补录",
                            1, diff);
                }
            }
        }

        // 警告 8：采用自定义运费窗口（与默认口径不同）
        if (CommissionPolicy.FREIGHT_MODE_CUSTOM.equals(actualMode)) {
            addCheckItem(warnings, "CUSTOM_FREIGHT_WINDOW",
                    "本次采用自定义运费窗口（" + prevStart + " ~ " + prevEnd.minusDays(1)
                            + "，与默认口径不同），请确认扣减范围与填写的原因",
                    1, ZERO);
        }

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("blocking", blocking);
        checks.put("warnings", warnings);
        return checks;
    }

    /** 按业务员冻结核算期间内氚云销售出库主单的销售、已收、未收考核快照。 */
    private Map<Long, Map<String, Object>> loadAssessmentSnapshots(LocalDate start, LocalDate endExclusive) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT o.employee_id, COALESCE(MAX(o.salesperson_name),MAX(e.name),'') employee_name,
                   COALESCE(SUM(COALESCE(o.sales_amount,0)),0) sales_amount_snapshot,
                   COALESCE(SUM(COALESCE(o.source_received_amount,o.received_amount,0)),0) received_amount_snapshot,
                   COALESCE(SUM(COALESCE(o.outstanding_amount,0)),0) outstanding_amount_snapshot
            FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            WHERE o.attribution_date>=? AND o.attribution_date<?
              AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
              AND """ + " " + H3_SOURCE_FILTER + "\nGROUP BY o.employee_id",
                this::row, start, endExclusive);
        for (Map<String, Object> row : rows) {
            result.put(((Number) row.get("employeeId")).longValue(), row);
        }
        return result;
    }

    /** 与业务员汇总同事务冻结逐单考核数据，历史重导只影响新版本。 */
    private void captureOutboundAssessmentLines(Long versionId, LocalDate start, LocalDate endExclusive) {
        jdbc.update("""
            INSERT INTO commission_outbound_assessment_lines(
                version_id,outbound_id,outbound_no,customer_name,employee_id,employee_name,
                attribution_date,sales_amount,received_amount,outstanding_amount,payment_status,channel_type)
            SELECT ?,o.id,o.outbound_no,o.customer_name,o.employee_id,
                   COALESCE(o.salesperson_name,e.name,CONCAT('业务员#',o.employee_id)),
                   o.attribution_date,COALESCE(o.sales_amount,0),
                   COALESCE(o.source_received_amount,o.received_amount,0),COALESCE(o.outstanding_amount,0),
                   o.payment_status,
                   CASE WHEN (""" + CommissionPolicy.DEALER_CASE + ") THEN 'DEALER' ELSE 'RETAIL' END\n"
                + "FROM sales_outbounds o\n"
                + "LEFT JOIN employees e ON e.id=o.employee_id\n"
                + "WHERE o.attribution_date>=? AND o.attribution_date<?\n"
                + "  AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL AND "
                + H3_SOURCE_FILTER, versionId, start, endExclusive);
    }

    /** 阻断项入列（count<=0 跳过）。 */
    private void addBlock(List<Map<String, Object>> out, String code, String message, Map<String, Object> agg) {
        long count = countValue(agg);
        if (count <= 0) return;
        out.add(checkItem(code, message, count, amountValue(agg)));
    }

    /** 警告项入列（count<=0 跳过）。 */
    private void addWarn(List<Map<String, Object>> out, String code, String message, Map<String, Object> agg) {
        long count = countValue(agg);
        if (count <= 0) return;
        out.add(checkItem(code, message, count, amountValue(agg)));
    }

    /** 阻断/警告项入列（Java 侧直接计算 count/amount）。 */
    private void addCheckItem(List<Map<String, Object>> out, String code, String message,
                              long count, BigDecimal amount) {
        if (count <= 0) return;
        out.add(checkItem(code, message, count, amount));
    }

    private Map<String, Object> checkItem(String code, String message, long count, BigDecimal amount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("message", message);
        item.put("count", count);
        item.put("amount", amount);
        return item;
    }

    /** 门禁报错：取前三条消息，其余指引回结账预览查看，避免一次性抛超长文本。 */
    private String gateBlockMessage(List<Map<String, Object>> blocking) {
        StringBuilder sb = new StringBuilder("结账前检查未通过（阻断 ").append(blocking.size()).append(" 项）：");
        int shown = Math.min(blocking.size(), 3);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append("；");
            sb.append(blocking.get(i).get("message"));
        }
        if (blocking.size() > shown) {
            sb.append("；其余 ").append(blocking.size() - shown).append(" 项详见结账预览");
        }
        return sb.toString();
    }

    private long countValue(Map<String, Object> row) {
        Object value = row == null ? null : row.get("cnt");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal amountValue(Map<String, Object> row) {
        return decimal(row, "amt");
    }

    private List<SettlementSource> loadUnsettledSources(LocalDate receiptStart, LocalDate receiptEnd,
                                                        LocalDate freightStart, LocalDate freightEnd) {
        return loadUnsettledSources(receiptStart, receiptEnd, freightStart, freightEnd, true);
    }

    /**
     * 加载尚未被有效版本占用的氚云收款/运杂费来源。
     * @param lockRows 核算结账（写事务）传 true，行级 FOR UPDATE 防并发重复占用；
     *                 结账前预览（只读事务）传 false——只读事务执行 FOR UPDATE 会被 MySQL 拒绝（error 1792），
     *                 预览不需要锁，防重仍由核算落库时的 PENDING→PROCESSED + UNIQUE 兜底。
     */
    private List<SettlementSource> loadUnsettledSources(LocalDate receiptStart, LocalDate receiptEnd,
                                                        LocalDate freightStart, LocalDate freightEnd,
                                                        boolean lockRows) {
        List<SettlementSource> sources = new ArrayList<>();
        String lockSql = lockRows ? " FOR UPDATE" : "";
        String receiptSql = """
            SELECT ps.id source_id, o.id outbound_id, o.outbound_no, o.customer_name, o.employee_id,
                   COALESCE(o.salesperson_name, e.name, CONCAT('业务员#',o.employee_id)) employee_name,
                   ps.receipt_date occurrence_date, ps.received_amount amount,
                   CASE WHEN (%s) THEN 1 ELSE 0 END dealer_flag,
                   %s channel_source
            FROM sales_outbound_payment_snapshots ps
            JOIN sales_outbounds o ON o.id=ps.sales_outbound_id
            LEFT JOIN employees e ON e.id=o.employee_id
            WHERE ps.receipt_date>=? AND ps.receipt_date<?
              AND ps.legacy_status='CONFIRMED' AND o.outbound_status='CONFIRMED'
              AND o.employee_id IS NOT NULL AND %s
              AND NOT EXISTS (
                  SELECT 1 FROM commission_calculation_source_lines l
                  WHERE l.active_source_key=CONCAT('RECEIPT:',ps.id)
              )
            ORDER BY ps.id
            """.formatted(CommissionPolicy.DEALER_CASE, CommissionPolicy.DEALER_SOURCE_CASE, H3_SOURCE_FILTER) + lockSql;
        jdbc.query(receiptSql, (rs, n) -> sources.add(source(rs, "RECEIPT")), receiptStart, receiptEnd);
        String freightSql = """
            SELECT o.id source_id, o.id outbound_id, o.outbound_no, o.customer_name, o.employee_id,
                   COALESCE(o.salesperson_name, e.name, CONCAT('业务员#',o.employee_id)) employee_name,
                   o.attribution_date occurrence_date, COALESCE(o.freight,0) amount,
                   CASE WHEN (%s) THEN 1 ELSE 0 END dealer_flag,
                   %s channel_source
            FROM sales_outbounds o
            LEFT JOIN employees e ON e.id=o.employee_id
            WHERE o.attribution_date>=? AND o.attribution_date<?
              AND o.outbound_status='CONFIRMED' AND o.employee_id IS NOT NULL
              AND COALESCE(o.freight,0)<>0 AND %s
              AND NOT EXISTS (
                  SELECT 1 FROM commission_calculation_source_lines l
                  WHERE l.active_source_key=CONCAT('FREIGHT:',o.id)
              )
            ORDER BY o.id
            """.formatted(CommissionPolicy.DEALER_CASE, CommissionPolicy.DEALER_SOURCE_CASE, H3_SOURCE_FILTER) + lockSql;
        jdbc.query(freightSql, (rs, n) -> sources.add(source(rs, "FREIGHT")), freightStart, freightEnd);
        return sources;
    }

    private SettlementSource source(ResultSet rs, String lineType) throws SQLException {
        java.sql.Date date = rs.getDate("occurrence_date");
        return new SettlementSource(lineType, rs.getLong("source_id"), rs.getLong("outbound_id"),
                rs.getString("outbound_no"), rs.getString("customer_name"), rs.getLong("employee_id"),
                rs.getString("employee_name"), date == null ? null : date.toLocalDate(),
                rs.getInt("dealer_flag") == 1, rs.getString("channel_source"), bd(rs, "amount"));
    }

    /**
     * base 员工聚合（G7）：金额仍分渠道累计；贡献改为按「来源行发生日」追溯当时比例逐行计算
     * （运杂费行贡献为负），与版本来源明细行的 applied_rate/commission_contribution 完全同口径，
     * 保证同一比例段内数字与旧算法一致（同率时逐行累加 = 汇总后乘率）。
     * 员工快照比例取本批最晚来源发生日的生效比例；同一批跨比例段时由 versionDetail 的
     * rateSegments / multiRateSegments 补充提示（版本明细不做落库）。
     */
    private List<Map<String, Object>> summarizeSources(List<SettlementSource> sources,
                                                       Map<Long, List<CommissionPolicy.RateEntry>> history) {
        Map<Long, Map<String, Object>> byEmployee = new LinkedHashMap<>();
        for (SettlementSource source : sources) {
            Map<String, Object> item = byEmployee.computeIfAbsent(source.employeeId(), id -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employeeId", id);
                row.put("employeeName", source.employeeName());
                row.put("dealerReceiptAmount", ZERO);
                row.put("retailReceiptAmount", ZERO);
                row.put("dealerFreightAmount", ZERO);
                row.put("retailFreightAmount", ZERO);
                row.put("dealerContribution", ZERO);
                row.put("retailContribution", ZERO);
                return row;
            });
            String key = source.dealer()
                    ? ("RECEIPT".equals(source.lineType()) ? "dealerReceiptAmount" : "dealerFreightAmount")
                    : ("RECEIPT".equals(source.lineType()) ? "retailReceiptAmount" : "retailFreightAmount");
            item.put(key, decimal(item, key).add(source.amount()));
            String contributionKey = source.dealer() ? "dealerContribution" : "retailContribution";
            CommissionPolicy.Rate rate = CommissionPolicy.rateAt(history, source.employeeId(), source.occurrenceDate());
            BigDecimal contribution = source.amount().multiply(source.dealer() ? rate.dealerRate() : rate.retailRate());
            if ("FREIGHT".equals(source.lineType())) contribution = contribution.negate();
            item.put(contributionKey, decimal(item, contributionKey).add(contribution));
        }
        for (Map.Entry<Long, Map<String, Object>> entry : byEmployee.entrySet()) {
            Long empId = entry.getKey();
            Map<String, Object> item = entry.getValue();
            LocalDate snapshotDate = sources.stream()
                    .filter(s -> s.employeeId() != null && s.employeeId().equals(empId) && s.occurrenceDate() != null)
                    .map(SettlementSource::occurrenceDate)
                    .max(LocalDate::compareTo).orElse(null);
            CommissionPolicy.Rate snapshot = CommissionPolicy.rateAt(history, empId, snapshotDate);
            BigDecimal commission = decimal(item, "dealerContribution").add(decimal(item, "retailContribution"));
            item.put("dealerRate", snapshot.dealerRate());
            item.put("retailRate", snapshot.retailRate());
            item.put("commissionAmount", money(commission));
        }
        return new ArrayList<>(byEmployee.values());
    }

    /**
     * base 来源占用行（RECEIPT/FREIGHT，G5+G7）：每行记录渠道判定来源 channel_source 与
     * 「按发生日追溯」的应用比例，防重依赖 active_source_key 唯一键与事务内 FOR UPDATE。
     */
    private int claimSources(Long versionId, List<SettlementSource> sources,
                             Map<Long, List<CommissionPolicy.RateEntry>> history) {
        int claimed = 0;
        for (SettlementSource source : sources) {
            CommissionPolicy.Rate rate = CommissionPolicy.rateAt(history, source.employeeId(), source.occurrenceDate());
            BigDecimal appliedRate = source.dealer() ? rate.dealerRate() : rate.retailRate();
            BigDecimal contribution = source.amount().multiply(appliedRate);
            if ("FREIGHT".equals(source.lineType())) contribution = contribution.negate();
            claimed += jdbc.update("""
                INSERT INTO commission_calculation_source_lines(
                    version_id,employee_id,employee_name,line_type,source_id,outbound_id,outbound_no,
                    customer_name,occurrence_date,channel_type,channel_source,amount,applied_rate,
                    commission_contribution,status,active_source_key)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)
                """, versionId, source.employeeId(), source.employeeName(), source.lineType(), source.sourceId(),
                    source.outboundId(), source.outboundNo(), source.customerName(), source.occurrenceDate(),
                    source.dealer() ? "DEALER" : "RETAIL", source.channelSource(), source.amount(), appliedRate,
                    contribution, source.lineType() + ":" + source.sourceId());
        }
        return claimed;
    }

    /**
     * 修正变化并入核算版本时的占用登记（G1，2026-09-04）：
     * 每笔「独立于本版本正常提成之外并入」的变化拆成经销/零售 ADJUSTMENT 来源行，
     * 纳入统一「一条明细只能被一个有效 FINAL 版本占用」约束；作废时随版本一并释放回滚。
     *
     * <p>若变化所属 (employee, outbound) 本次同时作为正常提成来源被占用（新增收款/首次运费
     * 本身触发单据级变化、金额已含入 base），该变化不再单列——由正常提成来源行覆盖，
     * 避免同一金额在来源明细中重复列示；其占用防重仍由 PENDING→PROCESSED 状态 + 行锁保证。
     *
     * <p>过滤条件与结账并入修正金额的 adjFilter 完全一致，保证 ADJUSTMENT 行合计 =
     * 独立并入的修正提成（与版本 items.adj_commission 口径可对账）。
     *
     * @return 写入的 ADJUSTMENT 来源行数
     */
    private int claimAdjustments(Long versionId, String adjFilter, Object[] adjParams, Set<String> claimedSourcePairs) {
        if (versionId == null || adjFilter == null || adjFilter.isBlank()) return 0;
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT c.id change_id, c.employee_id, c.employee_name, c.outbound_id, c.outbound_no,
                   c.customer_name, c.receipt_date, c.ship_date,
                   c.before_receipt_dealer, c.after_receipt_dealer,
                   c.before_receipt_retail, c.after_receipt_retail,
                   c.before_freight_dealer, c.after_freight_dealer,
                   c.before_freight_retail, c.after_freight_retail,
                   c.dealer_rate, c.retail_rate, c.change_commission
            FROM commission_outbound_changes c
            WHERE c.status='PENDING' """ + adjFilter + """
            ORDER BY c.id
            FOR UPDATE
            """, this::row, adjParams);
        int claimed = 0;
        for (Map<String, Object> r : rows) {
            Object empObj = r.get("employeeId");
            Long employeeId = empObj instanceof Number n ? n.longValue() : null;
            if (employeeId == null || employeeId <= 0) continue;
            Object outboundObj = r.get("outboundId");
            if (outboundObj instanceof Number obn && claimedSourcePairs.contains(employeeId + ":" + obn.longValue())) {
                continue; // 该变化影响已含入本版本正常提成，不单列
            }
            BigDecimal dRecD = decimal(r, "afterReceiptDealer").subtract(decimal(r, "beforeReceiptDealer"));
            BigDecimal dFreD = decimal(r, "afterFreightDealer").subtract(decimal(r, "beforeFreightDealer"));
            BigDecimal dRecR = decimal(r, "afterReceiptRetail").subtract(decimal(r, "beforeReceiptRetail"));
            BigDecimal dFreR = decimal(r, "afterFreightRetail").subtract(decimal(r, "beforeFreightRetail"));
            BigDecimal netDealer = dRecD.subtract(dFreD);
            BigDecimal netRetail = dRecR.subtract(dFreR);
            if (netDealer.signum() == 0 && netRetail.signum() == 0) continue;
            LocalDate occurrenceDate = changeOccurrenceDate(r);
            if (occurrenceDate == null) continue;
            BigDecimal commission = decimal(r, "changeCommission");
            if (netDealer.signum() != 0 && netRetail.signum() != 0) {
                BigDecimal contributionDealer = money(netDealer.multiply(decimal(r, "dealerRate")));
                claimed += insertAdjustmentLine(versionId, r, "DEALER", occurrenceDate, money(netDealer),
                        decimal(r, "dealerRate"), contributionDealer);
                claimed += insertAdjustmentLine(versionId, r, "RETAIL", occurrenceDate, money(netRetail),
                        decimal(r, "retailRate"), commission.subtract(contributionDealer));
            } else if (netDealer.signum() != 0) {
                claimed += insertAdjustmentLine(versionId, r, "DEALER", occurrenceDate, money(netDealer),
                        decimal(r, "dealerRate"), commission);
            } else {
                claimed += insertAdjustmentLine(versionId, r, "RETAIL", occurrenceDate, money(netRetail),
                        decimal(r, "retailRate"), commission);
            }
        }
        return claimed;
    }

    /** 变化行归属日期：优先回款日期，其次发货日期；均无（旧历史数据）则放弃单列。 */
    private LocalDate changeOccurrenceDate(Map<String, Object> r) {
        Object rd = r.get("receiptDate");
        Object sd = r.get("shipDate");
        if (rd instanceof java.sql.Date d) return d.toLocalDate();
        if (sd instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    /**
     * 写一条 ADJUSTMENT 来源占用行（INSERT IGNORE 幂等；占用键 = 调整 id + 渠道）。
     * 金额为经销/零售渠道净变化额（Δ回款 − Δ运费，负数=抵扣），贡献为该渠道净额 × 原结算比例，
     * 两渠道贡献合计 = 该笔变化 change_commission（与修正提成口径一致）。
     */
    private int insertAdjustmentLine(Long versionId, Map<String, Object> r, String channel,
                                     LocalDate occurrenceDate, BigDecimal amount, BigDecimal rate,
                                     BigDecimal contribution) {
        Object empObj = r.get("employeeId");
        long employeeId = empObj instanceof Number n ? n.longValue() : 0L;
        long outboundId = r.get("outboundId") instanceof Number n ? n.longValue() : 0L;
        Object changeIdObj = r.get("changeId");
        long changeId = changeIdObj instanceof Number n ? n.longValue() : 0L;
        if (employeeId <= 0 || outboundId <= 0 || changeId <= 0) return 0;
        Object no = r.get("outboundNo");
        Object customer = r.get("customerName");
        String employeeName = r.get("employeeName") == null ? "业务员#" + employeeId : String.valueOf(r.get("employeeName"));
        return jdbc.update("""
            INSERT IGNORE INTO commission_calculation_source_lines(
                version_id, employee_id, employee_name, line_type, source_id, outbound_id, outbound_no,
                customer_name, occurrence_date, channel_type, channel_source, amount, applied_rate,
                commission_contribution, status, active_source_key)
            VALUES (?,?,?, 'ADJUSTMENT', ?,?,?,?,?,?, NULL, ?,?,?, 'ACTIVE', ?)
            """, versionId, employeeId, employeeName, changeId, outboundId,
                no == null ? null : String.valueOf(no), customer == null ? null : String.valueOf(customer),
                occurrenceDate, channel, amount, rate, contribution,
                "ADJUSTMENT:" + changeId + ":" + channel);
    }

    /** 本版本已作为正常提成来源占用的 (employee:outbound) 集合（判断修正变化是否与其重叠）。 */
    private Set<String> claimedSourcePairs(List<SettlementSource> sources) {
        Set<String> pairs = new LinkedHashSet<>();
        for (SettlementSource s : sources) {
            pairs.add(s.employeeId() + ":" + s.outboundId());
        }
        return pairs;
    }

    private Long insertVersion(LocalDate start, LocalDate end, String periodLabel, String versionNo,
                               String remark, LocalDateTime now, Long operatorId, String operatorName,
                               String settlementType, LocalDate freightStart, LocalDate freightEnd,
                               String freightMode) {
        jdbc.update("""
            INSERT INTO commission_calculation_versions(
                period, start_date, end_date, version_no, remark, status, calculated_at,
                created_by, created_by_name, settlement_type, freight_start_date, freight_end_date, freight_period_mode)
            VALUES (?,?,?,?,?, 'FINAL',?,?,?,?,?,?,?)
            """, periodLabel, start, end, versionNo, remark, now, operatorId, operatorName,
                settlementType, freightStart, freightEnd, freightMode);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM commission_calculation_versions WHERE start_date=? AND end_date=? AND version_no=?",
                Long.class, start, end, versionNo);
    }

    // ==================== 3. 查询 ====================

    /** 核算版本列表（全部版本，按核算时间倒序），附每版本汇总与核算日期范围与「可作废」标记。 */
    public List<Map<String, Object>> listVersions() {
        List<Map<String, Object>> list = jdbc.query("""
            SELECT v.id, v.period, v.start_date, v.end_date, v.version_no, v.remark, v.status,
                   v.calculated_at, v.created_by_name,
                   v.settlement_type, v.freight_start_date, v.freight_end_date, v.freight_period_mode,
                   v.paid_flag, v.paid_by, v.paid_at, v.void_reason, v.void_by, v.void_at,
                   COUNT(i.id) employee_count,
                   COALESCE(SUM(i.base_commission),0) total_base_commission,
                   COALESCE(SUM(i.adj_commission),0) total_adj_commission,
                   COALESCE(SUM(i.total_commission),0) total_commission
            FROM commission_calculation_versions v
            LEFT JOIN commission_calculation_items i ON i.version_id=v.id
            GROUP BY v.id
            ORDER BY v.calculated_at DESC, v.id DESC
            """, this::row);
        for (Map<String, Object> row : list) {
            // 已标记发放的 FINAL 版本不可作废。
            boolean paid = "PAID".equals(String.valueOf(row.get("paidFlag")));
            row.put("voidable", "FINAL".equals(String.valueOf(row.get("status"))) && !paid);
        }
        return list;
    }

    /**
     * 作废核算版本：状态置 VOID，释放其收款/运杂费/调整来源占用，并将修正变化回滚为 PENDING。
     * 约束（V259）：仅 FINAL 且未发放版本可作废；作废原因必填，审计留痕。
     */
    @Transactional
    public Map<String, Object> voidVersion(Long versionId, String reason) {
        if (versionId == null) throw new IllegalArgumentException("请指定核算版本");
        String cleanReason = clean(reason);
        if (cleanReason == null) throw new IllegalArgumentException("请填写作废原因");
        Map<String, Object> v = oneOrNull("""
            SELECT id, period, start_date, end_date, version_no, status, paid_flag, calculated_at, created_by_name
            FROM commission_calculation_versions WHERE id=?
            """, versionId);
        if (v == null) throw new IllegalArgumentException("核算版本不存在");
        if (!"FINAL".equals(String.valueOf(v.get("status")))) {
            throw new IllegalArgumentException("仅已结账（FINAL）版本可作废");
        }
        if ("PAID".equals(String.valueOf(v.get("paidFlag")))) {
            throw new IllegalArgumentException("该版本提成已标记发放，不可作废；如需调整请通过补充核算或人工处理");
        }
        Object sObj = v.get("startDate");
        Object eObj = v.get("endDate");
        if (sObj == null || eObj == null) {
            throw new IllegalArgumentException("旧版核算版本（无核算日期范围）不支持作废");
        }
        Long operatorId = currentUsers.current().employeeId();
        String operatorName = currentUsers.current().name();
        jdbc.update("""
            UPDATE commission_calculation_versions
               SET status='VOID', void_reason=?, void_by=?, void_at=NOW()
             WHERE id=?
            """, cleanReason, operatorId, versionId);
        int released = jdbc.update("""
            UPDATE commission_calculation_source_lines
               SET status='RELEASED', active_source_key=NULL, released_at=NOW()
             WHERE version_id=? AND status='ACTIVE'
            """, versionId);
        int rolled = jdbc.update("""
            UPDATE commission_outbound_changes SET status='PENDING', version_id=NULL
            WHERE version_id=?
            """, versionId);
        auditLog.log(VERSION_ENTITY, versionId, String.valueOf(v.get("versionNo")), "VOID",
                "作废核算版本 " + v.get("period") + "（原因：" + cleanReason + "；操作人：" + operatorName
                        + "）：释放核算来源 " + released + " 笔，回滚已并入修正变化 " + rolled + " 笔（恢复待核算）");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        result.put("versionNo", v.get("versionNo"));
        result.put("status", "VOID");
        result.put("voidReason", cleanReason);
        result.put("voidAt", LocalDateTime.now().toString());
        result.put("rolledBackChanges", rolled);
        result.put("releasedSourceLines", released);
        return result;
    }

    /** 标记核算版本提成已发放：仅 FINAL 版本可发放；发放后版本不可作废（审计留痕）。 */
    @Transactional
    public Map<String, Object> markVersionPaid(Long versionId) {
        if (versionId == null) throw new IllegalArgumentException("请指定核算版本");
        Map<String, Object> v = oneOrNull("""
            SELECT id, period, version_no, status, paid_flag FROM commission_calculation_versions WHERE id=?
            """, versionId);
        if (v == null) throw new IllegalArgumentException("核算版本不存在");
        if (!"FINAL".equals(String.valueOf(v.get("status")))) {
            throw new IllegalArgumentException("仅已结账（FINAL）版本可标记发放");
        }
        if ("PAID".equals(String.valueOf(v.get("paidFlag")))) {
            throw new IllegalArgumentException("该版本已标记发放，请勿重复操作");
        }
        Long operatorId = currentUsers.current().employeeId();
        String operatorName = currentUsers.current().name();
        jdbc.update("""
            UPDATE commission_calculation_versions SET paid_flag='PAID', paid_by=?, paid_at=NOW() WHERE id=?
            """, operatorId, versionId);
        auditLog.log(VERSION_ENTITY, versionId, String.valueOf(v.get("versionNo")), "MARK_PAID",
                "标记核算版本 " + v.get("period") + " 提成为已发放（操作人：" + operatorName + "）");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        result.put("versionNo", v.get("versionNo"));
        result.put("paidFlag", "PAID");
        return result;
    }

    /** 核算版本明细（业务员行 + 版本汇总）。 */
    public Map<String, Object> versionDetail(Long versionId) {
        if (versionId == null) throw new IllegalArgumentException("请指定核算版本");
        Map<String, Object> version = oneOrNull("""
            SELECT v.id, v.period, v.start_date, v.end_date, v.version_no, v.remark, v.status,
                   v.calculated_at, v.created_by_name,
                   v.settlement_type, v.freight_start_date, v.freight_end_date, v.freight_period_mode,
                   v.paid_flag, v.paid_by, v.paid_at, v.void_reason, v.void_by, v.void_at
            FROM commission_calculation_versions v WHERE v.id=?
            """, versionId);
        if (version == null) throw new IllegalArgumentException("核算版本不存在");
        List<Map<String, Object>> items = jdbc.query("""
            SELECT * FROM commission_calculation_items WHERE version_id=? ORDER BY total_commission DESC, employee_name
            """, this::row, versionId);
        BigDecimal totalBase = ZERO, totalAdj = ZERO, totalCommission = ZERO;
        for (Map<String, Object> r : items) {
            totalBase = totalBase.add(bd(r, "baseCommission"));
            totalAdj = totalAdj.add(bd(r, "adjCommission"));
            totalCommission = totalCommission.add(bd(r, "totalCommission"));
        }
        version.put("items", items);
        version.put("employeeCount", items.size());
        version.put("totalBaseCommission", totalBase);
        version.put("totalAdjCommission", totalAdj);
        version.put("totalCommission", totalCommission);
        List<Map<String, Object>> sourceLines = jdbc.query("""
            SELECT id, employee_id, employee_name, line_type, source_id, outbound_id, outbound_no,
                   customer_name, occurrence_date, channel_type, channel_source, amount, applied_rate,
                   commission_contribution, status
            FROM commission_calculation_source_lines
            WHERE version_id=?
            ORDER BY employee_name, occurrence_date, line_type, id
            """, this::row, versionId);
        version.put("sourceLines", sourceLines);
        version.put("sourceLineCount", sourceLines.size());
        List<Map<String, Object>> assessmentLines = jdbc.query("""
            SELECT outbound_id,outbound_no,customer_name,employee_id,employee_name,attribution_date,
                   sales_amount,received_amount,outstanding_amount,payment_status,channel_type
            FROM commission_outbound_assessment_lines
            WHERE version_id=? ORDER BY employee_name,attribution_date,outbound_id
            """, this::row, versionId);
        version.put("assessmentLines", assessmentLines);
        version.put("assessmentLineCount", assessmentLines.size());
        // G7：同一员工本批来源跨比例段时，附 rateSegments 供前端"多段比例"提示（由来源行现算，不落库）
        for (Map<String, Object> item : items) {
            Long empId = item.get("employeeId") instanceof Number n ? n.longValue() : null;
            if (empId == null) continue;
            List<Map<String, Object>> segments = rateSegmentsFor(sourceLines, empId);
            item.put("rateSegments", segments);
            item.put("multiRateSegments", segments.size() > 1);
        }
        return version;
    }

    /**
     * 两个核算版本逐业务员对比：输出每业务员两版各自金额与差额（diff = 新版 − 旧版）。
     * @param newerVersionId 新版（被减数）
     * @param olderVersionId 旧版（减数）
     */
    public Map<String, Object> compareVersions(Long newerVersionId, Long olderVersionId) {
        if (newerVersionId == null || olderVersionId == null) {
            throw new IllegalArgumentException("请选择两个核算版本进行对比");
        }
        if (newerVersionId.equals(olderVersionId)) throw new IllegalArgumentException("请选择两个不同的核算版本");
        Map<String, Object> newer = oneOrNull("SELECT id, period, start_date, end_date, version_no, calculated_at FROM commission_calculation_versions WHERE id=?",
                newerVersionId);
        Map<String, Object> older = oneOrNull("SELECT id, period, start_date, end_date, version_no, calculated_at FROM commission_calculation_versions WHERE id=?",
                olderVersionId);
        if (newer == null || older == null) throw new IllegalArgumentException("核算版本不存在");
        // 仅支持同一核算期间内的版本对比：不同期间的版本组成口径不同（回款/运杂费窗口均不同），
        // 逐业务员差额无业务可比性
        if (!Objects.equals(newer.get("startDate"), older.get("startDate"))
                || !Objects.equals(newer.get("endDate"), older.get("endDate"))) {
            throw new IllegalArgumentException("仅支持同一核算期间内的版本对比（不同期间版本无业务可比性）");
        }

        Map<Long, Map<String, Object>> nMap = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> oMap = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM commission_calculation_items WHERE version_id=?",
                this::row, newerVersionId).forEach(r -> nMap.put(((Number) r.get("employeeId")).longValue(), r));
        jdbc.query("SELECT * FROM commission_calculation_items WHERE version_id=?",
                this::row, olderVersionId).forEach(r -> oMap.put(((Number) r.get("employeeId")).longValue(), r));

        Set<Long> empIds = new LinkedHashSet<>(nMap.keySet());
        empIds.addAll(oMap.keySet());
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalBaseDiff = ZERO, totalAdjDiff = ZERO, totalCommissionDiff = ZERO;
        for (Long empId : empIds) {
            Map<String, Object> n = nMap.get(empId);
            Map<String, Object> o = oMap.get(empId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", empId);
            row.put("employeeName", n != null ? String.valueOf(n.get("employeeName"))
                    : String.valueOf(o.get("employeeName")));
            row.put("baseCommissionNew", bd(n, "baseCommission"));
            row.put("baseCommissionOld", bd(o, "baseCommission"));
            row.put("baseCommissionDiff", bd(n, "baseCommission").subtract(bd(o, "baseCommission")));
            row.put("adjCommissionNew", bd(n, "adjCommission"));
            row.put("adjCommissionOld", bd(o, "adjCommission"));
            row.put("adjCommissionDiff", bd(n, "adjCommission").subtract(bd(o, "adjCommission")));
            row.put("totalCommissionNew", bd(n, "totalCommission"));
            row.put("totalCommissionOld", bd(o, "totalCommission"));
            row.put("totalCommissionDiff", bd(n, "totalCommission").subtract(bd(o, "totalCommission")));
            row.put("adjReceiptNew", bd(n, "adjReceiptDealer").add(bd(n, "adjReceiptRetail")));
            row.put("adjReceiptOld", bd(o, "adjReceiptDealer").add(bd(o, "adjReceiptRetail")));
            row.put("adjReceiptDiff", bd(n, "adjReceiptDealer").add(bd(n, "adjReceiptRetail"))
                    .subtract(bd(o, "adjReceiptDealer").add(bd(o, "adjReceiptRetail"))));
            row.put("adjFreightNew", bd(n, "adjFreightDealer").add(bd(n, "adjFreightRetail")));
            row.put("adjFreightOld", bd(o, "adjFreightDealer").add(bd(o, "adjFreightRetail")));
            row.put("adjFreightDiff", bd(n, "adjFreightDealer").add(bd(n, "adjFreightRetail"))
                    .subtract(bd(o, "adjFreightDealer").add(bd(o, "adjFreightRetail"))));
            items.add(row);
            totalBaseDiff = totalBaseDiff.add(bd(n, "baseCommission").subtract(bd(o, "baseCommission")));
            totalAdjDiff = totalAdjDiff.add(bd(n, "adjCommission").subtract(bd(o, "adjCommission")));
            totalCommissionDiff = totalCommissionDiff.add(bd(n, "totalCommission").subtract(bd(o, "totalCommission")));
        }
        items.sort((a, b) -> ((BigDecimal) b.get("totalCommissionDiff")).compareTo((BigDecimal) a.get("totalCommissionDiff")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newerVersion", newer);
        result.put("olderVersion", older);
        result.put("totalBaseCommissionDiff", totalBaseDiff);
        result.put("totalAdjCommissionDiff", totalAdjDiff);
        result.put("totalCommissionDiff", totalCommissionDiff);
        result.put("employeeCount", items.size());
        result.put("items", items);
        return result;
    }

    /** 已生效单变化明细（可选按状态/批次过滤，支持 limit/offset 分页），单据级。 */
    public List<Map<String, Object>> listChanges(String status, Long batchId, Integer limit, Long offset) {
        StringBuilder sql = new StringBuilder("""
            SELECT c.id, c.batch_id, c.outbound_id, c.outbound_no, c.customer_name,
                   c.receipt_date, c.ship_date,
                   c.employee_id, c.employee_name, c.prev_employee_id, c.prev_employee_name,
                   c.recorded_at, c.source,
                   c.before_receipt_dealer, c.after_receipt_dealer,
                   c.before_receipt_retail, c.after_receipt_retail,
                   c.before_freight_dealer, c.after_freight_dealer,
                   c.before_freight_retail, c.after_freight_retail,
                   c.dealer_rate, c.retail_rate, c.change_commission, c.remark, c.status, c.version_id
            FROM commission_outbound_changes c
            WHERE 1=1
            """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status=?");
            params.add(status.trim());
        }
        if (batchId != null) {
            sql.append(" AND c.batch_id=?");
            params.add(batchId);
        }
        sql.append(" ORDER BY c.recorded_at DESC, c.id DESC");
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ?");
            params.add(limit);
            if (offset != null && offset > 0) {
                sql.append(" OFFSET ?");
                params.add(offset);
            }
        }
        return jdbc.query(sql.toString(), this::row, params.toArray());
    }

    /** 变化数量（可选按状态过滤）：前端待核算徽标用，避免拉全量明细。 */
    public int countChanges(String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM commission_outbound_changes WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            params.add(status.trim());
        }
        return jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
    }

    // ==================== P4 CSV 导出（G8，2026-09-04） ====================

    /**
     * 提成发放汇总导出（每业务员一行）：正常（回款−运费）×比例 + 修正 + 应发，随版本口径。
     */
    public byte[] exportVersionItems(Long versionId) {
        Map<String, Object> v = requireExportVersion(versionId);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT i.employee_id, i.employee_name, i.dealer_rate, i.retail_rate,
                   i.base_receipt_dealer, i.base_receipt_retail,
                   i.base_freight_dealer, i.base_freight_retail,
                   i.adj_receipt_dealer, i.adj_receipt_retail,
                   i.adj_freight_dealer, i.adj_freight_retail,
                   i.base_commission, i.adj_commission, i.total_commission,
                   i.sales_amount_snapshot, i.received_amount_snapshot,
                   i.outstanding_amount_snapshot, i.collection_rate_snapshot
            FROM commission_calculation_items i
            WHERE i.version_id=? ORDER BY i.employee_id""", this::row, versionId);
        StringBuilder csv = new StringBuilder("\uFEFF提成发放汇总（核算版本 ")
                .append(v.get("versionNo")).append("，共 ").append(rows.size()).append(" 人）\r\n");
        appendCsvMeta(csv, v);
        csv.append("\r\n业务员,期间销售额快照,氚云已收款快照,氚云未收款快照,回款率快照,经销商比例,零售比例,正常经销回款,正常零售回款,正常经销运费,正常零售运费,"
                + "修正经销回款,修正零售回款,修正经销运费,修正零售运费,正常提成,修正提成,应发提成\r\n");
        for (Map<String, Object> r : rows) {
            csv.append(q(r.get("employeeName"))).append(',')
                    .append(qAmt(r.get("salesAmountSnapshot"))).append(',')
                    .append(qAmt(r.get("receivedAmountSnapshot"))).append(',')
                    .append(qAmt(r.get("outstandingAmountSnapshot"))).append(',')
                    .append(qRate(r.get("collectionRateSnapshot"))).append(',')
                    .append(qRate(r.get("dealerRate"))).append(',')
                    .append(qRate(r.get("retailRate"))).append(',')
                    .append(qAmt(r.get("baseReceiptDealer"))).append(',')
                    .append(qAmt(r.get("baseReceiptRetail"))).append(',')
                    .append(qAmt(r.get("baseFreightDealer"))).append(',')
                    .append(qAmt(r.get("baseFreightRetail"))).append(',')
                    .append(qAmt(r.get("adjReceiptDealer"))).append(',')
                    .append(qAmt(r.get("adjReceiptRetail"))).append(',')
                    .append(qAmt(r.get("adjFreightDealer"))).append(',')
                    .append(qAmt(r.get("adjFreightRetail"))).append(',')
                    .append(qAmt(r.get("baseCommission"))).append(',')
                    .append(qAmt(r.get("adjCommission"))).append(',')
                    .append(qAmt(r.get("totalCommission"))).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 核算来源明细导出（来源行粒度）：回款/运杂费/调整三类行，含渠道来源与导入批次线索。
     */
    public byte[] exportVersionLines(Long versionId) {
        Map<String, Object> v = requireExportVersion(versionId);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT l.line_type, l.employee_id, l.employee_name, l.outbound_id,
                   l.outbound_no, l.customer_name, l.occurrence_date,
                   l.channel_type, l.channel_source, l.source_id,
                   l.amount, l.applied_rate, l.commission_contribution, l.status
            FROM commission_calculation_source_lines l
            WHERE l.version_id=? ORDER BY l.employee_id, l.occurrence_date, l.id""", this::row, versionId);
        Map<Long, String> batchByOutbound = resolveOutboundImportBatches(rows);
        StringBuilder csv = new StringBuilder("\uFEFF核算来源明细（核算版本 ")
                .append(v.get("versionNo")).append("，共 ").append(rows.size()).append(" 行）\r\n");
        appendCsvMeta(csv, v);
        csv.append("\r\n类型,业务员,出库单号,客户,归属日期,渠道,渠道来源,来源金额,适用比例,提成贡献,状态,来源ID,导入批次\r\n");
        for (Map<String, Object> r : rows) {
            String batch = batchByOutbound.get(parseId(r.get("outboundId")));
            csv.append(q(lineTypeLabel(String.valueOf(r.get("lineType"))))).append(',')
                    .append(q(r.get("employeeName"))).append(',')
                    .append(q(r.get("outboundNo"))).append(',')
                    .append(q(r.get("customerName"))).append(',')
                    .append(q(r.get("occurrenceDate"))).append(',')
                    .append(q(channelTypeLabel(String.valueOf(r.get("channelType"))))).append(',')
                    .append(q(channelSourceLabel(String.valueOf(r.get("channelSource"))))).append(',')
                    .append(qAmt6(r.get("amount"))).append(',')
                    .append(qRate(r.get("appliedRate"))).append(',')
                    .append(qAmt6(r.get("commissionContribution"))).append(',')
                    .append(q(sourceStatusLabel(String.valueOf(r.get("status"))))).append(',')
                    .append(q(r.get("sourceId"))).append(',')
                    .append(q(batch)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 修正（调整）明细导出：版本已并入的变化记录，单据级核对口径来源批次。
     */
    public byte[] exportVersionAdjustments(Long versionId) {
        Map<String, Object> v = requireExportVersion(versionId);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT c.employee_id, c.employee_name, c.prev_employee_id, c.prev_employee_name,
                   c.outbound_no, c.customer_name, c.receipt_date, c.ship_date,
                   c.before_receipt_dealer, c.after_receipt_dealer,
                   c.before_receipt_retail, c.after_receipt_retail,
                   c.before_freight_dealer, c.after_freight_dealer,
                   c.before_freight_retail, c.after_freight_retail,
                   c.dealer_rate, c.retail_rate, c.change_commission,
                   c.source, c.recorded_at, c.remark, c.status,
                   b.file_name AS batch_file_name
            FROM commission_outbound_changes c
            LEFT JOIN h3_import_batches b ON b.id = c.batch_id
            WHERE c.version_id=? AND c.status='PROCESSED' ORDER BY c.id""", this::row, versionId);
        StringBuilder csv = new StringBuilder("\uFEFF修正（调整）明细（核算版本 ")
                .append(v.get("versionNo")).append("，共 ").append(rows.size()).append(" 条）\r\n");
        appendCsvMeta(csv, v);
        csv.append("\r\n业务员,原业务员,出库单号,客户,回款归属日期,运费归属日期,"
                + "变化前经销回款,变化后经销回款,变化前零售回款,变化后零售回款,"
                + "变化前经销运费,变化后经销运费,变化前零售运费,变化后零售运费,"
                + "经销比例,零售比例,变化提成,来源,导入批次,识别时间,备注,状态\r\n");
        for (Map<String, Object> r : rows) {
            String batch = r.get("batchFileName") == null ? "" : String.valueOf(r.get("batchFileName"));
            csv.append(q(r.get("employeeName"))).append(',')
                    .append(q(r.get("prevEmployeeName"))).append(',')
                    .append(q(r.get("outboundNo"))).append(',')
                    .append(q(r.get("customerName"))).append(',')
                    .append(q(r.get("receiptDate"))).append(',')
                    .append(q(r.get("shipDate"))).append(',')
                    .append(qAmt(r.get("beforeReceiptDealer"))).append(',')
                    .append(qAmt(r.get("afterReceiptDealer"))).append(',')
                    .append(qAmt(r.get("beforeReceiptRetail"))).append(',')
                    .append(qAmt(r.get("afterReceiptRetail"))).append(',')
                    .append(qAmt(r.get("beforeFreightDealer"))).append(',')
                    .append(qAmt(r.get("afterFreightDealer"))).append(',')
                    .append(qAmt(r.get("beforeFreightRetail"))).append(',')
                    .append(qAmt(r.get("afterFreightRetail"))).append(',')
                    .append(qRate(r.get("dealerRate"))).append(',')
                    .append(qRate(r.get("retailRate"))).append(',')
                    .append(qAmt(r.get("changeCommission"))).append(',')
                    .append(q(changeSourceLabel(String.valueOf(r.get("source"))))).append(',')
                    .append(q(batch)).append(',')
                    .append(q(r.get("recordedAt"))).append(',')
                    .append(q(r.get("remark"))).append(',')
                    .append(q("PROCESSED".equals(String.valueOf(r.get("status"))) ? "已并入" : String.valueOf(r.get("status"))))
                    .append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // -------- 导出内部工具 --------

    private Map<String, Object> requireExportVersion(Long versionId) {
        if (versionId == null) throw new IllegalArgumentException("请指定核算版本");
        Map<String, Object> v = oneOrNull("SELECT * FROM commission_calculation_versions WHERE id=?", versionId);
        if (v == null) throw new IllegalArgumentException("核算版本不存在");
        return v;
    }

    /** 文件头元信息（口径快照），与核算版本页展示一致。 */
    private void appendCsvMeta(StringBuilder csv, Map<String, Object> v) {
        csv.append("版本号,").append(q(v.get("versionNo"))).append("\r\n");
        Object sd = v.get("startDate");
        Object ed = v.get("endDate");
        String range = sd == null ? String.valueOf(v.get("period")) : sd + " ~ " + ed;
        csv.append("核算期间,").append(q(range)).append("\r\n");
        String mode = freightModeLabel(String.valueOf(v.get("freightPeriodMode")));
        Object fs = v.get("freightStartDate");
        Object fe = v.get("freightEndDate");
        csv.append("运费扣减窗口,").append(q(fs == null ? mode : mode + "（" + fs + " ~ " + fe + "）")).append("\r\n");
        csv.append("结算类型,").append(q(settlementTypeLabel(String.valueOf(v.get("settlementType"))))).append("\r\n");
        csv.append("核算时间,").append(q(v.get("calculatedAt"))).append("\r\n");
        csv.append("核算人,").append(q(v.get("createdByName"))).append("\r\n");
        csv.append("发放状态,").append(q("PAID".equals(String.valueOf(v.get("paidFlag"))) ? "已发放" : "未发放")).append("\r\n");
        if (v.get("remark") != null && !String.valueOf(v.get("remark")).isBlank()) {
            csv.append("备注,").append(q(v.get("remark"))).append("\r\n");
        }
    }

    /** 解析来源行涉及的出库单「最近一次导入批次」文件线索（无则留空），避免逐行 N+1。 */
    private Map<Long, String> resolveOutboundImportBatches(List<Map<String, Object>> rows) {
        Set<Long> outboundIds = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Long id = parseId(r.get("outboundId"));
            if (id != null) outboundIds.add(id);
        }
        Map<Long, String> batchByOutbound = new HashMap<>();
        forEachChunk(outboundIds, chunk -> {
            List<Map<String, Object>> hits = jdbc.query(
                    "SELECT r.business_id, r.batch_id, b.file_name "
                            + "FROM h3_import_rows r JOIN h3_import_batches b ON b.id = r.batch_id "
                            + "WHERE r.business_id IN (" + inClause(chunk.size()) + ") AND r.batch_id IS NOT NULL "
                            + "ORDER BY r.id DESC",
                    this::row, chunk.toArray());
            for (Map<String, Object> hit : hits) {
                Long outboundId = parseId(hit.get("businessId"));
                if (outboundId != null && !batchByOutbound.containsKey(outboundId)) {
                    batchByOutbound.put(outboundId, "#" + hit.get("batchId") + " " + hit.get("fileName"));
                }
            }
        });
        return batchByOutbound;
    }

    private Long parseId(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String q(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String qAmt(Object value) {
        if (value == null) return q("0.00");
        return q(new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    private static String qRate(Object value) {
        if (value == null) return q("");
        return q(new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString());
    }

    /** 贡献溯源 6 位（来源行粒度，保留与 DB 一致精度；发放类 2 位列请用 qAmt）。 */
    private static String qAmt6(Object value) {
        if (value == null) return q("0");
        return q(new BigDecimal(String.valueOf(value)).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
    }

    private static String lineTypeLabel(String type) {
        if (type == null) return "";
        return switch (type) {
            case "RECEIPT" -> "回款";
            case "FREIGHT" -> "运杂费";
            case "ADJUSTMENT" -> "调整";
            default -> type;
        };
    }

    private static String channelTypeLabel(String type) {
        if (type == null) return "";
        return switch (type) {
            case "DEALER" -> "经销商";
            case "RETAIL" -> "终端";
            default -> type;
        };
    }

    private static String channelSourceLabel(String source) {
        if (source == null || source.isBlank()) return "";
        return switch (source) {
            case "DEALER_FIELD" -> "出库单填经销商";
            case "MARGIN_FALLBACK" -> "毛利率兜底";
            case "DEFAULT_RETAIL" -> "默认终端";
            default -> source;
        };
    }

    private static String sourceStatusLabel(String status) {
        if (status == null) return "";
        return switch (status) {
            case "ACTIVE" -> "已占用";
            case "RELEASED" -> "已释放";
            default -> status;
        };
    }

    private static String changeSourceLabel(String source) {
        if (source == null) return "";
        return switch (source) {
            case "IMPORT" -> "氚云导入";
            default -> source;
        };
    }

    private static String freightModeLabel(String mode) {
        if (mode == null) return "";
        return switch (mode) {
            case "PREVIOUS_EQUAL_PERIOD" -> "前一个等长周期";
            case "CUSTOM" -> "自定义窗口";
            default -> "结束日所在月上一完整自然月";
        };
    }

    private static String settlementTypeLabel(String type) {
        if (type == null) return "";
        return switch (type) {
            case "SUPPLEMENT" -> "补充核算";
            case "RECALCULATION" -> "作废后重新核算";
            default -> "正常核算";
        };
    }

    // ==================== 内部工具 ====================

    private List<Map<String, Object>> items(Map<String, Object> summary) {
        Object v = summary.get("items");
        return v instanceof List<?> list ? cast(list) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cast(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) out.add((Map<String, Object>) o);
        return out;
    }

    private void forEachChunk(Collection<Long> ids, java.util.function.Consumer<List<Long>> consumer) {
        List<Long> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            consumer.accept(list.subList(i, Math.min(list.size(), i + IN_CHUNK)));
        }
    }

    private String inClause(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    /** 核算日期范围校验：起止必填且 start <= end。 */
    private void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) throw new IllegalArgumentException("请选择核算日期范围");
        if (start.isAfter(end)) throw new IllegalArgumentException("核算开始日期不能晚于结束日期");
    }

    /**
     * 由某员工在版本内的来源行推导「多段比例」提示（G7，P2）：
     * 来源行已带发生日与 applied_rate，同渠道比例在后续行变化处切新段；
     * 段内记录双侧比例与行数。行序按版本查询的 occurrence_date 升序（接口查询保证），
     * 此处仍做一次稳定排序以防御性兜底。仅用于版本明细展示，不落库。
     */
    private List<Map<String, Object>> rateSegmentsFor(List<Map<String, Object>> lines, long employeeId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            Object emp = l.get("employeeId");
            if (!(emp instanceof Number en) || en.longValue() != employeeId) continue;
            Object dateObj = l.get("occurrenceDate");
            Object rateObj = l.get("appliedRate");
            Object channel = l.get("channelType");
            if (!(dateObj instanceof java.sql.Date d)) continue;
            if (!(rateObj instanceof Number rn)) continue;
            String ch = String.valueOf(channel);
            if (!"DEALER".equals(ch) && !"RETAIL".equals(ch)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toLocalDate());
            row.put("channel", ch);
            row.put("rate", new BigDecimal(rn.toString()));
            rows.add(row);
        }
        if (rows.isEmpty()) return new ArrayList<>();
        rows.sort((a, b) -> ((LocalDate) a.get("date")).compareTo((LocalDate) b.get("date")));
        List<Map<String, Object>> segments = new ArrayList<>();
        Map<String, Object> seg = null;
        BigDecimal dealerRate = null;
        BigDecimal retailRate = null;
        for (Map<String, Object> row : rows) {
            LocalDate date = (LocalDate) row.get("date");
            String channel = (String) row.get("channel");
            BigDecimal rate = (BigDecimal) row.get("rate");
            BigDecimal prior = "DEALER".equals(channel) ? dealerRate : retailRate;
            if (seg == null || (prior != null && prior.compareTo(rate) != 0)) {
                if (seg != null) seg.put("effectiveTo", date.minusDays(1));
                seg = new LinkedHashMap<>();
                seg.put("effectiveFrom", date);
                seg.put("dealerRate", dealerRate);
                seg.put("retailRate", retailRate);
                seg.put("sourceLineCount", 0);
                segments.add(seg);
            }
            if ("DEALER".equals(channel)) dealerRate = rate; else retailRate = rate;
            seg.put("dealerRate", dealerRate);
            seg.put("retailRate", retailRate);
            seg.put("effectiveTo", date);
            seg.put("sourceLineCount", ((Number) seg.get("sourceLineCount")).intValue() + 1);
        }
        return segments;
    }

    private BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private BigDecimal bd(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? ZERO : v;
    }

    private BigDecimal decimal(Map<String, Object> m, String key) {
        if (m == null) return ZERO;
        Object v = m.get(key);
        if (v == null) return ZERO;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(String.valueOf(v));
    }

    private BigDecimal bd(Map<String, Object> m, String key) {
        return decimal(m, key);
    }

    private Map<String, Object> oneOrNull(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, this::row, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> row(ResultSet rs, int n) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) m.put(camel(md.getColumnLabel(i)), rs.getObject(i));
        return m;
    }

    private String camel(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if (c == '_') upper = true;
            else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
