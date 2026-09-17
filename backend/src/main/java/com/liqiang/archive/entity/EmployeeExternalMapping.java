package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 员工多系统身份映射（P5.1 统一员工体系）。
 * <p>
 * 一对多：同一员工可同时绑定多个外部系统账号：
 * <ul>
 *     <li>H3YUN 氚云 = OwnerId / 业务员姓名兜底</li>
 *     <li>DINGTALK 钉钉 = userId / 报销申请人</li>
 *     <li>SYSTEM = 本项目后台登录 username（未来接入权限时使用）</li>
 * </ul>
 *
 * 反查场景（从外部数据回填员工时）：
 * <pre>
 *   WHERE source_system = :system AND external_user_id = :id
 *   → 唯一索引 uk_eem_source_external 直接命中单行
 * </pre>
 *
 * @since V136
 */
@Getter
@Setter
@Entity
@Table(name = "employee_external_mappings", indexes = {
        @Index(name = "uk_eem_emp_source_external", columnList = "employee_id, source_system, external_user_id", unique = true),
        @Index(name = "uk_eem_source_external", columnList = "source_system, external_user_id", unique = true),
        @Index(name = "idx_eem_employee", columnList = "employee_id"),
        @Index(name = "idx_eem_source", columnList = "source_system"),
        @Index(name = "idx_eem_external_name", columnList = "external_name")
})
public class EmployeeExternalMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** H3YUN / DINGTALK / SYSTEM。 */
    @NotBlank(message = "来源系统不能为空")
    @Size(max = 20)
    @Column(name = "source_system", nullable = false, length = 20)
    private String sourceSystem;

    /** 外部用户标识：H3YUN=OwnerId / DINGTALK=userId / SYSTEM=login username。 */
    @NotBlank(message = "外部用户ID不能为空")
    @Size(max = 100)
    @Column(name = "external_user_id", nullable = false, length = 100)
    private String externalUserId;

    /** 外部显示名（可能与本名不同，兜底匹配用）。 */
    @Size(max = 100)
    @Column(name = "external_name", length = 100)
    private String externalName;

    @Size(max = 100)
    @Column(name = "external_department", length = 100)
    private String externalDepartment;

    /** 源系统状态。氚云授权状态不等同于内部员工在职状态。 */
    @Size(max = 50)
    @Column(name = "external_status", length = 50)
    private String externalStatus;

    @Size(max = 200)
    @Column(name = "external_position", length = 200)
    private String externalPosition;

    /** 氚云应用角色，仅留作来源快照，不用于内部岗位/RBAC 授权。 */
    @Size(max = 500)
    @Column(name = "external_roles", length = 500)
    private String externalRoles;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** 每个 source_system 下一般仅一个主映射。 */
    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = Boolean.FALSE;

    @Column(name = "mapped_at", nullable = false)
    private LocalDateTime mappedAt;

    @Size(max = 100)
    @Column(name = "mapped_by", length = 100)
    private String mappedBy;

    @Size(max = 500, message = "备注不能超过500字")
    @Column(length = 500)
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void ensureMappedAt() {
        if (mappedAt == null) mappedAt = LocalDateTime.now();
    }

    public EmployeeExternalMapping() {
    }
}
