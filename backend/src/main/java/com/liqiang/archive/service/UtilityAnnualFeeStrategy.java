package com.liqiang.archive.service;

import java.math.BigDecimal;

public class UtilityAnnualFeeStrategy implements AnnualFeeStrategy {

    @Override
    public BigDecimal calculate(int feeYear) {
        Integer amount = switch (feeYear) {
            case 1, 2, 3 -> 600;
            case 4, 5 -> 900;
            case 6, 7, 8 -> 1200;
            case 9, 10 -> 2000;
            default -> null;
        };
        return amount == null ? null : BigDecimal.valueOf(amount);
    }

    @Override
    public String getPatentType() {
        return "实用新型";
    }
}