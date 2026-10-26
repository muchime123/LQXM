package com.liqiang.archive.service;

import com.liqiang.archive.util.H3LedgerMatchKey;
import com.liqiang.archive.util.ProductFeeFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 销售出库产品分析的<b>公共口径契约</b>：费用判据单一来源 + 出库日期区间的语义。
 *
 * <p><b>费用判据</b>：本页的公共过滤片段 {@code BASE} 曾经自己写了一份费用名单
 * （{@code category NOT LIKE '%费用%'} + 11 个编号字面量），而不是走项目唯一实现
 * {@link ProductFeeFilter}。两套判据边界不同 —— 类目为「运费」但编号不是费用名的行
 * （实测 8 行：开模费 7 + 定制片材 1）被静默漏进产品统计，混进销售额与出库单数。
 *
 * <p><b>日期区间</b>：{@code BASE} 上加区间后必须仍是「同一个口径 + 一个时间限制」，
 * 两端为空时要与原口径<b>逐字相同</b>，否则「全部历史」的结果会和加时间维度之前不一致。
 * 入参是拼进 SQL 的，因此只接受 {@code yyyy-MM-dd} 白名单格式。
 *
 * <p>启用方式：真库断言需要 {@code -Dmain.mysql=true}，否则静默跳过。
 * {@code mvn -o test -Dtest=H3SalesProductAnalysisPolicyTest -Dmain.mysql=true}
 */
class H3SalesProductAnalysisPolicyTest {

    /**
     * 2026-10-20 实测基线（出库数据重导后刷新）。数据重导后需刷新这组常量。
     *
     * <p>组合数是<b>名称优先解析后</b>的口径：按氚云录入的原编号分组更少，按实体编号（名称优先、
     * 编号兜底）是 {@value #ALL_COMBINATIONS}。行数两种口径都是 {@value #ALL_LINE_COUNT}（归并只改
     * 归属，不增不减明细行）。
     *
     * <p>⚠️ 刷新记录（10-07 → 10-20）：
     * <pre>
     *   ALL_LINE_COUNT        171,367 → 177,893（+6,526）
     *   Y2026_LINE_COUNT       26,472 →  32,998（+6,526）  ← 两口径增量相同=同一次重导，非口径漂移
     *   ALL_COMBINATIONS       12,737 →  13,268（+531）
     *   Y2026_COMBINATIONS      3,975 →   4,628（+653）
     * </pre>
     * 判据：两个日期口径的行数增量必须一致；若不一致，是明细口径出问题，要查代码而不是改常量。
     */
    private static final long ALL_LINE_COUNT = 177_893L;
    private static final int ALL_COMBINATIONS = 13_268;
    /** 按原编号分组时的组合数（口径对照，仅用于断言两者确实不同）。 */
    private static final int OLD_COMBINATIONS_BY_RAW_CODE = 12_611;
    private static final long Y2026_LINE_COUNT = 32_998L;
    private static final int Y2026_COMBINATIONS = 4_628;

    // ===== 第一层：口径单一来源（无数据库依赖，永远运行） =====

    @Test
    void baseSqlRendersFeeFilterFromSingleSource() {
        String base = H3SalesProductAnalysisService.BASE;

        assertTrue(base.contains(ProductFeeFilter.categoryInList()),
                "BASE 的类目判据必须直接引用 ProductFeeFilter.categoryInList()：" + base);
        assertTrue(base.contains(ProductFeeFilter.codeInList()),
                "BASE 的编号判据必须直接引用 ProductFeeFilter.codeInList()：" + base);
    }

    @Test
    void baseSqlKeepsNoHandWrittenFeeList() {
        String base = H3SalesProductAnalysisService.BASE;

        assertFalse(base.contains("NOT LIKE '%费用%'"),
                "禁止在 BASE 里另写「类目含费用」判据——那正是漏掉「类目=运费」的原因");
        assertFalse(base.contains("'开模费'") || base.contains("'定制片材'"),
                "禁止在 BASE 里手写费用编号字面量，判据只能来自 ProductFeeFilter");
    }

    @Test
    void baseWithoutDateRangeIsCharacterForCharacterTheOldPolicy() {
        assertEquals(H3SalesProductAnalysisService.BASE, H3SalesProductAnalysisService.base("", ""),
                "日期两端都为空即「全部历史」，必须与加时间维度之前的口径逐字相同");
    }

    @Test
    void baseAppendsHalfOpenDateBounds() {
        String bounded = H3SalesProductAnalysisService.base("2026-01-01", "2026-12-31");

        assertTrue(bounded.startsWith(H3SalesProductAnalysisService.BASE),
                "日期条件只能是在原口径后追加的限制，不能改写原条件");
        assertTrue(bounded.contains("o.ship_date>='2026-01-01'"));
        assertTrue(bounded.contains("DATE_ADD('2026-12-31',INTERVAL 1 DAY)"),
                "结束日必须用半开区间，否则 ship_date 改成 DATETIME 后当天数据会整批漏掉");
    }

    @Test
    void dateBoundsAreValidatedAgainstIsoWhitelist() {
        assertEquals("2026-01-01", H3SalesProductAnalysisService.normDate(" 2026-01-01 "));
        assertEquals("", H3SalesProductAnalysisService.normDate(null));
        assertEquals("", H3SalesProductAnalysisService.normDate("   "));
        assertThrows(IllegalArgumentException.class,
                () -> H3SalesProductAnalysisService.normDate("2026/01/01"), "只接受 yyyy-MM-dd");
        assertThrows(IllegalArgumentException.class,
                () -> H3SalesProductAnalysisService.normDate("2026-01-01' OR 1=1 --"),
                "日期是拼进 SQL 的，注入形状必须被格式白名单拒掉");
    }

    // ===== 第一层：出库明细的「名称优先、编号兜底」口径 =====

    @Test
    void itemsAreGroupedByResolvedCodeNotByRawOutboundCode() {
        String sql = H3SalesProductAnalysisService.itemsSql("", "");

        assertTrue(sql.contains(H3LedgerMatchKey.UNIQUE_NAME_CODE),
                "明细查询必须连上「名称 → 权威编号」映射表：" + sql);
        assertTrue(sql.contains(H3LedgerMatchKey.nameJoinOn("nm", "i.product_name")),
                "必须按出库明细的产品名称关联（氚云本来就是按名称关联的）");
        assertTrue(sql.contains("COALESCE(nm.code,TRIM(i.product_code))"),
                "实体编号 = 名称优先、编号兜底");
        assertFalse(sql.contains("GROUP BY TRIM(i.product_code)"),
                "不得再按氚云录入的原编号分组——那是把同一产品拆成多行的根因");
        assertTrue(sql.contains("codeRewritten") && sql.contains("rawCodes"),
                "必须保留「编号被改写」标记与原始录入编号，供页面标记与按原编号搜索");
    }

    @Test
    void itemsSqlWithoutDateRangeIsThePlainBaseWhere() {
        String sql = H3SalesProductAnalysisService.itemsSql("", "");

        assertTrue(sql.contains(H3SalesProductAnalysisService.BASE_WHERE),
                "日期为空时 WHERE 必须与 BASE_WHERE 逐字相同，否则「全部历史」口径会漂移");
        assertTrue(sql.startsWith("SELECT "), "明细 SQL 必须以 SELECT 开头");
    }

    // ===== 第二层：真库不变量 =====

    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void analyzeNeverEmitsFeeCategoryRows() throws Exception {
        Map<String, Object> result = service().analyze("", "ALL", "", "", false, "", "", "", "", 1, 200);

        List<Map<String, Object>> categories = castList(result.get("categories"));
        assertFalse(categories.isEmpty(), "真库应能汇总出类目清单");

        // ① 费用类目一行都不许出现（这是「类目=运费」8 行漏网的反例断言）
        for (Map<String, Object> row : categories) {
            String category = String.valueOf(row.get("category"));
            assertFalse(ProductFeeFilter.isFeeCategory(category),
                    "费用类目不得出现在分析结果，实际残留：" + row);
        }

        // ② 两条查询（类目汇总 / 明细分组）必须共用同一份口径
        long lines = categories.stream().mapToLong(r -> ((Number) r.get("lineCount")).longValue()).sum();
        assertEquals(ALL_LINE_COUNT, lines, "BASE 口径行数基线，数据重导后需刷新");
        assertEquals(ALL_COMBINATIONS, ((Number) result.get("total")).intValue(),
                "编号×类目组合数基线，数据重导后需刷新");

        System.out.println("[销售出库产品分析] 类目数=" + categories.size() + " 口径行数=" + lines
                + " 组合数=" + result.get("total") + " summary=" + result.get("summary"));
    }

    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void dateRangeNarrowsTheSamePolicyAndSwapsReversedBounds() throws Exception {
        Map<String, Object> y2026 = service().analyze("", "ALL", "", "", false, "", "",
                "2026-01-01", "2026-12-31", 1, 200);

        long lines = castList(y2026.get("categories")).stream()
                .mapToLong(r -> ((Number) r.get("lineCount")).longValue()).sum();
        assertEquals(Y2026_LINE_COUNT, lines, "2026 年口径行数基线，数据重导后需刷新");
        assertEquals(Y2026_COMBINATIONS, ((Number) y2026.get("total")).intValue(),
                "2026 年编号×类目组合数基线，数据重导后需刷新");

        // ① 区间是「限制」而不是「过滤明细」：明细里每一行的出库日都必须落在区间内
        for (Map<String, Object> item : castList(y2026.get("items"))) {
            assertTrue(iso(item.get("firstShip")).compareTo("2026-01-01") >= 0,
                    "区间内行的首次出库日不得早于起始日：" + item.get("productCode"));
            assertTrue(iso(item.get("lastShip")).compareTo("2026-12-31") <= 0,
                    "区间内行的末次出库日不得晚于结束日（结束日当天必须含在内）：" + item.get("productCode"));
        }

        // ② 区间反向时自动交换，而不是给出空结果
        Map<String, Object> reversed = service().analyze("", "ALL", "", "", false, "", "",
                "2026-12-31", "2026-01-01", 1, 200);
        assertEquals(y2026.get("total"), reversed.get("total"), "日期反序应自动交换边界");
    }

    /** 空区间（早于数据起点）必须给出空结果而不是报错，页面自己显示空状态。 */
    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void emptyRangeYieldsEmptyResultNotAnError() throws Exception {
        Map<String, Object> none = service().analyze("", "ALL", "", "", false, "", "",
                "1990-01-01", "1990-12-31", 1, 200);

        assertEquals(0, ((Number) none.get("total")).intValue());
        assertTrue(castList(none.get("items")).isEmpty());
        assertTrue(castList(none.get("categories")).isEmpty());
    }

    // ===== 第二层：真库的「名称优先」口径 =====

    /** 归并只改「行属于哪个组合」，不增不减明细行；组合数则应变成名称优先口径。 */
    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void groupingByResolvedCodeKeepsEveryLine() throws Exception {
        Map<String, Object> all = service().analyze("", "ALL", "", "", false, "", "", "", "", 1, 100);

        assertEquals(ALL_LINE_COUNT, ((Number) summaryOf(all).get("lineCount")).longValue(),
                "按实体编号归并不得丢失任何明细行");
        assertEquals(ALL_COMBINATIONS, ((Number) all.get("total")).intValue(),
                "名称优先口径的组合数基线，数据重导后需刷新");
        assertNotEquals(OLD_COMBINATIONS_BY_RAW_CODE, ALL_COMBINATIONS,
                "名称优先口径的组合数应与按原编号分组不同，否则说明口径没有真正生效");
    }

    /**
     * 氚云把 SLH239 录成了 239SLH 的「前后缀对调」写法（实测 2,487 行）。
     * 按名称应归并到产品信息里的 239SLH，且按氚云录入的原编号也要能搜到。
     */
    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void searchingByRawOutboundCodeFindsTheResolvedProduct() throws Exception {
        Map<String, Object> result = service().analyze("SLH239", "ALL", "", "", false, "", "", "", "", 1, 50);
        List<Map<String, Object>> items = castList(result.get("items"));

        assertFalse(items.isEmpty(), "按氚云录入的原始编号也要能搜到（页面展示的是实体编号）");
        Map<String, Object> hit = items.stream()
                .filter(r -> "239SLH".equals(String.valueOf(r.get("productCode"))))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "SLH239 应归并到产品信息里的 239SLH，实际：" + items));
        assertEquals(1, ((Number) hit.get("codeRewritten")).intValue(), "该组合应标记编号被改写");
        String rawCodes = String.valueOf(hit.get("rawCodes"));
        assertTrue(rawCodes.contains("SLH239"), "原始录入编号必须原样保留以便追溯");
        // 「查看出库」深链靠这一列透传给台账（台账按逗号分隔的多个编号过滤），格式不能变。
        assertTrue(rawCodes.contains("239SLH") && rawCodes.contains(","),
                "rawCodes 应是逗号分隔的完整原始编号列表，实际：" + rawCodes);
        assertFalse(rawCodes.contains(" / "), "接口用逗号分隔，展示层的「 / 」由前端转换：" + rawCodes);
        assertEquals(1, ((Number) hit.get("inCatalog")).intValue(),
                "按名称解析后必须能对上产品信息——按原编号本来是找不到的");
        System.out.println("[销售出库产品分析] SLH239 → " + hit.get("productCode")
                + " 原始编号=" + hit.get("rawCodes") + " 出库单数=" + hit.get("orderCount")
                + " 库存行=" + hit.get("nonZeroStockRows"));
    }

    /**
     * 氚云里有一批把<b>大类名当编号</b>填的明细行（{@code 模具} 一个值压了 5,669 行、252 个名称；
     * {@code 模具}/{@code 配件}/{@code SLH} 在产品信息里<b>都不是编号</b>）。名称优先后这类「毒桶」
     * 必须被打散到真实产品上（实测 5,669 行里 5,655 行拆到 {@code 004模具} 等 248 个真实编号）。
     * 剩下的极少数是名称重名（歧义）的行，按口径退回原编号——这是「拿不准就不猜」的正确行为。
     */
    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void genericPlaceholderCodeSplitsIntoRealProducts() throws Exception {
        Map<String, Object> result = service().analyze("模具", "ALL", "", "", false, "", "", "", "", 1, 100);
        List<Map<String, Object>> items = castList(result.get("items"));

        assertFalse(items.isEmpty(), "「模具」类产品应能搜到");
        long realProducts = items.stream()
                .filter(r -> {
                    String code = String.valueOf(r.get("productCode"));
                    return !"模具".equals(code) && code.endsWith("模具");
                })
                .count();
        assertTrue(realProducts >= 10,
                "应解析出多个 00X模具 真实产品，实际 " + realProducts + " 个：" + items);

        // 仍以「模具」呈现的行只能是「名称歧义 → 退回原编号」的兜底行，不得是改写行。
        for (Map<String, Object> row : items) {
            if ("模具".equals(String.valueOf(row.get("productCode")))) {
                assertEquals(0, ((Number) row.get("codeRewritten")).intValue(),
                        "落到「模具」自身的只应是名称歧义退回原编号的行：" + row);
            }
        }
    }

    // ===== 真库夹具 =====

    private static H3SalesProductAnalysisService shared;

    private static H3SalesProductAnalysisService service() throws Exception {
        if (shared == null) {
            Map<String, String> env = new HashMap<>();
            for (String line : Files.readAllLines(Path.of(".env"))) {
                int i = line.indexOf('=');
                if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
            }
            String url = env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive");
            var ds = new DriverManagerDataSource(url,
                    env.getOrDefault("DB_USERNAME", "root"),
                    env.getOrDefault("DB_PASSWORD", ""));
            shared = new H3SalesProductAnalysisService(new JdbcTemplate(ds));
        }
        return shared;
    }

    /** 真库返回的日期可能是 java.sql.Date / LocalDate，统一取 ISO 前 10 位再比。 */
    private static String iso(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(Map<String, Object> result) {
        return (Map<String, Object>) result.get("summary");
    }
}
