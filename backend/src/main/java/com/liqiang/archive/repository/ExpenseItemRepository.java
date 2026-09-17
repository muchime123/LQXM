package com.liqiang.archive.repository;

import com.liqiang.archive.entity.ExpenseItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, Long> {

    List<ExpenseItem> findByExpenseClaimIdOrderBySequenceNoAsc(Long expenseClaimId);

    void deleteByExpenseClaimId(Long expenseClaimId);
}
