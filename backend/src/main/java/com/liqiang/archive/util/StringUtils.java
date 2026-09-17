package com.liqiang.archive.util;

/**
 * Shared string utilities — extracted from duplicated private methods in Service classes.
 */
public final class StringUtils {

    private StringUtils() {
        // utility class
    }

    /**
     * Null-safe: returns empty string if input is null, otherwise the original value.
     */
    public static String nn(String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns null if input is null or blank, otherwise the trimmed value.
     * Trim is defensive — callers handle query params or DB values that benefit from whitespace cleanup.
     */
    public static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }
}
