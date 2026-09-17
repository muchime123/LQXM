package com.liqiang.archive.service;

import java.util.Locale;

/** Shared, deliberately conservative normalizer for duplicate candidates. */
public final class DuplicateValueNormalizer {

    private DuplicateValueNormalizer() {
    }

    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s_\\-—–（）()【】\\[\\]，,。.]", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
