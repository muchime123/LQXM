package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CompanyAssetRepository extends JpaRepository<CompanyAsset, Long> {

    // ========== 正常查询（自动过滤 deleted_at IS NULL）==========

    /** 按分类查询，按 sortOrder 升序 */
    List<CompanyAsset> findByCategoryOrderBySortOrderAsc(String category);

    /** 查询所有资产，按分类+排序 */
    List<CompanyAsset> findAllByOrderByCategoryAscSortOrderAsc();

    // ========== 软删除相关（原生 SQL 绕过 @SQLRestriction）==========

    /** 软删除：设置 deleted_at */
    @Modifying
    @Query("UPDATE CompanyAsset a SET a.deletedAt = :now WHERE a.id = :id")
    int softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 恢复：清除 deleted_at */
    @Modifying
    @Query("UPDATE CompanyAsset a SET a.deletedAt = NULL WHERE a.id = :id")
    int restore(@Param("id") Long id);

    /** 查询回收站（包含已删除的）*/
    @Query(value = "SELECT * FROM company_assets WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<CompanyAsset> findDeleted();

    @Query(value = "SELECT * FROM company_assets WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    Optional<CompanyAsset> findDeletedById(@Param("id") Long id);

    /** 物理删除（仅用于永久删除回收站中的记录）*/
    @Modifying
    @Query(value = "DELETE FROM company_assets WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int permanentDelete(@Param("id") Long id);

    /** 取消某分类下所有默认标记 */
    @Modifying
    @Query("UPDATE CompanyAsset a SET a.defaultAsset = false WHERE a.category = :category AND a.defaultAsset = true")
    int clearDefaultInCategory(@Param("category") String category);
}
