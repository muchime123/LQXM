package com.liqiang.archive.service;

import java.math.BigDecimal;

public class InventionAnnualFeeStrategy implements AnnualFeeStrategy {

    @Override
    public BigDecimal calculate(int feeYear) {
        Integer amount = switch (feeYear) {
            case 1, 2, 3 -> 900;
            case 4, 5, 6 -> 1200;
            case 7, 8, 9 -> 2000;
            case 10, 11, 12 -> 4000;
            case 13, 14, 15 -> 6000;
            case 16, 17, 18, 19, 20 -> 8000;
            default -> null;
        };
        return amount == null ? null : BigDecimal.valueOf(amount);
    }

    @Override
    public String getPatentType() {
        return "发明专利";
    }
}