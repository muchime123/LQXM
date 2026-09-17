package com.liqiang.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算总账（Settlement Ledger）统一 DTO。
 * <p>
 * <b>权威回款唯一公式（V204，2026-08-29 财务确认）：</b>
 * <pre>
 *   outbound_received(outboundId) =
 *       SUM(sales_outbound_payment_snapshots.received_amount)
 *       WHERE legacy_status = 'CONFIRMED'            -- 出库「收款详细」子表
 *   received_verified = true                         -- 子表即财务审核结果
 *   received_source   = 'SNAPSHOT'
 * </pre>
 * 收款记录（SINGLE_OUTBOUND）/ 多订单结清（MULTI_OUTBOUND）仅是审核前的流程申请单，
 * 不直接参与回款计量，仅用于流程追踪与「申请 vs 结果」对账。
 */
public final class SettlementLedgerDtos {
    private SettlementLedgerDtos() {}

    /** 出库级结算总账条目。 */
    public record OutboundLedger(
            Long outboundId,
            BigDecimal salesAmount,
            BigDecimal factReceived,        // 权威回款：出库收款子表 CONFIRMED 合计
            BigDecimal embeddedRegistered,  // 同源子表登记合计（与 factReceived 一致，保留列位）
            BigDecimal sourceReceived,      // 氚云出库主表 source_received_amount（追溯展示）
            BigDecimal displayReceived,     // 展示口径最终值（= min(factReceived, salesAmount)）
            BigDecimal displayOutstanding,  // sales - displayReceived
            String   paymentStatus,         // 未回款 / 部分回款 / 已结清
            String   settlementStatus,      // UNSETTLED / PARTIAL / SETTLED
            String   receivedSource,        // SNAPSHOT / NONE
            boolean  receivedVerified,     // true（子表即财务审核结果，恒真）
            BigDecimal snapshotReceived     // 快照回款金额（= displayReceived，仅用于对账，恒一致）
    ) {}

    /** 客户级未分配到账池条目（钱到了还没核销到具体出库）。 */
    public record UnallocatedPoolItem(
            Long receiptId,
            String receiptNo,
            String receiptType,
            String customerName,
            String salespersonName,
            java.time.LocalDate receiptDate,
            BigDecimal receiptAmount,
            BigDecimal allocatedAmount,     // 通常 = 0
            BigDecimal unallocatedAmount    // 未分配余额（= receiptAmount - IFNULL(allocatedAmount,0)）
    ) {}

    /** 全局对账差异清单项（receipt_amount ≠ SUM(allocated_amount)）。 */
    public record AlignmentIssue(
            Long receiptId,
            String receiptNo,
            String receiptType,
            String customerName,
            BigDecimal receiptAmount,
            BigDecimal allocSum,
            BigDecimal diff,                // receipt - alloc
            String severity                 // WITHIN_1YUAN / MISMATCH
    ) {}

    /** 全局结算汇总（页面顶部 5 个卡片）。 */
    public record SettlementDigest(
            int factReceiptCount,           // 白名单到账单条数
            BigDecimal factReceiptAmountWan,
            int outboundLinkedCnt,
            int zeroLinkedCnt,              // ZERO_LINKED（未分配池）
            BigDecimal zeroLinkedAmountWan,
            BigDecimal factDispatchedWan,   // 已分配到具体出库的金额（万元）
            BigDecimal unallocatedPoolWan,  // 未分配池金额合计
            int alignmentExactCnt,
            int alignmentIssueCnt,
            BigDecimal alignmentIssueWan,
            String generatedAt
    ) {}

    /** 某业务单聚合回款（传给 businessDetail / businessTimeline 的共享结构）。 */
    public record BusinessLedger(
            Long businessOrderId,
            BigDecimal factReceived,        // 该业务单下所有出库的白名单分配合计
            BigDecimal displayReceived,     // 展示口径合计（含内嵌登记）
            int linkedReceiptCount,         // 关联到账单数
            List<LinkedReceiptRow> receipts
    ) {}

    public record LinkedReceiptRow(
            Long receiptId,
            String receiptNo,
            String receiptType,
            BigDecimal allocatedAmount,
            String allocationStatus,
            String outboundNoSnapshot,
            java.time.LocalDate receiptDate,
            boolean factReceipt             // true=正式回款白名单；false=已迁出快照，仅展示
    ) {}
}
