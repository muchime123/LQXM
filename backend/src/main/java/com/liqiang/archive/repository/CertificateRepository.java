package com.liqiang.archive.repository;

import com.liqiang.archive.entity.Certificate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

    /**
     * 多条件分页搜索：资产状态、资产类别、专利类型、关键词（名称/专利号/申请号/纯数字查询号）
     */
    @Query("""
            SELECT c FROM Certificate c
            WHERE (:assetStatus IS NULL OR c.assetStatus = :assetStatus)
              AND (:assetCategory IS NULL OR c.assetCategory = :assetCategory)
              AND (:patentType IS NULL OR c.patentType = :patentType)
              AND (:feeStatus IS NULL OR c.feeStatus = :feeStatus)
            AND (:keyword IS NULL OR
                 c.name LIKE CONCAT('%', :keyword, '%') OR
                 c.patentNo LIKE CONCAT('%', :keyword, '%') OR
                 c.applicationNo LIKE CONCAT('%', :keyword, '%') OR
                 c.queryNo LIKE CONCAT('%', :keyword, '%') OR
                 c.publicationNo LIKE CONCAT('%', :keyword, '%') OR
                 c.inventors LIKE CONCAT('%', :keyword, '%') OR
                 c.applicant LIKE CONCAT('%', :keyword, '%'))
            """)
    Page<Certificate> search(
            @Param("assetStatus") String assetStatus,
            @Param("assetCategory") String assetCategory,
            @Param("patentType") String patentType,
            @Param("feeStatus") String feeStatus,
            @Param("keyword") String keyword,
            Pageable pageable);

    /** 统计各资产状态数量 */
    @Query("SELECT c.assetStatus, COUNT(c) FROM Certificate c GROUP BY c.assetStatus")
    List<Object[]> countByAssetStatus();

    /** 统计各专利类型数量 */
    @Query("SELECT c.patentType, COUNT(c) FROM Certificate c WHERE c.assetCategory = '专利' GROUP BY c.patentType")
    List<Object[]> countByPatentType();

    /** 查询 N 天内到期年费（fee_due_date between today and today+days） —— 排除已失效/已转让记录 */
    @Query("SELECT c FROM Certificate c WHERE c.assetStatus NOT IN ('EXPIRED', 'TRANSFERRED') AND c.feeDueDate BETWEEN :from AND :to ORDER BY c.feeDueDate ASC")
    List<Certificate> findDueSoon(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 查询 N 天内保护期到期（expire_date between today and today+days） —— 排除已失效/已转让记录 */
    @Query("SELECT c FROM Certificate c WHERE c.assetStatus NOT IN ('EXPIRED', 'TRANSFERRED') AND c.expireDate IS NOT NULL AND c.expireDate BETWEEN :from AND :to ORDER BY c.expireDate ASC")
    List<Certificate> findExpiringSoon(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 已失效记录数 */
    long countByAssetStatus(String assetStatus);

    /** 统计缴费状态 */
    @Query("SELECT c.feeStatus, COUNT(c) FROM Certificate c GROUP BY c.feeStatus")
    List<Object[]> countByFeeStatus();

    /** 统计各资产类别数量 */
    @Query("SELECT c.assetCategory, COUNT(c) FROM Certificate c GROUP BY c.assetCategory")
    List<Object[]> countByAssetCategory();

    /** 自动清理（deletedAt 早于 N 天前） */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM certificates WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    int autoCleanTrash(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 官网公开的证书/专利：website_enabled=true 且 asset_status='有效'。
     * 先按 website_featured DESC（精选在前），再 website_sort_order ASC，最后 grant_date DESC（最新授权在前）。
     * 用于「资质与创新成果」创新分区以及首页资质精选。
     */
    @Query("""
            SELECT c FROM Certificate c
            WHERE c.websiteEnabled = true
              AND c.assetStatus = 'ACTIVE'
            ORDER BY c.websiteFeatured DESC, c.websiteSortOrder ASC, c.grantDate DESC, c.id DESC
            """)
    List<Certificate> findAllWebsiteEnabled(Pageable pageable);

    /**
     * 官网公开专利分页查询：website_enabled=true 且 asset_status='有效'。
     * 支持按 asset_category（专利/软著/商标）和 patent_type（发明专利/实用新型/外观设计/软著）筛选，
     * 支持关键词搜索（名称/专利号/申请号/公开号/发明人/申请人）。
     * 排序：website_featured DESC, website_sort_order ASC, grant_date DESC, id DESC。
     */
    @Query("""
            SELECT c FROM Certificate c
            WHERE c.websiteEnabled = true
              AND c.assetStatus = 'ACTIVE'
              AND (:assetCategory IS NULL OR c.assetCategory = :assetCategory)
              AND (:patentType IS NULL OR c.patentType = :patentType)
              AND (:keyword IS NULL OR
                   c.name LIKE CONCAT('%', :keyword, '%') OR
                   c.patentNo LIKE CONCAT('%', :keyword, '%') OR
                   c.applicationNo LIKE CONCAT('%', :keyword, '%') OR
                   c.publicationNo LIKE CONCAT('%', :keyword, '%') OR
                   c.inventors LIKE CONCAT('%', :keyword, '%') OR
                   c.applicant LIKE CONCAT('%', :keyword, '%'))
            ORDER BY c.websiteFeatured DESC, c.websiteSortOrder ASC, c.grantDate DESC, c.id DESC
            """)
    Page<Certificate> searchWebsite(
            @Param("assetCategory") String assetCategory,
            @Param("patentType") String patentType,
            @Param("keyword") String keyword,
            Pageable pageable);

    /** 官网公开专利类型列表（去重，按出现频次降序）。 */
    @Query("""
            SELECT DISTINCT c.patentType FROM Certificate c
            WHERE c.websiteEnabled = true
              AND c.assetStatus = 'ACTIVE'
              AND c.patentType IS NOT NULL
              AND c.patentType <> ''
            ORDER BY c.patentType
            """)
    List<String> findWebsitePatentTypes();

    /** 官网公开资产类别列表（去重）。 */
    @Query("""
            SELECT DISTINCT c.assetCategory FROM Certificate c
            WHERE c.websiteEnabled = true
              AND c.assetStatus = 'ACTIVE'
              AND c.assetCategory IS NOT NULL
              AND c.assetCategory <> ''
            ORDER BY c.assetCategory
            """)
    List<String> findWebsiteAssetCategories();

    /** 官网公开专利总数（首页统计用）。 */
    @Query("""
            SELECT COUNT(c) FROM Certificate c
            WHERE c.websiteEnabled = true
              AND c.assetStatus = 'ACTIVE'
            """)
    long countWebsiteEnabled();

    /** 官网重点展示专利数（website_enabled=true + website_featured=true + 有效）。 */
    @Query("""
            SELECT COUNT(c) FROM Certificate c
            WHERE c.websiteEnabled = true
              AND c.websiteFeatured = true
              AND c.assetStatus = 'ACTIVE'
            """)
    long countWebsiteFeatured();
}
