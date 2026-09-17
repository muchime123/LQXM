package com.liqiang.archive.service;

import com.liqiang.archive.dto.SettlementLedgerDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 六层架构 · 第 4 层（财务结算层）的统一入口。
 *
 * <h3>回款口径（2026-08-29 业务模型修正，以财务确认为准）：</h3>
 *
 * <p>氚云销售出库主表的最终金额是回款计量的<b>唯一权威事实</b>：
 * <pre>
 *   outbound_received(outboundId) = sales_outbounds.received_amount
 * </pre>
 *
 * <p>氚云内部的收款记录与多订单结清流程不导入、不维护、不展示。
 *
 * <p>禁止在 Service / Controller / RowMapper 里把 receipts 的申请金额当作回款直接相加。
 */
@Service
@RequiredArgsConstructor
public class SettlementLedgerService {

    private final JdbcTemplate jdbc;

    // ================================================================================
    //  白名单谓词（SQL 片段常量，全项目唯一入口）—— 每一条都有注释说明为什么存在
    // ================================================================================

    /**
     * 正式事实层「银行到账单」白名单 WHERE 片段（receipts 表上用）。
     * 组成：
     *   · legacy_migrated_tag IS NULL  ← V171 打标：排除 OUTBOUND_EMBEDDED 已迁出快照
     *   · receipt_type <> MULTI_OUTBOUND ← 多订单结清是累计结清快照，不是新增银行到账
     */
    public static final String FACT_RECEIPT_PREDICATE =
            "r.legacy_migrated_tag IS NULL AND COALESCE(r.receipt_type, '') <> 'MULTI_OUTBOUND' ";

    /**
     * 正式事实层「核销分配」白名单 WHERE 片段（receipt_allocations 表上用，需 JOIN receipts r）。
     * 组成：
     *   · allocation_origin = 'DIRECT'    ← 只接受正式收款核销，排除出库内嵌与多订单结清快照
     *   · 隐含依赖 receipts 端的 legacy_migrated_tag IS NULL 做双重保险
     */
    public static final String FACT_ALLOCATION_PREDICATE =
            "ra.allocation_origin = 'DIRECT' AND r.legacy_migrated_tag IS NULL " +
            "AND COALESCE(r.receipt_type, '') <> 'MULTI_OUTBOUND' ";

    // ================================================================================
    //  公式 A：出库级总账（最常用，refreshOutboundSnapshot 的核心数据来源）
    // ================================================================================

    /**
     * 为单张出库计算"财务口径正式回款 + 展示口径 display_received"。
     *
     * <p>结果可直接写回 sales_outbounds.received_amount / payment_status / settlement_status。
     * 若出库不存在（孤儿 allocation 场景），返回 null，调用方需跳过刷新。
     */
    public OutboundLedger computeOutboundLedger(Long outboundId) {
        List<OutboundLedger> list = jdbc.query("""
                SELECT s.id,
                       COALESCE(s.sales_amount, 0),
                       COALESCE(s.received_amount, 0) AS fact_received,
                       COALESCE(s.received_amount, 0) AS embedded_registered,
                       COALESCE(s.source_received_amount, 0),
                      COALESCE(s.status, '') AS status
                 FROM sales_outbounds s
                WHERE s.id = ?
                """, (rs, n) -> buildOutboundLedger(
                        rs.getLong(1),
                        rs.getBigDecimal(2),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getString("status") == null ? "" : rs.getString("status")
                ), outboundId);
        return list.isEmpty() ? null : list.getFirst();
    }

    /** 批量版：一次刷新一批出库（refreshAllOutboundSnapshots 场景）。 */
    public List<OutboundLedger> computeOutboundLedgers(List<Long> outboundIds) {
        if (outboundIds == null || outboundIds.isEmpty()) return List.of();
        String placeholders = String.join(",", outboundIds.stream().map(x -> "?").toArray(String[]::new));
        String sql =
                "SELECT s.id,\n" +
                "       COALESCE(s.sales_amount, 0),\n" +
                "       COALESCE(s.received_amount, 0),\n" +
                "       COALESCE(s.received_amount, 0),\n" +
                "       COALESCE(s.source_received_amount, 0),\n" +
                "       COALESCE(s.status, '')\n" +
                "  FROM sales_outbounds s\n" +
                " WHERE s.id IN (" + placeholders + ")";
        return jdbc.query(sql, (rs, n) -> buildOutboundLedger(
                rs.getLong(1),
                rs.getBigDecimal(2),
                rs.getBigDecimal(3),
                rs.getBigDecimal(4),
                rs.getBigDecimal(5),
                rs.getString(6)
        ), outboundIds.toArray());
    }

    private OutboundLedger buildOutboundLedger(Long id, BigDecimal sales, BigDecimal factRecv,
                                                BigDecimal embeddedReg, BigDecimal srcRecv, String rawStatus) {
        BigDecimal _0 = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal f = nullToZero(factRecv);  // 权威回款：氚云销售出库最终已收款金额
        BigDecimal e = nullToZero(embeddedReg);
        BigDecimal s = nullToZero(srcRecv);

        // 只消费氚云销售出库最终结果，不重建氚云内部收款流程。
        BigDecimal display = f;
        String receivedSource = f.signum() != 0 ? "SNAPSHOT" : "NONE";
        boolean receivedVerified = true;
        // 保留带符号的源金额，不把退货、抵扣和超额收款截断。
        BigDecimal outstanding = (sales == null ? _0 : sales).subtract(display);
        String ps = outstanding.signum() <= 0 && display.signum() > 0 ? "已结清"
                : display.signum() > 0 ? "部分回款" : "未回款";
        String ss = settlementStatusOf(sales, display);
        // 快照即权威，对账口径与 received 一致（无差异）
        BigDecimal snapshot = display;
        return new OutboundLedger(id, sales == null ? _0 : sales, f, e, s, display, outstanding, ps, ss,
                receivedSource, receivedVerified, snapshot);
    }

    // ================================================================================
    //  公式 B：客户级未分配到账池
    // ================================================================================

    /** 返回「银行钱已到账但尚未核销到具体出库」的全部未分配条目列表（ZERO_LINKED）。 */
    public List<UnallocatedPoolItem> listUnallocatedPool(int limit) {
        return jdbc.query("""
                SELECT r.id, r.receipt_no, r.receipt_type,
                       r.customer_name, r.salesperson_name, r.receipt_date,
                       r.receipt_amount,
                       COALESCE(SUM(ra.allocated_amount), 0),
                       r.receipt_amount - COALESCE(SUM(ra.allocated_amount), 0)
                  FROM receipts r
                  LEFT JOIN receipt_allocations ra
                         ON ra.receipt_id = r.id
                        AND ra.allocation_status = 'CONFIRMED'
                 """ + " WHERE " + FACT_RECEIPT_PREDICATE + """
                 GROUP BY r.id, r.receipt_no, r.receipt_type,
                          r.customer_name, r.salesperson_name, r.receipt_date,
                          r.receipt_amount
                HAVING r.receipt_amount - COALESCE(SUM(ra.allocated_amount), 0) > 0.005
                 ORDER BY r.receipt_date DESC, r.id DESC
                 LIMIT ?
                """, (rs, n) -> new UnallocatedPoolItem(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5),
                        rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(),
                        rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9)
                ), limit);
    }

    // ================================================================================
    //  公式 C：全局对账差异清单
    // ================================================================================

    /** 返回 receipt_amount 与 SUM(CONFIRMED allocated_amount) 对不齐的条目。 */
    public List<AlignmentIssue> listAlignmentIssues(int limit) {
        return jdbc.query("""
                SELECT r.id, r.receipt_no, r.receipt_type, r.customer_name,
                       r.receipt_amount,
                       COALESCE(SUM(CASE WHEN ra.allocation_status='CONFIRMED'
                                         THEN ra.allocated_amount END), 0) AS alloc_sum,
                       r.receipt_amount - COALESCE(SUM(CASE WHEN ra.allocation_status='CONFIRMED'
                                                            THEN ra.allocated_amount END), 0) AS diff
                  FROM receipts r
                  LEFT JOIN receipt_allocations ra ON ra.receipt_id = r.id
                 """ + " WHERE " + FACT_RECEIPT_PREDICATE + """
                 GROUP BY r.id, r.receipt_no, r.receipt_type, r.customer_name, r.receipt_amount
                HAVING ABS(r.receipt_amount - COALESCE(SUM(CASE WHEN ra.allocation_status='CONFIRMED'
                                                                 THEN ra.allocated_amount END), 0)) >= 0.01
                 ORDER BY ABS(diff) DESC
                 LIMIT ?
                """, (rs, n) -> {
            BigDecimal diff = rs.getBigDecimal(7);
            String sev = diff != null && diff.abs().compareTo(BigDecimal.ONE) < 0
                    ? "WITHIN_1YUAN" : "MISMATCH";
            return new AlignmentIssue(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), diff, sev);
        }, limit);
    }

    // ================================================================================
    //  公式 D：全局汇总（Dashboard / 财务看板顶部卡片数据）
    // ================================================================================

    public SettlementDigest digest() {
        BigDecimal fact = oneBig("""
                SELECT COALESCE(SUM(r.receipt_amount), 0)
                  FROM receipts r """ + " WHERE " + FACT_RECEIPT_PREDICATE);
        int factCnt = oneInt("SELECT COUNT(*) FROM receipts r WHERE " + FACT_RECEIPT_PREDICATE);

        Integer zeroCnt = oneInt("""
                SELECT COUNT(*) FROM (
                    SELECT r.id
                      FROM receipts r
                      LEFT JOIN receipt_allocations ra
                             ON ra.receipt_id = r.id
                            AND ra.allocation_status = 'CONFIRMED'
                     """ + " WHERE " + FACT_RECEIPT_PREDICATE + """
                     GROUP BY r.id
                    HAVING COUNT(ra.id) = 0
                ) z""");
        BigDecimal zeroAmt = oneBig("""
                SELECT COALESCE(SUM(z.receipt_amount), 0) FROM (
                    SELECT r.receipt_amount
                      FROM receipts r
                      LEFT JOIN receipt_allocations ra
                             ON ra.receipt_id = r.id
                            AND ra.allocation_status = 'CONFIRMED'
                     """ + " WHERE " + FACT_RECEIPT_PREDICATE + """
                     GROUP BY r.id, r.receipt_amount
                    HAVING COUNT(ra.id) = 0
                ) z""");

        BigDecimal dispatched = oneBig("""
                SELECT COALESCE(SUM(ra.allocated_amount), 0)
                  FROM receipt_allocations ra
                  JOIN receipts r ON r.id = ra.receipt_id
                 WHERE ra.allocation_status = 'CONFIRMED'
                 """ + " AND " + FACT_ALLOCATION_PREDICATE);

        Integer issueCnt = oneInt("""
                SELECT COUNT(*) FROM (
                    SELECT r.id
                      FROM receipts r
                      LEFT JOIN receipt_allocations ra ON ra.receipt_id = r.id
                     """ + " WHERE " + FACT_RECEIPT_PREDICATE + """
                     GROUP BY r.id, r.receipt_amount
                    HAVING ABS(r.receipt_amount
                             - COALESCE(SUM(CASE WHEN ra.allocation_status='CONFIRMED'
                                                THEN ra.allocated_amount END), 0)) >= 0.01
                ) i""");
        BigDecimal issueAmt = oneBig("""
                SELECT COALESCE(SUM(ABS(i.diff)), 0) FROM (
                    SELECT r.receipt_amount
                         - COALESCE(SUM(CASE WHEN ra.allocation_status='CONFIRMED'
                                             THEN ra.allocated_amount END), 0) AS diff
                      FROM receipts r
                      LEFT JOIN receipt_allocations ra ON ra.receipt_id = r.id
                     """ + " WHERE " + FACT_RECEIPT_PREDICATE + """
                     GROUP BY r.id, r.receipt_amount
                    HAVING ABS(r.receipt_amount
                             - COALESCE(SUM(CASE WHEN ra.allocation_status='CONFIRMED'
                                                 THEN ra.allocated_amount END), 0)) >= 0.01
                ) i""");

        int exactCnt = factCnt - issueCnt;
        return new SettlementDigest(
                factCnt, wan(fact),
                factCnt - (zeroCnt == null ? 0 : zeroCnt),
                zeroCnt == null ? 0 : zeroCnt,
                wan(zeroAmt),
                wan(dispatched),
                wan(zeroAmt),   // 未分配池 = ZERO_LINKED 金额
                exactCnt,
                issueCnt == null ? 0 : issueCnt,
                wan(issueAmt),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
    }

    // ================================================================================
    //  辅助方法
    // ================================================================================

    public static String settlementStatusOf(BigDecimal salesAmount, BigDecimal receivedAmount) {
        if (salesAmount == null || salesAmount.signum() <= 0) return "UNSETTLED";
        if (receivedAmount == null || receivedAmount.signum() == 0) return "UNSETTLED";
        int cmp = receivedAmount.compareTo(salesAmount);
        if (cmp >= 0) return "SETTLED";
        // 允许 0.01% 尾差（四舍五入导致 1 分内差额按 SETTLED 计）
        BigDecimal threshold = salesAmount.multiply(new BigDecimal("0.9999"));
        if (receivedAmount.compareTo(threshold) >= 0) return "SETTLED";
        return "PARTIAL";
    }

    private BigDecimal oneBig(String sql, Object... args) {
        BigDecimal r = jdbc.queryForObject(sql, BigDecimal.class, args);
        return r == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : r;
    }
    private int oneInt(String sql, Object... args) {
        Integer r = jdbc.queryForObject(sql, Integer.class, args);
        return r == null ? 0 : r;
    }
    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : v.setScale(2, RoundingMode.HALF_UP);
    }
    private static BigDecimal wan(BigDecimal v) {
        return nullToZero(v).divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP);
    }
}
