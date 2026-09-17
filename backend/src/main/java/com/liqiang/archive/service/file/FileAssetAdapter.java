package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;

import java.util.List;
import java.util.Set;

public interface FileAssetAdapter {

    OwnerType ownerType();

    Set<Capability> capabilities();

    List<FileHandle> list(Long ownerId);

    FileHandle get(Long assetId);

    default FileHandle updateTags(Long assetId, String tags, boolean replace) {
        throw unsupported(Capability.UPDATE_TAGS);
    }

    default FileHandle setDefault(Long assetId) {
        throw unsupported(Capability.SET_DEFAULT);
    }

    default void reorder(List<ReorderItem> items) {
        throw unsupported(Capability.REORDER);
    }

    default void moveOwner(Long assetId, OwnerType targetOwnerType, Long targetOwnerId) {
        throw unsupported(Capability.MOVE_OWNER);
    }

    default void moveCategory(Long assetId, String targetCategory) {
        throw unsupported(Capability.MOVE_CATEGORY);
    }

    default void softDelete(Long assetId) {
        throw unsupported(Capability.DELETE);
    }

    default void restore(Long assetId) {
        throw unsupported(Capability.RESTORE);
    }

    default void permanentDelete(Long assetId, boolean deletePhysical) {
        throw unsupported(Capability.PERMANENT_DELETE);
    }

    /**
     * 设置或清除封面图片文件名。
     * 清除时传 coverFileName = null。
     * 由调用方负责物理文件的上传/删除（与原文件同目录），本方法仅负责写入数据库字段。
     */
    default FileHandle setCover(Long assetId, String coverFileName) {
        throw new UnsupportedOperationException("File behavior is not supported: SET_COVER");
    }

    private static UnsupportedOperationException unsupported(Capability capability) {
        return new UnsupportedOperationException("File behavior is not supported: " + capability);
    }

    record ReorderItem(Long id, Integer sortOrder) {
    }
}
