package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原因归类词典：拿**周复盘表里的真原文**当断言输入。
 *
 * <p>【为什么这么测】苏平的手工周报里，同一类原因有几十种写法（「没时间装」「档期排满」
 * 「组装人手不足，请假未补人、外加工延迟」都是组装原因）。词典一旦被改坏（比如把配件排到工期之后，
 * 「配件外采时间不够」就会变成工期原因），看板上的原因分布会静默跑偏 —— 没有报错，只有数字不对。
 * 故这里把周报里出现过的原文逐条钉住，改词典必须过这一关。
 */
class ProductionTrackingReasonTaxonomyTest {

    @Test void weeklyReportTextsLandInTheSixCategories() {
        // 组装原因：周报里占逾期 41.9%、延期 70%，写法最多
        assertEquals("ASSEMBLY", classify("组装人手不足，请假未补人、外加工延迟"));
        assertEquals("ASSEMBLY", classify("没时间装，档期排满"));
        assertEquals("ASSEMBLY", classify("没时间装"));
        assertEquals("ASSEMBLY", classify("输送带错了"));
        // 入库原因
        assertEquals("INBOUND", classify("入库延迟、入库员请假"));
        assertEquals("INBOUND", classify("入库延迟"));
        // 工期原因 —— 「工期设置偏短」是车间排期问题，不是业务员填单问题
        assertEquals("LEAD_TIME", classify("工期设置偏短、未剔除休息日和节假日"));
        assertEquals("LEAD_TIME", classify("工期短"));
        assertEquals("LEAD_TIME", classify("时间来不及"));
        // 业务员原因 —— 「工期计算问题」是业务员填单时算错，与上面的「工期设置偏短」必须分得开
        assertEquals("SALES", classify("送单延迟、订单不确定、工期计算问题"));
        assertEquals("SALES", classify("订单不确定"));
        assertEquals("SALES", classify("业务员订单创建时间超过订单要求完成时间"));
        // 配件原因 —— 「配件外采时间不够」含「时间不够」字样，绝不能被工期原因抢走
        assertEquals("PARTS", classify("配件外采时间不够"));
        assertEquals("PARTS", classify("差钣金配件"));
        assertEquals("PARTS", classify("配件延迟"));
        // 模具原因
        assertEquals("MOLD", classify("模具延迟"));
    }

    @Test void blanksAndUnmatchedTextsHaveTheirOwnBucket() {
        assertEquals("UNRECORDED", classify(null));
        assertEquals("UNRECORDED", classify(""));
        assertEquals("UNRECORDED", classify("   "));
        // 与分析侧填充的空值写法一致：空原因在榜上就叫「未记录」
        assertEquals("UNRECORDED", classify("未记录"));
        assertEquals("OTHER", classify("客户验收延迟"));
        assertEquals("原因未记录", ProductionTrackingReasonTaxonomy.labelOf("UNRECORDED"));
        assertEquals("组装原因", ProductionTrackingReasonTaxonomy.labelOf("ASSEMBLY"));
    }

    /** 归并成类别后按单数降序，且各类别之和＝原文榜之和（一条都不能在归并中丢掉）。 */
    @Test void aggregateKeepsEveryOrderAndRanksByCount() {
        var result = ProductionTrackingReasonTaxonomy.aggregate(List.of(
                entry("没时间装", 3), entry("入库延迟", 2), entry("模具延迟", 1),
                entry("配件外采时间不够", 1), entry("未记录", 1)));
        assertEquals(List.of("组装原因", "入库原因", "配件原因", "模具原因", "原因未记录"),
                result.stream().map(row -> row.get("label")).toList(),
                "按单数降序，同数按周报的列举顺序（组装 > 入库 > 工期 > 业务员 > 配件 > 模具）");
        long sum = result.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum();
        assertEquals(8, sum, "归并前后总单数必须相等");
        // 占比的分母是总单数，不是六类之和：8 单里组装 3 单 → 37.5%
        assertEquals(37.5, ((Number) result.get(0).get("rate")).doubleValue());
        assertEquals(List.of("没时间装"), result.get(0).get("samples"));
    }

    @Test void aggregateOfNothingIsEmpty() {
        assertTrue(ProductionTrackingReasonTaxonomy.aggregate(List.of()).isEmpty());
    }

    private static String classify(String reason) {
        return ProductionTrackingReasonTaxonomy.classify(reason);
    }

    private static Map<String, Object> entry(String name, int count) {
        return Map.<String, Object>of("name", name, "count", count);
    }
}
