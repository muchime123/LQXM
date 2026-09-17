package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统一员工主表（P5.1 统一员工体系）。
 * <p>
 * 这是公司所有业务模块共享的"内部员工身份源"：
 * <ul>
 *     <li>业务员归属 = {@code employee_id}（取代 V127 前依赖的 salesperson_name 字符串匹配）</li>
 *     <li>钉钉报销申请人归属 = 通过 {@link EmployeeExternalMapping} 反查</li>
 *     <li>氚云 OwnerId 归属 = 通过 {@link EmployeeExternalMapping} 反查</li>
 *     <li>行级权限过滤 = 根据登录账号映射的 employee_id 决定可见范围</li>
 * </ul>
 *
 * 官网团队展示（company_team_members）表独立保留，不受本实体影响。
 *
 * @since V136
 */
@Getter
@Setter
@Entity
@Table(name = "employees", indexes = {
        @Index(name = "uk_employees_name_phone", columnList = "name, phone", unique = true),
        @Index(name = "uk_employees_employee_no", columnList = "employee_no", unique = true),
        @Index(name = "idx_employees_department", columnList = "department"),
        @Index(name = "idx_employees_status", columnList = "status"),
        @Index(name = "idx_employees_is_salesperson", columnList = "is_salesperson"),
        @Index(name = "idx_employees_name", columnList = "name")
})
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工号，如 LQ001；允许为空以便 UI 事后分配。 */
    @Size(max = 20)
    @Column(name = "employee_no", length = 20, unique = true)
    private String employeeNo;

    @NotBlank(message = "员工姓名不能为空")
    @Size(max = 100, message = "员工姓名不能超过100字")
    @Column(nullable = false, length = 100)
    private String name;

    /** MALE / FEMALE / OTHER / NULL。 */
    @Size(max = 10)
    @Column(length = 10)
    private String gender;

    @Size(max = 100)
    @Column(length = 100)
    private String department;

    @Column(name = "department_id")
    private Long departmentId;

    /** 所属公司主体；与工作部门分离，暂未确认时允许为空。 */
    @Column(name = "company_id")
    private Long companyId;

    @Size(max = 200)
    @Column(length = 200)
    private String position;

    @Size(max = 20)
    @Column(length = 20)
    private String phone;

    /** 身份证号（18 位，敏感字段：仅详情返回，列表摘要不输出）。 */
    @Size(max = 18, message = "身份证号不能超过18位")
    @Column(name = "id_card_number", length = 18)
    private String idCardNumber;

    @Size(max = 100)
    @Column(length = 100)
    private String email;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    @Column(name = "leave_date")
    private LocalDate leaveDate;

    /** ACTIVE / RESIGNED / ON_LEAVE。DB 存英文枚举，Service 层做中文翻译。 */
    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 是否业务员（影响提成归属、业务员维度行级权限）。 */
    @Column(name = "is_salesperson", nullable = false)
    private Boolean isSalesperson = Boolean.FALSE;

    @Size(max = 500, message = "备注不能超过500字")
    @Column(length = 500)
    private String remark;

    @Size(max = 100)
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Size(max = 100)
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Employee() {
    }
}
