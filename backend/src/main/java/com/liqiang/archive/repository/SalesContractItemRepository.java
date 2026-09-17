package com.liqiang.archive.repository;

import com.liqiang.archive.entity.SalesContractItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SalesContractItemRepository extends JpaRepository<SalesContractItem, Long> {

    List<SalesContractItem> findByContractIdOrderByLineNoAsc(Long contractId);

    void deleteByContractId(Long contractId);

    List<SalesContractItem> findByContractIdInOrderByContractIdAscLineNoAsc(List<Long> contractIds);

    /** 某客户历史采购过的产品名称（去重，按名称排序）。 */
    @Query("""
            SELECT DISTINCT i.productName FROM SalesContractItem i
            WHERE i.productName IS NOT NULL AND i.productName <> ''
              AND i.contractId IN (
                    SELECT c.id FROM SalesContract c WHERE c.customerId = :customerId)
            ORDER BY i.productName
            """)
    List<String> findDistinctProductNamesByCustomerId(@Param("customerId") Long customerId);
}