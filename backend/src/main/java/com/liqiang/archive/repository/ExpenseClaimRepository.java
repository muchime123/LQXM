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
              AND (:unallocatedOnly = false OR NOT EXISTS (
                    SELECT 1 FROM ExpenseAllocation a
                    WHERE a.expenseClaimId = e.id AND a.allocationStatus = 'CONFIRMED'))
            ORDER BY COALESCE(e.approvalFinishedDate, e.claimDate, e.createdAt) DESC
            """)
    Page<ExpenseClaim> searchPage(
            @Param("keyword") String keyword,
            @Param("employeeId") Long employeeId,
            @Param("status") String status,
            @Param("category") String category,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("unallocatedOnly") boolean unallocatedOnly,
            Pageable pageable);

    @Query("SELECT COUNT(e) FROM ExpenseClaim e WHERE e.approvalStatus <> 'REJECTED' AND e.approvalStatus <> 'CANCELED' AND e.approvalStatus <> 'TERMINATED'")
    long countValidClaims();

    @Query("SELECT COALESCE(SUM(e.approvedAmount), 0) FROM ExpenseClaim e WHERE e.approvalStatus = 'APPROVED' AND MONTH(e.approvalFinishedDate) = MONTH(CURRENT_DATE) AND YEAR(e.approvalFinishedDate) = YEAR(CURRENT_DATE)")
    java.math.BigDecimal sumThisMonthApprovedAmount();

    @Query("SELECT COUNT(e) FROM ExpenseClaim e WHERE e.employeeId IS NULL AND (e.approvalStatus = 'APPROVED' OR e.approvalStatus = 'PENDING')")
    long countAwaitingMapping();

    /**
     * P6.2 待归集合计：
     * 已通过审批（APPROVED）且 核定金额(或 fallback 申请金额)>0，但 没有任何 CONFIRMED 状态的 expense_allocations。
     * 注意：已审批通过是归集的前提（否则金额还不确定）。
     */
    @Query("""
           SELECT COUNT(e) FROM ExpenseClaim e
           WHERE e.approvalStatus = 'APPROVED'
             AND COALESCE(e.approvedAmount, e.totalAmount, 0) > 0
             AND NOT EXISTS (
                 SELECT 1 FROM ExpenseAllocation a
                 WHERE a.expenseClaimId = e.id AND a.allocationStatus = 'CONFIRMED'
             )
           """)
    long countPendingAllocation();

    /** 待归集核定金额合计（口径同 countPendingAllocation） */
    @Query("""
           SELECT COALESCE(SUM(COALESCE(e.approvedAmount, e.totalAmount, 0)), 0) FROM ExpenseClaim e
           WHERE e.approvalStatus = 'APPROVED'
             AND COALESCE(e.approvedAmount, e.totalAmount, 0) > 0
             AND NOT EXISTS (
                 SELECT 1 FROM ExpenseAllocation a
                 WHERE a.expenseClaimId = e.id AND a.allocationStatus = 'CONFIRMED'
             )
           """)
    java.math.BigDecimal sumPendingAllocationAmount();
}
