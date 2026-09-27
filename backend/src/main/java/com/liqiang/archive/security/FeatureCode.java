package com.liqiang.archive.security;

/**
 * 功能码常量（V249）：与 system_features.feature_code 保持一致。
 * 行级数据范围按功能区分，Service 在追加数据范围时标注所属功能。
 */
public final class FeatureCode {

    private FeatureCode() {
    }

    /** 销售业务总览（旧） */
    public static final String BUSINESS = "BUSINESS";
    /** 销售出库与核销（旧） */
    public static final String SALES_OUTBOUND = "SALES_OUTBOUND";
    /** 销售出库费用（旧） */
    public static final String ORDER_EXPENSE = "ORDER_EXPENSE";
    /** 销售出库利润（旧） */
    public static final String ORDER_PROFIT = "ORDER_PROFIT";
    /** 销售出库提成（旧） */
    public static final String ORDER_COMMISSION = "ORDER_COMMISSION";
    /** 费用报销（旧） */
    public static final String EXPENSE = "EXPENSE";
    /** 业务利润（旧） */
    public static final String PROFIT = "PROFIT";
    /** 提成规则（旧） */
    public static final String COMMISSION = "COMMISSION";
    /** 月度提成核算 */
    public static final String MONTHLY_COMMISSION = "MONTHLY_COMMISSION";
    /** 生产制造 */
    public static final String MANUFACTURING = "MANUFACTURING";
    /** 采购订单（外采） */
    public static final String PURCHASE_ORDER = "PURCHASE_ORDER";
}
