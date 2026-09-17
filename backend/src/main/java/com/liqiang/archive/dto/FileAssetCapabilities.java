package com.liqiang.archive.dto;

import com.liqiang.archive.dto.FileDtos.Capability;

import java.util.Set;

public final class FileAssetCapabilities {

    private FileAssetCapabilities() {
    }

    public static final Set<Capability> PRODUCT_ASSET = Set.of(
            Capability.PREVIEW,
            Capability.DOWNLOAD,
            Capability.DELETE,
            Capability.RESTORE,
            Capability.PERMANENT_DELETE,
            Capability.MOVE_OWNER,
            Capability.MOVE_CATEGORY,
            Capability.UPDATE_TAGS,
            Capability.SET_DEFAULT,
            Capability.REORDER,
            Capability.OPEN_FOLDER
    );

    public static final Set<Capability> PACKAGING_ASSET = Set.of(
            Capability.PREVIEW,
            Capability.DOWNLOAD,
            Capability.DELETE,
            Capability.RESTORE,
            Capability.PERMANENT_DELETE,
            Capability.UPDATE_TAGS,
            Capability.SET_DEFAULT,
            Capability.REORDER,
            Capability.OPEN_FOLDER
    );

    public static final Set<Capability> COMPANY_ASSET = Set.of(
            Capability.PREVIEW,
            Capability.DOWNLOAD,
            Capability.DELETE,
            Capability.RESTORE,
            Capability.PERMANENT_DELETE,
            Capability.MOVE_CATEGORY,
            Capability.UPDATE_TAGS,
            Capability.SET_DEFAULT,
            Capability.REORDER,
            Capability.OPEN_FOLDER
    );

    public static final Set<Capability> COMPANY_FIELD_FILE = Set.of(
            Capability.PREVIEW,
            Capability.DOWNLOAD,
            Capability.DELETE,
            Capability.OPEN_FOLDER
    );

    public static final Set<Capability> CERTIFICATE = Set.of(
            Capability.PREVIEW,
            Capability.DOWNLOAD,
            Capability.DELETE,
            Capability.OPEN_FOLDER
    );
}
