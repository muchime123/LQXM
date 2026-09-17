package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CompanyCertification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CompanyCertificationRepository extends JpaRepository<CompanyCertification, Long> {

    @Query("SELECT c.category, COUNT(c) FROM CompanyCertification c GROUP BY c.category")
    List<Object[]> countByCategory();

    /** 查询 N 天内到期（expiry_date between today and today+days），排除长期有效 */
    @Query("SELECT c FROM CompanyCertification c WHERE c.isPermanent = false AND c.expiryDate IS NOT NULL AND c.expiryDate BETWEEN :from AND :to ORDER BY c.expiryDate ASC")
    List<CompanyCertification> findExpiringSoon(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 官网资质列表查询：关键词（匹配 name/certNumber/issuingAuthority）+ 分类筛选，分页。
     * 只返回 website_enabled=true 的资质，按 website_sort_order 升序排序。
     */
    @Query("SELECT c FROM CompanyCertification c WHERE c.websiteEnabled = true "
            + "AND (:keyword IS NULL OR :keyword = '' OR "
            + "LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(c.certNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(c.issuingAuthority) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:category IS NULL OR :category = '' OR c.category = :category)")
    Page<CompanyCertification> searchWebsiteCertifications(@Param("keyword") String keyword,
                                                            @Param("category") String category,
                                                            Pageable pageable);

    /** 官网已发布资质的分类列表。 */
    @Query("SELECT DISTINCT c.category FROM CompanyCertification c "
            + "WHERE c.websiteEnabled = true AND c.category IS NOT NULL AND c.category <> '' "
            + "ORDER BY c.category")
    List<String> findWebsiteCategories();

    /**
     * 官网资质页「全部公开资质」：不分页，用于分 Section 展示。
     * 只返回 website_enabled=true 的资质，按 website_sort_order 升序 + id 升序排列。
     */
    @Query("SELECT c FROM CompanyCertification c WHERE c.websiteEnabled = true "
            + "ORDER BY c.websiteSortOrder ASC, c.id ASC")
    List<CompanyCertification> findAllWebsiteEnabled();
}
