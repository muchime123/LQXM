package com.liqiang.archive.repository;

import com.liqiang.archive.dto.PackagingMoldDtos.VariantMoldSummary;
import com.liqiang.archive.entity.MoldVariantLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MoldVariantLinkRepository extends JpaRepository<MoldVariantLink, Long> {

    List<MoldVariantLink> findByMoldIdOrderByCreatedAtAsc(Long moldId);

    List<MoldVariantLink> findByVariantIdOrderByCreatedAtAsc(Long variantId);

    List<MoldVariantLink> findByMoldIdIn(List<Long> moldIds);

    @Query("SELECT l.mold.id, COUNT(l) FROM MoldVariantLink l WHERE l.mold.id IN :moldIds GROUP BY l.mold.id")
    List<Object[]> countByMoldIds(@Param("moldIds") List<Long> moldIds);

    @Query("SELECT l FROM MoldVariantLink l WHERE l.mold.id = :moldId AND l.variant.id = :variantId")
    List<MoldVariantLink> findByMoldAndVariant(@Param("moldId") Long moldId, @Param("variantId") Long variantId);

    /**
     * 将某规格下所有未软删的默认关联置为非默认。
     * <p>用于设置新默认模具时在同一事务内取消旧默认，配合 V53 部分唯一索引
     * {@code uk_variant_default_mold} 保证每规格最多一个默认模具。
     */
    @Modifying
    @Query("UPDATE MoldVariantLink l SET l.isDefault = false " +
            "WHERE l.variant.id = :variantId AND l.isDefault = true AND l.deletedAt IS NULL")
    int clearDefaultsByVariantId(@Param("variantId") Long variantId);

    /**
     * 按系列批量查询各规格的模具汇总：默认模具 + 总数，供规格列表卡片展示。
     * <p>通过 variant.model.modelId = :modelId 关联查询，仅统计未软删的关联和模具。
     */
    @Query("SELECT new com.liqiang.archive.dto.PackagingMoldDtos$VariantMoldSummary(" +
            "l.variant.id, " +
            "MAX(CASE WHEN l.isDefault = true THEN l.mold.id END), " +
            "MAX(CASE WHEN l.isDefault = true THEN l.mold.moldCode END), " +
            "MAX(CASE WHEN l.isDefault = true THEN l.mold.status END), " +
            "COUNT(DISTINCT l.mold.id)) " +
            "FROM MoldVariantLink l " +
            "WHERE l.variant.model.id = :modelId " +
            "  AND l.deletedAt IS NULL " +
            "  AND l.mold.deletedAt IS NULL " +
            "GROUP BY l.variant.id")
    List<VariantMoldSummary> summarizeByModelId(@Param("modelId") Long modelId);

    @Modifying
    @Query("UPDATE MoldVariantLink l SET l.deletedAt = CURRENT_TIMESTAMP WHERE l.mold.id = :moldId")
    void softDeleteByMoldId(@Param("moldId") Long moldId);

    /**
     * 跨系列关联：模具归属 model 与 规格归属 model 不一致。
     * 列顺序: moldId, moldCode, variantId, variantName, linkId,
     *         moldModelId, moldModelName, variantModelId, variantModelName
     */
    @Query(value = "SELECT m.id, m.mold_code, v.id, v.variant_name, l.id, " +
            "mm.id, mm.name, vm.id, vm.name " +
            "FROM mold_variant_links l " +
            "INNER JOIN packaging_molds m ON m.id = l.mold_id " +
            "INNER JOIN packaging_variants v ON v.id = l.variant_id " +
            "INNER JOIN packaging_models mm ON mm.id = m.model_id " +
            "INNER JOIN packaging_models vm ON vm.id = v.model_id " +
            "WHERE l.deleted_at IS NULL AND m.deleted_at IS NULL AND v.deleted_at IS NULL " +
            "  AND mm.id <> vm.id", nativeQuery = true)
    List<Object[]> findCrossModelLinkRows();

    /**
     * 默认模具被标记为停用/维修，但仍为该规格的默认模具。
     * 列顺序: moldId, moldCode, variantId, variantName,
     *         modelId, modelName, linkId, moldStatus
     */
    @Query(value = "SELECT m.id, m.mold_code, v.id, v.variant_name, " +
            "mm.id, mm.name, l.id, m.status " +
            "FROM mold_variant_links l " +
            "INNER JOIN packaging_molds m ON m.id = l.mold_id " +
            "INNER JOIN packaging_variants v ON v.id = l.variant_id " +
            "INNER JOIN packaging_models mm ON mm.id = v.model_id " +
            "WHERE l.deleted_at IS NULL AND m.deleted_at IS NULL AND v.deleted_at IS NULL " +
            "  AND l.is_default = TRUE AND m.status IN ('stopped', 'repair')", nativeQuery = true)
    List<Object[]> findDefaultMoldInactiveRows();

    /**
     * 关联的规格所属大系列已被软删除（残留引用）。
     * 列顺序: moldId, moldCode, variantId, variantName, modelId, modelName, linkId
     */
    @Query(value = "SELECT m.id, m.mold_code, v.id, v.variant_name, mm.id, mm.name, l.id " +
            "FROM mold_variant_links l " +
            "INNER JOIN packaging_molds m ON m.id = l.mold_id " +
            "INNER JOIN packaging_variants v ON v.id = l.variant_id " +
            "INNER JOIN packaging_models mm ON mm.id = v.model_id " +
            "WHERE l.deleted_at IS NULL AND m.deleted_at IS NULL AND v.deleted_at IS NULL " +
            "  AND mm.deleted_at IS NOT NULL", nativeQuery = true)
    List<Object[]> findMoldDeletedVariantReferenceRows();

    /**
     * 恢复指定模具的所有已软删规格关联（仅恢复规格及其所属系列均未软删的关联）。
     * <p>用于回收站恢复模具时，级联恢复随模具一起被软删的关联记录。
     * 跳过规格或规格所属系列已软删的关联，避免产生悬挂引用。
     */
    @Modifying
    @Query(value = "UPDATE mold_variant_links l " +
            "JOIN packaging_variants v ON v.id = l.variant_id " +
            "JOIN packaging_models m ON m.id = v.model_id " +
            "SET l.deleted_at = null " +
            "WHERE l.mold_id = :moldId " +
            "AND l.deleted_at IS NOT NULL " +
            "AND v.deleted_at IS NULL " +
            "AND m.deleted_at IS NULL", nativeQuery = true)
    int restoreByMoldId(@Param("moldId") Long moldId);

    /**
     * 物理删除指定模具与规格的软删除关联（绕过 @SQLRestriction）。
     * <p>用于重新创建关联前清理占用唯一索引的软删除记录，避免 409 冲突。
     */
    @Modifying
    @Query(value = "DELETE FROM mold_variant_links WHERE mold_id = :moldId AND variant_id = :variantId AND deleted_at IS NOT NULL", nativeQuery = true)
    int permanentDeleteSoftDeletedByMoldAndVariant(@Param("moldId") Long moldId, @Param("variantId") Long variantId);

    /**
     * 查找存在多条未软删默认关联的 variant（重复默认模具）。
     * 列顺序: variantId, defaultCount
     */
    @Query(value = "SELECT l.variant_id, COUNT(*) AS cnt " +
            "FROM mold_variant_links l " +
            "WHERE l.is_default = TRUE AND l.deleted_at IS NULL " +
            "GROUP BY l.variant_id HAVING COUNT(*) > 1", nativeQuery = true)
    List<Object[]> findDuplicateDefaultLinks();

    /**
     * 批量修复重复默认模具：对每个有多条默认关联的 variant，
     * 保留最早创建的一条（id 最小），其余置为 is_default = false。
     * <p>使用子查询定位需要清除的行：同 variant 下 id > MIN(id) 的默认关联。
     * @return 受影响行数
     */
    @Modifying
    @Query(value = "UPDATE mold_variant_links l SET l.is_default = FALSE " +
            "WHERE l.is_default = TRUE AND l.deleted_at IS NULL " +
            "AND l.id NOT IN (" +
            "  SELECT min_id FROM (" +
            "    SELECT MIN(l2.id) AS min_id " +
            "    FROM mold_variant_links l2 " +
            "    WHERE l2.is_default = TRUE AND l2.deleted_at IS NULL " +
            "    GROUP BY l2.variant_id" +
            "  ) keep_ids" +
            ") AND l.variant_id IN (" +
            "  SELECT l3.variant_id FROM mold_variant_links l3 " +
            "  WHERE l3.is_default = TRUE AND l3.deleted_at IS NULL " +
            "  GROUP BY l3.variant_id HAVING COUNT(*) > 1" +
            ")", nativeQuery = true)
    int fixDuplicateDefaultLinks();
}
