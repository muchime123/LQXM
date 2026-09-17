package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ExpenseAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseAllocationRepository extends JpaRepository<ExpenseAllocation, Long> {

    /** 查某 claim 下所有分配（含 REVERSED 但不含 DRAFT 永久暂存）；P6.2e 详情页默认过 CONFIRMED 过滤。 */
    List<ExpenseAllocation> findByExpenseClaimIdOrderByIdAsc(Long claimId);

    /** 仅返回生效（CONFIRMED）分配；用于金额合计校验。 */
    List<ExpenseAllocation> findByExpenseClaimIdAndAllocationStatusOrderByIdAsc(Long claimId, String status);

    /** 按目标反向查询：某业务 / 部门 / 公司 / 对公付款下归集了哪些费用。 */
    List<ExpenseAllocation> findByTargetTypeAndTargetIdAndAllocationStatusOrderByIdDesc(String targetType, Long targetId, String status);

    /** 某 claim 下所有 CONFIRMED 分配合计金额（用于校验）。 */
    @Query("SELECT COALESCE(SUM(a.allocatedAmount), 0) FROM ExpenseAllocation a" +
           " WHERE a.expenseClaimId = :claimId AND a.allocationStatus = 'CONFIRMED'")
    BigDecimal sumConfirmedByClaim(@Param("claimId") Long claimId);

    /**
     * 批量按 claimIds 聚合 CONFIRMED 分配 sum + count；
     * 列表页用「一次 IN 查询」回填 20/40/80 条 claim 的归集进度，避免 N+1。
     * 返回 Object[3]{ expenseClaimId(Long), sum(BigDecimal), count(Long) }。
     */
    @Query("""
           SELECT a.expenseClaimId,
                  COALESCE(SUM(a.allocatedAmount), 0),
                  COUNT(a)
             FROM ExpenseAllocation a
            WHERE a.expenseClaimId IN :claimIds
              AND a.allocationStatus = 'CONFIRMED'
            GROUP BY a.expenseClaimId
           """)
    List<Object[]> sumAndCountConfirmedByClaimIds(@Param("claimIds") List<Long> claimIds);

    /** 同 claim + target 是否已有 CONFIRMED（UI 重复目标提示用）。 */
    Optional<ExpenseAllocation> findFirstByExpenseClaimIdAndTargetTypeAndTargetIdAndAllocationStatus(
            Long claimId, String targetType, Long targetId, String status);
}
