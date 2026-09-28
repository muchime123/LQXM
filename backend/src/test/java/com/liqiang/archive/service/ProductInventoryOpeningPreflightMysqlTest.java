package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「氚云库存 → ERP 期初」开账干跑（决策 {@code #38}）的<b>真库对账测试</b>。
 *
 * <p><b>为什么是真库测试而不是纯单测</b>：干跑的口径全部由<b>真库里的两份台账</b>决定
 * （费用行混在仓库明细里、缺价行、编号对不上的行、代发仓），单测造不出这些真实分布。
 * 本测试直接调 {@link ProductInventoryMergeService#openingPreflight()}，断言它报出的
 * 计数与 {@code #38} 实测口径一致。
 *
 * <p><b>钉住四件事</b>：
 * <ol>
 *   <li><b>口径缺口恰为 #38⑤ 的 27 行</b>（缺价 16 + 编号对不上产品目录 11）——
 *       这批行按 #38 是「金额置 null、<b>仍进账</b>」，而当前 {@code confirm} 会判 REVIEW 跳过。
 *       干跑必须把它们单列出来，否则 T 日会静默少开这 27 行。</li>
 *   <li><b>代发仓不进账</b>（#38⑥）：{@code DROP_SHIP} 不持有库存。</li>
 *   <li><b>只开有数的行</b>（#38③）：零 / 负 / 数量缺失一律不进账。</li>
 *   <li><b>费用行不进账</b>：判据走 {@code ProductFeeFilter}（唯一实现），进账集内应为 0 行。</li>
 * </ol>
 *
 * <p><b>只读</b>：{@code openingPreflight()} 不写任何表（不建主轴、不写余额、不开流水）。
 * 本测试也只读真库，跑完零残留。
 *
 * <p><b>隔离边界与已知前提</b>：干跑只走 {@code preview()} 的只读路径，不触
 * {@code sourceIdentityService} / {@code users} / {@code objectMapper}（三者仅 {@code confirm} 用），
 * 故手工构造 service 时后三参传 {@code null}。若干跑被改成依赖它们，本测试会立刻 NPE 失败。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ProductInventoryOpeningPreflightMysqlTest -Derp.foundation.mysql=true}
 * （不带 {@code -Derp.foundation.mysql=true} 会被静默跳过）。
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ProductInventoryOpeningPreflightMysqlTest {

    /** #38⑤ 实测：进账集内缺价 16 行 + 编号对不上产品目录 11 行。 */
    private static final int POLICY_GAP_ROWS = 27;

    @Test
    void preflightReportsDecision38Counts() throws Exception {
        Map<String, Object> result = newService().openingPreflight();

        Map<String, Object> summary = cast(result.get("summary"));
        List<Map<String, Object>> opening = castList(result.get("opening"));
        List<Map<String, Object>> gaps = castList(result.get("policyGaps"));
        List<Map<String, Object>> blocked = castList(result.get("blocked"));
        List<Map<String, Object>> excluded = castList(result.get("excluded"));

        System.out.println("[开账干跑] summary = " + summary);
        System.out.println("[开账干跑] 口径缺口(前 30) =");
        gaps.stream().limit(30).forEach(g -> System.out.println("   " + g));
        System.out.println("[开账干跑] 真异常(前 20) =");
        blocked.stream().limit(20).forEach(b -> System.out.println("   " + b));
        System.out.println("[开账干跑] 不进账(前 20) =");
        excluded.stream().limit(20).forEach(e -> System.out.println("   " + e));

        // ===== ① 口径缺口 = #38⑤ 的 27 行（缺价 + 编号对不上），金额一律按 null 计 =====
        assertEquals(POLICY_GAP_ROWS, gaps.size(),
                "口径缺口应为 #38⑤ 的 27 行（缺价 16 + 编号对不上目录 11）");
        for (Map<String, Object> gap : gaps) {
            assertNull(gap.get("stockAmount"),
                    "按 #38⑤ 缺价/编号对不上的行进账时金额必须置 null：" + gap);
        }

        // ===== ② 代发仓一律不进账（#38⑥）=====
        for (Map<String, Object> row : opening) {
            assertNotEquals(ProductInventoryMergeService.DROP_SHIP_WAREHOUSE, row.get("warehouse"),
                    "代发仓不得出现在进账清单里（#38⑥ DROP_SHIP 不持有库存）：" + row);
        }
        assertEquals(number(summary.get("excludedDropShip")), countDisposition(excluded, "EXCLUDED_DROP_SHIP"),
                "代发仓剔除计数应等于 excluded 里标记为 EXCLUDED_DROP_SHIP 的行数");

        // ===== ③ 只开有数的行（#38③）=====
        for (Map<String, Object> row : opening) {
            BigDecimal qty = (BigDecimal) row.get("quantity");
            assertNotNull(qty, "进账行数量不得为 null：" + row);
            assertTrue(qty.signum() > 0, "进账行数量必须 > 0（#38③ 只开有数的行）：" + row);
        }

        // ===== ④ 进账集内不得出现费用行（判据 = ProductFeeFilter）=====
        for (Map<String, Object> row : opening) {
            String code = trim(row.get("productCode"));
            String name = trim(row.get("productName"));
            assertNotEquals(name, code, "费用/非标行的特征是 名称 = 编号，不得进账：" + row);
        }

        // ===== ⑤ 分类必须互斥且完备（不依赖数据的不变式：谁漏分类，这里立刻红）=====
        assertEquals(number(summary.get("previewTotalRows")),
                number(summary.get("openingRows")) + number(summary.get("policyGapRows"))
                        + number(summary.get("blockedRows")) + number(summary.get("excludedRows")),
                "进账 + 口径缺口 + 真异常 + 不进账 必须等于预览总行（分类互斥且无遗漏）");

        // ===== ⑥ NULL 身份行不进账（#38④）：进账集外仍有 1 行有量的 NULL 行（295QZDQTJ，缺名称）=====
        assertEquals(1, number(summary.get("excludedNullIdentity")),
                "名称/编号为 NULL 的有量行应为 1 行（另一行 加工费 已被 ProductFeeFilter 按编号剔除）");

        // ===== ⑦ 代发仓有量且非费用的行 = 22（#38⑥ 实测：28 行有数 − 6 行本身即费用）=====
        assertEquals(22, number(summary.get("excludedDropShipPositive")),
                "代发仓有量且非费用的行应为 22");
        assertTrue(number(summary.get("excludedDropShipPositive")) <= number(summary.get("excludedDropShip")),
                "有量的代发仓行是代发仓行的子集");

        // ===== ⑧ 开销非空且为正 =====
        assertFalse(opening.isEmpty(), "按 #38 口径应有进账行");
        assertTrue(number(summary.get("openingProducts")) > 0, "进账应涉及产品主轴");
        assertTrue(((BigDecimal) summary.get("openingAmount")).signum() > 0, "期初金额应为正");
    }

    private static int countDisposition(List<Map<String, Object>> rows, String disposition) {
        return (int) rows.stream().filter(r -> disposition.equals(r.get("disposition"))).count();
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static String trim(Object value) {
        return value == null ? null : value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** 手工构造：干跑是只读路径，不依赖 sourceIdentityService / users / objectMapper。 */
    private static ProductInventoryMergeService newService() throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int i = line.indexOf('=');
            if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        String url = env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive");
        var ds = new DriverManagerDataSource(url,
                env.getOrDefault("DB_USERNAME", "root"),
                env.getOrDefault("DB_PASSWORD", ""));
        return new ProductInventoryMergeService(new JdbcTemplate(ds), null, null, null);
    }
}
