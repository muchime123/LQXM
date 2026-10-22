package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CashflowJournalReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 2026 年流水日记账期间报表；来源分录保持只读。 */
@RestController
@RequestMapping("/api/cashflow-journal")
@RequiredArgsConstructor
public class CashflowJournalController {
    private final JdbcTemplate jdbc;
    private final CashflowJournalReviewService reviewService;

    @GetMapping("/review-batches")
    @RequirePermission("integration.h3")
    public Map<String, Object> reviewBatches() {
        return reviewService.pendingBatches();
    }

    @GetMapping("/review-batches/{batchId}/diff")
    @RequirePermission("integration.h3")
    public Map<String, Object> reviewDiff(@PathVariable long batchId,
                                          @RequestParam(required = false) String account,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return reviewService.diff(batchId, account, page, size);
    }

    @GetMapping("/accounts")
    @RequirePermission("integration.h3")
    public Map<String, Object> accounts(@RequestParam LocalDate start, @RequestParam LocalDate end) {
        validatePeriod(start, end);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT a.id, a.account_name, a.currency, a.opening_balance,
                       COALESCE(SUM(CASE WHEN e.entry_date >= '2026-01-01' AND e.entry_date < ? THEN COALESCE(e.income,0)-COALESCE(e.expense,0) ELSE 0 END),0) AS before_net,
                       COALESCE(SUM(CASE WHEN e.entry_date BETWEEN ? AND ? THEN e.income ELSE 0 END),0) AS income,
                       COALESCE(SUM(CASE WHEN e.entry_date BETWEEN ? AND ? THEN e.expense ELSE 0 END),0) AS expense,
                       COUNT(CASE WHEN e.entry_date BETWEEN ? AND ? THEN 1 END) AS entry_count
                  FROM h3_cash_accounts a
                  LEFT JOIN h3_cashflow_entries e ON e.external_source='H3YUN' AND e.account_name=a.account_name
                 WHERE a.external_source='H3YUN'
                 GROUP BY a.id,a.account_name,a.currency,a.opening_balance
                 ORDER BY entry_count DESC, a.account_name ASC
                """, (rs, ignored) -> accountRow(rs), start, start, end, start, end, start, end);
        Map<String, CurrencyTotal> byCurrency = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String currency = (String) item.get("currency");
            CurrencyTotal total = byCurrency.computeIfAbsent(currency, CurrencyTotal::new);
            total.count++;
            total.income = total.income.add((BigDecimal) item.get("income"));
            total.expense = total.expense.add((BigDecimal) item.get("expense"));
            BigDecimal closing = (BigDecimal) item.get("closingBalance");
            if (closing == null) total.unknownOpening = true;
            else total.closing = total.closing.add(closing);
        }
        List<Map<String, Object>> totals = new ArrayList<>();
        for (CurrencyTotal total : byCurrency.values()) {
            totals.add(Map.of("currency", total.currency, "count", total.count,
                    "income", total.income, "expense", total.expense,
                    "closing", total.closing, "unknownOpening", total.unknownOpening));
        }
        return Map.of("items", items, "totals", totals, "start", start, "end", end);
    }

    @GetMapping("/accounts/{accountId}/monthly")
    @RequirePermission("integration.h3")
    public Map<String, Object> monthly(@PathVariable long accountId) {
        List<Map<String, Object>> accountRows = jdbc.query("""
                SELECT account_name,currency,opening_balance FROM h3_cash_accounts
                 WHERE id=? AND external_source='H3YUN'
                """, (rs, ignored) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountName", rs.getString("account_name"));
            row.put("currency", rs.getString("currency"));
            row.put("opening", rs.getBigDecimal("opening_balance"));
            return row;
        }, accountId);
        if (accountRows.isEmpty()) throw new IllegalArgumentException("账户不存在");
        Map<String, Object> account = accountRows.getFirst();
        String name = (String) account.get("accountName");
        List<Map<String, Object>> source = jdbc.query("""
                SELECT MONTH(entry_date) AS month_no,
                       COALESCE(SUM(income),0) AS income,
                       COALESCE(SUM(expense),0) AS expense,
                       COUNT(*) AS entry_count
                  FROM h3_cashflow_entries
                 WHERE external_source='H3YUN' AND account_name=?
                   AND entry_date BETWEEN '2026-01-01' AND '2026-12-31'
                 GROUP BY MONTH(entry_date)
                """, (rs, ignored) -> Map.of(
                "month", rs.getInt("month_no"), "income", rs.getBigDecimal("income"),
                "expense", rs.getBigDecimal("expense"), "entryCount", rs.getLong("entry_count")), name);
        Map<Integer, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> row : source) byMonth.put((Integer) row.get("month"), row);
        BigDecimal balance = (BigDecimal) account.get("opening");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Month month : Month.values()) {
            int number = month.getValue();
            Map<String, Object> row = byMonth.get(number);
            BigDecimal income = row == null ? BigDecimal.ZERO : (BigDecimal) row.get("income");
            BigDecimal expense = row == null ? BigDecimal.ZERO : (BigDecimal) row.get("expense");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", number);
            item.put("openingBalance", balance);
            item.put("income", income);
            item.put("expense", expense);
            item.put("netFlow", income.subtract(expense));
            balance = balance == null ? null : balance.add(income).subtract(expense);
            item.put("closingBalance", balance);
            item.put("entryCount", row == null ? 0L : row.get("entryCount"));
            items.add(item);
        }
        return Map.of("accountId", accountId, "accountName", name,
                "currency", account.get("currency"), "items", items);
    }

    @GetMapping("/entries")
    @RequirePermission("integration.h3")
    public Map<String, Object> entries(@RequestParam long accountId, @RequestParam LocalDate start,
                                       @RequestParam LocalDate end, @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "30") int size) {
        validatePeriod(start, end);
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        String name = jdbc.query("SELECT account_name FROM h3_cash_accounts WHERE id=? AND external_source='H3YUN'",
                rs -> rs.next() ? rs.getString(1) : null, accountId);
        if (name == null) throw new IllegalArgumentException("账户不存在");
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM h3_cashflow_entries
                 WHERE external_source='H3YUN' AND account_name=? AND entry_date BETWEEN ? AND ?
                """, Long.class, name, start, end);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT id,entry_date,summary,supplier_raw,counterparty_raw,reimbursed_by,invoiced,
                       income,expense,balance,fx_rate,fx_cny,settle_rate,settle_cny,fx_gain_loss
                  FROM h3_cashflow_entries
                 WHERE external_source='H3YUN' AND account_name=? AND entry_date BETWEEN ? AND ?
                 ORDER BY entry_date,id LIMIT ? OFFSET ?
                """, (rs, ignored) -> entryRow(rs), name, start, end, size, (page - 1) * size);
        return Map.of("items", items, "total", total == null ? 0 : total, "page", page, "size", size);
    }

    /** 不依赖来源行序的确定性异常；逐笔余额差额需先保存 Excel 原行号。 */
    @GetMapping("/issues")
    @RequirePermission("integration.h3")
    public Map<String, Object> issues(@RequestParam LocalDate start, @RequestParam LocalDate end,
                                      @RequestParam(required = false) Long accountId) {
        validatePeriod(start, end);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT a.id AS account_id,a.account_name,NULL AS entry_id,NULL AS entry_date,
                       'MISSING_OPENING' AS issue_code,'账户缺少期初余额' AS detail
                  FROM h3_cash_accounts a
                 WHERE a.external_source='H3YUN' AND a.opening_balance IS NULL AND (? IS NULL OR a.id=?)
                UNION ALL
                SELECT a.id,e.account_name,e.id,e.entry_date,
                       'MISSING_DATE','流水有收支但日期缺失'
                  FROM h3_cashflow_entries e JOIN h3_cash_accounts a ON a.account_name=e.account_name
                 WHERE e.external_source='H3YUN' AND a.external_source='H3YUN'
                   AND e.entry_date IS NULL AND (COALESCE(e.income,0)<>0 OR COALESCE(e.expense,0)<>0)
                   AND (? IS NULL OR a.id=?)
                UNION ALL
                SELECT a.id,e.account_name,e.id,e.entry_date,
                       'BOTH_SIDES','同一行同时填写收入与支出'
                  FROM h3_cashflow_entries e JOIN h3_cash_accounts a ON a.account_name=e.account_name
                 WHERE e.external_source='H3YUN' AND a.external_source='H3YUN'
                   AND e.entry_date BETWEEN ? AND ? AND COALESCE(e.income,0)<>0 AND COALESCE(e.expense,0)<>0
                   AND (? IS NULL OR a.id=?)
                UNION ALL
                SELECT a.id,e.account_name,e.id,e.entry_date,
                       'MISSING_FX','外币流水缺少汇率和人民币折算值'
                  FROM h3_cashflow_entries e JOIN h3_cash_accounts a ON a.account_name=e.account_name
                 WHERE e.external_source='H3YUN' AND a.external_source='H3YUN'
                   AND a.currency<>'CNY' AND e.entry_date BETWEEN ? AND ?
                   AND (COALESCE(e.income,0)<>0 OR COALESCE(e.expense,0)<>0)
                   AND e.fx_rate IS NULL AND e.fx_cny IS NULL AND (? IS NULL OR a.id=?)
                ORDER BY account_name,entry_date,entry_id
                """, (rs, ignored) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountId", rs.getLong("account_id"));
            row.put("accountName", rs.getString("account_name"));
            row.put("entryId", rs.getObject("entry_id"));
            row.put("entryDate", rs.getObject("entry_date"));
            row.put("issueCode", rs.getString("issue_code"));
            row.put("detail", rs.getString("detail"));
            return row;
        }, accountId, accountId, accountId, accountId, start, end, accountId, accountId,
                start, end, accountId, accountId);
        return Map.of("items", items, "total", items.size());
    }

    private static void validatePeriod(LocalDate start, LocalDate end) {
        if (start.isAfter(end) || start.getYear() != 2026 || end.getYear() != 2026) {
            throw new IllegalArgumentException("当前仅支持 2026 年内的起止日期");
        }
    }

    private static Map<String, Object> accountRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        BigDecimal sourceOpening = rs.getBigDecimal("opening_balance");
        BigDecimal income = rs.getBigDecimal("income");
        BigDecimal expense = rs.getBigDecimal("expense");
        BigDecimal opening = sourceOpening == null ? null : sourceOpening.add(rs.getBigDecimal("before_net"));
        row.put("id", rs.getLong("id"));
        row.put("accountName", rs.getString("account_name"));
        row.put("currency", rs.getString("currency"));
        row.put("openingBalance", opening);
        row.put("income", income);
        row.put("expense", expense);
        row.put("netFlow", income.subtract(expense));
        row.put("closingBalance", opening == null ? null : opening.add(income).subtract(expense));
        row.put("entryCount", rs.getLong("entry_count"));
        return row;
    }

    private static Map<String, Object> entryRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("id", "entry_date", "summary", "supplier_raw", "counterparty_raw",
                "reimbursed_by", "invoiced", "income", "expense", "balance", "fx_rate", "fx_cny",
                "settle_rate", "settle_cny", "fx_gain_loss")) {
            row.put(key, rs.getObject(key));
        }
        return row;
    }

    private static final class CurrencyTotal {
        private final String currency;
        private int count;
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;
        private BigDecimal closing = BigDecimal.ZERO;
        private boolean unknownOpening;

        private CurrencyTotal(String currency) { this.currency = currency; }
    }
}
