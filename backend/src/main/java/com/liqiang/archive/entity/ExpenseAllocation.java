package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * P6.2 费用归集分配实体。
 * <p>
 * 表达「1 条报销单 → 多目标」的分摊关系。目标 4 类：
 * <ul>
 *   <li>DEPARTMENT    → 部门 ID（管理会计口径：按部门统计费用）</li>
 *   <li>COMPANY       → 公司 ID（期间费用：无法分配到具体业务的公司级支出）</li>
 *   <li>CORP_PAYMENT  → 对公付款记录 ID（出纳对账：报销 vs 银行流水匹配）</li>
 * </ul>
 * 金额约束（Service 层，DB 用 CHECK + FK 粗拦截）：
 * 同 claim 下所有 CONFIRMED 分配合计 ≤ claim.approved_amount（fallback total_amount）。
 */
@Entity
@Table(name = "expense_allocations", indexes = {
        @Index(name = "idx_ea_claim",  columnList = "expense_claim_id"),
        @Index(name = "idx_ea_item",   columnList = "expense_item_id"),
        @Index(name = "idx_ea_target", columnList = "target_type, target_id"),
        @Index(name = "idx_ea_status", columnList = "allocation_status")
})
@Getter
@Setter
public class ExpenseAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expense_claim_id", nullable = false)
    private Long expenseClaimId;

    @Column(name = "expense_item_id")
    private Long expenseItemId;

    @Size(max = 30)
    @Column(name = "target_type", length = 30, nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Size(max = 100)
    @Column(name = "target_no_snapshot", length = 100)
    private String targetNoSnapshot;

    @Size(max = 300)
    @Column(name = "target_name_snapshot", length = 300)
    private String targetNameSnapshot;

    /** MANUAL / EXACT_OUTBOUND_NO / RECOMMENDED（V205 订单费用归单溯源）。 */
    @Size(max = 20)
    @Column(name = "match_method", length = 20)
    private String matchMethod;

    /** 推荐置信度 0~100；EXACT_OUTBOUND_NO 为 100。 */
    @Column(name = "confidence")
    private Integer confidence;

    /** PENDING → CONFIRMED 的确认人。 */
    @Size(max = 100)
    @Column(name = "confirmed_by", length = 100)
    private String confirmedBy;

    /** PENDING → CONFIRMED 的确认时间。 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "allocation_ratio_pct", precision = 9, scale = 4)
    private BigDecimal allocationRatioPct;

    @Column(name = "allocated_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal allocatedAmount;

    @Size(max = 20)
    @Column(name = "allocation_status", length = 20, nullable = false)
    private String allocationStatus = "CONFIRMED";

    /** MANUAL / AUTO_RULE / ADJUSTMENT。 */
    @Size(max = 30)
    @Column(name = "allocation_source", length = 30, nullable = false)
    private String allocationSource = "MANUAL";

    /** 命中 expense_allocation_rules.id（AUTO_RULE 时回写）。 */
    @Column(name = "allocation_rule_id")
    private Long allocationRuleId;

    /** 命中 expense_allocation_runs.id（自动归集的每次运行关联）。 */
    @Column(name = "allocation_run_id")
    private Long allocationRunId;

    @Size(max = 500)
    @Column(name = "remark", length = 500)
    private String remark;

    @Size(max = 100)
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.allocationStatus == null) this.allocationStatus = "CONFIRMED";
        if (this.allocatedAmount == null) this.allocatedAmount = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
