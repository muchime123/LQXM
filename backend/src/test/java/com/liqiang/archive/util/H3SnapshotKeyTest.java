package com.liqiang.archive.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 氚云源快照判等键回归。钉住「同一份数据换导出渠道后仍判 UNCHANGED」，
 * 同时钉住「真实变化必须仍判 UPDATE」——后者是防止归一化过度导致漏报的红线。
 *
 * <p>对应故障：{@code source_hash} 被表头空白 / 数值位数 / 日期分隔符差异污染后，
 * 每次重导全量判 UPDATE，幂等永不收敛（见 {@code H3FactImportService#unchanged}）。
 */
class H3SnapshotKeyTest {

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ====== 列顺序无关（原 hashGroup 的 TreeMap 语义必须保持） ======

    @Test
    void columnOrderDoesNotMatter() {
        Map<String, String> a = row("出库单号", "CK001", "客户", "张三", "金额", "100.5");
        Map<String, String> b = row("金额", "100.5", "出库单号", "CK001", "客户", "张三");
        assertEquals(H3SnapshotKey.canonicalize(a), H3SnapshotKey.canonicalize(b));
    }

    // ====== 表头文本归一 ======

    @Test
    void headerInternalWhitespaceIsRemoved() {
        // 流水日记账真实源表头：「客   户    供应商」夹带不规则空白
        assertEquals("客户供应商", H3SnapshotKey.canonicalKey("客   户    供应商"));
        assertEquals("客户供应商", H3SnapshotKey.canonicalKey("客户供应商"));
        // 全角空格 / NBSP / 制表符同处理
        assertEquals("合同编号", H3SnapshotKey.canonicalKey("合同\u3000编\u00a0号"));
        assertEquals("合同编号", H3SnapshotKey.canonicalKey("\t合同编号\n"));
    }

    @Test
    void headerWhitespaceVariantsProduceSameRowKey() {
        Map<String, String> a = row("客   户    供应商", "温州某某", "金额", "10");
        Map<String, String> b = row("客户供应商", "温州某某", "金额", "10.00");
        assertEquals(H3SnapshotKey.canonicalize(a), H3SnapshotKey.canonicalize(b));
    }

    @Test
    void fullWidthHeaderIsFolded() {
        assertEquals("ABC1", H3SnapshotKey.canonicalKey("ＡＢＣ１"));
        assertNull(H3SnapshotKey.canonicalKey("   "));
        assertNull(H3SnapshotKey.canonicalKey(null));
    }

    // ====== 值：数值渲染归一 ======

    @Test
    void numericRenderingVariantsAreEqual() {
        assertEquals("1234.5", H3SnapshotKey.canonicalValue("1234.50"));
        assertEquals("1234.5", H3SnapshotKey.canonicalValue("1234.5"));
        assertEquals("1234.5", H3SnapshotKey.canonicalValue("1,234.5"));
        assertEquals("1234.5", H3SnapshotKey.canonicalValue("１２３４.５０")); // 全角
        assertEquals("1234.5", H3SnapshotKey.canonicalValue(" 1234.5 "));
        assertEquals("0.5", H3SnapshotKey.canonicalValue(".5"));
        assertEquals("0.5", H3SnapshotKey.canonicalValue("0.500"));
        assertEquals("0", H3SnapshotKey.canonicalValue("0.00"));
    }

    @Test
    void largeNumberIsNotRenderedInScientificNotation() {
        // BigDecimal.stripTrailingZeros() 会把 1000 变成 1E+3，必须用 toPlainString 抵消
        assertEquals("1000", H3SnapshotKey.canonicalValue("1000"));
        assertEquals("1000000", H3SnapshotKey.canonicalValue("1000000.00"));
    }

    @Test
    void nonNumericTextStaysText() {
        assertNull(H3SnapshotKey.canonicalNumber("R2026-08-31"));
        assertEquals("R2026-08-31", H3SnapshotKey.canonicalValue("R2026-08-31"));
        assertNull(H3SnapshotKey.canonicalNumber("1,23"));   // 非标准千分位 → 不进数字分支
        assertEquals("1,23", H3SnapshotKey.canonicalValue("1,23"));
    }

    // ====== 值：日期渲染归一 ======

    @Test
    void dateSeparatorAndZeroTimeVariantsAreEqual() {
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026-08-31"));
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026/8/31"));
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026.8.31"));
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026年8月31日"));
        // 纯日期字段在部分导出里带无意义零点时间戳
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026-08-31 00:00:00"));
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026-08-31 00:00"));
        assertEquals("2026-08-31", H3SnapshotKey.canonicalValue("2026-08-31T00:00:00.000"));
    }

    @Test
    void realTimeIsKeptAndSecondsArePadded() {
        assertEquals("2026-08-31T10:30:00", H3SnapshotKey.canonicalValue("2026-08-31 10:30:00"));
        assertEquals("2026-08-31T10:30:00", H3SnapshotKey.canonicalValue("2026-08-31 10:30"));
        // Java Timestamp.toString() 会带 .0 小数位
        assertEquals("2026-08-31T10:30:00", H3SnapshotKey.canonicalValue("2026-08-31 10:30:00.0"));
        // 有时间的日期不得被折叠成纯日期
        assertNotEquals(
                H3SnapshotKey.canonicalValue("2026-08-31"),
                H3SnapshotKey.canonicalValue("2026-08-31 10:30:00"));
    }

    @Test
    void illegalDateShapeStaysText() {
        assertEquals("2026-13-45", H3SnapshotKey.canonicalValue("2026-13-45")); // 越界不认成日期
        assertEquals("45900", H3SnapshotKey.canonicalValue("45900"));            // 五位整数不推断序列号
    }

    // ====== 空值处理：新增空列不得改变判等键 ======

    @Test
    void blankAndDashValuesAreDropped() {
        Map<String, String> a = row("出库单号", "CK001", "备注", "张三");
        Map<String, String> b = row("出库单号", "CK001", "备注", "张三", "新字段", "");
        Map<String, String> c = row("出库单号", "CK001", "备注", "张三", "新字段", "-");
        Map<String, String> d = row("出库单号", "CK001", "备注", "张三", "新字段", "   ");
        assertEquals(H3SnapshotKey.canonicalize(a), H3SnapshotKey.canonicalize(b));
        assertEquals(H3SnapshotKey.canonicalize(a), H3SnapshotKey.canonicalize(c));
        assertEquals(H3SnapshotKey.canonicalize(a), H3SnapshotKey.canonicalize(d));
        assertFalse(H3SnapshotKey.canonicalize(b).containsKey("新字段"));
    }

    @Test
    void canonicalValueDropsBlank() {
        assertNull(H3SnapshotKey.canonicalValue(null));
        assertNull(H3SnapshotKey.canonicalValue(""));
        assertNull(H3SnapshotKey.canonicalValue("   "));
        assertNull(H3SnapshotKey.canonicalValue("-"));
        assertNull(H3SnapshotKey.canonicalValue("\u3000"));
    }

    // ====== 红线：真实变化必须仍判为变化 ======

    @Test
    void realBusinessChangeIsStillDetected() {
        Map<String, String> before = row("出库单号", "CK001", "金额", "100.5", "客户", "张三");
        Map<String, String> after = row("出库单号", "CK001", "金额", "100.6", "客户", "张三");
        assertNotEquals(H3SnapshotKey.canonicalize(before), H3SnapshotKey.canonicalize(after));

        assertNotEquals(
                H3SnapshotKey.canonicalize(row("客户", "张三")),
                H3SnapshotKey.canonicalize(row("客户", "李四")));
        // 值从空变成有值 → 必须报变化（空值被丢弃，键集合变了）
        assertNotEquals(
                H3SnapshotKey.canonicalize(row("客户", "张三")),
                H3SnapshotKey.canonicalize(row("客户", "张三", "备注", "已开票")));
        // 数值 100 与 100.5 不能被误判相等
        assertNotEquals(H3SnapshotKey.canonicalValue("100"), H3SnapshotKey.canonicalValue("100.5"));
    }

    @Test
    void valueInternalWhitespaceIsDeliberatelyNotCollapsed() {
        // 刻意保守：归一化过头会造成「假 UNCHANGED」（真实改名被吞掉），宁可多报一次 UPDATE
        assertNotEquals(
                H3SnapshotKey.canonicalValue("张  三"),
                H3SnapshotKey.canonicalValue("张三"));
    }

    // ====== 幂等：两轮导入必须收敛 ======

    @Test
    void canonicalizeIsIdempotent() {
        Map<String, String> raw = row(
                "客   户    供应商", "温州某某",
                "金额", "1,234.50",
                "日期", "2026/8/31",
                "空列", "-",
                "时间", "2026-08-31 10:30:00.0");
        Map<String, String> once = H3SnapshotKey.canonicalize(raw);
        Map<String, String> twice = H3SnapshotKey.canonicalize(once);
        assertEquals(once, twice);
        assertEquals("1234.5", once.get("金额"));
        assertEquals("2026-08-31", once.get("日期"));
        assertEquals("2026-08-31T10:30:00", once.get("时间"));
    }

    // ====== 归一撞键的确定性（不得依赖 Map 迭代顺序） ======

    @Test
    void collidingCanonicalKeysResolveDeterministically() {
        Map<String, String> a = row("报 工人", "甲", "报工人", "乙");
        Map<String, String> b = row("报工人", "乙", "报 工人", "甲");
        Map<String, String> ca = H3SnapshotKey.canonicalize(a);
        Map<String, String> cb = H3SnapshotKey.canonicalize(b);
        assertEquals(ca, cb);
        assertEquals(1, ca.size());
        assertTrue(ca.containsKey("报工人"));
    }

    // ====== 子表行序保持敏感（行序是业务事实） ======

    @Test
    void rowOrderIsPreservedByCaller() {
        Map<String, String> r1 = H3SnapshotKey.canonicalize(row("产品", "A", "数量", "1"));
        Map<String, String> r2 = H3SnapshotKey.canonicalize(row("产品", "B", "数量", "2"));
        assertNotEquals(java.util.List.of(r1, r2), java.util.List.of(r2, r1));
    }
}
