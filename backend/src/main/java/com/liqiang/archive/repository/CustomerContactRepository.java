package com.liqiang.archive.repository;

import com.liqiang.archive.entity.CustomerContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, Long> {

    /** 主要联系人排前，其余按创建顺序（单客户）。 */
    List<CustomerContact> findByCustomerIdOrderByIsPrimaryDescIdAsc(Long customerId);

    /** 批量加载多个客户的联系人（列表页取主要联系方式用）。 */
    List<CustomerContact> findByCustomerIdIn(Collection<Long> customerIds);

    long countByCustomerId(Long customerId);

    void deleteByCustomerId(Long customerId);
}