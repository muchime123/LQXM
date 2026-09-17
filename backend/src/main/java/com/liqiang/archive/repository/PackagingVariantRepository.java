package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackagingVariantRepository extends JpaRepository<PackagingVariant, Long> {

    @Query("select v from PackagingVariant v join fetch v.model m order by m.name, v.sortOrder, v.id")
    List<PackagingVariant> findAllActiveWithModel();

    List<PackagingVariant> findByModelIdOrderBySortOrderAscIdAsc(Long modelId);

    @Query("SELECT v.model.id, COUNT(v) FROM PackagingVariant v WHERE v.model.id IN :modelIds GROUP BY v.model.id")
    List<Object[]> countByModelIds(@Param("modelIds") List<Long> modelIds);

    // ====== 软删除/回收站 ======

    @Query(value = "SELECT * FROM packaging_variants WHERE model_id = :modelId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<PackagingVariant> findDeletedByModelId(@Param("modelId") Long modelId);

    @Modifying
    @Query("UPDATE PackagingVariant v SET v.deletedAt = null WHERE v.id = :id")
    void restoreById(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM packaging_variants WHERE id = :id", nativeQuery = true)
    void permanentDeleteById(@Param("id") Long id);

    @Query(value = "SELECT * FROM packaging_variants WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<PackagingVariant> findAllDeleted();

    /**
     * 没有任何 MoldVariantLink 的规格（规格无模具）。
     * 列顺序: variantId, variantName, modelId, modelName
     */
    @Query(value = "SELECT v.id, v.variant_name, m.id, m.name " +
            "FROM packaging_variants v " +
            "INNER JOIN packaging_models m ON m.id = v.model_id " +
            "WHERE v.deleted_at IS NULL AND m.deleted_at IS NULL AND NOT EXISTS (" +
            "  SELECT 1 FROM mold_variant_links l " +
            "  WHERE l.variant_id = v.id AND l.deleted_at IS NULL) " +
            "ORDER BY m.name, v.variant_name LIMIT 500", nativeQuery = true)
    List<Object[]> findVariantsWithoutMoldRaw();
}
