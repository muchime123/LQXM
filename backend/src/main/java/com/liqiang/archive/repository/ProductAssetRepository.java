package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductAssetRepository extends JpaRepository<ProductAsset, Long>, JpaSpecificationExecutor<ProductAsset> {

    List<ProductAsset> findByModelIdOrderBySortOrderAscIdAsc(Long modelId);

    List<ProductAsset> findByVariantIdOrderBySortOrderAscIdAsc(Long variantId);

    List<ProductAsset> findByModelIdAndCategoryOrderBySortOrderAscIdAsc(Long modelId, String category);

    List<ProductAsset> findByVariantIdAndCategoryOrderBySortOrderAscIdAsc(Long variantId, String category);

    Optional<ProductAsset> findFirstByModelIdAndCategoryAndFileName(Long modelId, String category, String fileName);

    Optional<ProductAsset> findFirstByModelIdAndCategoryAndFileNameAndVariantId(Long modelId, String category, String fileName, Long variantId);

    /**
     * 批量查询：按产品ID列表和类别查询所有素材（用于 defaultImage 的 N+1 优化，后续在内存中按 fileName 过滤）
     */
    @Query("SELECT a FROM ProductAsset a WHERE a.model.id IN :modelIds AND a.category = :category")
    List<ProductAsset> findAllByModelIdsAndCategory(@Param("modelIds") List<Long> modelIds, @Param("category") String category);

    long countByModelId(Long modelId);

    long countByMachineTiersId(Long machineTierId);

    /**
     * 批量统计：按产品ID和分类汇总素材数量
     */
    @Query("SELECT a.model.id, a.category, COUNT(a) FROM ProductAsset a WHERE a.model.id IN :modelIds GROUP BY a.model.id, a.category")
    List<Object[]> countByModelIdsGroupByCategory(@Param("modelIds") List<Long> modelIds);

    // ====== 软删除/回收站 ======

    /** 查询某产品下已软删除的素材 */
    @Query(value = "SELECT * FROM product_assets WHERE model_id = :modelId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<ProductAsset> findDeletedByModelId(@Param("modelId") Long modelId);

    /** 恢复软删除 */
    @Modifying
    @Query("UPDATE ProductAsset a SET a.deletedAt = null WHERE a.id = :id")
    void restoreById(@Param("id") Long id);

    /** 永久删除 */
    @Modifying
    @Query(value = "DELETE FROM product_assets WHERE id = :id", nativeQuery = true)
    void permanentDeleteById(@Param("id") Long id);

    /** 批量永久删除素材 */
    @Modifying
    @Query(value = "DELETE FROM product_assets WHERE id IN :ids", nativeQuery = true)
    void permanentDeleteByIds(@Param("ids") List<Long> ids);

    /** 查询某配置下已软删除的素材 */
    @Query(value = "SELECT * FROM product_assets WHERE variant_id = :variantId AND deleted_at IS NOT NULL", nativeQuery = true)
    List<ProductAsset> findDeletedByVariantId(@Param("variantId") Long variantId);

    /** 查询所有已软删除的素材（原生SQL绕过@SQLRestriction） */
    @Query(value = "SELECT * FROM product_assets WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<ProductAsset> findAllDeleted();

    /** 按 ID 查询素材，忽略软删除状态。用于直接永久删除本地文件时定位记录。 */
    @Query(value = "SELECT * FROM product_assets WHERE id = :id", nativeQuery = true)
    Optional<ProductAsset> findByIdIncludingDeleted(@Param("id") Long id);

    @Modifying
    @Query("UPDATE ProductAsset a SET a.variant = null WHERE a.variant.id IN :variantIds")
    void clearVariantIds(@Param("variantIds") List<Long> variantIds);
}
