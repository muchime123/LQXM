package com.liqiang.archive.util;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum FileCategory {

    SKU_IMAGE("sku_image", StorageScope.PRODUCT),
    MAIN_IMAGE("main_image", StorageScope.PRODUCT),
    SCENE_IMAGE("scene_image", StorageScope.PRODUCT),
    DETAIL_IMAGE("detail_image", StorageScope.PRODUCT),
    PARAMETER_IMAGE("parameter_image", StorageScope.PRODUCT),
    MANUAL("manual", StorageScope.PRODUCT),
    DEVICE_VIDEO("device_video", StorageScope.PRODUCT),
    OPERATION_VIDEO("operation_video", StorageScope.PRODUCT),
    BROCHURE("brochure", StorageScope.PRODUCT),
    DRAWING("drawing", StorageScope.PRODUCT),
    CERTIFICATE("certificate", StorageScope.FLAT),
    COMPANY("company", StorageScope.FLAT),
    COMPANY_FINANCE("company_finance", StorageScope.FLAT),
    COMPANY_TEAM("company_team", StorageScope.FLAT),
    DOCUMENT("document", StorageScope.FLAT),
    PATENT("patent", StorageScope.FLAT),
    OTHER("other", StorageScope.FLAT),
    EMPLOYEE_FILE("employee_file", StorageScope.EMPLOYEE),

    PKG_MAIN_IMAGE("main_image", StorageScope.PACKAGING),
    PKG_SCENE_IMAGE("scene_image", StorageScope.PACKAGING),
    PKG_SPEC_SHEET("spec_sheet", StorageScope.PACKAGING),
    PKG_DRAWING("drawing", StorageScope.PACKAGING),
    PKG_PRINTING("printing", StorageScope.PACKAGING),
    PKG_PACKAGE("package", StorageScope.PACKAGING),
    PKG_USAGE_VIDEO("usage_video", StorageScope.PACKAGING);

    private final String folder;
    private final StorageScope scope;

    FileCategory(String folder, StorageScope scope) {
        this.folder = folder;
        this.scope = scope;
    }

    public String folder() {
        return folder;
    }

    public StorageScope scope() {
        return scope;
    }

    public static FileCategory of(String category) {
        if (category == null) {
            return null;
        }
        // 历史接口曾使用业务别名；统一文件能力仍需识别它们以定位既有文件。
        if ("company_asset".equals(category)) return COMPANY;
        if ("sku".equals(category)) return SKU_IMAGE;
        FileCategory byName = BY_NAME.get(category);
        if (byName != null) {
            return byName;
        }
        return Stream.of(values())
                .filter(e -> e.folder.equals(category))
                .findFirst()
                .orElse(null);
    }

    public static String folderOf(String category) {
        FileCategory fileCategory = of(category);
        return fileCategory == null ? null : fileCategory.folder;
    }

    public static StorageScope scopeOf(String category) {
        FileCategory fileCategory = of(category);
        return fileCategory == null ? null : fileCategory.scope;
    }

    public static boolean isProduct(String category) {
        return scopeOf(category) == StorageScope.PRODUCT;
    }

    public static boolean isPackaging(String category) {
        return scopeOf(category) == StorageScope.PACKAGING;
    }

    public enum StorageScope {
        PRODUCT,
        PACKAGING,
        FLAT,
        EMPLOYEE
    }

    private static final Map<String, FileCategory> BY_NAME =
            Stream.of(values()).collect(Collectors.toMap(
                    e -> e.name().toLowerCase(),
                    Function.identity()
            ));
}
