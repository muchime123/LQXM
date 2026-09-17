package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByModelIdOrderBySortOrderAsc(Long modelId);

    List<ProductVariant> findByIdIn(List<Long> ids);

    Optional<ProductVariant> findByModelIdAndSortOrder(Long modelId, int sourceRowNumber);

    Optional<ProductVariant> findFirstByModelIdAndSpecKey(Long modelId, String specKey);

    /** 按 ID 集合批量查询变体（避免循环内 findById 的 N+1） */
    List<ProductVariant> findByIdIn(Iterable<Long> ids);

    /** 统计某机型档位下未软删除的规格数量 */
    long countByMachineTierId(Long machineTierId);

    List<ProductVariant> findByMachineTierId(Long machineTierId);

    /**
     * 批量统计：按产品ID汇总配置数量
     */
    @Query("SELECT v.model.id, COUNT(v) FROM ProductVariant v WHERE v.model.id IN :modelIds GROUP BY v.model.id")
    List<Object[]> countByModelIds(@Param("modelIds") List<Long> modelIds);

    // ====== 软删除/回收站 ======

    /** 查询某产品下已软删除的配置 */
    @Query(value = "SELECT * FROM product_variants WHERE model_id = :modelId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<ProductVariant> findDeletedByModelId(@Param("modelId") Long modelId);

    /** 恢复软删除 */
    @Modifying
    @Query("UPDATE ProductVariant v SET v.deletedAt = null WHERE v.id = :id")
    void restoreById(@Param("id") Long id);

    /** 永久删除 */
    @Modifying
    @Query(value = "DELETE FROM product_variants WHERE id = :id", nativeQuery = true)
    void permanentDeleteById(@Param("id") Long id);

    /** 永久删除某产品下所有已软删除的配置 */
    @Modifying
    @Query(value = "DELETE FROM product_variants WHERE id IN :ids", nativeQuery = true)
    void permanentDeleteByIds(@Param("ids") List<Long> ids);

    /** 查询所有已软删除的配置（原生SQL绕过@SQLRestriction） */
    @Query(value = """
        SELECT v.*, m.name AS model_name
        FROM product_variants v
        LEFT JOIN product_models m ON v.model_id = m.id
        WHERE v.deleted_at IS NOT NULL
        ORDER BY v.deleted_at DESC
        """, nativeQuery = true)
    List<ProductVariant> findAllDeleted();
}
