package com.liqiang.archive.entity;

public enum FeeStatus {
    TO_CHECK("待查询/待核实"),
    PAID("已支付"),
    UNPAID("未支付"),
    OVERDUE("已失效待缴费"),
    REGISTERED("已登记/已公告");

    private final String displayName;

    FeeStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static FeeStatus fromDisplayName(String displayName) {
        if (displayName == null) return null;
        for (FeeStatus status : values()) {
            if (status.displayName.equals(displayName)) {
                return status;
            }
        }
        return null;
    }

    public static String toDisplayName(String dbValue) {
        if (dbValue == null) return null;
        try {
            return FeeStatus.valueOf(dbValue).getDisplayName();
        } catch (IllegalArgumentException e) {
            return dbValue;
        }
    }

    public static String toDbValue(String displayName) {
        if (displayName == null) return null;
        FeeStatus status = fromDisplayName(displayName);
        return status != null ? status.name() : displayName;
    }
}