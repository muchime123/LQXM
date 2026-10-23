package com.liqiang.archive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** 手填日记账批次差异预览：只列证据，不自动认定跨批次的同一笔。 */
@Service
@RequiredArgsConstructor
public class CashflowJournalReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Map<String, Object> pendingBatches() {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT id,file_name,status,total_count,created_at
                  FROM h3_import_batches
                 WHERE data_type='CASHFLOW_JOURNAL' AND status='PREVIEW_READY'
                 ORDER BY id DESC LIMIT 20
                """, (rs, ignored) -> Map.of(
                "id", rs.getLong("id"), "fileName", rs.getString("file_name"),
                "status", rs.getString("status"), "totalCount", rs.getInt("total_count"),
                "createdAt", rs.getTimestamp("created_at").toLocalDateTime()));
        return Map.of("items", items);
    }

    public Map<String, Object> diff(long batchId, String account, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        List<String> batches = jdbc.query("""
                SELECT status FROM h3_import_batches WHERE id=? AND data_type='CASHFLOW_JOURNAL'
                """, (rs, ignored) -> rs.getString(1), batchId);
        if (batches.isEmpty()) throw new NoSuchElementException("流水日记账批次不存在");
        List<ReviewRow> incoming = jdbc.query("""
                SELECT external_id,main_json
                  FROM h3_import_staging_rows WHERE batch_id=? ORDER BY row_seq
                """, (rs, ignored) -> incomingRow(rs.getString("external_id"), rs.getString("main_json")), batchId);
        if (incoming.isEmpty()) return Map.of("batchId", batchId, "status", batches.getFirst(),
                "items", List.of(), "total", 0, "oldOnlyCount", 0, "newOnlyCount", 0,
                "accounts", List.of(), "stagingAvailable", false);

        List<ReviewRow> old = jdbc.query("""
                SELECT external_id,account_name,entry_date,summary,income,expense,balance
                  FROM h3_cashflow_entries WHERE external_source='H3YUN'
                """, (rs, ignored) -> new ReviewRow(rs.getString("external_id"), rs.getString("account_name"),
                null, rs.getDate("entry_date") == null ? null : rs.getDate("entry_date").toString(),
                rs.getString("summary"), rs.getBigDecimal("income"), rs.getBigDecimal("expense"),
                rs.getBigDecimal("balance")));
        Comparison comparison = compare(incoming, old, account);
        List<Map<String, Object>> changed = comparison.items();
        int from = (int) Math.min(changed.size(), (long) (page - 1) * size);
        int to = Math.min(changed.size(), from + size);
        return Map.of("batchId", batchId, "status", batches.getFirst(), "items", changed.subList(from, to),
                "total", changed.size(), "oldOnlyCount", comparison.oldOnlyCount(),
                "newOnlyCount", comparison.newOnlyCount(), "accounts", comparison.accounts(),
                "stagingAvailable", true);
    }

    static Comparison compare(List<ReviewRow> incoming, List<ReviewRow> old, String account) {
        Map<String, Set<String>> incomingKeys = new HashMap<>();
        for (ReviewRow row : incoming) {
            incomingKeys.computeIfAbsent(row.account(), ignored -> new HashSet<>()).add(row.key());
        }
        Map<String, Set<String>> oldKeys = new HashMap<>();
        for (ReviewRow row : old) oldKeys.computeIfAbsent(row.account(), ignored -> new HashSet<>()).add(row.key());

        List<Map<String, Object>> changed = new ArrayList<>();
        Map<String, Integer> affected = new LinkedHashMap<>();
        int oldOnly = 0;
        int newOnly = 0;
        for (ReviewRow row : old) {
            Set<String> current = incomingKeys.get(row.account());
            if (current != null && !current.contains(row.key())) {
                affected.merge(row.account(), 1, Integer::sum);
                if (matches(account, row.account())) {
                    changed.add(view("OLD_ONLY", row));
                    oldOnly++;
                }
            }
        }
        for (ReviewRow row : incoming) {
            if (row.key().endsWith("#期初")) continue;
            if (!oldKeys.getOrDefault(row.account(), Set.of()).contains(row.key())) {
                affected.merge(row.account(), 1, Integer::sum);
                if (matches(account, row.account())) {
                    changed.add(view("NEW_ONLY", row));
                    newOnly++;
                }
            }
        }
        changed.sort(Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("accountName")))
                .thenComparing(row -> String.valueOf(row.get("entryDate")))
                .thenComparing(row -> String.valueOf(row.get("sourceRowNumber"))));
        return new Comparison(changed, oldOnly, newOnly, affected.keySet().stream().sorted().toList());
    }

    private ReviewRow incomingRow(String key, String json) {
        try {
            Map<String, String> main = mapper.readValue(json, new TypeReference<>() {});
            return new ReviewRow(key, main.getOrDefault("账户", ""), main.get("__sourceRowNumber"),
                    main.get("日期"), main.get("摘要"), main.get("收入"), main.get("支出"), main.get("结存"));
        } catch (IOException e) {
            throw new IllegalStateException("流水日记账暂存快照无法读取", e);
        }
    }

    private static boolean matches(String requested, String actual) {
        return requested == null || requested.isBlank() || requested.equals(actual);
    }

    private static Map<String, Object> view(String kind, ReviewRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("key", row.key());
        result.put("accountName", row.account());
        result.put("sourceRowNumber", row.sourceRowNumber());
        result.put("entryDate", row.entryDate());
        result.put("summary", row.summary());
        result.put("income", row.income());
        result.put("expense", row.expense());
        result.put("balance", row.balance());
        return result;
    }

    record ReviewRow(String key, String account, String sourceRowNumber, String entryDate,
                     String summary, Object income, Object expense, Object balance) {}

    record Comparison(List<Map<String, Object>> items, int oldOnlyCount, int newOnlyCount,
                      List<String> accounts) {}

    // ==================== 来源结存核对（只读派生，P1） ====================

    /**
     * 单个账户在期间内的来源结存核对。
     *
     * <p>行序取「账户内 entry_date, id」——这是**展示顺序**，不是交易发生顺序的断言。
     * 核对只回答「相邻两行的来源结存能不能接上」，不做任何重新排序以求连续。
     */
    public CashflowReviewRules.SourceBalanceReview sourceBalanceReview(Long accountId, LocalDate start, LocalDate end) {
        List<CashflowReviewRules.SourceBalanceRow> rows = jdbc.query("""
                SELECT e.id,e.entry_date,e.income,e.expense,e.balance
                  FROM h3_cashflow_entries e JOIN h3_cash_accounts a ON a.id=?
                 WHERE e.external_source='H3YUN' AND e.account_name=a.account_name
                   AND e.entry_date BETWEEN ? AND ?
                 ORDER BY e.entry_date,e.id
                """, (rs, ignored) -> {
            java.sql.Date date = rs.getDate("entry_date");
            return new CashflowReviewRules.SourceBalanceRow(rs.getLong("id"),
                    date == null ? null : date.toLocalDate(), rs.getBigDecimal("income"),
                    rs.getBigDecimal("expense"), rs.getBigDecimal("balance"));
        }, accountId, start, end);
        return CashflowReviewRules.reviewSourceBalance(rows, null);
    }

    /**
     * 单账户在期间内的来源末行证据（P1 辅助对拍专用，只读）。
     *
     * <p>⚠️ 这里<b>不再</b>用「逐笔链零断点」当可靠性判据。逐笔链仍然计算（它回答的是
     * 「展示顺序下行与行之间能不能接上」），但末行能不能代表期末<b>只由</b>
     * {@link CashflowOrderEvidence#resolveClosingEvidence} 的「逐日路径唯一」决定：
     * 顺序没有被数据唯一确定时，一律记「缺少可靠来源顺序」，不宣称可比。
     *
     * <p>行序仍按 {@code entry_date, id} 取，仅为给 {@link CashflowReviewRules#reviewSourceBalance}
     * 一个确定性展示顺序；该顺序<b>不</b>被当作交易顺序。
     */
    public CashflowSourceEvidence sourceClosingEvidence(Long accountId, LocalDate start, LocalDate end) {
        List<CashflowReviewRules.SourceBalanceRow> rows = jdbc.query("""
                SELECT e.id,e.entry_date,e.income,e.expense,e.balance
                  FROM h3_cashflow_entries e JOIN h3_cash_accounts a ON a.id=?
                 WHERE e.external_source='H3YUN' AND e.account_name=a.account_name
                   AND e.entry_date BETWEEN ? AND ?
                 ORDER BY e.entry_date,e.id
                """, (rs, ignored) -> {
            java.sql.Date date = rs.getDate("entry_date");
            return new CashflowReviewRules.SourceBalanceRow(rs.getLong("id"),
                    date == null ? null : date.toLocalDate(), rs.getBigDecimal("income"),
                    rs.getBigDecimal("expense"), rs.getBigDecimal("balance"));
        }, accountId, start, end);
        CashflowReviewRules.SourceBalanceReview display = CashflowReviewRules.reviewSourceBalance(rows, null);
        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        return new CashflowSourceEvidence(evidence, display.checkedLinks(), display.exceptionCount());
    }

    /**
     * 来源末行证据 + 该账户在「展示顺序」下的逐笔链统计。
     *
     * @param evidence      末行是否可作为期间期末的证据
     * @param checkedLinks  展示顺序下参与核对的行间链接数（只作明细提示，不作期末断言）
     * @param exceptionCount 展示顺序下的断点数量
     */
    public record CashflowSourceEvidence(CashflowOrderEvidence.Evidence evidence,
                                         int checkedLinks, int exceptionCount) {}

    /**
     * 期间内**全部**有流水的账户的核对结果（含 0 断点的账户）。
     *
     * <p>含干净账户是刻意的：账户行需要显示自己真实的「已衔接链接数」，汇总卡片也需要按筛选后的账户集求和。
     * 判断「是否有异常」一律用 {@link CashflowReviewRules.SourceBalanceReview#hasExceptions()}，不要用 map 是否命中。
     */
    public Map<String, CashflowReviewRules.SourceBalanceReview> sourceBalanceReviewsAll(LocalDate start, LocalDate end) {
        List<String> accounts = new ArrayList<>();
        List<CashflowReviewRules.SourceBalanceRow> rows = jdbc.query("""
                SELECT account_name,id,entry_date,income,expense,balance
                  FROM h3_cashflow_entries
                 WHERE external_source='H3YUN' AND entry_date BETWEEN ? AND ?
                 ORDER BY account_name,entry_date,id
                """, (rs, ignored) -> {
            accounts.add(rs.getString("account_name"));
            java.sql.Date date = rs.getDate("entry_date");
            return new CashflowReviewRules.SourceBalanceRow(rs.getLong("id"),
                    date == null ? null : date.toLocalDate(), rs.getBigDecimal("income"),
                    rs.getBigDecimal("expense"), rs.getBigDecimal("balance"));
        }, start, end);
        Map<String, List<CashflowReviewRules.SourceBalanceRow>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            grouped.computeIfAbsent(accounts.get(i), ignored -> new ArrayList<>()).add(rows.get(i));
        }
        Map<String, CashflowReviewRules.SourceBalanceReview> result = new LinkedHashMap<>();
        grouped.forEach((account, list) -> result.put(account, CashflowReviewRules.reviewSourceBalance(list, null)));
        return result;
    }

    /** 期间内全部账户的来源结存核对；只返回存在断点的账户（兼容既有调用方）。 */
    public Map<String, CashflowReviewRules.SourceBalanceReview> sourceBalanceReviewByAccount(LocalDate start, LocalDate end) {
        Map<String, CashflowReviewRules.SourceBalanceReview> result = new LinkedHashMap<>();
        sourceBalanceReviewsAll(start, end).forEach((account, review) -> {
            if (review.hasExceptions()) result.put(account, review);
        });
        return result;
    }

    /** 期间内全部账户的断点明细（账户 → 断点列表），供核对清单展开。只含存在断点的账户。 */
    public Map<String, List<CashflowReviewRules.SourceBalanceException>> sourceBalanceBreakdown(LocalDate start, LocalDate end) {
        Map<String, List<CashflowReviewRules.SourceBalanceException>> result = new LinkedHashMap<>();
        sourceBalanceReviewsAll(start, end).forEach((account, review) -> {
            if (review.hasExceptions()) result.put(account, review.exceptions());
        });
        return result;
    }
}
