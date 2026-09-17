package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingMold;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PackagingMoldRepository extends JpaRepository<PackagingMold, Long> {

    Optional<PackagingMold> findByMoldCode(String moldCode);

    List<PackagingMold> findByMoldCodeIn(Collection<String> moldCodes);

    @Query("SELECT COUNT(m) FROM PackagingMold m WHERE m.group.id = :groupId")
    long countByGroupId(@Param("groupId") Long groupId);

    /**
     * 按模号、刻字、盒型、状态等过滤搜索。
     * keyword 命中范围: moldCode / baseMoldCode / bottomEngraving / moldCategory / rawAccessoriesText。
     */
    @Query("SELECT m FROM PackagingMold m WHERE "
            + "(:keyword IS NULL OR :keyword = '' OR "
            + "LOWER(m.moldCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.baseMoldCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.bottomEngraving) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.moldCategory) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.rawAccessoriesText) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:modelId IS NULL OR m.model.id = :modelId) "
            + "AND (:moldRole IS NULL OR :moldRole = '' OR m.moldRole = :moldRole) "
            + "AND (:moldTypeCode IS NULL OR :moldTypeCode = '' OR m.moldTypeCode = :moldTypeCode) "
            + "AND (:status IS NULL OR :status = '' OR m.status = :status) "
            + "AND (:engravingType IS NULL OR :engravingType = '' OR m.engravingType = :engravingType) "
            + "AND (:hasVariant IS NULL "
            + "     OR (:hasVariant = true AND EXISTS (SELECT 1 FROM MoldVariantLink l WHERE l.mold = m)) "
            + "     OR (:hasVariant = false AND NOT EXISTS (SELECT 1 FROM MoldVariantLink l WHERE l.mold = m)))")
    Page<PackagingMold> searchFiltered(@Param("keyword") String keyword,
                                        @Param("modelId") Long modelId,
                                        @Param("moldRole") String moldRole,
                                        @Param("moldTypeCode") String moldTypeCode,
                                        @Param("status") String status,
                                        @Param("engravingType") String engravingType,
                                        @Param("hasVariant") Boolean hasVariant,
                                        Pageable pageable);

    @Query("SELECT DISTINCT m.moldTypeCode FROM PackagingMold m WHERE m.moldTypeCode IS NOT NULL AND m.moldTypeCode <> '' ORDER BY m.moldTypeCode")
    List<String> findDistinctTypeCodes();

    @Query("SELECT DISTINCT m.status FROM PackagingMold m WHERE m.status IS NOT NULL AND m.status <> '' ORDER BY m.status")
    List<String> findDistinctStatuses();

    @Query("SELECT DISTINCT m.moldRole FROM PackagingMold m WHERE m.moldRole IS NOT NULL AND m.moldRole <> '' ORDER BY m.moldRole")
    List<String> findDistinctRoles();

    /**
     * 仅查询全部模号（轻量投影），用于导入预览阶段判断 new/merge，避免全量加载实体。
     */
    @Query("SELECT m.moldCode FROM PackagingMold m")
    List<String> findAllMoldCodes();

    /**
     * 有系列但未分配规格族的模具（包材详情页会落在未分组之外）。
     */
    List<PackagingMold> findByGroupIdIsNullAndModelIdIsNotNullAndDeletedAtIsNullOrderByMoldCodeAsc();

    /**
     * 未关联任何规格的模具（LEFT JOIN 排除法）。
     * 仅返回到 200 条以内，便于后续 UI 分桶展示；生产可用 SQL EXPLAIN 验证性能。
     */
    @Query(value = "SELECT m.* FROM packaging_molds m " +
            "WHERE m.deleted_at IS NULL AND NOT EXISTS (" +
            "  SELECT 1 FROM mold_variant_links l " +
            "  WHERE l.mold_id = m.id AND l.deleted_at IS NULL) " +
            "ORDER BY m.mold_code ASC LIMIT 500", nativeQuery = true)
    List<PackagingMold> findAllWithoutVariantLinks();

    /**
     * 模具归属系列与规格族归属系列不一致。
     * 列顺序: moldId, moldCode, groupId, groupName, moldModelId, moldModelName, groupModelId
     */
    @Query(value = "SELECT m.id, m.mold_code, g.id, g.name, " +
            "mm.id, mm.name, gm.id " +
            "FROM packaging_molds m " +
            "INNER JOIN packaging_models mm ON mm.id = m.model_id " +
            "INNER JOIN packaging_mold_groups g ON g.id = m.group_id " +
            "INNER JOIN packaging_models gm ON gm.id = g.model_id " +
            "WHERE m.deleted_at IS NULL AND g.deleted_at IS NULL " +
            "  AND m.model_id <> g.model_id", nativeQuery = true)
    List<Object[]> findMoldModelGroupMismatchesRaw();

    /**
     * 同一模号被分配到不同系列。
     * 列顺序: moldCode, modelCount
     */
    @Query(value = "SELECT m.mold_code, COUNT(DISTINCT m.model_id) cnt " +
            "FROM packaging_molds m " +
            "WHERE m.deleted_at IS NULL " +
            "GROUP BY m.mold_code " +
            "HAVING COUNT(DISTINCT m.model_id) > 1", nativeQuery = true)
    List<Object[]> findMoldCodeSharedCrossModelsRaw();

    /**
     * 批量修复双归属不一致：将 mold.model_id 回填为 mold.group.model_id。
     * <p>仅处理 mold 有 group 且二者 model_id 不一致的行。
     * @return 受影响行数
     */
    @Modifying
    @Query(value = "UPDATE packaging_molds m " +
            "SET m.model_id = g.model_id " +
            "FROM packaging_mold_groups g " +
            "WHERE m.group_id = g.id " +
            "AND m.deleted_at IS NULL AND g.deleted_at IS NULL " +
            "AND m.model_id <> g.model_id", nativeQuery = true)
    int fixMoldModelFromGroup();

    // ====== 统计计数（避免 searchFiltered 分页查询的性能浪费） ======

    long countByStatus(String status);

    long countByMoldRole(String moldRole);

    /** 已发布到官网的模具数量（官网工作台统计用）。 */
    long countByWebsiteEnabledTrue();

    // ====== 软删除/回收站 ======

    @Modifying
    @Query("UPDATE PackagingMold m SET m.deletedAt = null WHERE m.id = :id")
    void restoreById(@Param("id") Long id);

    /**
     * 查询已软删除但 mold_code 仍占用唯一索引的模具（绕过 @SQLRestriction）。
     * <p>用于导入确认前清理同名软删除记录，避免唯一索引冲突 (409 Conflict)。
     */
    @Query(value = "SELECT * FROM packaging_molds WHERE deleted_at IS NOT NULL AND mold_code IN :moldCodes",
            nativeQuery = true)
    List<PackagingMold> findDeletedByMoldCodeIn(@Param("moldCodes") Collection<String> moldCodes);
}
