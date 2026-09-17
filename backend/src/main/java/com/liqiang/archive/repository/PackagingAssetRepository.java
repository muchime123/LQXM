package com.liqiang.archive.repository;

import com.liqiang.archive.entity.PackagingAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PackagingAssetRepository extends JpaRepository<PackagingAsset, Long> {

    List<PackagingAsset> findByModelIdOrderBySortOrderAscIdAsc(Long modelId);

    List<PackagingAsset> findByVariantIdOrderBySortOrderAscIdAsc(Long variantId);

    @Query("SELECT a.model.id, COUNT(a) FROM PackagingAsset a WHERE a.model.id IN :modelIds GROUP BY a.model.id")
    List<Object[]> countByModelIds(@Param("modelIds") List<Long> modelIds);

    @Query("SELECT a FROM PackagingAsset a WHERE a.model.id IN :modelIds AND a.category = :category")
    List<PackagingAsset> findAllByModelIdsAndCategory(@Param("modelIds") List<Long> modelIds, @Param("category") String category);

    // ====== 软删除/回收站 ======

    @Modifying
    @Query("UPDATE PackagingAsset a SET a.deletedAt = null WHERE a.id = :id")
    void restoreById(@Param("id") Long id);

    @Modifying
    @Query(value = "DELETE FROM packaging_assets WHERE id = :id", nativeQuery = true)
    void permanentDeleteById(@Param("id") Long id);

    @Query(value = "SELECT * FROM packaging_assets WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    Optional<PackagingAsset> findDeletedById(@Param("id") Long id);
}
