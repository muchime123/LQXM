package com.liqiang.archive.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 氚云历史台账的排序契约（2026-10-08）。
 *
 * <p><b>为什么需要它</b>：{@code /api/h3-imports/ledger/{kind}} 的 20 类台账原先<b>一律</b>
 * {@code ORDER BY id DESC}。id 只随「导入批次 + 该批内 Excel 行序」增长，与业务时间、单号毫无关系，
 * 于是同一天的出库散落在不同批次里、列表看着乱，而且一张表都没有排序入口。
 *
 * <p><b>契约</b>：每类台账声明一份<b>白名单</b>（可排序键 → SQL 表达式）+ 一个默认序。
 * 前端只传 {@code sort}（键）与 {@code direction}（方向），服务端渲染 ORDER BY；
 * 非白名单键<b>静默回落默认序</b>（前端持有的值可能已过期，不抛错）。
 *
 * <p><b>方向语义</b>：方向只作用于主键，兜底键方向固定为 DESC —— 与本项目其余列表排序的口径一致。
 * 注意不要写成 {@code cmp.reversed()} 那种「把兜底链一起翻转」的写法：
 * 那会让「按金额降序」时同金额的行反而按编号升序排，每次加一列都要重新解释业务含义。
 */
final class LedgerSort {

    /**
     * 通用排序键。各台账按自身列情况取用，没有对应列的键不要暴露。
     * <p>键名是前后端契约的一部分，前端 SORT_KEYS 必须与之一致。
     */
    static final String TIME = "time";     // 业务时间（默认键）
    static final String CODE = "code";     // 单号 / 编号 / 月份
    static final String AMOUNT = "amount"; // 金额
    static final String NAME = "name";     // 往来单位名称（客户/供应商/经销商/账户）

    private LedgerSort() {}

    /**
     * 一类台账的排序白名单。
     *
     * @param keys         可排序键 → SQL 表达式（不含方向）
     * @param defaultOrder 默认序（不含前导 {@code ORDER BY}）——不传 sort 或 sort 不在白名单时使用
     * @param tieBreak     兜底键表达式（通常主表 id）；显式排序时固定追加 {@code ,<tieBreak> DESC}
     */
    record Spec(Map<String, String> keys, String defaultOrder, String tieBreak) {
        Spec {
            keys = Map.copyOf(keys);
        }

        /** 便捷构造：{@code kv} 为「键, 表达式」交替的变长参数。 */
        static Spec of(String defaultOrder, String tieBreak, String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return new Spec(m, defaultOrder, tieBreak);
        }
    }

    /** 该台账声明的可排序键（集合语义），供契约测试与文档对齐。 */
    static List<String> sortableKeys(Spec spec) {
        return List.copyOf(spec.keys().keySet());
    }

    /** sort 是否为该台账的白名单键。 */
    static boolean accepts(Spec spec, String sort) {
        return sort != null && spec.keys().containsKey(sort.trim());
    }

    /**
     * 渲染 ORDER BY 子句（含前导空格）。
     *
     * <p>{@code sort} 为空或不在白名单 → 回落 {@code defaultOrder}；
     * 否则用该键的表达式 + 方向，并固定追加兜底键 {@code DESC}。
     * 方向判定：仅 {@code asc}（忽略大小写/空白）为升序，其余一律降序。
     */
    static String orderBy(Spec spec, String sort, String direction) {
        String key = sort == null ? "" : sort.trim();
        String expr = spec.keys().get(key);
        if (expr == null) return " ORDER BY " + spec.defaultOrder();
        boolean asc = "asc".equalsIgnoreCase(direction == null ? "" : direction.trim());
        return " ORDER BY " + expr + (asc ? " ASC" : " DESC") + "," + spec.tieBreak() + " DESC";
    }
}
