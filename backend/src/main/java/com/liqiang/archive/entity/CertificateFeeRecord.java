package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "certificate_fee_records", indexes = {
        @Index(name = "idx_fee_certificate_id", columnList = "certificate_id")
    })
public class CertificateFeeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属证书 ID */
    @Column(name = "certificate_id", nullable = false)
    private Long certificateId;

    /** 年费年度，如第5年 */
    @Column(name = "fee_year")
    private Integer feeYear;

    /** 实际缴费日期 */
    @Column(name = "paid_date", nullable = false)
    private LocalDate paidDate;

    /** 缴费截止日期 */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 缴费金额（元） */
    @Column(precision = 10, scale = 2)
    private BigDecimal amount;

    /** 费用类型：年费/申请费/授权费/维持费/其他 */
    @Column(name = "fee_type", length = 50)
    private String feeType = "年费";

    /** 收据/流水号 */
    @Column(name = "receipt_no", length = 100)
    private String receiptNo;

    /** 订单编号（专利业务办理系统生成） */
    @Column(name = "order_no", length = 100)
    private String orderNo;

    /** 订单交易号（支付平台返回） */
    @Column(name = "transaction_no", length = 100)
    private String transactionNo;

    /** 票据抬头/公司名称 */
    @Column(name = "receipt_title", length = 200)
    private String receiptTitle;

    /** 发票状态：未开票/已开票/已归档 */
    @Column(name = "invoice_status", length = 20)
    private String invoiceStatus;

    /** 缴费人 */
    @Column(length = 100)
    private String payer;

    /** 备注 */
    @Column(columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CertificateFeeRecord() {
    }
}
