package com.liqiang.archive.entity;

/** 客户类型（DB 存英文枚举，前端展示中文）。 */
public enum CustomerType {
    ENTERPRISE("企业"),
    COOPERATIVE("合作社"),
    FARM("农场"),
    INDIVIDUAL("个体"),
    PERSONAL("个人"),
    OTHER("其他");

    private final String displayName;

    CustomerType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CustomerType fromDisplayName(String displayName) {
        if (displayName == null) return null;
        for (CustomerType type : values()) {
            if (type.displayName.equals(displayName)) {
                return type;
            }
        }
        return null;
    }

    public static String toDisplayName(String dbValue) {
        if (dbValue == null) return null;
        try {
            return CustomerType.valueOf(dbValue).getDisplayName();
        } catch (IllegalArgumentException e) {
            return dbValue;
        }
    }

    public static String toDbValue(String displayName) {
        if (displayName == null) return null;
        CustomerType type = fromDisplayName(displayName);
        return type != null ? type.name() : displayName;
    }
}