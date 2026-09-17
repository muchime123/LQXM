package com.liqiang.archive.entity;

public enum AssetStatus {
    ACTIVE("有效"),
    EXPIRED("已失效"),
    PENDING("未生效"),
    TRANSFERRED("已转让");

    private final String displayName;

    AssetStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static AssetStatus fromDisplayName(String displayName) {
        if (displayName == null) return null;
        for (AssetStatus status : values()) {
            if (status.displayName.equals(displayName)) {
                return status;
            }
        }
        return null;
    }

    public static String toDisplayName(String dbValue) {
        if (dbValue == null) return null;
        try {
            return AssetStatus.valueOf(dbValue).getDisplayName();
        } catch (IllegalArgumentException e) {
            return dbValue;
        }
    }

    public static String toDbValue(String displayName) {
        if (displayName == null) return null;
        AssetStatus status = fromDisplayName(displayName);
        return status != null ? status.name() : displayName;
    }
}