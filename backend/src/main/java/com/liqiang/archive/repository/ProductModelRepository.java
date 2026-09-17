package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ProductModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductModelRepository extends JpaRepository<ProductModel, Long> {

    Optional<ProductModel> findByName(String name);

    /** 官网 slug 查询。slug 唯一，未发布的产品 slug 为 NULL 不会命中。 */
    Optional<ProductModel> findByWebsiteSlug(String websiteSlug);

    List<ProductModel> findByIdIn(List<Long> ids);

    @Query("SELECT COALESCE(MAX(m.sortOrder), 0) FROM ProductModel m")
    Integer findMaxSortOrder();

    @Query("SELECT m FROM ProductModel m WHERE "
            + "(:keyword IS NULL OR :keyword = '' OR "
            + "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.modelCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.category) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:category IS NULL OR :category = '' OR m.category = :category) "
            + "AND (:status IS NULL OR :status = '' OR m.status = :status) "
            + "AND ("
            + "  :listFilter IS NULL OR :listFilter = '' OR "
            + "  (:listFilter = 'mainImage' AND (m.defaultImage IS NULL OR m.defaultImage = '')) OR "
            + "  (:listFilter = 'baseInfo' AND ("
            + "    m.name IS NULL OR m.name = '' OR "
            + "    m.category IS NULL OR m.category = '' OR "
            + "    m.status IS NULL OR m.status = ''"
            + "  )) OR "
            + "  (:listFilter = 'assets' AND NOT EXISTS (SELECT 1 FROM ProductAsset a WHERE a.model.id = m.id))"
            + ")")
    Page<ProductModel> searchFiltered(@Param("keyword") String keyword,
                                       @Param("category") String category,
                                       @Param("status") String status,
                                       @Param("listFilter") String listFilter,
                                       Pageable pageable);

    @Query("SELECT m FROM ProductModel m WHERE "
            + "(:keyword IS NULL OR :keyword = '' OR "
            + "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.modelCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(m.category) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ProductModel> search(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT DISTINCT m.category FROM ProductModel m WHERE m.category IS NOT NULL AND m.category <> '' ORDER BY m.category")
    List<String> findDistinctCategories();

    @Query("SELECT DISTINCT m.status FROM ProductModel m WHERE m.status IS NOT NULL AND m.status <> '' ORDER BY m.status")
    List<String> findDistinctStatuses();

    // ====== 软删除/回收站 ======

    /** 查询所有已软删除的产品 */
    @Query(value = "SELECT * FROM product_models WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<ProductModel> findDeleted();

    /** 恢复软删除 */
    @Modifying
    @Query("UPDATE ProductModel m SET m.deletedAt = null WHERE m.id = :id")
    void restoreById(@Param("id") Long id);

    /** 永久删除 */
    @Modifying
    @Query(value = "DELETE FROM product_models WHERE id = :id", nativeQuery = true)
    void permanentDeleteById(@Param("id") Long id);

    // ====== 官网发布 ======

    /**
     * 官网产品列表查询：只返回 website_enabled=true 的产品。
     * 支持关键词（匹配 name/modelCode/category）+ 分类筛选。
     */
    @Query(value = "SELECT m.* FROM product_models m WHERE m.website_enabled = TRUE "
            + "AND (:keyword = '' OR LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(m.model_code) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(m.category) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:category = '' OR m.category = :category) "
            + "AND (:application = '' OR JSON_CONTAINS(m.application_tags, JSON_QUOTE(:application))) "
            + "AND (:container = '' OR JSON_CONTAINS(m.container_tags, JSON_QUOTE(:container))) "
            + "AND (:process = '' OR JSON_CONTAINS(m.packaging_process_tags, JSON_QUOTE(:process))) "
            + "AND (:capacity = '' OR m.capacity_level = :capacity) "
            + "ORDER BY m.sort_order ASC, m.id ASC",
            countQuery = "SELECT COUNT(*) FROM product_models m WHERE m.website_enabled = TRUE "
                    + "AND (:keyword = '' OR LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) "
                    + "OR LOWER(m.model_code) LIKE LOWER(CONCAT('%', :keyword, '%')) "
                    + "OR LOWER(m.category) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
                    + "AND (:category = '' OR m.category = :category) "
                    + "AND (:application = '' OR JSON_CONTAINS(m.application_tags, JSON_QUOTE(:application))) "
                    + "AND (:container = '' OR JSON_CONTAINS(m.container_tags, JSON_QUOTE(:container))) "
                    + "AND (:process = '' OR JSON_CONTAINS(m.packaging_process_tags, JSON_QUOTE(:process))) "
                    + "AND (:capacity = '' OR m.capacity_level = :capacity)", nativeQuery = true)
    Page<ProductModel> searchWebsiteProducts(@Param("keyword") String keyword,
                                              @Param("category") String category,
                                              @Param("application") String application,
                                              @Param("container") String container,
                                              @Param("process") String process,
                                              @Param("capacity") String capacity,
                                              Pageable pageable);

    /** 官网已发布产品的分类列表（用于产品中心 Tab）。 */
    @Query("SELECT DISTINCT m.category FROM ProductModel m "
            + "WHERE m.websiteEnabled = true AND m.category IS NOT NULL AND m.category <> '' "
            + "ORDER BY m.category")
    List<String> findWebsiteCategories();

    /**
     * 官网首页精选产品：website_enabled=true AND website_featured=true，按 sortOrder 排序。
     * 用于首页「核心产品」区域，后台通过 website_featured 标记决定哪些产品上首页。
     */
    @Query("SELECT m FROM ProductModel m WHERE m.websiteEnabled = true AND m.websiteFeatured = true "
            + "ORDER BY m.sortOrder ASC, m.id ASC")
    List<ProductModel> findWebsiteFeaturedProducts(Pageable pageable);

    /** 所有已发布到官网的产品（官网工作台健康度检查用）。 */
    List<ProductModel> findByWebsiteEnabledTrue();

}
