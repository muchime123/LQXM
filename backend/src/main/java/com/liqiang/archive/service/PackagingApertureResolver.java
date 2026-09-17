package com.liqiang.archive.service;

import com.liqiang.archive.entity.PackagingVariant;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/** Canonicalizes the sealing opening of a package. Height and capacity never participate. */
@Component
public class PackagingApertureResolver {
    public record Aperture(String type, String shape, String key, String label,
                           String lengthMm, String widthMm, String diameterMm) {}

    public Aperture fromPackaging(PackagingVariant variant) {
        String shape = normalizeShape(firstNonBlank(variant.getShapeType(), variant.getModel().getShapeType()));
        if ("ROUND".equals(shape)) {
            String diameter = number(firstNonBlank(variant.getTopDiameterMm(), variant.getBottomDiameterMm()));
            return diameter == null ? null : round(diameter);
        }
        String length = number(variant.getLengthMm());
        String width = number(variant.getWidthMm());
        if (length != null && width != null) return rect(shape, length, width);
        return null;
    }

    public Aperture fromSpecification(String moldShape, String type, String length, String width, String diameter) {
        String normalizedType = clean(type) == null ? inferType(moldShape) : type.trim().toUpperCase(Locale.ROOT);
        if ("ROUND".equals(normalizedType)) {
            String value = number(diameter);
            if (value == null) throw new IllegalArgumentException("圆模具必须填写封口直径");
            return round(value);
        }
        if ("RECT".equals(normalizedType)) {
            String l = number(length);
            String w = number(width);
            if (l == null || w == null) throw new IllegalArgumentException("方模具必须填写封口长和宽");
            return rect(normalizeShape(moldShape), l, w);
        }
        throw new IllegalArgumentException("暂不支持无法标准化的模具尺寸类型");
    }

    public String normalizeMoldShape(String value, Aperture aperture) {
        String normalized = normalizeShape(value);
        if ("ROUND".equals(normalized) || "ROUND".equals(aperture.type())) return "圆模具";
        if ("OVAL".equals(normalized)) return "椭圆模具";
        if ("CUSTOM".equals(normalized)) return "异形模具";
        return "方模具";
    }

    private Aperture round(String diameter) {
        String canonical = canonical(diameter);
        return new Aperture("ROUND", "ROUND", "ROUND:" + canonical, "Ø" + display(canonical) + "mm",
                null, null, display(canonical));
    }

    private Aperture rect(String shape, String length, String width) {
        BigDecimal left = decimal(length);
        BigDecimal right = decimal(width);
        BigDecimal max = left.max(right);
        BigDecimal min = left.min(right);
        String canonicalMin = canonical(min.toPlainString());
        String canonicalMax = canonical(max.toPlainString());
        String normalizedShape = "OVAL".equals(shape) ? "OVAL" : "RECT";
        return new Aperture("RECT", normalizedShape,
                normalizedShape + ":" + canonicalMin + "x" + canonicalMax,
                display(canonicalMax) + "×" + display(canonicalMin) + "mm",
                display(canonicalMax), display(canonicalMin), null);
    }

    private String inferType(String shape) {
        return "ROUND".equals(normalizeShape(shape)) ? "ROUND" : "RECT";
    }

    private String normalizeShape(String value) {
        String shape = clean(value);
        if (shape == null) return "RECT";
        String lower = shape.toLowerCase(Locale.ROOT);
        if (lower.contains("round") || lower.contains("circle") || shape.contains("圆")) return "ROUND";
        if (lower.contains("oval") || shape.contains("椭圆")) return "OVAL";
        if (lower.contains("custom") || shape.contains("异形")) return "CUSTOM";
        return "RECT";
    }

    private String number(String value) {
        String cleaned = clean(value);
        if (cleaned == null) return null;
        String numeric = cleaned.replaceAll("[^0-9.]", "");
        if (numeric.isBlank()) return null;
        try {
            return display(canonical(numeric));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private String canonical(String value) {
        return decimal(value).stripTrailingZeros().toPlainString();
    }

    private String display(String value) {
        return decimal(value).stripTrailingZeros().toPlainString();
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (clean(value) != null) return value.trim();
        return null;
    }
}
