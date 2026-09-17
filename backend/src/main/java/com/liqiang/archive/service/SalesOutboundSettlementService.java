package com.liqiang.archive.service;

import com.liqiang.archive.dto.SettlementLedgerDtos.OutboundLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 氚云销售出库导入后的内部派生服务。
 * 原始事实只由氚云导入写入；本服务不再提供独立销售出库页面的查询或人工修复 API。
 */
@Service
@RequiredArgsConstructor
public class SalesOutboundSettlementService {
    private final JdbcTemplate jdbc;
    private final SettlementLedgerService ledger;

    private static final Map<String, String> OUTBOUND_STATUS_NORMALIZATION = Map.ofEntries(
            Map.entry("已生效", "CONFIRMED"), Map.entry("生效", "CONFIRMED"),
            Map.entry("已完结", "CONFIRMED"), Map.entry("完结", "CONFIRMED"),
            Map.entry("CONFIRMED", "CONFIRMED"), Map.entry("EFFECTIVE", "CONFIRMED"),
            Map.entry("COMPLETED", "CONFIRMED"),
            Map.entry("草稿", "DRAFT"), Map.entry("DRAFT", "DRAFT"),
            Map.entry("进行中", "PROCESSING"), Map.entry("生产中", "PROCESSING"),
            Map.entry("加工中", "PROCESSING"), Map.entry("制作中", "PROCESSING"),
            Map.entry("PROCESSING", "PROCESSING"), Map.entry("IN_PROGRESS", "PROCESSING"),
            Map.entry("已取消", "CANCELLED"), Map.entry("取消", "CANCELLED"),
            Map.entry("CANCELLED", "CANCELLED"), Map.entry("CANCELED", "CANCELLED"));

    public static String normalizeOutboundStatus(String raw) {
        if (raw == null || raw.isBlank()) return "CONFIRMED";
        String normalized = OUTBOUND_STATUS_NORMALIZATION.get(raw.trim());
        return normalized != null ? normalized : "CONFIRMED";
    }

    private static String settlementStatusOf(BigDecimal sales, BigDecimal received) {
        if (sales == null || sales.signum() <= 0) return "UNSETTLED";
        if (received != null && received.signum() > 0 && received.compareTo(sales) >= 0) return "SETTLED";
        if (received != null && received.signum() > 0) return "PARTIAL";
        return "UNSETTLED";
    }

    /** 导入或编排完成后，按氚云最终收款值刷新本地派生状态。 */
    public void refreshOutboundSnapshotById(Long outboundId) {
        OutboundLedger value = ledger.computeOutboundLedger(outboundId);
        if (value == null) return;
        BigDecimal sales = value.salesAmount();
        BigDecimal received = value.displayReceived();
        BigDecimal difference = value.snapshotReceived().compareTo(received) > 0
                ? value.snapshotReceived().subtract(received) : BigDecimal.ZERO;
        String rawStatus;
        try {
            rawStatus = jdbc.queryForObject("SELECT status FROM sales_outbounds WHERE id=?", String.class, outboundId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            rawStatus = null;
        }
        jdbc.update("""
            UPDATE sales_outbounds
               SET outbound_status=?, settlement_status=?, received_diff_amount=?,
                   received_source=?, received_verified=?, snapshot_received_amount=?
             WHERE id=?
            """, normalizeOutboundStatus(rawStatus),
                value.settlementStatus(),
                difference.abs().compareTo(new BigDecimal("0.01")) > 0 ? difference : null,
                value.receivedSource(), value.receivedVerified() ? 1 : 0, value.snapshotReceived(), outboundId);
    }

    public static String normalizeOutboundNo(String source) {
        if (source == null) return null;
        StringBuilder result = new StringBuilder();
        for (char value : source.trim().toCharArray()) {
            if (value >= '０' && value <= '９') result.append((char) (value - '０' + '0'));
            else if (value >= 'Ａ' && value <= 'Ｚ') result.append((char) (value - 'Ａ' + 'A'));
            else if (value >= 'ａ' && value <= 'ｚ') result.append((char) (value - 'ａ' + 'a'));
            else result.append(value);
        }
        String normalized = result.toString().replaceAll("[#＃\\s\\-]+", "");
        normalized = normalized.replaceFirst("^(单|号|NO\\.|NO)", "");
        return normalized.replaceFirst("(单|号)$", "");
    }
}
