package com.liqiang.archive.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
}
