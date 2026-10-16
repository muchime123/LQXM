package com.liqiang.archive.util;

/**
 * 氚云台账匹配键：<b>产品名称优先、产品编号兜底</b>的唯一口径来源
 * （2026-10-07 LQ 拍板 + 真库取证）。
 *
 * <p><b>为什么不能只按编号匹配</b>：氚云系统里「仓库明细」与「产品信息」是<b>按产品名称关联</b>
 * 的；{@code product_code} 只是一个手工填写的文本字段，没有关联校验，写错不影响氚云自身的
 * 业务流转。真库实测（{@code product_archive}，2026-10-07）：
 * <ul>
 *   <li>296 行仓库明细的编号在产品信息里<b>查不到</b>，但名称 <b>100% 查得到</b>（这 296 行数量全为 0）；</li>
 *   <li>55 行编号对上了却指向<b>完全不同的产品</b>（撞号），其中名称另有正确编号的 29 行；</li>
 *   <li>编号归一化（去空格/横杠/点、全角转半角、大写）后<b>多恢复 0 行</b> —— 差异不是写法问题，是真错字；</li>
 *   <li>错法无规律：{@code 400QZDQTJ}→{@code 399QZDQTJ}、{@code 340QZDQTJ}→{@code 400QZDQTJ}、
 *       {@code 1436YX}→{@code 1439YX}、{@code YY178}↔{@code 178YY}，偏移量 +1/+3/+8/+60 都有。</li>
 * </ul>
 *
 * <p><b>匹配规则</b>（{@link #UNIQUE_NAME_CODE} 与 {@link #matchCode}）：
 * <ol>
 *   <li>名称归一化后在产品信息里<b>唯一</b>命中 → 取该名称对应的编号；</li>
 *   <li>名称重名（歧义，实测 139 个名称）或查不到 → <b>退回按编号匹配</b>（拿不准就不猜）；</li>
 *   <li>两者都没有 → 该行在产品信息侧没有定义，页面照常显示为「缺产品信息」。</li>
 * </ol>
 *
 * <p><b>实测收益与安全性</b>：产品库存清单 17,111 → 16,806 行（消 305 行「同一产品出现两次」）；
 * 323 行编号被改写；<b>非零库存的 1,697 行编号完全不变</b>（真有货的行编号本来就是对的）；
 * 同一实体被两个不同名称认领（误合并）<b>0 例</b>；异常统计（缺产品 / 缺价）不变。
 *
 * <p>⚠️ 氚云只读（决策 {@code #23}）：本类只影响 ERP 侧的匹配结论，<b>不回写、不订正氚云的编号</b>。
 * 编号确实写错的行由页面标记（{@code codeConflict}）提示人工核对。
 *
 * <p>⚠️ 调用方：{@code ProductInventoryService}（清单视图 / 异常行视图 / 快照陈旧抽样）与
 * {@code H3SalesProductAnalysisService}（库存段）<b>必须共用本类</b>，否则两页库存口径会漂移。
 */
public final class H3LedgerMatchKey {

    /** 全角空格 U+3000（氚云导出的名称里真实存在）。 */
    private static final String IDEOGRAPHIC_SPACE = "\u3000";

    private H3LedgerMatchKey() {
    }

    /**
     * 名称归一化的 SQL 表达式：去首尾半角空格 → 去内部空白（半角 + 全角）→ 转小写。
     *
     * <p>刻意<b>不做</b>字形以外的处理：不改全半角括号、不删标点、不剥尺寸单位 ——
     * 名称在这里是「人工录入的原文」，任何聪明规则都会让它与真实录入脱钩。
     *
     * @param nameExpr 名称列或表达式（不含引号），如 {@code ws.product_name}
     */
    public static String nameKey(String nameExpr) {
        return "LOWER(REPLACE(REPLACE(TRIM(" + nameExpr + "),' ',''),'" + IDEOGRAPHIC_SPACE + "',''))";
    }

    /**
     * 「名称 → 编号」映射派生表：<b>只收录产品信息里名称唯一的条目</b>。
     *
     * <p>{@code HAVING COUNT(DISTINCT TRIM(product_code))=1} 是关键：重名名称（实测 139 个，
     * 涉及 299 个编号）不进这张表，于是它们自然退回按编号匹配 —— 这是「拿不准就不猜」的落地点。
     */
    public static final String UNIQUE_NAME_CODE =
            "(SELECT " + nameKey("product_name") + " k,MIN(TRIM(product_code)) code" +
            " FROM h3_product_catalog" +
            " WHERE TRIM(COALESCE(product_name,''))<>''" +
            " GROUP BY 1 HAVING COUNT(DISTINCT TRIM(product_code))=1)";

    /**
     * 一行的实体编号：名称唯一命中时取产品信息侧编号，否则<b>原样保留本行编号</b>。
     *
     * @param codeExpr 行自身的编号表达式（不含引号）
     * @param nmAlias  {@link #UNIQUE_NAME_CODE} 在本查询里的别名
     */
    public static String matchCode(String codeExpr, String nmAlias) {
        return "COALESCE(" + nmAlias + ".code,TRIM(" + codeExpr + "))";
    }

    /** 连接 {@link #UNIQUE_NAME_CODE} 的 ON 条件（供各调用方拼 JOIN）。 */
    public static String nameJoinOn(String nmAlias, String nameExpr) {
        return nmAlias + ".k=" + nameKey(nameExpr);
    }

    /**
     * 「该编号已被仓库明细行按名称认领」的排除子查询，用于产品库存清单第二段
     * （产品信息独有行）：编号不在仓库明细里、<b>且没有被任何明细行的名称认领</b>时，才补一行。
     *
     * <p>⚠️ 判据必须是「<b>这个编号被认领</b>」，不能简化成「仓库明细里存在同名行」——
     * 后者会把重名名称对应的产品信息行一并误排除（实测多排 28 行，如 {@code 模具120} /
     * {@code 1733YX}，它们的名称在明细里也有，但那份明细并不能确定指向本编号）。
     *
     * @param codeExpr 产品信息侧编号表达式（不含引号）
     */
    public static String notClaimed(String codeExpr) {
        return "NOT EXISTS (SELECT 1 FROM h3_warehouse_stock s2" +
               " JOIN " + UNIQUE_NAME_CODE + " n2 ON n2.k=" + nameKey("s2.product_name") +
               " WHERE n2.code=TRIM(" + codeExpr + "))";
    }

    /**
     * 「编号不可信」判据：本行氚云录入的编号，与它<b>名称所指产品</b>的编号不一致时置 1。
     *
     * <p>两种成因都要抓：
     * <ol>
     *   <li>{@code 原编号 <> match_code} —— 编号写错但名称把产品救回来了（296 行 + 撞号可救的 29 行）；</li>
     *   <li>{@code match_code = 原编号}（名称歧义/查不到，没能救回）但该编号在产品信息里
     *       另有所指（名称不同）—— 即编号撞号（55 行里的剩余部分）。</li>
     * </ol>
     *
     * @param codeExpr      行自身编号表达式（不含引号）
     * @param nameExpr      行自身名称表达式（不含引号）
     * @param matchCodeExpr 已解析好的实体编号表达式（外层用派生表列 {@code v.match_code}，
     *                      内层用 {@link #matchCode}）
     * @param pcAlias       已按实体编号关联好的产品信息行别名
     */
    public static String codeConflict(String codeExpr, String nameExpr, String matchCodeExpr, String pcAlias) {
        return "CASE WHEN TRIM(" + codeExpr + ")<>" + matchCodeExpr +
               " OR (" + pcAlias + ".product_code IS NOT NULL" +
               " AND LOWER(CONVERT(TRIM(" + pcAlias + ".product_name) USING utf8mb4))" +
               "<>LOWER(CONVERT(TRIM(COALESCE(" + nameExpr + ",'')) USING utf8mb4)))" +
               " THEN 1 ELSE 0 END";
    }
}
