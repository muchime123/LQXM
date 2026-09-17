package com.liqiang.archive.service;

import java.math.BigDecimal;

public interface AnnualFeeStrategy {
    BigDecimal calculate(int feeYear);
    String getPatentType();
}