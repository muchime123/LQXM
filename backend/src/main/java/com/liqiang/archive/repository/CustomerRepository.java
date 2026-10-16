package com.liqiang.archive.repository;

import com.liqiang.archive.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * 多条件分页搜索：
     * 关键词匹配 客户名称/简称/编号/信用代码，以及 联系人姓名/手机号（EXISTS 子查询避免多对多行膨胀）。
     * 负责人按业务员姓名过滤（EXISTS 子查询）。
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE (:customerType IS NULL OR c.customerType = :customerType)
              AND (:industry IS NULL OR c.industry = :industry)
              AND (COALESCE(:noRegion, 0) = 0 OR (c.province IS NULL OR c.province = ''))
              AND (COALESCE(:noRegion, 0) = 1 OR :province IS NULL OR c.province = :province)
              AND (COALESCE(:noRegion, 0) = 1 OR :city IS NULL OR c.city = :city)
              AND (COALESCE(:noRegion, 0) = 1 OR :district IS NULL OR c.district = :district)
              AND (:relationStatus IS NULL OR c.relationStatus = :relationStatus)
              AND (:status IS NULL OR c.status = :status)
              AND (:salesperson IS NULL OR EXISTS (
                    SELECT 1 FROM CustomerSalespersonRelation r
                    WHERE r.customerId = c.id AND r.salespersonName = :salesperson))
              AND (:keyword IS NULL OR
                   c.customerName LIKE CONCAT('%', :keyword, '%') OR
                   c.shortName LIKE CONCAT('%', :keyword, '%') OR
                   c.customerCode LIKE CONCAT('%', :keyword, '%') OR
                   c.creditCode LIKE CONCAT('%', :keyword, '%') OR
                   EXISTS (SELECT 1 FROM CustomerContact cc
                           WHERE cc.customerId = c.id
                             AND (cc.name LIKE CONCAT('%', :keyword, '%')
                               OR cc.mobile LIKE CONCAT('%', :keyword, '%'))))
            """)
    Page<Customer> search(
            @Param("customerType") String customerType,
            @Param("industry") String industry,
            @Param("province") String province,
            @Param("city") String city,
            @Param("district") String district,
            @Param("relationStatus") String relationStatus,
            @Param("status") String status,
            @Param("salesperson") String salesperson,
            @Param("keyword") String keyword,
            @Param("noRegion") Integer noRegion,
            Pageable pageable);

    @Query("SELECT DISTINCT c.industry FROM Customer c WHERE c.industry IS NOT NULL AND c.industry <> '' ORDER BY c.industry")
    List<String> findIndustries();

    @Query("""
            SELECT DISTINCT c.province FROM Customer c
            WHERE c.province IS NOT NULL AND c.province <> '' AND c.deletedAt IS NULL
            ORDER BY c.province
            """)
    List<String> findProvinces();

    /** 区域分布读取全部有效客户的地区字段；各行政层级由服务层分别判断，不要求三级同时完整。 */
    @Query("""
            SELECT c.province, c.city, c.district FROM Customer c
            WHERE c.deletedAt IS NULL
            """)
    List<Object[]> findProvinceCityDistrictTuples();

    /** 省份已合法但市县不完整或地区状态异常的客户（与客户列表“待补市县”筛选同口径）。 */
    @Query("""
            SELECT COUNT(c) FROM Customer c
            WHERE c.deletedAt IS NULL
              AND c.province IN (:officialProvinces)
              AND (c.city IS NULL OR c.city = '' OR c.district IS NULL OR c.district = ''
                   OR c.addressTidyStatus <> 'NORMAL')
            """)
    long countCustomersWithPartialRegion(@Param("officialProvinces") List<String> officialProvinces);

    /**
     * 未标注地区：省份为空或不是官方省名（上不了地图），且不是已判定的境外客户。
     * 省份已合法但市/区县缺失的客户属于「待补市县」，不计入未标注。
     */
    @Query("""
            SELECT COUNT(c) FROM Customer c
            WHERE c.deletedAt IS NULL
              AND (c.province IS NULL OR c.province = ''
                   OR (c.province NOT IN (:officialProvinces) AND c.province <> :overseas))
            """)
    long countCustomersWithoutProvince(@Param("officialProvinces") List<String> officialProvinces,
                                       @Param("overseas") String overseas);

    /** 已判定为境外的客户数（province=「境外」）：不参与大陆省级分布与地区治理。 */
    @Query("""
            SELECT COUNT(c) FROM Customer c
            WHERE c.deletedAt IS NULL AND c.province = :overseas
            """)
    long countOverseasCustomers(@Param("overseas") String overseas);

    /** 下一个客户编号序号（取现库中最大 CUS 序号，无则返回 0）。MySQL 原生查询。 */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(customer_code, 4) AS UNSIGNED)), 0)
            FROM customers WHERE customer_code LIKE 'CUS%'
            """, nativeQuery = true)
    Long findMaxCustomerSequence();

    Optional<Customer> findByCustomerName(String customerName);

    /**
     * 「就地新增客户」查重：同名且未删除的客户（取最早一条）。
     * 客户档案是客户身份的锚点，业务页面快速建档时不能凭同名再建一条，否则订单/出库口径会分叉。
     */
}
