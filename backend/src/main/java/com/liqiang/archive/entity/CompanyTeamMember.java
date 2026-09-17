package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "company_team_members")
public class CompanyTeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 统一员工ID（employees.id），V176 新增；外部身份导入映射后回填。 */
    @Column(name = "employee_id")
    private Long employeeId;

    /** 外部系统成员ID（氚云 ObjectId），V186 新增；与 employee_external_mappings.H3YUN 对齐。 */
    @Column(name = "external_user_id", length = 100)
    private String externalUserId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String position;

    @Column(length = 100)
    private String department;

    @Column(length = 20)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "photo_file_name", length = 500)
    private String photoFileName;

    @Column(name = "is_core")
    private Boolean isCore;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CompanyTeamMember() {
    }
}
