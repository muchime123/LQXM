package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 费用报销明细（1 条 claim = N 条消费明细）。
 * <p>
 * P6.1 仅做钉钉同步只读镜像；费用仅作台账留档。
 *
 * @since V138
 */
@Getter
@Setter
@Entity
@Table(name = "expense_items", indexes = {
        @Index(name = "idx_ei_claim", columnList = "expense_claim_id"),
        @Index(name = "idx_ei_external", columnList = "external_id"),
        @Index(name = "idx_ei_item_date", columnList = "item_date"),
        @Index(name = "idx_ei_type", columnList = "expense_type")
})
public class ExpenseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "expense_claim_id", nullable = false)
    private Long expenseClaimId;

    @Size(max = 100)
    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "sequence_no")
    private Integer sequenceNo;

    @Size(max = 50)
    @Column(name = "expense_type", length = 50)
    private String expenseType;

    @Size(max = 50)
    @Column(name = "category_snapshot", length = 50)
    private String categorySnapshot;

    @Column(name = "item_date")
    private LocalDate itemDate;

    @Size(max = 500)
    @Column(name = "item_desc", length = 500)
    private String itemDesc;

    // ===== 金额 =====
    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    // ===== 票据 =====
    @Size(max = 100)
    @Column(name = "invoice_no", length = 100)
    private String invoiceNo;

    @Size(max = 50)
    @Column(name = "receipt_type", length = 50)
    private String receiptType;

    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;

    @Column(name = "attachment_info", columnDefinition = "LONGTEXT")
    private String attachmentInfo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ExpenseItem() {
    }
}
