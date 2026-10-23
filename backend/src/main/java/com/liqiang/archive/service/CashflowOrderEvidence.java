package com.liqiang.archive.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 来源结存辅助对拍：<b>顺序证据</b>与<b>末行可比性</b>的唯一判定处。
 *
 * <p>存在的理由（2026-10-23 由 LQ 指出、随后在真实库上实测确认）：
 * 本系统流水来自氚云导出件，<b>没有银行交易序号</b>。曾经用过两条「末行身份可靠」的判据——
 * ① 逐笔链零断点；② 期间末最后一个有结存的日期上所有结存值唯一——<b>两条都不成立</b>：
 * <ul>
 *   <li>② 只能证明同日几笔的结存值没有分歧，<b>不能</b>证明这个值就是账户真实期末余额；</li>
 *   <li>① 如果沿 {@code entry_date, id} 检查，只说明「这种排列下没发现差异」。
 *       实测：浙江利强工商银行 2026-01-03 按 id 排 0 断点，但同日结存是
 *       937651.99 → 931319.57 → … → 863997.55 →（账户有误退回，等额冲销）863997.55，
 *       同日结存<b>来回穿越</b>；换一种同日排列同样能自洽。</li>
 * </ul>
 *
 * <p>因此本类只承认一种顺序证据：<b>路径锁定</b>——给定当日各笔的净额与真实结存，
 * 如果「让每笔结存都接得上」的排列<b>恰好只有一种</b>，那么这一天的顺序就被数据唯一确定了。
 * 有多个自洽排列时，任何自洽排列都不是「被证实的顺序」，一律不承认。
 *
 * <p>⚠️ 调用方不得用本类的结果去反推交易顺序（它只回答「顺序是否唯一」），
 * 也不得把 {@link Skip#NO_RELIABLE_ORDER} 笼统写成「无法核对」。
 */
public final class CashflowOrderEvidence {

    // ==================== 跳过原因（文案单一来源） ====================

    /** 缺少可靠来源顺序：同日层内顺序不可证实（本库无银行交易序号）。 */
    public static final String NO_RELIABLE_ORDER = "NO_RELIABLE_SOURCE_ORDER";
    /** 期内逐日求解出现无解：数据本身矛盾，链断。 */
    public static final String CHAIN_BROKEN = "SOURCE_CHAIN_BROKEN";
    /** 期间内没有任何带结存的行。 */
    public static final String NO_BALANCE_ROWS = "NO_BALANCE_ROWS";
    /** 期间内不足两行，无从衔接。 */
    public static final String NOT_ENOUGH_ROWS = "NOT_ENOUGH_ROWS";

    /**
     * 跳过原因的固定文案。全部指向<b>哪种证据缺失</b>，不出现「无法核对」这类含糊说法。
     */
    public static String skipLabel(String code) {
        return switch (code == null ? "" : code) {
            case NO_RELIABLE_ORDER -> "缺少可靠来源顺序（同日多笔的层内顺序不可证实）";
            case CHAIN_BROKEN -> "来源结存链本身存在断点";
            case NO_BALANCE_ROWS -> "期间内没有带来源结存的行";
            case NOT_ENOUGH_ROWS -> "期间内不足两行，无从衔接";
            default -> "未提供跳过原因";
        };
    }

    private CashflowOrderEvidence() {}

    // ==================== 结论 ====================

    /**
     * 单账户在期间内「来源末行能不能代表期末」的结论。
     *
     * @param comparable true 表示末行结存可作为期间期末的来源证据
     * @param date       末行所属日期（comparable 或链断时有值）
     * @param balance    末行末端结存（仅 comparable 时有值）
     * @param skipCode   跳过原因码，取值见本类 NO_RELIABLE_ORDER 等常量；comparable 时为 null
     * @param detail     证据说明，写进对拍日志供人工复核
     */
    public record Evidence(boolean comparable, LocalDate date, BigDecimal balance,
                           String skipCode, String detail) {
        public String skipLabel() {
            return skipCode == null ? null : CashflowOrderEvidence.skipLabel(skipCode);
        }

        static Evidence skip(LocalDate date, String code, String detail) {
            return new Evidence(false, date, null, code, detail);
        }
    }

    /** 单日求解结果。 */
    public enum DayState {
        /** 自洽排列恰好一种 ⇒ 该日顺序被数据唯一确定。 */
        UNIQUE,
        /** 当日不足两笔，或全部结存相同：不构成任何顺序信息，但也不构成矛盾。 */
        TRIVIAL,
        /** 存在两种以上自洽排列 ⇒ 顺序不可证实。 */
        MULTIPLE,
        /** 一条自洽排列都没有 ⇒ 数据矛盾。 */
        BROKEN
    }

    /**
     * 单日求解结果。
     *
     * @param solutions 自洽排列数（&gt;1 时可能被截断，只用于区分「1」与「不止 1」）
     * @param exhausted 是否因搜索预算耗尽而提前结束（此时 solutions 不可信，一律按 MULTIPLE 处理）
     */
    public record DayOrder(DayState state, int solutions, boolean exhausted) {}

    // ==================== 主入口 ====================

    /**
     * 逐日做路径锁定，回答「期间末行的来源结存能不能当期末」。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>无带结存的行 / 不足两行 → 对应跳过原因；</li>
     *   <li>任一日 <b>无解</b> → {@link #CHAIN_BROKEN}（数据矛盾是事实，优先报出）；</li>
     *   <li>任一日 <b>多解或搜索未穷尽</b> → {@link #NO_RELIABLE_ORDER}（顺序不可证实）；</li>
     *   <li>全部日期唯一/无信息 → 末日末端结存即来源期末，comparable。</li>
     * </ol>
     *
     * @param rows 账户内期间流水，<b>顺序不限</b>：本方法自己按日期分组，不信任传入顺序
     */
    public static Evidence resolveClosingEvidence(List<CashflowReviewRules.SourceBalanceRow> rows,
                                                  BigDecimal tolerance) {
        if (rows == null || rows.isEmpty()) {
            return Evidence.skip(null, NO_BALANCE_ROWS, "期间内无流水");
        }
        BigDecimal limit = limitOf(tolerance);

        Map<LocalDate, List<CashflowReviewRules.SourceBalanceRow>> byDate = new TreeMap<>();
        int withBalance = 0;
        for (CashflowReviewRules.SourceBalanceRow row : rows) {
            if (row.entryDate() == null) continue;
            if (row.balance() != null) withBalance++;
            byDate.computeIfAbsent(row.entryDate(), ignored -> new ArrayList<>()).add(row);
        }
        if (byDate.isEmpty()) {
            return Evidence.skip(null, NO_BALANCE_ROWS, "全部流水缺日期");
        }
        LocalDate lastDate = byDate.keySet().stream().max(LocalDate::compareTo).orElse(null);
        if (withBalance == 0) {
            return Evidence.skip(lastDate, NO_BALANCE_ROWS, "期间内 " + rows.size() + " 行均无来源结存");
        }
        if (rows.size() < 2) {
            return Evidence.skip(lastDate, NOT_ENOUGH_ROWS, "期间内仅 1 行");
        }

        int unique = 0, trivial = 0, multiple = 0, broken = 0;
        List<LocalDate> brokenDays = new ArrayList<>();
        List<LocalDate> ambiguousDays = new ArrayList<>();
        for (Map.Entry<LocalDate, List<CashflowReviewRules.SourceBalanceRow>> day : byDate.entrySet()) {
            DayOrder order = lockDay(day.getValue(), limit);
            switch (order.state()) {
                case UNIQUE -> unique++;
                case TRIVIAL -> trivial++;
                case MULTIPLE -> {
                    multiple++;
                    if (ambiguousDays.size() < 5) ambiguousDays.add(day.getKey());
                }
                case BROKEN -> {
                    broken++;
                    if (brokenDays.size() < 5) brokenDays.add(day.getKey());
                }
                default -> { }
            }
        }

        if (broken > 0) {
            return Evidence.skip(lastDate, CHAIN_BROKEN,
                    "逐日求解出现 " + broken + " 天无解（" + brokenDays + "），数据本身矛盾");
        }
        if (multiple > 0) {
            return Evidence.skip(lastDate, NO_RELIABLE_ORDER,
                    "逐日求解有 " + multiple + " 天存在多种自洽排列（" + ambiguousDays + "），"
                            + "已唯一锁定 " + unique + " 天；本库无银行交易序号，层内顺序不可证实");
        }
        // 一天都没锁住（全部是「无信息日」）⇒ 没有任何顺序证据，末行同样不能宣称可比。
        // 「结存全程不动」这种日子虽然不矛盾，但它对确定顺序/确定期末毫无贡献。
        if (unique == 0) {
            return Evidence.skip(lastDate, NO_RELIABLE_ORDER,
                    "期间内 " + trivial + " 天均为「同日结存相同／单笔」等无顺序信息的日子，"
                            + "没有任何一天被数据唯一锁定；本库无银行交易序号，层内顺序不可证实");
        }

        BigDecimal closing = closingOfLockedDay(byDate.get(lastDate), limit);
        if (closing == null) {
            return Evidence.skip(lastDate, NO_RELIABLE_ORDER,
                    "末日 " + lastDate + " 顺序虽唯一但末端结存无法确定");
        }
        return new Evidence(true, lastDate, closing, null,
                "逐日顺序唯一（唯一 " + unique + " 天 / 无信息 " + trivial + " 天），末日 " + lastDate
                        + " 末端结存 " + closing);
    }

    // ==================== 单日求解 ====================

    /**
     * 当日路径搜索的节点预算。
     *
     * <p>实测最坏是「大量等额收付对称对」的数据（每对可互换 ⇒ 2^k 条自洽路径）：
     * 纯 DFS 在 20 笔已 354ms、25 笔超分钟级。预算耗尽时一律<b>保守判多解</b>——
     * 找不到唯一性就是没有证据，绝不允许「搜不完就当唯一」。
     */
    static final int SEARCH_BUDGET = 120_000;

    /** 金额比较容差默认值：1 分。调用方不传时用它，避免把 null 传到 compareTo。 */
    static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.01");

    /**
     * 求单日「自洽排列」的个数（用于区分恰好 1 种与不止 1 种）。
     *
     * <p><b>不假定谁是首笔</b>：当日开始结存 {@code b0} 未知，所以对每一笔都假设「它是首笔」，
     * 由 {@code b0 = 该笔结存 − 该笔净额} 反推开始结存，再向下搜索。
     *
     * <p>⚠️ 必须<b>按完整排列去重</b>：同一条真实排列会被它的每一笔各「认领」一次，
     * 若按首笔累加解数，任何 n&gt;1 的单日都会假性判成多解。
     *
     * <p>剪枝：第 k 步结存必须等于当日某笔结存值（容差内），否则该分支不可能自洽。
     */
    static DayOrder lockDay(List<CashflowReviewRules.SourceBalanceRow> dayRows, BigDecimal limit) {
        int n = dayRows.size();
        if (n <= 1) return new DayOrder(DayState.TRIVIAL, 1, false);
        BigDecimal eps = limitOf(limit);
        BigDecimal shared = uniformBalance(dayRows, eps);
        if (shared != null) {
            // 全部结存相同：任何排列都「接得上」，但这对确定顺序毫无信息
            return new DayOrder(DayState.TRIVIAL, 1, false);
        }
        if (hasSwapAmbiguity(dayRows, eps)) {
            // 两笔结存相同且净额相反 ⇒ 同位置互换仍自洽 ⇒ 顺序不唯一，无需搜索
            return new DayOrder(DayState.MULTIPLE, 2, false);
        }

        Set<String> orders = new HashSet<>();
        int[] budget = {SEARCH_BUDGET};
        boolean anyBranch = false;
        for (int first = 0; first < n; first++) {
            if (dayRows.get(first).balance() == null) continue;
            BigDecimal b0 = dayRows.get(first).balance().subtract(net(dayRows.get(first)));
            boolean[] used = new boolean[n];
            used[first] = true;
            StringBuilder path = new StringBuilder().append(first);
            anyBranch |= walk(dayRows, used, path, b0.add(net(dayRows.get(first))), 1, eps, orders, budget);
            if (orders.size() > 1) return new DayOrder(DayState.MULTIPLE, orders.size(), false);
            if (budget[0] <= 0 && orders.size() <= 1) {
                return new DayOrder(DayState.MULTIPLE, Math.max(2, orders.size()), true);
            }
        }
        if (!anyBranch) return new DayOrder(DayState.BROKEN, 0, false);
        return new DayOrder(orders.size() == 1 ? DayState.UNIQUE : DayState.MULTIPLE, orders.size(), false);
    }

    /**
     * 深度优先遍历自洽排列，把完整路径指纹塞进 {@code sink}。
     *
     * @return 是否至少走通一条完整排列
     */
    private static boolean walk(List<CashflowReviewRules.SourceBalanceRow> rows, boolean[] used,
                                StringBuilder path, BigDecimal runningBalance, int placed,
                                BigDecimal limit, Set<String> sink, int[] budget) {
        if (budget[0] <= 0) return false;
        budget[0]--;
        if (placed == rows.size()) {
            sink.add(path.toString());
            return true;
        }
        boolean found = false;
        for (int next = 0; next < rows.size(); next++) {
            if (used[next]) continue;
            CashflowReviewRules.SourceBalanceRow row = rows.get(next);
            if (row.balance() == null) continue;
            BigDecimal candidate = runningBalance.add(net(row));
            if (candidate.subtract(row.balance()).abs().compareTo(limit) > 0) continue;
            used[next] = true;
            int before = path.length();
            path.append('-').append(next);
            found |= walk(rows, used, path, candidate, placed + 1, limit, sink, budget);
            path.setLength(before);
            used[next] = false;
            if (sink.size() > 1 || budget[0] <= 0) return found;
        }
        return found;
    }

    /** 顺序已锁定的那一天，末端结存 = 走完唯一路径后的最后一条结存。 */
    private static BigDecimal closingOfLockedDay(List<CashflowReviewRules.SourceBalanceRow> dayRows,
                                                  BigDecimal limit) {
        BigDecimal eps = limitOf(limit);
        BigDecimal uniform = uniformBalance(dayRows, eps);
        if (uniform != null) return uniform;
        DayOrder order = lockDay(dayRows, eps);
        if (order.state() != DayState.UNIQUE) return null;
        int n = dayRows.size();
        for (int first = 0; first < n; first++) {
            if (dayRows.get(first).balance() == null) continue;
            BigDecimal b0 = dayRows.get(first).balance().subtract(net(dayRows.get(first)));
            boolean[] used = new boolean[n];
            used[first] = true;
            BigDecimal end = descendEnd(dayRows, used, b0.add(net(dayRows.get(first))), 1, eps);
            if (end != null) return end;
        }
        return null;
    }

    /** 向下走唯一路径，返回末端结存；若出现第二条路径则返回 null（保守）。 */
    private static BigDecimal descendEnd(List<CashflowReviewRules.SourceBalanceRow> rows, boolean[] used,
                                         BigDecimal runningBalance, int placed, BigDecimal limit) {
        if (placed == rows.size()) return runningBalance;
        BigDecimal found = null;
        for (int next = 0; next < rows.size(); next++) {
            if (used[next]) continue;
            CashflowReviewRules.SourceBalanceRow row = rows.get(next);
            if (row.balance() == null) continue;
            BigDecimal candidate = runningBalance.add(net(row));
            if (candidate.subtract(row.balance()).abs().compareTo(limit) > 0) continue;
            used[next] = true;
            BigDecimal end = descendEnd(rows, used, candidate, placed + 1, limit);
            used[next] = false;
            if (end != null) {
                if (found != null) return null;
                found = end;
            }
        }
        return found;
    }

    // ==================== 小工具 ====================

    /**
     * 容差默认值。调用方传 null 时不能把 null 一路传到 {@code BigDecimal.compareTo}（会 NPE），
     * 也别当成 0——金额比较用严格相等太脆，取 1 分。
     */
    private static BigDecimal limitOf(BigDecimal limit) {
        return limit == null ? DEFAULT_TOLERANCE : limit;
    }

    private static BigDecimal net(CashflowReviewRules.SourceBalanceRow row) {
        return CashflowReviewRules.nz(row.income()).subtract(CashflowReviewRules.nz(row.expense()));
    }

    /** 当日全部行结存是否唯一（容差内）；唯一则返回该值，否则 null。 */
    private static BigDecimal uniformBalance(List<CashflowReviewRules.SourceBalanceRow> rows, BigDecimal limit) {
        BigDecimal first = null;
        for (CashflowReviewRules.SourceBalanceRow row : rows) {
            if (row.balance() == null) return null;
            if (first == null) {
                first = row.balance();
            } else if (row.balance().subtract(first).abs().compareTo(limit) > 0) {
                return null;
            }
        }
        return first;
    }

    /**
     * 当日是否存在<b>可互换</b>的结存重复——即两笔结存相同且净额互为相反数。
     *
     * <p>⚠️ 只是「结存值出现了两次」并不构成歧义：如果这两笔在排列里被别的行隔开，
     * 互换后链条就断了，真实自洽排列仍可能只有一个。
     * 2026-10-23 实测反例（温州数创温州银行 2026-10-10）：6 行里 6140.70 出现两次
     * （一笔 +7963、一笔 −7963），粗放的「有重复就判多解」把原本唯一的排列误杀。
     *
     * <p>真正的歧义只发生在两笔<b>都等于同一个结存值</b>且<b>净额相反</b>时——
     * 它们在同一位置互换后逐行仍然自洽，顺序不可证。
     */
    private static boolean hasSwapAmbiguity(List<CashflowReviewRules.SourceBalanceRow> rows, BigDecimal limit) {
        for (int i = 0; i < rows.size(); i++) {
            CashflowReviewRules.SourceBalanceRow a = rows.get(i);
            if (a.balance() == null) continue;
            for (int j = i + 1; j < rows.size(); j++) {
                CashflowReviewRules.SourceBalanceRow b = rows.get(j);
                if (b.balance() == null) continue;
                if (a.balance().subtract(b.balance()).abs().compareTo(limit) > 0) continue;
                if (net(a).add(net(b)).abs().compareTo(limit) <= 0) return true;
            }
        }
        return false;
    }

    /** 供日志/前端展示的「按日证据摘要」（只读，不做判定）。 */
    public static Map<String, Object> describeDay(List<CashflowReviewRules.SourceBalanceRow> dayRows,
                                                  BigDecimal tolerance) {
        BigDecimal limit = limitOf(tolerance);
        DayOrder order = lockDay(dayRows, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", order.state().name());
        result.put("solutions", order.solutions());
        result.put("exhausted", order.exhausted());
        return result;
    }
}
