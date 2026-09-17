package com.liqiang.archive.repository;

import com.liqiang.archive.entity.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /**
     * 多条件分页搜索：关键词（姓名/工号/手机/邮箱）+ 部门 + 状态 + 是否业务员。
     */
    @Query("""
            SELECT e FROM Employee e
            WHERE (:department IS NULL OR e.department = :department)
              AND (:status IS NULL OR e.status = :status)
              AND (:isSalesperson IS NULL OR e.isSalesperson = :isSalesperson)
              AND (:accountStatus IS NULL OR
                   (:accountStatus = 'MISSING_PHONE' AND (e.phone IS NULL OR e.phone = '')) OR
                   (:accountStatus = 'MISSING_ACCOUNT' AND e.phone IS NOT NULL AND e.phone <> '' AND NOT EXISTS
                       (SELECT a.id FROM LoginAccount a WHERE a.employeeId = e.id)) OR
                   (:accountStatus = 'DISABLED' AND EXISTS
                       (SELECT a.id FROM LoginAccount a WHERE a.employeeId = e.id AND a.enabled = false)) OR
                   (:accountStatus = 'NORMAL' AND e.phone IS NOT NULL AND e.phone <> '' AND EXISTS
                       (SELECT a.id FROM LoginAccount a WHERE a.employeeId = e.id AND a.enabled = true)))
              AND (:keyword IS NULL OR
                   e.name LIKE CONCAT('%', :keyword, '%') OR
                   e.employeeNo LIKE CONCAT('%', :keyword, '%') OR
                   e.phone LIKE CONCAT('%', :keyword, '%') OR
                   e.email LIKE CONCAT('%', :keyword, '%') OR
                   e.position LIKE CONCAT('%', :keyword, '%'))
            ORDER BY e.department ASC NULLS LAST, e.employeeNo ASC NULLS LAST, e.id ASC
            """)
    Page<Employee> search(
            @Param("department") String department,
            @Param("status") String status,
            @Param("isSalesperson") Boolean isSalesperson,
            @Param("accountStatus") String accountStatus,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("SELECT DISTINCT e.department FROM Employee e WHERE e.department IS NOT NULL AND e.department <> '' ORDER BY e.department")
    List<String> findDepartments();

    Optional<Employee> findByNameAndPhone(String name, String phone);

    Optional<Employee> findByEmployeeNo(String employeeNo);

    /** 通过姓名精确查找（仅用于导入反查兜底；若姓名重复请结合手机号）。 */
    List<Employee> findByName(String name);

    /** 通过手机号精确查找（通讯录导入反查辅助；本地可能存旧号，仅作参考）。 */
    List<Employee> findByPhone(String phone);

    long countByStatus(String status);

    long countByIsSalespersonTrueAndStatus(String status);

    List<Employee> findByStatusOrderByNameAsc(String status);

    /** 下一个工号序列号（取现库 LQnnn 最大数值位，无则返回 0）。MySQL 原生查询。 */
    @Query(value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(employee_no, 3) AS UNSIGNED)), 0)
            FROM employees WHERE employee_no LIKE 'LQ%'
            """, nativeQuery = true)
    Long findMaxEmployeeLQSequence();
}
