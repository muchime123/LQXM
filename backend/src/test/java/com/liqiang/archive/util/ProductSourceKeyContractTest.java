package com.liqiang.archive.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产品来源键的口径契约测试。
 *
 * <p>钉住的是一处「写进去、读不出来」的静默故障面：销售出库关联工作台用 SQL 表达式算 key 写入
 * {@code source_identity_aliases}，重导时服务端用 Java 算 key 读回。两者一旦不等，人工的产品归类结论
 * 无法复用，产品归类静默退回「待归类」——不报错、不告警，只有人发现「明明归类过又没了」才暴露。
 *
 * <p><b>期望值来源</b>：2026-09-16 用真库 harness 对 30414 条真实出库明细行做两侧比对（当时全一致），
 * 再用 14 条构造边界输入定位出 4 个真实差异点。本测试固化比对结论，口径一律以 SQL 为准
 * （source_key 的落库权威路径是工作台 SQL）。
 *
 * <p>全角空格在源码里写成 {@code \u3000} 转义，避免源文件编码差异把测试悄悄改味。
 */
class ProductSourceKeyContractTest {

    private static final String IDEOGRAPHIC_SPACE = "\u3000";

    // ===== 形态契约 =====

    @Test
    void codeTakesPrecedenceAndOnlyStripsAsciiSpace() {
        assertEquals("P001", ProductSourceKey.of("P001", "产品A", "规格1", "台"));
        // 首尾半角空格被剥离（SQL 的 TRIM 同样只认 U+0020）
        assertEquals("P001", ProductSourceKey.of("  P001  ", "产品A", "规格1", "台"));
        // 有编码时名称/规格/单位完全不参与，改它们不影响 key
        assertEquals(ProductSourceKey.of("P001", "甲", "乙", "丙"),
                ProductSourceKey.of("P001", "产品A", "规格1", "台"));
    }

    @Test
    void noCodeFallsBackToHashedNameForm() {
        String key = ProductSourceKey.of(null, "产品A", "规格1", "台");
        assertTrue(ProductSourceKey.isNameForm(key));
        assertEquals(ProductSourceKey.NAME_PREFIX + ProductSourceKey.sha256Hex("产品a|规格1|台"), key);
        // 哈希体固定 64 位小写 hex
        String hex = key.substring(ProductSourceKey.NAME_PREFIX.length());
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]{64}"));
    }

    @Test
    void blankCodeIsTreatedAsMissingOnlyForPureAsciiSpace() {
        // 纯半角空格：SQL 侧 NULLIF(TRIM(x),'') 判为空 → 走哈希
        assertTrue(ProductSourceKey.isNameForm(ProductSourceKey.of("   ", "产品A", null, null)));
        assertTrue(ProductSourceKey.isNameForm(ProductSourceKey.of(null, "产品A", null, null)));
        assertTrue(ProductSourceKey.isNameForm(ProductSourceKey.of("", "产品A", null, null)));
    }

    // ===== 边界契约：以 SQL 为准（Java 侧曾在此处与 SQL 不一致）=====

    /**
     * 编码本身是「全角空格」或「制表符」时，MySQL 的 TRIM() 不会把它清掉，
     * 于是 SQL 侧把这个字符**当作编码原样返回**。Java 侧历史上的 isBlank()/trim() 会判为空而改走哈希，
     * 两侧 key 因此不同 —— 这类脏编码一旦存在，工作台写的结论服务端永远读不回来。
     */
    @Test
    void whitespaceCodeIsReturnedAsIsInsteadOfHashing() {
        String ideographic = ProductSourceKey.of(IDEOGRAPHIC_SPACE, "产品A", null, null);
        assertEquals(IDEOGRAPHIC_SPACE, ideographic);
        assertFalse(ProductSourceKey.isNameForm(ideographic));

        String tab = ProductSourceKey.of("\t", "产品A", null, null);
        assertEquals("\t", tab);
        assertFalse(ProductSourceKey.isNameForm(tab));
    }

    /**
     * 名称/规格/单位首尾的制表符与换行**保留在哈希输入里**（SQL 的 TRIM 只去 U+0020）。
     * Excel 单元格软换行（Alt+Enter）导出后就长这样，不是纯理论场景。
     */
    @Test
    void leadingTrailingNonSpaceWhitespaceStaysInHashInput() {
        String plain = ProductSourceKey.of(null, "产品A", "", "");
        assertNotEquals(plain, ProductSourceKey.of(null, "\t产品A", "", ""));
        assertNotEquals(plain, ProductSourceKey.of(null, "产品A\n", "", ""));
        assertEquals(ProductSourceKey.sha256Hex("\t产品a||"),
                ProductSourceKey.of(null, "\t产品A", "", "").substring(ProductSourceKey.NAME_PREFIX.length()));
        assertEquals(ProductSourceKey.sha256Hex("产品a\n||"),
                ProductSourceKey.of(null, "产品A\n", "", "").substring(ProductSourceKey.NAME_PREFIX.length()));
    }

    /** 内部（非首尾）的半角与全角空格一律删除，只剩余内部空白不影响 key。 */
    @Test
    void innerSpacesAreRemovedInBothWidths() {
        assertEquals(ProductSourceKey.of(null, "产品A", "", ""),
                ProductSourceKey.of(null, "产品 A", "", ""));
        assertEquals(ProductSourceKey.of(null, "产品A", "", ""),
                ProductSourceKey.of(null, "产品" + IDEOGRAPHIC_SPACE + "A", "", ""));
        assertEquals(ProductSourceKey.of(null, "产品A", "", ""),
                ProductSourceKey.of(null, IDEOGRAPHIC_SPACE + "产品A" + IDEOGRAPHIC_SPACE, "", ""));
    }

    /** 大小写折叠对 ASCII 与全角拉丁字母都与 MySQL LOWER() 一致（已实测）。 */
    @Test
    void caseFoldsForAsciiAndFullwidth() {
        assertEquals(ProductSourceKey.of(null, "Product Name", "Spec", "KG"),
                ProductSourceKey.of(null, "product name", "spec", "kg"));
        assertEquals(ProductSourceKey.of(null, "ＡＢＣ", "", ""),
                ProductSourceKey.of(null, "ａｂｃ", "", ""));
    }

    @Test
    void nullAndBlankInputsNeverThrow() {
        assertTrue(ProductSourceKey.isNameForm(ProductSourceKey.of(null, null, null, null)));
        assertTrue(ProductSourceKey.isNameForm(ProductSourceKey.of("", null, null, null)));
        assertEquals(ProductSourceKey.of(null, null, null, null),
                ProductSourceKey.of(null, "", "  ", IDEOGRAPHIC_SPACE));
        assertFalse(ProductSourceKey.isNameForm(null));
    }

    // ===== SQL 渲染契约 =====

    /**
     * 固定 SQL 文本。真库 harness 已验证这段表达式与 {@link ProductSourceKey#of} 在 14 条边界输入上一致
     * （改前有 4 条不一致，本测试随同源化一起落地）。
     * <p>谁改动这段 SQL 就必须同步 {@code of} 的实现，否则测试红 —— 这是防「两份口径各自演化」的护栏。
     */
    @Test
    void sqlExpressionIsPinnedAndMatchesJavaSemantics() {
        String expected = "COALESCE(NULLIF(TRIM(i.product_code),''),"
                + "CONCAT('NAME|',SHA2(CONCAT_WS('|',"
                + "LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.product_name,'')),' ',''),'" + IDEOGRAPHIC_SPACE + "','')),"
                + "LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.specification,'')),' ',''),'" + IDEOGRAPHIC_SPACE + "','')),"
                + "LOWER(REPLACE(REPLACE(TRIM(COALESCE(i.unit,'')),' ',''),'" + IDEOGRAPHIC_SPACE + "',''))"
                + "),256)))";
        assertEquals(expected, ProductSourceKey.sqlExpr("i"));
    }

    /** 前缀参数化：空串前缀用于 UPDATE 语句（无别名），"i" 用于 JOIN 分组查询。 */
    @Test
    void sqlExpressionHonoursTablePrefix() {
        assertTrue(ProductSourceKey.sqlExpr("").contains("TRIM(product_code)"));
        assertTrue(ProductSourceKey.sqlExpr("").contains("COALESCE(product_name,'')"));
        // 别名带不带点号都渲染成带点号 —— 防 "i" + "product_code" 粘成 "iproduct_code"（运行期才炸）
        assertEquals(ProductSourceKey.sqlExpr("i."), ProductSourceKey.sqlExpr("i"));
        assertTrue(ProductSourceKey.sqlExpr("i").contains("TRIM(i.product_code)"));
        assertTrue(ProductSourceKey.sqlExpr("i").contains("COALESCE(i.product_name,'')"));
        assertFalse(ProductSourceKey.sqlExpr("i").contains("iproduct_code"));
        assertEquals(ProductSourceKey.sqlExpr(""), ProductSourceKey.sqlExpr(null));
    }

    /**
     * 防回退：两处 SQL 站点都必须派生自 util，不得再出现手工复制的第二份副本。
     * 历史上它们正是各自演化出了与 Java 不同的语义。
     */
    @Test
    void bothSqlSitesDeriveFromThisUtil() throws Exception {
        var field = Class.forName("com.liqiang.archive.service.OutboundRelationGovernanceService")
                .getDeclaredField("PRODUCT_KEY_EXPR");
        field.setAccessible(true);
        assertEquals(ProductSourceKey.sqlExpr("i"), field.get(null));

        // SourceIdentityService 不应再保留产品来源键的私有实现
        assertThrows(NoSuchMethodException.class, () -> Class
                .forName("com.liqiang.archive.service.SourceIdentityService")
                .getDeclaredMethod("productSourceKey", String.class, String.class, String.class, String.class));
    }
}
