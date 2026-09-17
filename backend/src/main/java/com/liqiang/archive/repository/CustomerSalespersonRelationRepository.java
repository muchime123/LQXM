package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CustomerSalespersonRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface CustomerSalespersonRelationRepository extends JpaRepository<CustomerSalespersonRelation, Long> {

    /** 当前负责人（PRIMARY）优先，历史/协同随后，均按开始日期升序。 */
    List<CustomerSalespersonRelation> findByCustomerIdOrderByRoleAscStartDateAscIdAsc(Long customerId);

    /** 列表页批量取负责人姓名，避免 N+1。 */
    List<CustomerSalespersonRelation> findByCustomerIdIn(Collection<Long> customerIds);

    /** 筛选下拉：全部出现过的业务员姓名（去重）。 */
    @Query("""
            SELECT DISTINCT r.salespersonName FROM CustomerSalespersonRelation r
            WHERE r.salespersonName IS NOT NULL AND r.salespersonName <> ''
            ORDER BY r.salespersonName
            """)
    List<String> findDistinctSalespersonNames();

    void deleteByCustomerId(Long customerId);
}