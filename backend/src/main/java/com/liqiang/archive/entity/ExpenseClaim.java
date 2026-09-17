package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 费用报销单（P6.1 钉钉日常报销同步只读事实层）。
 * <p>
 * 来源系统写入：DINGTALK = 钉钉审批流。
 * P6.1 不允许业务层直接改写本数据；归集关系走独立的 expense_allocations 表（P6.2 上线）。
 * 员工关联：优先 employee_id（DINGTALK userId → employee_external_mappings → employees），
 * 失败则置 NULL 但保留 dingtalk_user_id + submitter_name_snapshot 供管理员后续人工映射。
 *
 * @since V138
 */
@Getter
@Setter
@Entity
@Table(name = "expense_claims", indexes = {
        @Index(name = "uk_ec_external", columnList = "external_source, external_id", unique = true),
        @Index(name = "idx_ec_employee", columnList = "employee_id"),
        @Index(name = "idx_ec_dingtalk_user", columnList = "dingtalk_user_id"),
        @Index(name = "idx_ec_status", columnList = "approval_status"),
        @Index(name = "idx_ec_category", columnList = "expense_category"),
        @Index(name = "idx_ec_claim_date", columnList = "claim_date"),
        @Index(name = "idx_ec_finished_date", columnList = "approval_finished_date")
})
public class ExpenseClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 30)
    @Column(name = "external_source", nullable = false, length = 30)
    private String externalSource = "DINGTALK";

    @NotBlank
    @Size(max = 100)
    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Size(max = 100)
    @Column(name = "claim_no", length = 100)
    private String claimNo;

    @Size(max = 300)
    @Column(length = 300)
    private String title;

    // ===== 归属人 =====
    @Column(name = "employee_id")
    private Long employeeId;

    @Size(max = 100)
    @Column(name = "dingtalk_user_id", length = 100)
    private String dingtalkUserId;

    @Size(max = 100)
    @Column(name = "submitter_name_snapshot", length = 100)
    private String submitterNameSnapshot;

    @Size(max = 200)
    @Column(name = "submitter_dept_snapshot", length = 200)
    private String submitterDeptSnapshot;

    @Size(max = 50)
    @Column(name = "dingtalk_dept_id", length = 50)
    private String dingtalkDeptId;

    @Column(name = "department_id")
    private Long departmentId;

    // ===== 审批 & 分类 =====
    @NotBlank
    @Size(max = 30)
    @Column(name = "approval_status", nullable = false, length = 30)
    private String approvalStatus = "PENDING";

    @Size(max = 50)
    @Column(name = "expense_category", length = 50)
    private String expenseCategory;

    @Column(name = "claim_date")
    private LocalDate claimDate;

    @Column(name = "approval_finished_date")
    private LocalDate approvalFinishedDate;

    // ===== 金额 =====
    @Column(name = "total_amount", precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "approved_amount", precision = 14, scale = 2)
    private BigDecimal approvedAmount;

    @NotBlank
    @Size(max = 10)
    @Column(nullable = false, length = 10)
    private String currency = "CNY";

    // ===== 备注 & 附件 =====
    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;

    @Column(name = "attachment_info", columnDefinition = "LONGTEXT")
    private String attachmentInfo;

    // ===== 来源追溯（与 H3 fact 表同构） =====
    @Size(max = 200)
    @Column(name = "department_name", length = 200)
    private String departmentName;

    @Size(max = 100)
    @Column(name = "source_created_by", length = 100)
    private String sourceCreatedBy;

    @Column(name = "source_created_at")
    private LocalDateTime sourceCreatedAt;

    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    @Size(max = 64)
    @Column(name = "source_hash", length = 64, columnDefinition = "char(64)")
    private String sourceHash;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    // ===== 审计时间戳 =====
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ExpenseClaim() {
    }
}
