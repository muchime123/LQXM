package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FileAssetBehaviorService {

    private final Map<OwnerType, FileAssetAdapter> adapters;

    public FileAssetBehaviorService(List<FileAssetAdapter> adapters) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("At least one file adapter must be registered");
        }
        this.adapters = new EnumMap<>(OwnerType.class);
        for (FileAssetAdapter adapter : adapters) {
            if (adapter == null) {
                throw new IllegalStateException("File adapter must not be null");
            }
            OwnerType ownerType = adapter.ownerType();
            if (ownerType == null) {
                throw new IllegalStateException("File adapter ownerType must not be null");
            }
            Set<Capability> capabilities = adapter.capabilities();
            if (capabilities == null || capabilities.isEmpty()) {
                throw new IllegalStateException("File adapter capabilities must not be empty for owner type: " + ownerType);
            }
            FileAssetAdapter previous = this.adapters.put(ownerType, adapter);
            if (previous != null) {
                throw new IllegalStateException("Duplicate file adapter registered for owner type: " + ownerType);
            }
        }
    }

    public List<FileHandle> list(OwnerType ownerType, Long ownerId) {
        FileAssetAdapter adapter = adapter(ownerType);
        List<FileHandle> handles = adapter.list(ownerId);
        if (handles == null) {
            throw new IllegalStateException("File adapter returned a null list for ownerType " + ownerType);
        }
        return handles.stream()
                .map(handle -> requireHandleCapabilities(ownerType, adapter, requireHandleIdentity(ownerType, null, handle)))
                .toList();
    }

    public Set<Capability> capabilities(OwnerType ownerType) {
        return Set.copyOf(adapter(ownerType).capabilities());
    }

    public Map<OwnerType, Set<Capability>> allCapabilities() {
        return Map.copyOf(adapters.entrySet()
                .stream()
                .collect(
                        () -> new EnumMap<OwnerType, Set<Capability>>(OwnerType.class),
                        (result, entry) -> result.put(entry.getKey(), Set.copyOf(entry.getValue().capabilities())),
                        Map::putAll
                ));
    }

    public FileHandle get(OwnerType ownerType, Long assetId) {
        Long requiredAssetId = requireAssetId(assetId);
        FileAssetAdapter adapter = adapter(ownerType);
        return requireHandleCapabilities(
                ownerType,
                adapter,
                requireHandleIdentity(ownerType, requiredAssetId, adapter.get(requiredAssetId))
        );
    }

    public FileHandle getForCapability(OwnerType ownerType, Long assetId, Capability capability) {
        if (capability == null) {
            throw new IllegalArgumentException("capability is required");
        }
        return requireAssetCapability(ownerType, assetId, capability);
    }

    public FileHandle updateTags(OwnerType ownerType, Long assetId, String tags, boolean replace) {
        if (tags == null) {
            throw new IllegalArgumentException("tags is required");
        }
        Long requiredAssetId = requireAssetId(assetId);
        FileAssetAdapter requestedAdapter = adapter(ownerType);
        FileHandle current = requireTagUpdateHandleIdentity(
                ownerType, requiredAssetId, requestedAdapter.get(requiredAssetId));
        FileAssetAdapter adapter = adapter(current.ownerType());
        requireHandleCapabilities(current.ownerType(), adapter, current);
        if (!current.capabilities().contains(Capability.UPDATE_TAGS)) {
            throw new UnsupportedOperationException(
                    "File behavior is not supported for ownerType " + current.ownerType()
                            + ", assetId " + requiredAssetId + ": " + Capability.UPDATE_TAGS
            );
        }
        FileHandle updated = requireTagUpdateHandleIdentity(
                current.ownerType(), requiredAssetId, adapter.updateTags(requiredAssetId, tags, replace));
        FileAssetAdapter resultAdapter = adapter(updated.ownerType());
        return requireHandleCapabilities(updated.ownerType(), resultAdapter, updated);
    }

    public FileHandle setDefault(OwnerType ownerType, Long assetId) {
        FileAssetAdapter adapter = adapterForAssetCapability(ownerType, assetId, Capability.SET_DEFAULT);
        Long requiredAssetId = requireAssetId(assetId);
        return requireHandleCapabilities(
                ownerType,
                adapter,
                requireHandleIdentity(ownerType, requiredAssetId, adapter.setDefault(requiredAssetId))
        );
    }

    public void reorder(OwnerType ownerType, List<FileAssetAdapter.ReorderItem> items) {
        FileAssetAdapter adapter = adapter(ownerType);
        List<FileAssetAdapter.ReorderItem> validItems = requireReorderItems(items);
        for (FileAssetAdapter.ReorderItem item : validItems) {
            requireAssetCapability(adapter, ownerType, item.id(), Capability.REORDER);
        }
        adapter.reorder(validItems);
    }

    public void moveOwner(OwnerType ownerType, Long assetId, OwnerType targetOwnerType, Long targetOwnerId) {
        if (targetOwnerType == null) {
            throw new IllegalArgumentException("targetOwnerType is required");
        }
        if (targetOwnerId == null) {
            throw new IllegalArgumentException("targetOwnerId is required");
        }
        FileAssetAdapter adapter = adapterForAssetCapability(ownerType, assetId, Capability.MOVE_OWNER);
        adapter.moveOwner(requireAssetId(assetId), targetOwnerType, targetOwnerId);
    }

    public void moveCategory(OwnerType ownerType, Long assetId, String targetCategory) {
        if (targetCategory == null || targetCategory.isBlank()) {
            throw new IllegalArgumentException("category is required");
        }
        FileAssetAdapter adapter = adapterForAssetCapability(ownerType, assetId, Capability.MOVE_CATEGORY);
        adapter.moveCategory(requireAssetId(assetId), targetCategory);
    }

    public void softDelete(OwnerType ownerType, Long assetId) {
        FileAssetAdapter adapter = adapterForAssetCapability(ownerType, assetId, Capability.DELETE);
        adapter.softDelete(requireAssetId(assetId));
    }

    public void restore(OwnerType ownerType, Long assetId) {
        FileAssetAdapter adapter = adapterForAssetCapability(ownerType, assetId, Capability.RESTORE);
        adapter.restore(requireAssetId(assetId));
    }

    public void permanentDelete(OwnerType ownerType, Long assetId, boolean deletePhysical) {
        FileAssetAdapter adapter = adapterForAssetCapability(ownerType, assetId, Capability.PERMANENT_DELETE);
        adapter.permanentDelete(requireAssetId(assetId), deletePhysical);
    }

    /**
     * 设置或清除自定义封面图片文件名。
     * 清除时传 coverFileName = null。
     * 仅负责写入数据库字段；物理文件的上传/删除由 Controller 层处理（与原文件同目录）。
     */
    public FileHandle setCover(OwnerType ownerType, Long assetId, String coverFileName) {
        Long requiredAssetId = requireAssetId(assetId);
        FileAssetAdapter adapter = adapter(ownerType);
        FileHandle current = requireHandleIdentity(ownerType, requiredAssetId, adapter.get(requiredAssetId));
        FileHandle updated = requireHandleIdentity(
                current.ownerType(),
                requiredAssetId,
                adapter.setCover(requiredAssetId, coverFileName)
        );
        return requireHandleCapabilities(updated.ownerType(), adapter(updated.ownerType()), updated);
    }

    private FileAssetAdapter adapter(OwnerType ownerType) {
        if (ownerType == null) {
            throw new IllegalArgumentException("ownerType is required");
        }
        FileAssetAdapter adapter = adapters.get(ownerType);
        if (adapter == null) {
            throw new UnsupportedOperationException("No file adapter registered for owner type: " + ownerType);
        }
        return adapter;
    }

    private FileAssetAdapter adapterForAssetCapability(OwnerType ownerType, Long assetId, Capability capability) {
        FileAssetAdapter adapter = adapter(ownerType);
        requireAssetCapability(adapter, ownerType, requireAssetId(assetId), capability);
        return adapter;
    }

    private FileHandle requireAssetCapability(OwnerType ownerType, Long assetId, Capability capability) {
        FileAssetAdapter adapter = adapter(ownerType);
        return requireAssetCapability(adapter, ownerType, requireAssetId(assetId), capability);
    }

    private FileHandle requireAssetCapability(FileAssetAdapter adapter, OwnerType ownerType, Long assetId, Capability capability) {
        if (!adapter.capabilities().contains(capability)) {
            throw new UnsupportedOperationException(
                    "File behavior is not supported for ownerType " + ownerType + ": " + capability
            );
        }
        FileHandle handle = requireHandleCapabilities(ownerType, adapter, requireHandleIdentity(ownerType, assetId, adapter.get(assetId)));
        if (handle.capabilities() == null || !handle.capabilities().contains(capability)) {
            throw new UnsupportedOperationException(
                    "File behavior is not supported for ownerType " + ownerType
                            + ", assetId " + assetId + ": " + capability
            );
        }
        return handle;
    }

    private static FileHandle requireHandleCapabilities(OwnerType ownerType, FileAssetAdapter adapter, FileHandle handle) {
        if (handle.capabilities() == null) {
            throw new IllegalStateException("File handle capabilities must not be null for ownerType " + ownerType);
        }
        if (!adapter.capabilities().containsAll(handle.capabilities())) {
            Set<Capability> unsupported = new HashSet<>(handle.capabilities());
            unsupported.removeAll(adapter.capabilities());
            throw new IllegalStateException(
                    "File handle exposes capabilities not declared by ownerType " + ownerType + ": " + unsupported
            );
        }
        return handle;
    }
    private static FileHandle requireHandleIdentity(OwnerType ownerType, Long assetId, FileHandle handle) {
        if (handle == null) {
            throw new IllegalStateException("File adapter returned a null handle for ownerType " + ownerType);
        }
        if (handle.ownerType() != ownerType) {
            throw new IllegalStateException(
                    "File adapter returned mismatched ownerType: expected " + ownerType + ", actual " + handle.ownerType()
            );
        }
        if (assetId != null && !assetId.equals(handle.assetId())) {
            throw new IllegalStateException(
                    "File adapter returned mismatched assetId: expected " + assetId + ", actual " + handle.assetId()
            );
        }
        return handle;
    }

    private static FileHandle requireTagUpdateHandleIdentity(OwnerType requestedOwnerType, Long assetId,
                                                              FileHandle handle) {
        if (handle == null) {
            throw new IllegalStateException("File adapter returned a null handle for ownerType " + requestedOwnerType);
        }
        boolean productOwnerTransition =
                requestedOwnerType == OwnerType.PRODUCT_MODEL && handle.ownerType() == OwnerType.PRODUCT_VARIANT
                        || requestedOwnerType == OwnerType.PRODUCT_VARIANT && handle.ownerType() == OwnerType.PRODUCT_MODEL;
        if (handle.ownerType() != requestedOwnerType && !productOwnerTransition) {
            throw new IllegalStateException(
                    "File adapter returned mismatched ownerType: expected " + requestedOwnerType
                            + ", actual " + handle.ownerType()
            );
        }
        if (!assetId.equals(handle.assetId())) {
            throw new IllegalStateException(
                    "File adapter returned mismatched assetId: expected " + assetId + ", actual " + handle.assetId()
            );
        }
        return handle;
    }
    private static Long requireAssetId(Long assetId) {
        if (assetId == null) {
            throw new IllegalArgumentException("assetId is required");
        }
        return assetId;
    }

    private static List<FileAssetAdapter.ReorderItem> requireReorderItems(List<FileAssetAdapter.ReorderItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items is required");
        }
        Set<Long> seenIds = new HashSet<>();
        for (FileAssetAdapter.ReorderItem item : items) {
            if (item == null || item.id() == null || item.sortOrder() == null) {
                throw new IllegalArgumentException("reorder item id and sortOrder are required");
            }
            if (!seenIds.add(item.id())) {
                throw new IllegalArgumentException("reorder item id must be unique: " + item.id());
            }
        }
        return items;
    }
}
