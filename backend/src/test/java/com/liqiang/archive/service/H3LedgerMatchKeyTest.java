package com.liqiang.archive.service;

import com.liqiang.archive.util.H3LedgerMatchKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 氚云台账匹配口径的<b>公共契约</b>：领料「名称优先、编号兜底」的匹配规则，以及它在
 * 产品库存清单 / 异常统计上的落地。
 *
 * <p><b>为什么改</b>：氚云系统里「产品信息」与「仓库明细」是<b>按产品名称关联</b>的，
 * {@code product_code} 只是人工填写的文本、没有关联校验。真库实测（2026-10-07）：
 * 296 行仓库明细的编号在产品信息里查不到、而名称 100% 查得到；55 行编号撞号；
 * 编号做归一化后多恢复 <b>0 行</b>（不是写法问题，是真错字）。
 *
 * <p><b>三条不许退化的边界</b>（本类逐条钉死）：
 * <ol>
 *   <li><b>重名不猜</b>：名称在产品信息里对应多个编号时，必须退回按编号匹配，
 *       而不是随便挑一个 —— 实测有 359 行落在重名名称下。</li>
 *   <li><b>认领判据不能用「存在同名行」代替</b>：产品信息独有行的排除必须锚在
 *       「这个编号被明细行认领」，否则重名名称的产品信息行会被整批误排除
 *       （实测多排 28 行，如 {@code 模具120} / {@code 1733YX}）。</li>
 *   <li><b>真有货的行不许被改写</b>：字号归零的 1,697 行非零库存行，其编号集合
 *       必须与按编号匹配时逐字相同（它们的编号本来就是对的）。</li>
 * </ol>
 *
 * <p>启用方式：真库断言需要 {@code -Derp.foundation.mysql=true}，否则静默跳过。
 * {@code mvn -o test -Dtest=H3LedgerMatchKeyTest -Derp.foundation.mysql=true}
 */
class H3LedgerMatchKeyTest {

    /** 2026-10-07 实测基线。数据重导后需刷新这组常量。 */
    private static final long VIEW_ROWS = 16_806L;
    private static final long VIEW_ROWS_BEFORE_NAME_MATCH = 17_111L;
    private static final long NONZERO_ROWS = 1_697L;
    private static final long NONZERO_CODES = 1_576L;
    private static final long CONFLICT_ROWS = 356L;
    private static final long AMBIGUOUS_NAME_ROWS = 359L;
    /** 缺价待处理行数：换匹配口径前后实测<b>都是 17</b>（含费用过滤与已处置扣除）。 */
    private static final long MISSING_PRICE_ROWS = 17L;

    // ===== 第一层：SQL 形状（无数据库依赖，永远运行） =====

    @Test
    void nameKeyStripsBothHalfAndFullWidthSpacesThenLowercases() {
        String key = H3LedgerMatchKey.nameKey("ws.product_name");

        assertTrue(key.startsWith("LOWER("), "名称键统一小写：" + key);
        assertTrue(key.contains("TRIM(ws.product_name)"), "先去首尾空格：" + key);
        assertTrue(key.contains(",' ','')"), "去内部半角空格：" + key);
        assertTrue(key.contains("\u3000"), "必须去全角空格 U+3000（氚云导出的名称里真实存在）：" + key);
    }

    @Test
    void uniqueNameMapRefusesAmbiguousNames() {
        String map = H3LedgerMatchKey.UNIQUE_NAME_CODE;

        assertTrue(map.contains("HAVING COUNT(DISTINCT TRIM(product_code))=1"),
                "重名名称必须整条不进映射表 —— 这是「拿不准就不猜」的落地点：" + map);
        assertTrue(map.contains("TRIM(COALESCE(product_name,''))<>''"),
                "名称为空的条目不得进入映射：" + map);
    }

    @Test
    void notClaimedAnchorsOnTheClaimedCodeNotMereNamePresence() {
        String clause = H3LedgerMatchKey.notClaimed("pc.product_code");

        assertTrue(clause.contains("n2.code=TRIM(pc.product_code)"),
                "排除判据必须锚在「这个编号被认领」：" + clause);
        assertTrue(clause.contains("JOIN"), "认领判定需要一个把明细行接到名称映射上的连接：" + clause);
        assertFalse(clause.contains("LIMIT"),
                "认领判定不依赖行序，出现 LIMIT 说明退化成了「挑一行」：" + clause);
    }

    @Test
    void matchCodeFallsBackToTheRowsOwnCode() {
        assertEquals("COALESCE(nm.code,TRIM(ws.product_code))",
                H3LedgerMatchKey.matchCode("ws.product_code", "nm"));
    }

    @Test
    void codeConflictCoversBothRewriteAndCollision() {
        String expr = H3LedgerMatchKey.codeConflict("v.product_code", "v.product_name", "v.match_code", "pc");

        assertTrue(expr.contains("TRIM(v.product_code)<>v.match_code"),
                "成因一：编号与名称所指产品不一致（编号写错、名称把它救回来了）：" + expr);
        assertTrue(expr.contains("pc.product_name"),
                "成因二：名称没救回来、但该编号在产品信息里另有所指（编号撞号）：" + expr);
        assertTrue(expr.endsWith("THEN 1 ELSE 0 END"), "必须是可直接拼进 SELECT 的 CASE 表达式：" + expr);
    }

    @Test
    void inventoryViewJoinsCatalogByResolvedCode() {
        String view = ProductInventoryService.VIEW_FROM;

        assertTrue(view.contains("pc.product_code=v.match_code"),
                "产品信息必须按「名称解析出的实体编号」关联，而不是原始编号：" + view);
        assertFalse(view.contains("pc.product_code=v.product_code"),
                "不得留有按原始编号关联产品信息的老写法：" + view);
        assertTrue(view.contains(H3LedgerMatchKey.UNIQUE_NAME_CODE),
                "清单视图必须复用唯一定义，不得内联另一份名称映射：" + view);
        assertTrue(view.contains(H3LedgerMatchKey.notClaimed("pc.product_code")),
                "产品信息独有段必须带「已被认领则不再补行」的排除：" + view);
    }

    // ===== 第二层：真库不变量 =====

    @Test
    @EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
    void nameMatchingRemovesTheDuplicateRowsWithoutLosingAnything() throws Exception {
        JdbcTemplate jdbc = jdbc();
        long rows = count(jdbc, "SELECT COUNT(*)" + ProductInventoryService.VIEW_FROM);

        assertEquals(VIEW_ROWS, rows, "清单行数基线（数据重导后需刷新）");
        assertTrue(rows < VIEW_ROWS_BEFORE_NAME_MATCH,
                "按编号匹配时同一产品会出现两次（错编号 + 正确编号各一行），名称匹配后必须变少");

        long rewritten = count(jdbc, "SELECT COUNT(*) FROM (SELECT "
                + "TRIM(v.product_code) pc,TRIM(v.match_code) mc" + ProductInventoryService.VIEW_FROM
                + ") t WHERE t.pc<>t.mc");
        System.out.println("[库存匹配] 清单行数=" + rows + "（改前 " + VIEW_ROWS_BEFORE_NAME_MATCH + "）"
                + " 编号被改写=" + rewritten + " 行");
        assertTrue(rewritten > 0, "应当有编号被名称匹配改写（实测 323 行），为 0 说明匹配根本没生效");
    }

    @Test
    @EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
    void rowsThatActuallyHoldStockKeepTheirOriginalCode() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Map<String, Object> row = jdbc.queryForMap("SELECT COUNT(*) rows_," +
                " COUNT(DISTINCT v.match_code) codes" + ProductInventoryService.VIEW_FROM +
                " WHERE COALESCE(v.quantity,0)<>0");

        assertEquals(NONZERO_ROWS, ((Number) row.get("rows_")).longValue(),
                "非零库存行数基线：真实有货的行不该被改写");
        assertEquals(NONZERO_CODES, ((Number) row.get("codes")).longValue(),
                "非零库存编号数基线：与按编号匹配时相同（真有货的编号本来就是对的）");
    }

    @Test
    @EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
    void ambiguousNamesFallBackToCodeMatchingInsteadOfGuessing() throws Exception {
        JdbcTemplate jdbc = jdbc();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) rows_,SUM(CASE WHEN nm.code IS NULL THEN 1 ELSE 0 END) fell_back" +
                " FROM h3_warehouse_stock ws" +
                " LEFT JOIN " + H3LedgerMatchKey.UNIQUE_NAME_CODE + " nm ON "
                + H3LedgerMatchKey.nameJoinOn("nm", "ws.product_name") +
                " JOIN (SELECT " + H3LedgerMatchKey.nameKey("product_name") + " k" +
                "       FROM h3_product_catalog WHERE TRIM(COALESCE(product_name,''))<>''" +
                "       GROUP BY 1 HAVING COUNT(DISTINCT TRIM(product_code))>1) amb" +
                " ON amb.k=" + H3LedgerMatchKey.nameKey("ws.product_name"));

        long rows = ((Number) row.get("rows_")).longValue();
        assertEquals(AMBIGUOUS_NAME_ROWS, rows, "落在重名名称下的明细行数基线（数据重导后需刷新）");
        assertEquals(rows, ((Number) row.get("fell_back")).longValue(),
                "重名名称下的行必须 100% 退回编号匹配，一行都不许被猜");
    }

    @Test
    @EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
    void codeConflictFlagCatchesRewritesAndCollisions() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Map<String, Object> row = jdbc.queryForMap("SELECT COUNT(*) rows_," +
                " SUM(CASE WHEN t.codeConflict=1 THEN 1 ELSE 0 END) conflicts," +
                " SUM(CASE WHEN t.resolvedCode<>t.productCode THEN 1 ELSE 0 END) resolvable" +
                " FROM (" + ProductInventoryService.VIEW_COLUMNS + ProductInventoryService.VIEW_FROM + ") t");

        long rows = ((Number) row.get("rows_")).longValue();
        long conflicts = ((Number) row.get("conflicts")).longValue();
        long resolvable = ((Number) row.get("resolvable")).longValue();

        assertEquals(VIEW_ROWS, rows);
        assertEquals(CONFLICT_ROWS, conflicts, "编号不可信标记行数基线（数据重导后需刷新）");
        assertTrue(resolvable > 0, "应该有编号被名称匹配改写（resolvedCode 与 productCode 不同）");
        assertTrue(conflicts > resolvable,
                "冲突标记必须比「编号被改写」更宽 —— 还有一批是名称没救回来、但该编号另有所指的撞号行");
        System.out.println("[库存匹配] 标记行=" + conflicts + " 其中编号被改写=" + resolvable);
    }

    @Test
    @EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
    void issueCountsStayAtBaseline() throws Exception {
        Map<String, Object> info = service().snapshotInfo();

        assertEquals(0L, ((Number) info.get("missingProductCount")).longValue(),
                "缺产品基线：非零库存行都能通过名称或编号落到产品信息上");
        assertEquals(MISSING_PRICE_ROWS, ((Number) info.get("missingPriceCount")).longValue(),
                "缺价基线：换匹配口径前后实测都是 17（旧按编号 / 新按名称各跑一遍对拍）"
                        + "——真有货的行编号本来就是对的，换键不该改变它们");
        System.out.println("[库存匹配] 缺产品=" + info.get("missingProductCount")
                + " 缺价=" + info.get("missingPriceCount")
                + " 已处置=" + info.get("disposedCount"));
    }

    // ===== 真库夹具 =====

    private static JdbcTemplate sharedJdbc;
    private static ProductInventoryService sharedService;

    private static JdbcTemplate jdbc() throws Exception {
        if (sharedJdbc == null) {
            Map<String, String> env = new HashMap<>();
            for (String line : Files.readAllLines(Path.of(".env"))) {
                int i = line.indexOf('=');
                if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
            }
            String url = env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive");
            var ds = new DriverManagerDataSource(url,
                    env.getOrDefault("DB_USERNAME", "root"),
                    env.getOrDefault("DB_PASSWORD", ""));
            sharedJdbc = new JdbcTemplate(ds);
        }
        return sharedJdbc;
    }

    /** 真库服务实例：只调 balances/snapshotInfo 这类不碰 {@code CurrentUserService} 的方法，故传 null。 */
    private static ProductInventoryService service() throws Exception {
        if (sharedService == null) sharedService = new ProductInventoryService(jdbc(), null);
        return sharedService;
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
