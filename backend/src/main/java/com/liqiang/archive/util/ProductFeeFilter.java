package com.liqiang.archive.util;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 费用/非产品项口径的单一来源。
 *
 * <p>这些行的「数量」列装的是金额（运费 120813、定制片材 1344071 等），混进库存或合并会虚增库存，
 * 必须过滤。历史上 Java（{@code ProductInventoryMergeService.FEE_CATEGORIES}）与 SQL
 * （{@code ProductInventoryService.FEE_FILTER}）各写一份名单，容易漂移——统一到这里。
 */
public final class ProductFeeFilter {

    private ProductFeeFilter() {}

    /** 费用类目：类目命中即排除。 */
    public static final Set<String> CATEGORIES = Set.of("费用", "制版费", "税点", "运费");

    /** 费用编号兜底：类目为空时使用（实测存在 code=加工费、类目为空的行）。 */
    public static final Set<String> CODES = Set.of(
            "运费", "加工费", "制版费", "税点", "模具费", "打样费", "样品费", "优惠", "加急费", "认证费", "代发运费");

    public static boolean isFeeCategory(String category) {
        return category != null && CATEGORIES.contains(category.trim());
    }

    public static boolean isFeeCode(String code) {
        return code != null && CODES.contains(code.trim());
    }

    /** 类目或编号任一命中即视为费用项。 */
    public static boolean isFee(String category, String code) {
        return isFeeCategory(category) || isFeeCode(code);
    }

    /** 渲染 SQL IN 列表，避免 SQL 侧再抄一份名单。 */
    public static String sqlIn(Set<String> values) {
        return values.stream().sorted().map(v -> "'" + v.replace("'", "''") + "'").collect(Collectors.joining(","));
    }

    public static String categoryInList() {
        return sqlIn(CATEGORIES);
    }

    public static String codeInList() {
        return sqlIn(CODES);
    }
}
