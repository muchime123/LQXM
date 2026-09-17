package com.liqiang.archive.service;

import java.util.HashMap;
import java.util.Map;

public class AnnualFeeStrategyFactory {

    private static final Map<String, AnnualFeeStrategy> STRATEGIES = new HashMap<>();

    static {
        STRATEGIES.put("发明专利", new InventionAnnualFeeStrategy());
        STRATEGIES.put("实用新型", new UtilityAnnualFeeStrategy());
        STRATEGIES.put("外观设计", new DesignAnnualFeeStrategy());
        STRATEGIES.put("外观专利", new DesignAnnualFeeStrategy());
    }

    public static AnnualFeeStrategy getStrategy(String patentType) {
        if (patentType == null) return null;
        String normalized = normalizePatentType(patentType);
        return STRATEGIES.get(normalized);
    }

    public static String normalizePatentType(String patentType) {
        if (patentType == null) return "";
        String trimmed = patentType.trim();
        if ("外观专利".equals(trimmed)) {
            return "外观设计";
        }
        return trimmed;
    }
}