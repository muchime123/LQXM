package com.liqiang.archive.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 氚云出库明细「产品来源键」的唯一口径来源。
 *
 * <p>三处必须同源，否则会出现「写进去、读不出来」的静默故障：销售出库关联工作台 apply 时用 SQL 表达式
 * 算 key 写入 {@code source_identity_aliases}，重导时 {@link com.liqiang.archive.service.SourceIdentityService}
 * 用 Java 算 key 读回。两者一旦不等，人工产品归类结论就无法复用，产品归类静默退回「待归类」——
 * 不报错、不告警。因此 Java 实现 {@link #of} 与 SQL 渲染 {@link #sqlExpr} 必须逐字对齐，
 * 由 {@code ProductSourceKeyContractTest} 钉住。
 *
 * <p>已应用的历史迁移 {@code V275__resolve_outbound_transaction_products.sql} 内含同一段 SQL 的旧副本；
 * 迁移是历史记录，不随本次同源化改动，也不得回改。
 *
 * <p><b>与 MySQL 的语义对齐（一律以 SQL 为准）</b>：source_key 的落库权威路径是工作台 SQL，
 * Java 侧必须复现它。三个容易踩的差异点：<br>
 * ① 只剥离首尾半角空格 U+0020 —— 既不能用 {@link String#trim()}（它连制表符/换行一起去掉），
 * 也不能用 {@link String#isBlank()}（它把全角空格 U+3000 也算空白），而 MySQL {@code TRIM()} 只认 U+0020。<br>
 * ② 内部删除半角空格与全角空格后再折叠小写。<br>
 * ③ 大小写折叠用 {@link Locale#ROOT}，与 MySQL {@code LOWER()} 在 utf8mb4 下对常见字符的行为一致
 * （拉丁字母、全角拉丁字母已实测一致）。
 */
public final class ProductSourceKey {

    /** 无产品编码时来源键的前缀。 */
    public static final String NAME_PREFIX = "NAME|";
    /** 全角空格：SQL 表达式里的 '　'。写成转义，避免源文件编码干扰。 */
    private static final String IDEOGRAPHIC_SPACE = "\u3000";
    private static final char SPACE = ' ';

    private ProductSourceKey() {
    }

    /**
     * 与 MySQL {@code TRIM()} 严格对齐：只剥离首尾半角空格 U+0020。
     * null 归一为 ""（对应 SQL 中每列外层的 {@code COALESCE(x,'')}）。
     */
    public static String sqlTrim(String value) {
        if (value == null) return "";
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) start++;
        while (end > start && value.charAt(end - 1) == SPACE) end--;
        return value.substring(start, end);
    }

    /** 与 SQL 片段 {@code LOWER(REPLACE(REPLACE(TRIM(COALESCE(x,'')),' ',''),'　',''))} 严格对齐。 */
    public static String normalizeComponent(String value) {
        return sqlTrim(value).replace(" ", "").replace(IDEOGRAPHIC_SPACE, "").toLowerCase(Locale.ROOT);
    }

    /**
     * 计算产品来源键：有产品编码时用编码本身，否则用「名称|规格|单位」归一后的 SHA-256。
     *
     * @return 永不为 null
     */
    public static String of(String code, String name, String specification, String unit) {
        String trimmedCode = sqlTrim(code);
        if (!trimmedCode.isEmpty()) return trimmedCode;
        String raw = String.join("|", normalizeComponent(name), normalizeComponent(specification),
                normalizeComponent(unit));
        return NAME_PREFIX + sha256Hex(raw);
    }

    /** 该来源键是否为「无编码」的哈希形态。 */
    public static boolean isNameForm(String sourceKey) {
        return sourceKey != null && sourceKey.startsWith(NAME_PREFIX);
    }

    /**
     * 渲染等价的 SQL 表达式，供工作台分组查询与产品归类回填共用，消除手工复制的第二份 SQL 文本。
     *
     * <p>别名与列名之间**必须**有点号，但拼接时极易漏（`TRIM(i` + `product_code)` 会粘成
     * `TRIM(iproduct_code)` 这种运行期才炸的语法错误）。因此这里接受 `"i"` 或 `"i."` 两种写法，
     * 统一补齐点号，从接口上消灭这个坑。
     *
     * @param tableAlias 表别名，如 {@code "i"} 或 {@code "i."}；无需前缀时传 {@code ""} 或 {@code null}
     */
    public static String sqlExpr(String tableAlias) {
        String prefix = tableAlias == null || tableAlias.isBlank() ? ""
                : tableAlias.endsWith(".") ? tableAlias : tableAlias + ".";
        return "COALESCE(NULLIF(TRIM(" + prefix + "product_code),''),"
                + "CONCAT('" + NAME_PREFIX + "',SHA2(CONCAT_WS('|',"
                + component("product_name", prefix) + ","
                + component("specification", prefix) + ","
                + component("unit", prefix) + "),256)))";
    }

    private static String component(String column, String prefix) {
        return "LOWER(REPLACE(REPLACE(TRIM(COALESCE(" + prefix + column + ",'')),' ',''),'"
                + IDEOGRAPHIC_SPACE + "',''))";
    }

    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }
}
