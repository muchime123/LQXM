package com.liqiang.archive.service;

import com.liqiang.archive.entity.PackagingModel;
import com.liqiang.archive.entity.PackagingVariant;
import org.springframework.stereotype.Service;

/**
 * 包装规格解析器：统一处理 PackagingVariant 字段为空时回退读取 PackagingModel 的继承逻辑。
 * <p>
 * 当前仅封装一处（4个调用点），避免散落的 `blank(v.getShapeType()) ? m.getShapeType() : v.getShapeType()`。
 */
@Service
public class PackagingSpecResolver {

    /**
     * 盒型形状：variant.shapeType → model.shapeType
     */
    public String shapeType(PackagingModel model, PackagingVariant variant) {
        String shape = variant != null && !blank(variant.getShapeType())
                ? variant.getShapeType()
                : (model == null ? null : model.getShapeType());
        return normalizeShape(shape);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String normalizeShape(String shape) {
        if (shape == null) return "unspecified";
        String s = shape.trim().toLowerCase();
        if (s.contains("圆") || "round".equals(s) || "circular".equals(s)) return "round";
        if (s.contains("方") || s.contains("矩") || "rectangle".equals(s) || "square".equals(s)) return "rectangle";
        if (s.contains("杯") || "cup".equals(s) || "cylinder".equals(s)) return "round";
        if (s.contains("托") || "tray".equals(s)) return "rectangle";
        return s.isBlank() ? "unspecified" : s;
    }
}
