package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ExpenseClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface ExpenseClaimRepository extends JpaRepository<ExpenseClaim, Long> {

    Optional<ExpenseClaim> findByExternalSourceAndExternalId(String externalSource, String externalId);

    /** Excel 导入兜底查重：API 同步写入的单 external_id=钉钉实例ID、claim_no=审批编号，需按 claim_no 再匹配一次。 */
    Optional<ExpenseClaim> findByClaimNo(String claimNo);

    @Query("""
            SELECT e FROM ExpenseClaim e
            WHERE (:keyword IS NULL OR :keyword = ''
                OR e.claimNo LIKE %:keyword%
                OR e.title LIKE %:keyword%
                OR e.submitterNameSnapshot LIKE %:keyword%
                OR e.remark LIKE %:keyword%)
              AND (:employeeId IS NULL OR e.employeeId = :employeeId)
              AND (:status IS NULL OR :status = '' OR e.approvalStatus = :status)
              AND (:category IS NULL OR :category = '' OR e.expenseCategory = :category)
              AND (:dateFrom IS NULL OR e.claimDate >= :dateFrom)
              AND (:dateTo IS NULL OR e.claimDate <= :dateTo)
            ORDER BY COALESCE(e.approvalFinishedDate, e.claimDate, e.createdAt) DESC
            """)
    Page<ExpenseClaim> searchPage(
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            @Param("status") String status,
            @Param("category") String category,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            Pageable pageable);

    @Query("SELECT COUNT(e) FROM ExpenseClaim e WHERE e.approvalStatus <> 'REJECTED' AND e.approvalStatus <> 'CANCELED' AND e.approvalStatus <> 'TERMINATED'")
    long countValidClaims();

    @Query("SELECT COALESCE(SUM(e.approvedAmount), 0) FROM ExpenseClaim e WHERE e.approvalStatus = 'APPROVED' AND MONTH(e.approvalFinishedDate) = MONTH(CURRENT_DATE) AND YEAR(e.approvalFinishedDate) = YEAR(CURRENT_DATE)")
    java.math.BigDecimal sumThisMonthApprovedAmount();

    @Query("SELECT COUNT(e) FROM ExpenseClaim e WHERE e.employeeId IS NULL AND (e.approvalStatus = 'APPROVED' OR e.approvalStatus = 'PENDING')")
    long countAwaitingMapping();
}
