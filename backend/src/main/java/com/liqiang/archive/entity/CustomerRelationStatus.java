package com.liqiang.archive.entity;

/** 客户关系状态（DB 存英文枚举，前端展示中文）。 */
public enum CustomerRelationStatus {
    NORMAL("正常"),
    KEY_INTEREST("重点关注"),
    STOPPED("停止合作"),
    NO_CONTACT("禁止联系");

    private final String displayName;

    CustomerRelationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CustomerRelationStatus fromDisplayName(String displayName) {
        if (displayName == null) return null;
        for (CustomerRelationStatus status : values()) {
            if (status.displayName.equals(displayName)) {
                return status;
            }
        }
        return null;
    }

    public static String toDisplayName(String dbValue) {
        if (dbValue == null) return null;
        try {
            return CustomerRelationStatus.valueOf(dbValue).getDisplayName();
        } catch (IllegalArgumentException e) {
            return dbValue;
        }
    }

    public static String toDbValue(String displayName) {
        if (displayName == null) return null;
        CustomerRelationStatus status = fromDisplayName(displayName);
        return status != null ? status.name() : displayName;
    }
}