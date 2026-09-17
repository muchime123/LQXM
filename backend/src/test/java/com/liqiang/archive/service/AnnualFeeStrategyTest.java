package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 专利年费策略计算测试（金额依据国家知识产权局年费标准）。
 */
class AnnualFeeStrategyTest {

    // ==================== 工厂 ====================

    @Test
    void factoryReturnsStrategyByPatentType() {
        assertInstanceOf(InventionAnnualFeeStrategy.class, AnnualFeeStrategyFactory.getStrategy("发明专利"));
        assertInstanceOf(UtilityAnnualFeeStrategy.class, AnnualFeeStrategyFactory.getStrategy("实用新型"));
        assertInstanceOf(DesignAnnualFeeStrategy.class, AnnualFeeStrategyFactory.getStrategy("外观设计"));
    }

    @Test
    void factoryNormalizesDesignAlias() {
        // “外观专利”是“外观设计”的别名
        assertInstanceOf(DesignAnnualFeeStrategy.class, AnnualFeeStrategyFactory.getStrategy("外观专利"));
        assertEquals("外观设计", AnnualFeeStrategyFactory.normalizePatentType("外观专利"));
        assertEquals("发明专利", AnnualFeeStrategyFactory.normalizePatentType(" 发明专利 "));
    }

    @Test
    void factoryReturnsNullForUnknownType() {
        assertNull(AnnualFeeStrategyFactory.getStrategy(null));
        assertNull(AnnualFeeStrategyFactory.getStrategy("商标"));
    }

    // ==================== 发明专利（1-20 年） ====================

    @Test
    void inventionFeeSchedule() {
        AnnualFeeStrategy s = new InventionAnnualFeeStrategy();
        assertEquals(BigDecimal.valueOf(900), s.calculate(1));
        assertEquals(BigDecimal.valueOf(900), s.calculate(3));
        assertEquals(BigDecimal.valueOf(1200), s.calculate(4));
        assertEquals(BigDecimal.valueOf(2000), s.calculate(7));
        assertEquals(BigDecimal.valueOf(4000), s.calculate(12));
        assertEquals(BigDecimal.valueOf(6000), s.calculate(15));
        assertEquals(BigDecimal.valueOf(8000), s.calculate(16));
        assertEquals(BigDecimal.valueOf(8000), s.calculate(20));
    }

    @Test
    void inventionFeeOutOfRangeReturnsNull() {
        AnnualFeeStrategy s = new InventionAnnualFeeStrategy();
        assertNull(s.calculate(0));
        assertNull(s.calculate(21));
    }

    // ==================== 实用新型（1-10 年） ====================

    @Test
    void utilityFeeSchedule() {
        AnnualFeeStrategy s = new UtilityAnnualFeeStrategy();
        assertEquals(BigDecimal.valueOf(600), s.calculate(1));
        assertEquals(BigDecimal.valueOf(900), s.calculate(5));
        assertEquals(BigDecimal.valueOf(1200), s.calculate(6));
        assertEquals(BigDecimal.valueOf(2000), s.calculate(10));
    }

    @Test
    void utilityFeeOutOfRangeReturnsNull() {
        assertNull(new UtilityAnnualFeeStrategy().calculate(11));
    }

    // ==================== 外观设计（1-15 年） ====================

    @Test
    void designFeeSchedule() {
        AnnualFeeStrategy s = new DesignAnnualFeeStrategy();
        assertEquals(BigDecimal.valueOf(600), s.calculate(1));
        assertEquals(BigDecimal.valueOf(900), s.calculate(4));
        assertEquals(BigDecimal.valueOf(1200), s.calculate(8));
        assertEquals(BigDecimal.valueOf(2000), s.calculate(9));
        assertEquals(BigDecimal.valueOf(3000), s.calculate(11));
        assertEquals(BigDecimal.valueOf(3000), s.calculate(15));
    }

    @Test
    void designFeeOutOfRangeReturnsNull() {
        assertNull(new DesignAnnualFeeStrategy().calculate(16));
    }
}
