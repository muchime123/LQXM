package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出库回款「单一权威」一致性守护（P1，见 docs/design/h3-received-single-authority-and-chain-consolidation-20260907.md）。
 *
 * <p>权威口径（2026-08-29 财务确认）：销售出库主表 {@code sales_outbounds.received_amount}
 * （氚云最终已收款）是回款计量的唯一权威；{@code sales_outbound_payment_snapshots} 明细按
 * {@code receipt_date} 承担月度归属。历史数据存在主表/明细不一致（V170 探查 29.9% 偏差），
 * 本服务在每次出库导入编排后维持恒等式：
 * <pre>
 *   received_amount ≡ SUM(快照明细.received_amount)   （仅 legacy_status='CONFIRMED'，与核算读取口径一致）
 * </pre>
 *
 * <p>实现（用户决策 D1：冲突时自动补/删差额行，主表永不被下行改写）：
 * <ol>
 *   <li>删除本批出库既有 AUTO_BALANCE 平衡行；</li>
 *   <li>diff = received_amount − SUM(CONFIRMED 明细)；|diff|&gt;0.01 时插入一条带符号 AUTO_BALANCE 行，
 *       明细合计因此恒等于主表，不修改任何原始行；</li>
 *   <li>按主表刷新 settlement_status 派生状态（派生状态批量单写点，取代历史 refreshOutboundSnapshotById 逐单刷写）。</li>
 * </ol>
 *
 * <p>AUTO_BALANCE 行归属日期（决策 D2 默认）：
 * {@code COALESCE(来源行最新收款日期, ship_date, business_date, source_created_at 日期, 导入日)}。
 * 平衡行以 {@code migration_batch='AUTO_BALANCE'} 打标（沿用快照表既有迁移批次标记列，
 * 无需新增 schema 列），导入中心/台账详情可见、可审计，由财务复核清单呈现（见
 * tools/reconcile_outbound_balance_20260907 审计脚本）。
 */
@Service
@RequiredArgsConstructor
public class OutboundBalanceService {

    private static final Logger log = LoggerFactory.getLogger(OutboundBalanceService.class);

    /** 平衡行迁移批次标记：与 LIVE_IMPORT / V171 / V193 并列的第三类来源。 */
    public static final String AUTO_BALANCE_BATCH = "AUTO_BALANCE";

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int CHUNK_SIZE = 400;

    private final JdbcTemplate jdbc;

    private record OutboundRow(
            Long id,
            String outboundNo,
            BigDecimal salesAmount,
            BigDecimal receivedAmount,
            LocalDate shipDate,
            LocalDate businessDate,
            LocalDate sourceCreatedDate,
            String settlementStatus
    ) {}

    private record SnapshotAgg(BigDecimal sumAmount, LocalDate maxReceiptDate, Integer maxIndex) {
        static SnapshotAgg empty() {
            return new SnapshotAgg(BigDecimal.ZERO, null, null);
        }
    }

    /** 编排步骤入口：批量平衡 + 派生状态刷新。返回统计供 StepOutcome.stats 展示。 */
    public Map<String, Object> reconcileAndRefresh(Collection<Long> outboundIds) {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<Long> ids = distinct(outboundIds);
        if (ids.isEmpty()) {
            stats.put("reason", "本批次没有受影响销售出库");
            return stats;
        }
        long t0 = System.currentTimeMillis();

        int removedAuto = deleteAutoLines(ids);
        int checked = 0;
        int derivedRefreshed = 0;
        int insertedAuto = 0;
        List<Long> balancedIds = new ArrayList<>();

        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            List<Long> part = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
            Map<Long, OutboundRow> rows = loadConfirmedOutbounds(part);
            if (rows.isEmpty()) continue;
            Map<Long, SnapshotAgg> aggs = loadSnapshotAggregates(part);
            for (OutboundRow row : rows.values()) {
                checked++;
                SnapshotAgg agg = aggs.getOrDefault(row.id, SnapshotAgg.empty());
                String derived = deriveSettlementStatus(row.salesAmount(), row.receivedAmount());
                if (!derived.equals(row.settlementStatus())) {
                    jdbc.update("UPDATE sales_outbounds SET settlement_status=? WHERE id=?",
                            derived, row.id());
                    derivedRefreshed++;
                }
                BigDecimal diff = nz(row.receivedAmount()).subtract(nz(agg.sumAmount()));
                if (diff.abs().compareTo(TOLERANCE) > 0) {
                    insertAutoLine(row, agg, diff);
                    insertedAuto++;
                    balancedIds.add(row.id());
                }
            }
        }

        stats.put("candidateOutbounds", ids.size());
        stats.put("checked", checked);
        stats.put("removedAutoLines", removedAuto);
        stats.put("insertedAutoLines", insertedAuto);
        stats.put("derivedStateRefreshed", derivedRefreshed);
        stats.put("balancedSampleIds", balancedIds.size() <= 20 ? balancedIds : balancedIds.subList(0, 20));
        log.info("出库回款平衡守护：候选 {}，检查 {}，清除旧平衡行 {}，新建平衡行 {}，派生状态刷新 {}，耗时 {}ms",
                ids.size(), checked, removedAuto, insertedAuto, derivedRefreshed,
                System.currentTimeMillis() - t0);
        return stats;
    }

    // ======================== 步骤一：清除既有平衡行 ========================

    private int deleteAutoLines(List<Long> ids) {
        int removed = 0;
        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            List<Long> part = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
            List<Object> params = new ArrayList<>();
            params.add(AUTO_BALANCE_BATCH);
            params.addAll(part);
            removed += jdbc.update("DELETE FROM sales_outbound_payment_snapshots "
                    + "WHERE migration_batch=? AND sales_outbound_id IN (" + placeholders(part.size()) + ")",
                    params.toArray());
        }
        return removed;
    }

    // ======================== 步骤二：读取主表权威值 / 快照聚合 ========================

    /** 仅处理 CONFIRMED 出库（与月度/正式核算读取口径一致）；非 CONFIRMED 出库不参与平衡。 */
    private Map<Long, OutboundRow> loadConfirmedOutbounds(List<Long> ids) {
        Map<Long, OutboundRow> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT o.id, o.outbound_no, o.sales_amount, o.received_amount,
                       o.ship_date, o.business_date, o.source_created_at,
                       COALESCE(o.settlement_status, 'UNSETTLED') AS settlement_status
                  FROM sales_outbounds o
                 WHERE o.outbound_status='CONFIRMED' AND o.id IN ("""
                + placeholders(ids.size()) + ")", rs -> {
            Long id = rs.getLong("id");
            result.put(id, new OutboundRow(
                    id,
                    rs.getString("outbound_no"),
                    rs.getBigDecimal("sales_amount"),
                    rs.getBigDecimal("received_amount"),
                    toLocalDate(rs.getDate("ship_date")),
                    toLocalDate(rs.getDate("business_date")),
                    rs.getTimestamp("source_created_at") == null ? null
                            : rs.getTimestamp("source_created_at").toLocalDateTime().toLocalDate(),
                    rs.getString("settlement_status")));
        }, ids.toArray());
        return result;
    }

    /**
     * AUTO_BALANCE 行已先删除，此处聚合纯来源行（金额/日期只计 CONFIRMED，与核算读取口径一致）。
     * 注意：installment_index 取该单【全部行】的最大值，避免与同单 PENDING 行的更大 index 冲突。
     */
    private Map<Long, SnapshotAgg> loadSnapshotAggregates(List<Long> ids) {
        Map<Long, SnapshotAgg> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ps.sales_outbound_id,
                       COALESCE(SUM(CASE WHEN ps.legacy_status='CONFIRMED' THEN ps.received_amount END), 0) AS sum_amount,
                       MAX(CASE WHEN ps.legacy_status='CONFIRMED' THEN ps.receipt_date END) AS max_receipt_date,
                       MAX(ps.installment_index) AS max_index
                  FROM sales_outbound_payment_snapshots ps
                 WHERE ps.sales_outbound_id IN ("""
                + placeholders(ids.size()) + ") GROUP BY ps.sales_outbound_id", rs -> {
            int rawIndex = rs.getInt("max_index");
            result.put(rs.getLong("sales_outbound_id"), new SnapshotAgg(
                    rs.getBigDecimal("sum_amount"),
                    toLocalDate(rs.getDate("max_receipt_date")),
                    rs.wasNull() ? null : rawIndex));
        }, ids.toArray());
        return result;
    }

    // ======================== 步骤三：写入平衡行 / 派生状态 ========================

    private void insertAutoLine(OutboundRow row, SnapshotAgg agg, BigDecimal diff) {
        int nextIndex = (agg.maxIndex() == null ? 0 : agg.maxIndex()) + 1;
        LocalDate receiptDate = chooseReceiptDate(agg.maxReceiptDate(),
                row.shipDate(), row.businessDate(), row.sourceCreatedDate());
        String externalId = "AUTO:" + row.id();
        String receiptNo = row.outboundNo() + "#BAL";
        String remark = diff.signum() >= 0
                ? "主表自动平衡差额（明细不足补差）" : "主表自动平衡差额（明细超额冲销）";
        jdbc.update("""
                INSERT INTO sales_outbound_payment_snapshots(
                    sales_outbound_id, installment_index, legacy_receipt_id, legacy_external_source,
                    legacy_external_id, legacy_receipt_no, legacy_status, receipt_date, received_amount,
                    payment_method, source_created_by, source_created_at, remark, migration_batch)
                VALUES(?,?,NULL,'AUTO_BALANCE',?,?,'CONFIRMED',?,?,NULL,NULL,NOW(),?,'AUTO_BALANCE')
                ON DUPLICATE KEY UPDATE
                    legacy_external_id=VALUES(legacy_external_id),
                    legacy_receipt_no=VALUES(legacy_receipt_no),
                    legacy_status=VALUES(legacy_status),
                    receipt_date=VALUES(receipt_date),
                    received_amount=VALUES(received_amount),
                    remark=VALUES(remark),
                    migration_batch='AUTO_BALANCE'
                """, row.id(), nextIndex, externalId, receiptNo, Date.valueOf(receiptDate), diff, remark);
    }

    /** 派生结算状态（与 SalesOutboundSettlementService.settlementStatusOf 保持同一定义，P3 收敛为单内核）。 */
    private static String deriveSettlementStatus(BigDecimal sales, BigDecimal received) {
        BigDecimal s = nz(sales);
        BigDecimal r = nz(received);
        if (s.signum() <= 0) return "UNSETTLED";
        if (r.signum() > 0 && r.compareTo(s) >= 0) return "SETTLED";
        if (r.signum() > 0) return "PARTIAL";
        return "UNSETTLED";
    }

    // ======================== 工具 ========================

    private static List<Long> distinct(Collection<Long> outboundIds) {
        Set<Long> set = new LinkedHashSet<>();
        if (outboundIds != null) {
            for (Long id : outboundIds) {
                if (id != null) set.add(id);
            }
        }
        return new ArrayList<>(set);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static LocalDate chooseReceiptDate(LocalDate maxSourceDate, LocalDate shipDate,
                                               LocalDate businessDate, LocalDate sourceCreatedDate) {
        if (maxSourceDate != null) return maxSourceDate;
        if (shipDate != null) return shipDate;
        if (businessDate != null) return businessDate;
        if (sourceCreatedDate != null) return sourceCreatedDate;
        return LocalDate.now();
    }

    private static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
