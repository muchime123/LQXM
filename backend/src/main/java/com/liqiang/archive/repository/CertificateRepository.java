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

}
