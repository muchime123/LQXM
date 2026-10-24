package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CashflowJournalReviewService;
import com.liqiang.archive.service.CashflowOrderEvidence;
import com.liqiang.archive.service.CashflowReviewRules;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 2026 年流水日记账期间报表；来源分录保持只读。
 *  A-0（2026-10-23）：全部端点由 integration.h3 收口为 cashflow.read——
 *  资金数据走独立财务读取权限，不再凭「氚云集成」权限查看（LQ P2-A 修订 5）。 */
@RestController
@RequestMapping("/api/cashflow-journal")
@RequiredArgsConstructor
public class CashflowJournalController {
    private final JdbcTemplate jdbc;
    private final CashflowJournalReviewService reviewService;

    @GetMapping("/review-batches")
    @RequirePermission("cashflow.read")
    public Map<String, Object> reviewBatches() {
        return reviewService.pendingBatches();
    }

    @GetMapping("/review-batches/{batchId}/diff")
    @RequirePermission("cashflow.read")
    public Map<String, Object> reviewDiff(@PathVariable long batchId,
                                          @RequestParam(required = false) String account,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return reviewService.diff(batchId, account, page, size);
    }

    @GetMapping("/accounts")
    @RequirePermission("cashflow.read")
    public Map<String, Object> accounts(@RequestParam LocalDate start, @RequestParam LocalDate end) {
        validatePeriod(start, end);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT a.id, a.account_name, a.currency, opening.amount AS opening_balance,
                       a.entity_id, a.status, e.name AS entity_name,
                       COALESCE(SUM(CASE WHEN e2.entry_date >= '2026-01-01' AND e2.entry_date < ? THEN COALESCE(e2.income,0)-COALESCE(e2.expense,0) ELSE 0 END),0) AS before_net,
                       COALESCE(SUM(CASE WHEN e2.entry_date BETWEEN ? AND ? THEN e2.income ELSE 0 END),0) AS income,
                       COALESCE(SUM(CASE WHEN e2.entry_date BETWEEN ? AND ? THEN e2.expense ELSE 0 END),0) AS expense,
                       COUNT(CASE WHEN e2.entry_date BETWEEN ? AND ? THEN 1 END) AS entry_count
                  FROM h3_cash_accounts a
                  LEFT JOIN cashflow_account_opening opening ON opening.account_id=a.id
                   AND opening.fiscal_year=2026 AND opening.active=1
                  LEFT JOIN cashflow_entities e ON e.id = a.entity_id
                  LEFT JOIN h3_cashflow_entries e2 ON e2.external_source='H3YUN' AND e2.account_name=a.account_name
                 WHERE a.external_source='H3YUN'
                 GROUP BY a.id,a.account_name,a.currency,opening.amount,a.entity_id,a.status,e.name
                 ORDER BY entry_count DESC, a.account_name ASC
                """, (rs, ignored) -> accountRow(rs), start, start, end, start, end, start, end);
        Map<String, CurrencyTotal> byCurrency = new LinkedHashMap<>();
        // 取全部账户的核对结果（含 0 断点），这样每个账户都能报出自己真实的「已衔接链接数」。
        Map<String, CashflowReviewRules.SourceBalanceReview> review =
                reviewService.sourceBalanceReviewsAll(start, end);
        String startText = start.toString();
        String endText = end.toString();
        int totalExceptions = 0;
        int totalLinks = 0;
        int affectedAccounts = 0;
        for (Map<String, Object> item : items) {
            String currency = (String) item.get("currency");
            CurrencyTotal total = byCurrency.computeIfAbsent(currency, CurrencyTotal::new);
            total.count++;
            total.income = total.income.add((BigDecimal) item.get("income"));
            total.expense = total.expense.add((BigDecimal) item.get("expense"));
            BigDecimal closing = (BigDecimal) item.get("closingBalance");
            if (closing == null) total.unknownOpening = true;
            else total.closing = total.closing.add(closing);
            CashflowReviewRules.SourceBalanceReview accountReview = review.get((String) item.get("accountName"));
            int exceptions = attachReview(item, accountReview, startText, endText);
            totalExceptions += exceptions;
            if (exceptions > 0) affectedAccounts++;
            totalLinks += accountReview == null ? 0 : accountReview.checkedLinks();
        }
        List<Map<String, Object>> totals = new ArrayList<>();
        for (CurrencyTotal total : byCurrency.values()) {
            totals.add(Map.of("currency", total.currency, "count", total.count,
                    "income", total.income, "expense", total.expense,
                    "closing", total.closing, "unknownOpening", total.unknownOpening));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("closingComputableCount",
                items.stream().filter(item -> CashflowReviewRules.CLOSING_OK.equals(item.get("closingStatus"))).count());
        summary.put("closingBlockedCount",
                items.stream().filter(item -> !CashflowReviewRules.CLOSING_OK.equals(item.get("closingStatus"))).count());
        summary.put("sourceBalanceExceptionCount", totalExceptions);
        summary.put("sourceBalanceCheckedLinks", totalLinks);
        summary.put("sourceBalanceAffectedAccounts", affectedAccounts);
        // 来源末行「顺序证据」统计（P1 辅助对拍用）。
        // ⚠️ 这里刻意不叫「核对通过/失败」：本库没有银行交易序号，同日多笔的层内顺序大多不可证实，
        // 只有被数据唯一锁定顺序的账户才谈得上「来源末行可比」。不可比时要告诉财务缺的是哪类证据。
        Map<String, Long> evidenceBreakdown = new LinkedHashMap<>();
        long evidenceComparable = 0;
        for (Map<String, Object> item : items) {
            Long id = item.get("id") == null ? null : ((Number) item.get("id")).longValue();
            String accountName = (String) item.get("accountName");
            if (id == null || accountName == null) continue;
            // ⚠️ 不要在这里跳过「本期零流水」的账户（10-23 修）：
            // 跳过会让这批账户既没有 sourceClosingComparable 字段、也不计入 skippedCount，
            // 结果是「可比 13 + 不可比 4 ≠ 35 个账户」，且前端悬停时拿不到任何跳过原因。
            // resolveClosingEvidence 对空行集本就返回 NO_BALANCE_ROWS /「期间内无流水」，交给它判。
            CashflowOrderEvidence.Evidence evidence =
                    reviewService.sourceClosingEvidence(id, start, end).evidence();
            if (evidence.comparable()) {
                evidenceComparable++;
            } else {
                evidenceBreakdown.merge(evidence.skipLabel(), 1L, Long::sum);
                item.put("sourceClosingComparable", false);
                item.put("sourceClosingSkipCode", evidence.skipCode());
                item.put("sourceClosingSkipLabel", evidence.skipLabel());
                continue;
            }
            item.put("sourceClosingComparable", true);
            item.put("sourceClosingDate", evidence.date() == null ? null : evidence.date().toString());
            item.put("sourceClosingBalance", evidence.balance());
        }
        summary.put("sourceClosingComparableCount", evidenceComparable);
        summary.put("sourceClosingSkippedCount", evidenceBreakdown.values().stream().mapToLong(Long::longValue).sum());
        summary.put("sourceClosingSkipBreakdown",
                evidenceBreakdown.entrySet().stream()
                        .map(entry -> Map.of("reason", entry.getKey(), "count", entry.getValue()))
                        .toList());
        // 人工对账维度单独计数：P1 恒为 0，前端不能用它冒充「已核对」。
        summary.put("manualReconciledCount",
                items.stream().filter(item -> !CashflowReviewRules.MANUAL_NOT_STARTED
                        .equals(item.get("manualReconcileStatus"))).count());
        summary.put("manualReconcilePendingCount",
                items.stream().filter(item -> CashflowReviewRules.MANUAL_NOT_STARTED
                        .equals(item.get("manualReconcileStatus"))).count());
        return Map.of("items", items, "totals", totals, "start", start, "end", end, "summary", summary);
    }

    @GetMapping("/accounts/{accountId}/monthly")
    @RequirePermission("cashflow.read")
    public Map<String, Object> monthly(@PathVariable long accountId) {
        List<Map<String, Object>> accountRows = jdbc.query("""
                SELECT a.account_name,a.currency,opening.amount AS opening_balance FROM h3_cash_accounts a
                  LEFT JOIN cashflow_account_opening opening ON opening.account_id=a.id
                   AND opening.fiscal_year=2026 AND opening.active=1
                 WHERE a.id=? AND a.external_source='H3YUN'
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
    @RequirePermission("cashflow.read")
    public Map<String, Object> entries(@RequestParam(required = false) Long accountId, @RequestParam LocalDate start,
                                       @RequestParam LocalDate end, @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "30") int size) {
        validatePeriod(start, end);
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        String name = null;
        if (accountId != null) {
            name = jdbc.query("SELECT account_name FROM h3_cash_accounts WHERE id=? AND external_source='H3YUN'",
                    rs -> rs.next() ? rs.getString(1) : null, accountId);
            if (name == null) throw new IllegalArgumentException("账户不存在");
        }
        StringBuilder where = new StringBuilder(" WHERE external_source='H3YUN' AND entry_date BETWEEN ? AND ?");
        List<Object> params = new ArrayList<>(List.of(start, end));
        if (name != null) {
            where.append(" AND account_name=?");
            params.add(name);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (INSTR(COALESCE(summary,''),?)>0 OR INSTR(COALESCE(supplier_raw,''),?)>0")
                    .append(" OR INSTR(COALESCE(counterparty_raw,''),?)>0 OR INSTR(COALESCE(reimbursed_by,''),?)>0)");
            String text = keyword.trim();
            for (int i = 0; i < 4; i++) params.add(text);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM h3_cashflow_entries" + where,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> items = jdbc.query("""
                SELECT id,account_name,entry_date,summary,supplier_raw,counterparty_raw,reimbursed_by,invoiced,
                       income,expense,balance,fx_rate,fx_cny,settle_rate,settle_cny,fx_gain_loss
                  FROM h3_cashflow_entries
                """ + where + " ORDER BY entry_date DESC,id DESC LIMIT ? OFFSET ?",
                (rs, ignored) -> entryRow(rs), pageParams.toArray());
        return Map.of("items", items, "total", total == null ? 0 : total, "page", page, "size", size);
    }

    /** 不依赖来源行序的确定性异常；逐笔余额差额需先保存 Excel 原行号。 */
    @GetMapping("/issues")
    @RequirePermission("cashflow.read")
    public Map<String, Object> issues(@RequestParam LocalDate start, @RequestParam LocalDate end,
                                      @RequestParam(required = false) Long accountId) {
        validatePeriod(start, end);
        List<Map<String, Object>> items = new ArrayList<>(dataIssues(start, end, accountId));
        items.addAll(detailIssues(start, end, accountId));
        return Map.of("items", items, "total", items.size(),
                "detailCheckedLinks", detailCheckedLinks(start, end, accountId));
    }

    /**
     * 来源结存断点 → 核对清单条目。
     *
     * <p>每条断点单独列出，附带「断在哪一行、差多少、未核实区间起止」，全部由数据推导；
     * 前端只按 issueCode 展示，不认账户名。
     */
    private List<Map<String, Object>> detailIssues(LocalDate start, LocalDate end, Long accountId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Long> accountIds = new HashMap<>();
        reviewService.sourceBalanceBreakdown(start, end).forEach((account, exceptions) -> {
            Long id = accountIds.computeIfAbsent(account, name -> jdbc.query(
                    "SELECT id FROM h3_cash_accounts WHERE external_source='H3YUN' AND account_name=?",
                    rs -> rs.next() ? rs.getLong(1) : null, name));
            if (accountId != null && !accountId.equals(id)) return;
            for (CashflowReviewRules.SourceBalanceException exception : exceptions) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("accountId", id);
                row.put("accountName", account);
                row.put("entryId", exception.entryId());
                row.put("entryDate", exception.entryDate() == null ? null : exception.entryDate().toString());
                row.put("issueCode", "SOURCE_BALANCE_BREAK");
                row.put("severity", "REVIEW");
                row.put("detail", "本行来源结存与上一行不衔接，差额 " + exception.gap().stripTrailingZeros().toPlainString());
                row.put("expectedBalance", exception.previousBalance() == null ? null
                        : exception.previousBalance().stripTrailingZeros().toPlainString());
                row.put("actualBalance", exception.balance().stripTrailingZeros().toPlainString());
                row.put("gap", exception.gap().stripTrailingZeros().toPlainString());
                row.put("rangeStart", start.toString());
                row.put("rangeEnd", end.toString());
                result.add(row);
            }
        });
        return result;
    }

    private int detailCheckedLinks(LocalDate start, LocalDate end, Long accountId) {
        if (accountId != null) {
            CashflowReviewRules.SourceBalanceReview review = reviewService.sourceBalanceReview(accountId, start, end);
            return review == null ? 0 : review.checkedLinks();
        }
        return reviewService.sourceBalanceReviewsAll(start, end).values().stream()
                .mapToInt(CashflowReviewRules.SourceBalanceReview::checkedLinks).sum();
    }

    private List<Map<String, Object>> dataIssues(LocalDate start, LocalDate end, Long accountId) {
        return jdbc.query("""
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
    }

    /**
     * 把两类核对结论挂到账户行上。两者刻意分开：
     * <ul>
     *   <li>{@code closingStatus} —— 期间余额（期初+收支）是否可用，是账户级结论；</li>
     *   <li>{@code sourceBalanceStatus} —— 逐笔来源结存是否全部衔接，是明细级结论。
     *       它有异常**不**影响 closingStatus，也不隐藏期间期末。</li>
     * </ul>
     */
    private static int attachReview(Map<String, Object> item,
                                    CashflowReviewRules.SourceBalanceReview review,
                                    String rangeStart, String rangeEnd) {
        BigDecimal opening = (BigDecimal) item.get("openingBalance");
        String closingStatus = CashflowReviewRules.closingStatusOf(opening, false);
        item.put("closingStatus", closingStatus);
        item.put("closingStatusLabel", CashflowJournalController.closingStatusLabel(closingStatus));
        // 人工对账维度：P1 无人工对账记录，恒为 NOT_STARTED。前端据此显式标注，避免把「可计算」读成「已核对」。
        item.put("manualReconcileStatus", CashflowReviewRules.MANUAL_NOT_STARTED);
        item.put("manualReconcileStatusLabel", CashflowReviewRules.manualReconcileLabel(CashflowReviewRules.MANUAL_NOT_STARTED));
        if (review == null || !review.hasExceptions()) {
        item.put("sourceBalanceStatus", CashflowReviewRules.DETAIL_OK);
        item.put("sourceBalanceExceptionCount", 0);
        item.put("sourceBalanceCheckedLinks", review == null ? 0 : review.checkedLinks());
        item.put("sourceBalanceReviewStart", null);
            item.put("sourceBalanceReviewEnd", null);
            item.put("reconcileSummary", CashflowReviewRules.reconcileSummaryOf(closingStatus, 0));
            return 0;
        }
        item.put("sourceBalanceStatus", CashflowReviewRules.DETAIL_EXCEPTIONS);
        item.put("sourceBalanceExceptionCount", review.exceptionCount());
        item.put("sourceBalanceCheckedLinks", review.checkedLinks());
        item.put("sourceBalanceReviewStart", review.firstDate() == null ? rangeStart : review.firstDate().toString());
        item.put("sourceBalanceReviewEnd", review.lastDate() == null ? rangeEnd : review.lastDate().toString());
        item.put("reconcileSummary", CashflowReviewRules.reconcileSummaryOf(closingStatus, review.exceptionCount()));
        return review.exceptionCount();
    }

    private static String closingStatusLabel(String status) {
        return switch (status) {
            case CashflowReviewRules.CLOSING_OK -> "期末可计算";
            case CashflowReviewRules.CLOSING_OPENING_MISSING -> "缺期初，期末不可计算";
            default -> status;
        };
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
        // P2-A（A-2）：账户主体与状态随行返回；历史统计 SQL 不按 status 过滤（LQ 修订 2：
        // 停用只影响未来操作，绝不改变历史统计结果）。
        long entityId = rs.getLong("entity_id");
        row.put("entityId", rs.wasNull() ? null : entityId);
        row.put("entityName", rs.getString("entity_name"));
        row.put("status", rs.getString("status"));
        return row;
    }

    private static Map<String, Object> entryRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("id", "account_name", "entry_date", "summary", "supplier_raw", "counterparty_raw",
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
