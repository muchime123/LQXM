package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VideoPersonalWorkspaceService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUsers;

    public Set<Long> favoriteIds() {
        return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT asset_id FROM video_user_favorites WHERE owner_key=?", Long.class, ownerKey()));
    }

    @Transactional
    public boolean setFavorite(long assetId, boolean favorite) {
        requireVideo(assetId);
        String owner = ownerKey();
        if (favorite) {
            jdbc.update("INSERT IGNORE INTO video_user_favorites(owner_key,asset_id) VALUES (?,?)", owner, assetId);
        } else {
            jdbc.update("DELETE FROM video_user_favorites WHERE owner_key=? AND asset_id=?", owner, assetId);
        }
        return favorite;
    }

    @Transactional
    public void recordView(long assetId) {
        requireVideo(assetId);
        jdbc.update("""
                INSERT INTO video_recent_views(owner_key,asset_id,view_count,last_viewed_at)
                VALUES (?,?,1,NOW())
                ON DUPLICATE KEY UPDATE view_count=view_count+1,last_viewed_at=NOW()
                """, ownerKey(), assetId);
    }

    public List<Map<String, Object>> recentViews(int limit) {
        return jdbc.queryForList("""
                SELECT r.asset_id AS assetId,r.view_count AS viewCount,r.last_viewed_at AS lastViewedAt,
                       COALESCE(NULLIF(a.video_title,''),a.file_name) AS title,m.name AS modelName
                FROM video_recent_views r
                JOIN product_assets a ON a.id=r.asset_id
                JOIN product_models m ON m.id=a.model_id
                WHERE r.owner_key=? AND a.deleted_at IS NULL
                ORDER BY r.last_viewed_at DESC LIMIT ?
                """, ownerKey(), Math.max(1, Math.min(limit, 100)));
    }

    public List<Map<String, Object>> collections() {
        return jdbc.queryForList("""
                SELECT c.id,c.name,c.description,c.updated_at AS updatedAt,COUNT(i.asset_id) AS itemCount
                FROM video_collections c LEFT JOIN video_collection_items i ON i.collection_id=c.id
                WHERE c.owner_key=? GROUP BY c.id,c.name,c.description,c.updated_at ORDER BY c.updated_at DESC
                """, ownerKey());
    }

    @Transactional
    public Map<String, Object> createCollection(String name, String description) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isBlank()) throw new IllegalArgumentException("清单名称不能为空");
        if (cleanName.length() > 120) throw new IllegalArgumentException("清单名称不能超过120个字符");
        jdbc.update("INSERT INTO video_collections(owner_key,name,description) VALUES (?,?,?)",
                ownerKey(), cleanName, description == null ? null : description.trim());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", cleanName);
        result.put("itemCount", 0);
        return result;
    }

    @Transactional
    public void addToCollection(long collectionId, long assetId) {
        requireOwnedCollection(collectionId);
        requireVideo(assetId);
        jdbc.update("INSERT IGNORE INTO video_collection_items(collection_id,asset_id) VALUES (?,?)", collectionId, assetId);
    }

    @Transactional
    public void removeFromCollection(long collectionId, long assetId) {
        requireOwnedCollection(collectionId);
        jdbc.update("DELETE FROM video_collection_items WHERE collection_id=? AND asset_id=?", collectionId, assetId);
    }

    public List<Map<String, Object>> collectionItems(long collectionId) {
        requireOwnedCollection(collectionId);
        return jdbc.queryForList("""
                SELECT a.id AS assetId,COALESCE(NULLIF(a.video_title,''),a.file_name) AS title,
                       a.file_name AS fileName,m.name AS modelName,i.added_at AS addedAt
                FROM video_collection_items i JOIN product_assets a ON a.id=i.asset_id
                JOIN product_models m ON m.id=a.model_id
                WHERE i.collection_id=? AND a.deleted_at IS NULL ORDER BY i.sort_order,i.added_at DESC
                """, collectionId);
    }

    private String ownerKey() {
        var user = currentUsers.current();
        if (user.employeeId() != null) return "EMPLOYEE:" + user.employeeId();
        if (user.compatibilityMode()) return "COMPATIBILITY";
        throw new SecurityException("请先登录后使用个人视频工作区");
    }

    private void requireVideo(long assetId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM product_assets WHERE id=? AND category IN ('device_video','operation_video') AND deleted_at IS NULL", Long.class, assetId);
        if (count == null || count == 0) throw new IllegalArgumentException("视频不存在: " + assetId);
    }

    private void requireOwnedCollection(long collectionId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM video_collections WHERE id=? AND owner_key=?", Long.class, collectionId, ownerKey());
        if (count == null || count == 0) throw new IllegalArgumentException("视频清单不存在或无权访问");
    }
}
