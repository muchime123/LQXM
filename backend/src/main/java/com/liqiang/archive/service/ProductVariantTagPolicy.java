package com.liqiang.archive.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts between a product specification label and the tags on its linked assets. */
public final class ProductVariantTagPolicy {
    private static final int MAX_TAGS = 12;

    private ProductVariantTagPolicy() {
    }

    static List<String> parse(String value) {
        if (value == null || value.isBlank()) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String part : value.split("[,，、]")) {
            String tag = part.trim().replaceAll("\\s+", " ");
            if (tag.isBlank()) continue;
            unique.putIfAbsent(key(tag), tag);
            if (unique.size() >= MAX_TAGS) break;
        }
        return List.copyOf(unique.values());
    }

    static String storageTags(String value) {
        return String.join(",", parse(value));
    }

    static String variantLabel(String value) {
        return String.join("，", specificationTags(value));
    }

    /** Content tags are independent metadata and must never be silently reclassified as a specification. */
    public static String contentTags(String value) {
        return storageTags(value);
    }

    static String specificationLabel(String outputCount, String moldShape) {
        List<String> values = new ArrayList<>();
        if (outputCount != null && !outputCount.isBlank()) values.add(outputCount.trim());
        if (moldShape != null && !moldShape.isBlank()) values.add(moldShape.trim());
        return String.join("，", values);
    }

    static String specKey(String outputCount, String moldShape) {
        String output = outputCount == null ? "" : key(outputCount);
        String mold = moldShape == null ? "" : key(moldShape);
        if (output.isBlank() && mold.isBlank()) return null;
        return output + "|" + mold;
    }

    static List<String> specificationTags(String value) {
        return parse(value).stream().filter(ProductVariantTagPolicy::isSpecificationTag).toList();
    }

    static String outputCount(String value) {
        return specificationTags(value).stream()
                .filter(ProductVariantTagPolicy::isOutputCount)
                .findFirst()
                .orElse(null);
    }

    static String moldShape(String value) {
        return specificationTags(value).stream()
                .filter(ProductVariantTagPolicy::isMoldShape)
                .findFirst()
                .orElse(null);
    }

    static boolean equivalent(String left, String right) {
        List<String> leftKeys = parse(left).stream().map(ProductVariantTagPolicy::key).sorted().toList();
        List<String> rightKeys = parse(right).stream().map(ProductVariantTagPolicy::key).sorted().toList();
        return leftKeys.equals(rightKeys);
    }

    static String replaceSpecificationTags(String assetTags, String oldVariantName, String newVariantName) {
        List<String> oldKeys = parse(oldVariantName).stream().map(ProductVariantTagPolicy::key).toList();
        Map<String, String> merged = new LinkedHashMap<>();
        for (String tag : parse(assetTags)) {
            if (!oldKeys.contains(key(tag))) merged.putIfAbsent(key(tag), tag);
        }
        for (String tag : parse(newVariantName)) merged.putIfAbsent(key(tag), tag);
        return String.join(",", new ArrayList<>(merged.values()));
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSpecificationTag(String value) {
        return isOutputCount(value) || isMoldShape(value);
    }

    private static boolean isOutputCount(String value) {
        return value != null && value.trim().matches("一出(?:[一二三四五六七八九十百]+|\\d+)");
    }

    private static boolean isMoldShape(String value) {
        if (value == null) return false;
        String tag = value.trim();
        return tag.endsWith("模具") || tag.endsWith("模") || tag.matches("(?:方|圆|异形|不规则)形?");
    }
}
