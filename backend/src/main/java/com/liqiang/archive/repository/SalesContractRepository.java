package com.liqiang.archive.repository;

import com.liqiang.archive.entity.SalesContract;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SalesContractRepository extends JpaRepository<SalesContract, Long> {

    boolean existsByOrderNo(String orderNo);

    Optional<SalesContract> findByOrderNo(String orderNo);

    long countByOrderNoStartingWith(String prefix);

    /**
     * 多条件分页搜索：关键词匹配 订单编号/客户/业务员；明细产品名用 EXISTS 子查询匹配。
     * 年份按「签订时间或发货日期」任一命中。
     */
    @Query("""
            SELECT c FROM SalesContract c
            WHERE (:status IS NULL OR c.status = :status)
              AND (:orderType IS NULL OR c.orderType = :orderType)
              AND (:salesperson IS NULL OR c.salespersonName = :salesperson)
              AND (:year IS NULL OR YEAR(COALESCE(c.signedDate, c.shipDate)) = :year)
              AND (:customerId IS NULL OR c.customerId = :customerId)
              AND (:keyword IS NULL OR
                   c.orderNo LIKE CONCAT('%', :keyword, '%') OR
                   c.customerName LIKE CONCAT('%', :keyword, '%') OR
                   c.salespersonName LIKE CONCAT('%', :keyword, '%') OR
                   EXISTS (SELECT 1 FROM SalesContractItem i
                           WHERE i.contractId = c.id
                             AND (i.productName LIKE CONCAT('%', :keyword, '%')
                               OR i.spec LIKE CONCAT('%', :keyword, '%'))))
            """)
    Page<SalesContract> search(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("orderType") String orderType,
            @Param("salesperson") String salesperson,
            @Param("year") Integer year,
            @Param("customerId") Long customerId,
            Pageable pageable);

    @Query("SELECT DISTINCT c.salespersonName FROM SalesContract c WHERE c.salespersonName IS NOT NULL AND c.salespersonName <> '' ORDER BY c.salespersonName")
    List<String> findSalespersonNames();

    @Query("SELECT DISTINCT YEAR(COALESCE(c.signedDate, c.shipDate)) FROM SalesContract c WHERE COALESCE(c.signedDate, c.shipDate) IS NOT NULL ORDER BY YEAR(COALESCE(c.signedDate, c.shipDate)) DESC")
    List<Integer> findSignedYears();

    long countByStatus(String status);

    long countByOrderType(String orderType);

    @Query("SELECT COUNT(c) FROM SalesContract c WHERE YEAR(COALESCE(c.signedDate, c.shipDate)) = :year")
    long countByOrderDateYear(@Param("year") int year);

    @Query("SELECT COALESCE(SUM(c.totalAmount), 0) FROM SalesContract c WHERE YEAR(COALESCE(c.signedDate, c.shipDate)) = :year")
    BigDecimal sumTotalAmountByOrderDateYear(@Param("year") int year);

    @Query("SELECT COALESCE(SUM(c.totalAmount), 0) FROM SalesContract c")
    BigDecimal sumTotalAmountAll();

    long countByCustomerId(Long customerId);

    @Query("SELECT COALESCE(SUM(c.totalAmount), 0) FROM SalesContract c WHERE c.customerId = :customerId")
    BigDecimal sumTotalAmountByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT MIN(COALESCE(c.signedDate, c.shipDate)) FROM SalesContract c WHERE c.customerId = :customerId")
    LocalDate minOrderDateByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT MAX(COALESCE(c.signedDate, c.shipDate)) FROM SalesContract c WHERE c.customerId = :customerId")
    LocalDate maxOrderDateByCustomerId(@Param("customerId") Long customerId);

    @Query("SELECT c FROM SalesContract c WHERE c.customerId = :customerId ORDER BY COALESCE(c.signedDate, c.shipDate) DESC, c.id DESC")
    List<SalesContract> findByCustomerIdOrderByOrderDateDescIdDesc(@Param("customerId") Long customerId);
}