package com.liqiang.archive.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 台账 Excel 表头指纹校验（fail-fast）。
 *
 * <p>背景：氚云导出为「两行表头」（第 1 行英文字段码、第 2 行中文列名），数据从第 3 行起，
 * 解析用固定列位。一旦上游报表改了列顺序或增删列，解析会**静默错位**——数量读到价格、
 * 名称读到规格之类，看起来一切正常却把脏数据写进台账，事后很难回溯。
 *
 * <p>因此导入前先校验第 2 行中文表头的关键列（列号 + 标签），任一不符即拒绝整批。
 * 只校验「参与业务键与金额计算的列」，允许报表在末尾追加无关列。
 */
public final class LedgerHeaderGuard {

    private LedgerHeaderGuard() {}

    /** 期望的列：index 从 0 起；label 为表头中文名（用 contains 匹配，容忍「产品明细.单位」这种带前缀写法）。 */
    public record Column(int index, String label) {}

    /** 产品信息台账（产品信息_*.xlsx）。 */
    public static final List<Column> PRODUCT_INFO = List.of(
            new Column(0, "产品类目"),
            new Column(1, "产品编号"),
            new Column(2, "产品名称"),
            new Column(3, "规格"),
            new Column(4, "单位"),
            new Column(7, "采购价格"));

    /** 仓库明细台账（仓库明细_*.xlsx）。 */
    public static final List<Column> WAREHOUSE_STOCK = List.of(
            new Column(0, "仓库信息"),
            new Column(1, "产品类目"),
            new Column(2, "产品编号"),
            new Column(3, "产品名称"),
            new Column(4, "规格"),
            new Column(5, "单位"),
            new Column(6, "库存数量"),
            new Column(7, "采购价格"));

    /** 经销商信息台账（经销商信息_*.xlsx）。 */
    public static final List<Column> DEALER = List.of(
            new Column(0, "修改时间"),
            new Column(1, "经销商编号"),
            new Column(3, "经销商名称"),
            new Column(4, "经销商地址"),
            new Column(6, "归属业务员"));

    /**
     * 校验表头指纹；不符时抛 {@link IllegalArgumentException}，消息里列出全部不匹配列便于比对。
     *
     * @param reportName 报表中文名（用于报错文案）
     * @param spec       期望列定义（上面的常量之一）
     * @param reader     按列号读取表头文本（读第 2 行中文表头）
     */
    public static void validate(String reportName, List<Column> spec, IntFunction<String> reader) {
        List<String> problems = new ArrayList<>();
        for (Column column : spec) {
            String actual = reader.apply(column.index());
            String normalized = actual == null ? "" : actual.replace(" ", "").replace("\u3000", "").trim();
            if (!normalized.contains(column.label())) {
                problems.add("第 " + (column.index() + 1) + " 列期望含「" + column.label() + "」，实际为「"
                        + (normalized.isEmpty() ? "空" : normalized) + "」");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("「" + reportName + "」台账表头与预期不符，为避免列位错位已拒绝导入："
                    + String.join("；", problems) + "。请确认导出的是该报表，或报表列顺序已变更（需同步解析列位）。");
        }
    }
}
