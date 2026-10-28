package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 产品生产工期（排产 → 入库完成）的**实库只读**核对。
 *
 * <p>跑法：{@code mvn -o test -Dtest=ProductionTrackingDurationMysqlTest -Dmain.mysql=true}
 *
 * <p>钉住三件事，缺一件这个数字就会静默变成「一个看起来合理的错数」：
 * <ol>
 *   <li><b>缺值节点不进和</b>：两级参考都没有的节点必须出现在 {@code missingStages} 里，
 *       而不是按 0 参与求和——否则「3 个节点没配」会显示成「工期 4 天」，偏小且看不出来。</li>
 *   <li><b>与跟单侧同源</b>：锚点 + 参考工期 必须等于链式推算出来的**末节点参考日期**。
 *       这条一旦破了，配置页显示 5 天、跟单详情推出来却是 7 天，两个页面各说各话。</li>
 *   <li><b>实测只用排产锚点</b>（2026-10-28 A2 收敛为只认排产）：未排产的单无锚点、不计入，
 *       样本数与独立 SQL 逐个产品对上。</li>
 * </ol>
 */
class ProductionTrackingDurationMysqlTest {

    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void referenceDaysIsTheSumOfEffectiveStagesAndMatchesTheNodeChain() throws Exception {
        var jdbc = jdbc();
        var service = new ProductionTrackingService(jdbc, null, null, new ObjectMapper(), null, null);
        var products = jdbc.queryForList("SELECT c.id AS id FROM manufacturing_task_config_items c "
                + "JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                + "WHERE c.config_type='PRODUCT_NAME' AND w.workshop_name LIKE '%封口%' ORDER BY c.id LIMIT 5");
        assertFalse(products.isEmpty(), "缺少封口机产品配置行，核对无从谈起");

        for (var product : products) {
            long taskConfigId = ((Number) product.get("id")).longValue();
            var result = service.productDuration(taskConfigId);
            BigDecimal reference = (BigDecimal) result.get("referenceDays");
            @SuppressWarnings("unchecked")
            var stages = (List<Map<String, Object>>) result.get("stages");
            @SuppressWarnings("unchecked")
            var missing = (List<String>) result.get("missingStages");

            // 1) 逐节点复核：非空值才进和，空值必须被点名
            BigDecimal expected = BigDecimal.ZERO;
            long expectedChain = 0;
            var expectedMissing = new ArrayList<String>();
            var benchmarks = new HashMap<String, BigDecimal>();
            var codes = new ArrayList<String>();
            for (var stage : stages) {
                String code = (String) stage.get("stageCode");
                codes.add(code);
                BigDecimal days = (BigDecimal) stage.get("days");
                if (days == null) {
                    expectedMissing.add((String) stage.get("stageLabel"));
                    continue;
                }
                expected = expected.add(days);
                expectedChain += Math.round(days.doubleValue());
                benchmarks.put(code, days);
            }
            assertEquals(expectedChain, ((Number) result.get("chainDays")).longValue(),
                    "chainDays 必须是逐段取整后相加（与跟单推算同源），不能用小数和：" + result.get("productName"));
            assertEquals(0, expected.setScale(1, RoundingMode.HALF_UP).compareTo(reference),
                    "参考工期必须等于所有有值节点的和：" + result.get("productName"));
            assertEquals(expectedMissing, missing, "缺值节点必须被点名，不能按 0 悄悄参与求和");
            assertEquals(stages.size(), ((Number) result.get("stageCount")).intValue(),
                    "分母是必经节点，按需工序不该出现在这里");

            // 2) 同源：锚点 + 参考工期 = 链式推算的末节点参考日期
            LocalDate anchor = LocalDate.of(2026, 10, 1);
            var applicable = new HashMap<String, Boolean>();
            var statuses = new HashMap<String, String>();
            for (String code : codes) {
                applicable.put(code, Boolean.TRUE);
                statuses.put(code, "PENDING");
            }
            var chain = ProductionTrackingService.computeNodeReferences(codes, anchor, benchmarks,
                    applicable, statuses, Map.of(), LocalDate.now());
            LocalDate lastReference = chain.get(codes.get(codes.size() - 1)).referenceDate();
            if (lastReference != null) {
                long span = java.time.temporal.ChronoUnit.DAYS.between(anchor, lastReference);
                // ⚠️ 比的是 chainDays 而不是 referenceDays：链式推算逐段四舍五入到整天（日期只能是整天），
                // 所以「系统真正在用的工期」是取整后的和。小数和与它对不上是必然的（12.5 → 13），
                // 用小数和去比会得到「差 1 天」，而那不是 bug、是两套口径——必须一开始就取同一套。
                assertEquals(span, ((Number) result.get("chainDays")).longValue(),
                        "chainDays 必须严格等于链式推算的末节点参考日期 − 锚点：" + result.get("productName")
                                + "（各段小数和 " + reference + "）");
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "main.mysql", matches = "true")
    void actualDurationCountsOnlyScheduleAnchoredOrdersThatFinished() throws Exception {
        var jdbc = jdbc();
        var service = new ProductionTrackingService(jdbc, null, null, new ObjectMapper(), null, null);
        var products = jdbc.queryForList("SELECT c.id AS id FROM manufacturing_task_config_items c "
                + "JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                + "WHERE c.config_type='PRODUCT_NAME' AND w.workshop_name LIKE '%封口%' ORDER BY c.id LIMIT 5");

        for (var product : products) {
            long taskConfigId = ((Number) product.get("id")).longValue();
            var result = service.productDuration(taskConfigId);
            String workshop = (String) result.get("workshopName");
            String name = (String) result.get("productName");

            // 独立写一遍口径：排产锚点（氚云排产时间，2026-10-28 A2 收敛为只认排产）+ 入库完成 + 0~60 天 + 非未来
            Long samples = jdbc.queryForObject("SELECT COUNT(DISTINCT p.id) FROM production_orders p "
                    + "JOIN production_tracking_state t ON t.production_order_id=p.id "
                    + "JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                    + "AND m.stage_code='WAREHOUSE' AND m.stage_status='DONE' AND m.completed_date IS NOT NULL "
                    + "WHERE p.production_line=? AND p.product_name=? "
                    + "AND (p.status IS NULL OR p.status NOT IN ('CANCELLED','CANCELED','已取消')) "
                    + "AND p.schedule_time IS NOT NULL "
                    + "AND DATEDIFF(m.completed_date,DATE(p.schedule_time)) BETWEEN 0 AND 60 "
                    + "AND m.completed_date<=CURDATE()",
                    Long.class, workshop, name);
            assertEquals(samples.intValue(), ((Number) result.get("actualSampleCount")).intValue(),
                    "实测样本只能来自排产锚点且已入库的单（申请日兜底不算）：" + name);
            if (samples == 0) {
                assertNull(result.get("actualP50Days"), "没有样本就不能给分位数：" + name);
            } else {
                assertNotNull(result.get("actualP50Days"), "有样本必须给出 P50：" + name);
            }
        }
    }

    private static JdbcTemplate jdbc() throws Exception {
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int i = line.indexOf('=');
            if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        var source = new DriverManagerDataSource(
                env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive"),
                env.getOrDefault("DB_USERNAME", "root"), env.getOrDefault("DB_PASSWORD", ""));
        return new JdbcTemplate(source);
    }
}
