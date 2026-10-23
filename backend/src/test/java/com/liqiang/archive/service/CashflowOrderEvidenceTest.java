package com.liqiang.archive.service;

import com.liqiang.archive.service.CashflowOrderEvidence.DayState;
import com.liqiang.archive.service.CashflowReviewRules.SourceBalanceRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 来源末行「顺序证据」判据（2026-10-23 按 LQ 的纠正重做）。
 *
 * <p>这组测试的核心是<b>反证</b>：证明「按 entry_date,id 逐笔零断点」和
 * 「同日结存值唯一」都不足以宣称末行可比，只有「逐日路径唯一」才可以。
 *
 * <p>其中 {@link #displayOrderZeroBreakDoesNotProveOrder()} 直接复刻真实库里的现象
 * （浙江利强工商银行 2026-01-03：按 id 排 0 断点，但同日结存反复穿越），
 * 是该判据存在的理由。
 */
class CashflowOrderEvidenceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 3);

    // ==================== 反证：旧判据不够 ====================

    @Test
    @DisplayName("反证①：按展示顺序零断点，不能证明顺序被确定")
    void displayOrderZeroBreakDoesNotProveOrder() {
        // 复刻真实形态：按 id 逐笔完全接得上，但同日确有另一种同样自洽的排列。
        // 数据经穷举验证存在 2 个自洽排列（见下方注释中的两条 id 顺序）。
        List<SourceBalanceRow> rows = List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("50"), null, new BigDecimal("1050")),
                new SourceBalanceRow(2, D1, null, new BigDecimal("50"), new BigDecimal("1000")),
                new SourceBalanceRow(3, D1, new BigDecimal("50"), null, new BigDecimal("1050")),
                new SourceBalanceRow(4, D1, new BigDecimal("100"), null, new BigDecimal("1150")),
                new SourceBalanceRow(5, D1, null, new BigDecimal("400"), new BigDecimal("750")),
                new SourceBalanceRow(6, D1, new BigDecimal("50"), null, new BigDecimal("800")));

        // 旧判据会放过它：逐笔链 0 断点
        CashflowReviewRules.SourceBalanceReview chain = CashflowReviewRules.reviewSourceBalance(rows, null);
        assertThat(chain.exceptionCount()).as("展示顺序下确实零断点").isZero();

        // 新判据不放过：穷举出 2 个自洽排列 ⇒ 顺序不可证
        assertThat(CashflowOrderEvidence.lockDay(rows, new BigDecimal("0.01")).state())
                .as("同日存在多个自洽排列，顺序不可证实")
                .isEqualTo(DayState.MULTIPLE);

        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        assertThat(evidence.comparable()).isFalse();
        assertThat(evidence.skipCode()).isEqualTo(CashflowOrderEvidence.NO_RELIABLE_ORDER);
        assertThat(evidence.skipLabel()).isEqualTo("缺少可靠来源顺序（同日多笔的层内顺序不可证实）");
    }

    @Test
    @DisplayName("反证②：同日结存值唯一，也不足以认定这是期末余额")
    void uniformSameDayBalanceIsNotEvidenceOfOrder() {
        // 同日 3 笔、结存全程 1000 不动：结存值唯一，但对「谁先谁后」「期末是多少」毫无信息。
        List<SourceBalanceRow> rows = List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("100"), null, new BigDecimal("1000")),
                new SourceBalanceRow(2, D1, null, new BigDecimal("100"), new BigDecimal("1000")),
                new SourceBalanceRow(3, D1, new BigDecimal("100"), null, new BigDecimal("1000")));
        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        assertThat(evidence.comparable()).isFalse();
        assertThat(evidence.skipCode()).isEqualTo(CashflowOrderEvidence.NO_RELIABLE_ORDER);
    }

    // ==================== 正证：路径唯一才承认 ====================

    @Test
    @DisplayName("路径唯一：+500 −200 +300 只有一种自洽排列，末行可比")
    void uniquePathIsComparable() {
        List<SourceBalanceRow> rows = List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("500"), null, new BigDecimal("10500")),
                new SourceBalanceRow(2, D1, null, new BigDecimal("200"), new BigDecimal("10300")),
                new SourceBalanceRow(3, D1, new BigDecimal("300"), null, new BigDecimal("10600")));
        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        assertThat(evidence.comparable()).isTrue();
        assertThat(evidence.balance()).isEqualByComparingTo("10600");
        assertThat(evidence.date()).isEqualTo(D1);
        assertThat(evidence.skipCode()).isNull();
    }

    @Test
    @DisplayName("入参顺序不影响判定：打乱后仍然路径唯一")
    void inputOrderDoesNotMatter() {
        List<SourceBalanceRow> unique = new ArrayList<>(uniqueDay());
        List<SourceBalanceRow> shuffled = new ArrayList<>(unique);
        Collections.shuffle(shuffled, new Random(20261023));

        CashflowOrderEvidence.Evidence a = CashflowOrderEvidence.resolveClosingEvidence(unique, null);
        CashflowOrderEvidence.Evidence b = CashflowOrderEvidence.resolveClosingEvidence(shuffled, null);
        assertThat(a.comparable()).isTrue();
        assertThat(b.comparable()).isTrue();
        assertThat(b.balance()).isEqualByComparingTo(a.balance());
    }

    @Test
    @DisplayName("跨日：多日都唯一时，末行取最后一天的末端结存")
    void multiDayUsesLastDayEndBalance() {
        List<SourceBalanceRow> rows = new ArrayList<>();
        BigDecimal closing = new BigDecimal("100000");
        int id = 1;
        for (LocalDate day : List.of(D1, D2)) {
            for (BigDecimal net : List.of(new BigDecimal("700"), new BigDecimal("-300"), new BigDecimal("1100"))) {
                closing = closing.add(net);
                rows.add(new SourceBalanceRow(id++, day,
                        net.signum() > 0 ? net : null, net.signum() < 0 ? net.negate() : null, closing));
            }
        }
        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        assertThat(evidence.comparable()).isTrue();
        assertThat(evidence.date()).isEqualTo(D2);
        assertThat(evidence.balance()).isEqualByComparingTo(closing);
    }

    // ==================== 数据矛盾要说清楚，不许含糊 ====================

    @Test
    @DisplayName("无解：结存被改坏时记为链断，而不是「无法核对」")
    void brokenChainHasItsOwnReason() {
        List<SourceBalanceRow> rows = List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("500"), null, new BigDecimal("10500")),
                new SourceBalanceRow(2, D1, null, new BigDecimal("200"), new BigDecimal("99999")),
                new SourceBalanceRow(3, D1, new BigDecimal("300"), null, new BigDecimal("10600")));
        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        assertThat(evidence.comparable()).isFalse();
        assertThat(evidence.skipCode()).isEqualTo(CashflowOrderEvidence.CHAIN_BROKEN);
        assertThat(evidence.detail()).contains("无解");
    }

    @Test
    @DisplayName("跳过原因文案逐条不同，且都不含「无法核对」这种含糊说法")
    void skipLabelsAreSpecific() {
        for (String code : List.of(CashflowOrderEvidence.NO_RELIABLE_ORDER,
                CashflowOrderEvidence.CHAIN_BROKEN,
                CashflowOrderEvidence.NO_BALANCE_ROWS,
                CashflowOrderEvidence.NOT_ENOUGH_ROWS)) {
            String label = CashflowOrderEvidence.skipLabel(code);
            assertThat(label).isNotBlank();
            assertThat(label).doesNotContain("无法核对");
        }
        assertThat(CashflowOrderEvidence.skipLabel(CashflowOrderEvidence.NO_RELIABLE_ORDER))
                .contains("缺少可靠来源顺序");
    }

    @Test
    @DisplayName("空数据与单行分别给出对应原因")
    void emptyAndSingleRowHaveOwnReasons() {
        assertThat(CashflowOrderEvidence.resolveClosingEvidence(List.of(), null).skipCode())
                .isEqualTo(CashflowOrderEvidence.NO_BALANCE_ROWS);
        assertThat(CashflowOrderEvidence.resolveClosingEvidence(
                List.of(new SourceBalanceRow(1, D1, new BigDecimal("10"), null, new BigDecimal("1010"))), null)
                .skipCode()).isEqualTo(CashflowOrderEvidence.NOT_ENOUGH_ROWS);
    }

    // ==================== 性能护栏 ====================

    @Test
    @DisplayName("同日 30 笔的对称为难数据必须在预算内结束，且结论是多解（不是唯一）")
    void symmetricWorstCaseTerminates() {
        List<SourceBalanceRow> rows = new ArrayList<>();
        BigDecimal balance = new BigDecimal("100000");
        int id = 1;
        for (int i = 0; i < 15; i++) {
            BigDecimal amount = BigDecimal.valueOf(1000 + i * 10L);
            balance = balance.add(amount);
            rows.add(new SourceBalanceRow(id++, D1, amount, null, balance));
            balance = balance.subtract(amount);
            rows.add(new SourceBalanceRow(id++, D1, null, amount, balance));
        }
        long t0 = System.nanoTime();
        CashflowOrderEvidence.Evidence evidence = CashflowOrderEvidence.resolveClosingEvidence(rows, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(evidence.comparable()).as("对称数据顺序不唯一").isFalse();
        assertThat(evidence.skipCode()).isEqualTo(CashflowOrderEvidence.NO_RELIABLE_ORDER);
        assertThat(ms).as("必须靠预算截断，不能真的跑组合爆炸").isLessThan(3000);
    }

    @Test
    @DisplayName("单日状态：唯一 / 多解 / 无解 三种可区分")
    void dayStateIsDistinguishable() {
        assertThat(lockState(List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("500"), null, new BigDecimal("10500")),
                new SourceBalanceRow(2, D1, null, new BigDecimal("200"), new BigDecimal("10300")))))
                .isEqualTo(DayState.UNIQUE);
        assertThat(lockState(List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("100"), null, new BigDecimal("1100")),
                new SourceBalanceRow(2, D1, null, new BigDecimal("100"), new BigDecimal("1000")),
                new SourceBalanceRow(3, D1, new BigDecimal("100"), null, new BigDecimal("1100")))))
                .isEqualTo(DayState.MULTIPLE);
        assertThat(lockState(List.of(
                new SourceBalanceRow(1, D1, new BigDecimal("100"), null, new BigDecimal("9999")),
                new SourceBalanceRow(2, D1, new BigDecimal("100"), null, new BigDecimal("1000")))))
                .isEqualTo(DayState.BROKEN);
    }

    /**
     * 结存值重复 ≠ 歧义。
     *
     * <p>真库反例（温州数创温州银行 2026-10-10，6 行）：6140.70 出现两次，
     * 分别是「支出 24730 后」与「支出 7963 后」，两者在排列里被 +7963 / −7963 隔开，
     * 互换后链条立刻断掉 ⇒ 真实自洽排列只有 1 个。
     * 粗放的「有重复结存就判多解」会把这种日子误杀（实测 13 个账户日）。
     */
    @Test
    @DisplayName("结存重复但被隔开：仍可能唯一，不得短路判多解")
    void duplicatedBalanceSeparatedIsNotAmbiguous() {
        List<SourceBalanceRow> rows = new ArrayList<>();
        // 结存序列 1170.30 → 870.70 → 30870.70 → 6140.70 → 14103.70 → 6140.70
        rows.add(new SourceBalanceRow(1, D1, null, bd("280"), bd("1170.30")));
        rows.add(new SourceBalanceRow(2, D1, null, bd("299.60"), bd("870.70")));
        rows.add(new SourceBalanceRow(3, D1, bd("30000"), null, bd("30870.70")));
        rows.add(new SourceBalanceRow(4, D1, null, bd("24730"), bd("6140.70")));
        rows.add(new SourceBalanceRow(5, D1, bd("7963"), null, bd("14103.70")));
        rows.add(new SourceBalanceRow(6, D1, null, bd("7963"), bd("6140.70")));

        // 6140.70 出现了两次，但被中间的 +7963/−7963 隔开
        assertThat(lockState(rows)).isEqualTo(DayState.UNIQUE);
    }

    /** 反过来：结存相同且净额相反的两笔，互换后仍自洽 ⇒ 必须判多解。 */
    @Test
    @DisplayName("结存相同且净额相反：可互换，必须判多解")
    void mirroredRowsAreAmbiguous() {
        List<SourceBalanceRow> rows = new ArrayList<>();
        rows.add(new SourceBalanceRow(1, D1, bd("500"), null, bd("1500")));
        rows.add(new SourceBalanceRow(2, D1, null, bd("500"), bd("1000")));
        rows.add(new SourceBalanceRow(3, D1, bd("800"), null, bd("1800")));
        rows.add(new SourceBalanceRow(4, D1, null, bd("800"), bd("1000")));

        assertThat(lockState(rows)).isEqualTo(DayState.MULTIPLE);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // ==================== helpers ====================

    /**
     * 容差传 null 不得 NPE：曾经 limit 一路传到 BigDecimal.compareTo 直接抛
     * NullPointerException（探针用未过滤的余额做交叉验证时踩到）。
     * 生产 SQL 已过滤 balance，但把「调用方必须自己保证」当成隐式契约太脆。
     */
    @Test
    void nullToleranceFallsBackInsteadOfNpe() {
        List<SourceBalanceRow> rows = uniqueDay();
        // 含 null 余额的行：结存缺失只能说明该行不参与顺序推断，不能炸
        rows.add(new SourceBalanceRow(99, D1, new BigDecimal("100"), null, null));
        assertThatCode(() -> CashflowOrderEvidence.lockDay(rows, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> CashflowOrderEvidence.resolveClosingEvidence(rows, null))
                .doesNotThrowAnyException();
    }

    private static DayState lockState(List<SourceBalanceRow> rows) {
        return CashflowOrderEvidence.lockDay(rows, new BigDecimal("0.01")).state();
    }

    private static List<SourceBalanceRow> uniqueDay() {
        List<SourceBalanceRow> rows = new ArrayList<>();
        BigDecimal balance = new BigDecimal("100000");
        int id = 1;
        for (BigDecimal net : List.of(new BigDecimal("3111"), new BigDecimal("-4222"),
                new BigDecimal("5333"), new BigDecimal("6444"), new BigDecimal("-1555"))) {
            balance = balance.add(net);
            rows.add(new SourceBalanceRow(id++, D1,
                    net.signum() > 0 ? net : null, net.signum() < 0 ? net.negate() : null, balance));
        }
        return rows;
    }
}
