package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 钉钉同步运行审计（1 次同步 = 1 条记录，含计数器 & 完整错误明细）。
 * <p>
 * 同步类型：P6.1 先实现 {@code DAILY_REIMBURSEMENT}（日常报销）。
 * 状态：RUNNING / SUCCESS / PARTIAL / FAILED / SKIPPED。
 * 触发：MANUAL 手动（前端「立即同步」按钮）/ SCHEDULED 定时（后续 @Scheduled）/ API 外部触发。
 *
 * @since V138
 */
@Getter
@Setter
@Entity
@Table(name = "dingtalk_sync_runs", indexes = {
        @Index(name = "idx_dsr_type_started", columnList = "sync_type, started_at"),
        @Index(name = "idx_dsr_status", columnList = "status")
})
public class DingtalkSyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 40)
    @Column(name = "sync_type", nullable = false, length = 40)
    private String syncType = "DAILY_REIMBURSEMENT";

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String status = "RUNNING";

    @NotBlank
    @Size(max = 40)
    @Column(name = "triggered_by", nullable = false, length = 40)
    private String triggeredBy = "MANUAL";

    @Size(max = 100)
    @Column(name = "triggered_by_name", length = 100)
    private String triggeredByName;

    // ===== 时间 =====
    @NotNull
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    // ===== 计数器 =====
    @Column(name = "fetched_count", nullable = false)
    private Integer fetchedCount = 0;

    @Column(name = "created_count", nullable = false)
    private Integer createdCount = 0;

    @Column(name = "updated_count", nullable = false)
    private Integer updatedCount = 0;

    @Column(name = "unchanged_count", nullable = false)
    private Integer unchangedCount = 0;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    @Column(name = "mapping_missed", nullable = false)
    private Integer mappingMissed = 0;

    @Column(name = "dept_mappings_created", nullable = false)
    private Integer deptMappingsCreated = 0;

    @Column(name = "dept_mappings_upgraded", nullable = false)
    private Integer deptMappingsUpgraded = 0;

    // ===== 错误 =====
    @Size(max = 500)
    @Column(name = "error_summary", length = 500)
    private String errorSummary;

    @Column(name = "error_details", columnDefinition = "LONGTEXT")
    private String errorDetails;

    // ===== 同步范围参数（便于管理员重跑同范围） =====
    @Column(name = "range_date_from")
    private LocalDate rangeDateFrom;

    @Column(name = "range_date_to")
    private LocalDate rangeDateTo;

    @Size(max = 1000)
    @Column(name = "extra_params", length = 1000)
    private String extraParams;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public DingtalkSyncRun() {
    }
}
