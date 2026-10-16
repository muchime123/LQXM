package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「出库名称示例」排序回归（销售出库产品分析页）。
 *
 * <p>钉住三件事：① 规格数字/标点不进归并键（同一款产品仅尺寸不同时归并键必须相等）；
 * ② 同一归并键的行排序后必然相邻（这就是「产品名差不多的放在一起」的判据）；
 * ③ 归并键相同但完整名称不同时，仍给出确定且可复现的顺序（同长度数字按数值感升序）。
 *
 * <p>排序只影响列表展示顺序，不参与任何匹配、判等或统计口径——因此这里的断言只谈顺序，不谈口径。
 */
class H3SalesProductAnalysisNameSortTest {

    private static Map<String, Object> row(String code, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", code);
        m.put("sampleNames", name);
        m.put("orderCount", 1L);
        m.put("salesAmount", "0.00");
        return m;
    }

    private static List<String> sortedNames(List<Map<String, Object>> rows, boolean desc) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(H3SalesProductAnalysisService.comparatorFor("sampleNames", desc));
        return copy.stream().map(r -> String.valueOf(r.get("sampleNames"))).toList();
    }

    private static List<String> sortedCodes(List<Map<String, Object>> rows, String field, boolean desc) {
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(H3SalesProductAnalysisService.comparatorFor(field, desc));
        return copy.stream().map(r -> String.valueOf(r.get("productCode"))).toList();
    }

    // ====== ① 归并键：忽略数字与标点 ======

    @Test
    void skeletonIgnoresSpecNumbersAndPunctuation() {
        String a = H3SalesProductAnalysisService.nameSkeleton("三边封袋 140*200+40mm");
        String b = H3SalesProductAnalysisService.nameSkeleton("三边封袋 150*220+40mm");
        assertEquals("三边封袋mm", a);
        assertEquals(a, b, "仅尺寸不同的同一款产品，归并键必须相等");
        assertNotEquals(a, H3SalesProductAnalysisService.nameSkeleton("卷膜 宽410mm"));
    }

    @Test
    void skeletonKeepsLettersAndLowercases() {
        assertEquals("机器frb", H3SalesProductAnalysisService.nameSkeleton("机器 FRB-770"));
        assertEquals("a标签", H3SalesProductAnalysisService.nameSkeleton("A4标签"));
        assertEquals("", H3SalesProductAnalysisService.nameSkeleton(null));
        assertEquals("", H3SalesProductAnalysisService.nameSkeleton("  "));
    }

    @Test
    void skeletonDropsMultiNameSeparator() {
        // 同编号多名称在列表里是 "A / B" 形式，分隔符不得成为归并键的一部分
        assertEquals("三边封袋mm三边封袋mm",
                H3SalesProductAnalysisService.nameSkeleton("三边封袋 140mm / 三边封袋 150mm"));
    }

    // ====== ② 同族相邻 ======

    @Test
    void sameFamilyRowsAreAdjacent() {
        List<Map<String, Object>> rows = List.of(
                row("P01", "三边封袋 150*220+40mm"),
                row("P02", "卷膜 宽410mm"),
                row("P03", "三边封袋 140*200+40mm"),
                row("P04", "包装盒"));
        List<String> sorted = sortedNames(rows, false);
        int first = sorted.indexOf("三边封袋 140*200+40mm");
        int second = sorted.indexOf("三边封袋 150*220+40mm");
        assertEquals(first + 1, second, "同一归并键的两行必须相邻，实际顺序=" + sorted);
    }

    @Test
    void sameFamilyStaysAdjacentWhenDescending() {
        List<Map<String, Object>> rows = List.of(
                row("P01", "三边封袋 150*220+40mm"),
                row("P02", "卷膜 宽410mm"),
                row("P03", "三边封袋 140*200+40mm"),
                row("P04", "包装盒"));
        List<String> sorted = sortedNames(rows, true);
        int first = sorted.indexOf("三边封袋 140*200+40mm");
        int second = sorted.indexOf("三边封袋 150*220+40mm");
        assertEquals(1, Math.abs(first - second), "降序只反转大方向，同族仍必须相邻，实际顺序=" + sorted);
    }

    // ====== ③ 同归并键的顺序确定 ======

    @Test
    void sameSkeletonOrderedByFullName() {
        List<Map<String, Object>> rows = List.of(
                row("P01", "三边封袋 150*220+40mm"),
                row("P03", "三边封袋 140*200+40mm"));
        assertEquals(List.of("三边封袋 140*200+40mm", "三边封袋 150*220+40mm"), sortedNames(rows, false));
    }

    // ====== 规格数字按数值比，不按字符逐个比 ======

    @Test
    void specNumbersCompareByValueNotByCharacter() {
        Comparator<Map<String, Object>> cmp = H3SalesProductAnalysisService.nameComparator();
        Map<String, Object> a1 = row("001DFH", "A-1豆腐盒-2300只");
        Map<String, Object> a3 = row("051和好盒", "A-3豆腐盒-1520只");
        Map<String, Object> a18 = row("005DFH", "A-18豆腐盒-2280只");
        Map<String, Object> a102 = row("09DFH", "A-102豆腐盒-1350只");
        assertTrue(cmp.compare(a1, a3) < 0, "1 < 3");
        assertTrue(cmp.compare(a3, a18) < 0, "3 < 18 —— 按字符逐个比会判出 A-1 < A-18 < A-3 的假序，此断言用于区分两种实现");
        assertTrue(cmp.compare(a18, a102) < 0, "18 < 102");
        assertEquals(List.of("A-1豆腐盒-2300只", "A-3豆腐盒-1520只", "A-18豆腐盒-2280只", "A-102豆腐盒-1350只"),
                sortedNames(List.of(a102, a3, a1, a18), false));
    }

    @Test
    void leadingZerosDoNotChangeNumericOrder() {
        Comparator<Map<String, Object>> cmp = H3SalesProductAnalysisService.nameComparator();
        assertEquals(0, cmp.compare(row("P01", "2014*2黑色-1000只"), row("P01", "2014*02黑色-1000只")),
                "规格 2 与 02 视为同一数值（编号相同则整行判等，靠编号兜底不会有歧义）");
        assertTrue(cmp.compare(row("P03", "2014*2黑色-1000只"), row("P04", "2014*10黑色-1000只")) < 0,
                "2 < 10（按字符逐个比会判反）");
    }

    @Test
    void sortIsDeterministicWhateverTheInputOrder() {
        List<Map<String, Object>> rows = List.of(
                row("P01", "三边封袋 150*220+40mm"),
                row("P02", "卷膜 宽410mm"),
                row("P03", "三边封袋 140*200+40mm"),
                row("P04", "包装盒"),
                row("P05", "机器 FRB-770"),
                row("P06", "阿胶盒"));
        List<String> base = sortedNames(rows, false);
        for (int i = 0; i < 5; i++) {
            List<Map<String, Object>> shuffled = new ArrayList<>(rows);
            Collections.shuffle(shuffled);
            assertEquals(base, sortedNames(shuffled, false), "同一批数据无论进来时什么顺序，排完都必须一样（第" + i + "轮）");
        }
    }

    // ====== 新增可排序列：金额 / 行数 / 库存 / 末次出库 ======

    @Test
    void moneyColumnComparesByDecimalNotByTruncatedLong() {
        Map<String, Object> low = row("P01", "甲");
        low.put("salesAmount", "100.40");
        Map<String, Object> high = row("P02", "乙");
        high.put("salesAmount", "100.50");
        // 若实现用 num() 的 longValue() 截断，两行金额都变成 100 → 主键判等 → 退回到兜底链的
        // 「销售额降序」，升序结果会颠倒成 [P02, P01]。这条断言正是用来区分两种实现的。
        assertEquals(List.of("P01", "P02"), sortedCodes(List.of(high, low), "salesAmount", false),
                "金额按小数比：100.40 在 100.50 之前");
    }

    @Test
    void descendingReversesPrimaryOnlyAndKeepsTieBreakDirection() {
        Map<String, Object> big = row("P01", "甲");
        big.put("orderCount", 5L);
        big.put("salesAmount", "900.00");
        Map<String, Object> small = row("P02", "乙");
        small.put("orderCount", 5L);
        small.put("salesAmount", "100.00");
        // 出库单数相同时，兜底链固定按销售额降序 —— 若换成「整体 reversed」，这里会反过来。
        assertEquals(List.of("P01", "P02"), sortedCodes(List.of(small, big), "orderCount", true),
                "按出库单数降序时，单数相同的行仍按销售额降序兜底");
    }

    @Test
    void lineCountAndStockColumnsSortNumerically() {
        Map<String, Object> a = row("P01", "甲");
        a.put("lineCount", 9L);
        a.put("nonZeroStockRows", 2L);
        Map<String, Object> b = row("P02", "乙");
        b.put("lineCount", 120L);
        b.put("nonZeroStockRows", 11L);
        assertEquals(List.of("P02", "P01"), sortedCodes(List.of(a, b), "lineCount", true));
        assertEquals(List.of("P02", "P01"), sortedCodes(List.of(a, b), "nonZeroStockRows", true));
        assertEquals(List.of("P01", "P02"), sortedCodes(List.of(b, a), "lineCount", false));
    }

    @Test
    void lastShipSortsByIsoTextAndMissingDateIsAnExtreme() {
        Map<String, Object> none = row("P01", "甲");
        none.put("lastShip", null);
        Map<String, Object> early = row("P02", "乙");
        early.put("lastShip", "2022-04-16");
        Map<String, Object> late = row("P03", "丙");
        late.put("lastShip", "2026-08-27");
        assertEquals(List.of("P01", "P02", "P03"), sortedCodes(List.of(late, none, early), "lastShip", false),
                "ISO 文本序 = 日期序；无出库日期的行升序排最前");
        assertEquals(List.of("P03", "P02", "P01"), sortedCodes(List.of(none, early, late), "lastShip", true),
                "降序时无出库日期的行排最后");
    }

    @Test
    void everyWhitelistedColumnSortsByItsOwnField() {
        // 逐列构造「只在该字段上不同」的两行：某列若被静默回落成出库单数，或白名单与 comparatorFor
        // 不同步，这里就会失败 —— 那正是「表头箭头与列表内容不一致」的成因。
        List<Object[]> cases = List.of(
                new Object[]{"sampleNames", "阿胶盒", "白卡纸盒"},
                new Object[]{"orderCount", 2L, 9L},
                new Object[]{"salesAmount", "10.00", "20.00"},
                new Object[]{"lineCount", 3L, 40L},
                new Object[]{"nonZeroStockRows", 1L, 5L},
                new Object[]{"lastShip", "2022-04-16", "2026-08-27"});
        assertEquals(H3SalesProductAnalysisService.SORTABLE_FIELDS.size(), cases.size(),
                "白名单新增/删除列时，此用例的覆盖清单必须同步");
        for (Object[] c : cases) {
            String field = (String) c[0];
            Map<String, Object> low = row("P01", "阿胶盒");
            low.put(field, c[1]);
            Map<String, Object> high = row("P02", "白卡纸盒");
            high.put(field, c[2]);
            assertEquals(List.of("P01", "P02"), sortedCodes(List.of(high, low), field, false),
                    "升序应把较小值排在前面：" + field);
            assertEquals(List.of("P02", "P01"), sortedCodes(List.of(low, high), field, true),
                    "降序应把较大值排在前面：" + field);
        }
    }

    // ====== 汉字为拼音序（实测 JDK21 zh_CN），不是码点序 ======

    @Test
    void chineseNamesFollowPinyinOrder() {
        Comparator<Map<String, Object>> cmp = H3SalesProductAnalysisService.nameComparator();
        Map<String, Object> a = row("P01", "阿胶盒");
        Map<String, Object> b = row("P02", "白卡纸盒");
        Map<String, Object> c = row("P03", "卷膜 宽410mm");
        assertTrue(cmp.compare(a, b) < 0, "a < bai");
        assertTrue(cmp.compare(b, c) < 0, "bai < juan");
        // 码点序下「阿」(U+963F) 会排到最后，这条断言正是用来区分两种实现的
        assertTrue(cmp.compare(c, a) > 0, "juan > a —— 若这里失败说明退化成码点序了");
    }
}
