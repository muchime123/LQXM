package com.liqiang.archive.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "certificates", indexes = {
        @Index(name = "idx_cert_asset_status", columnList = "asset_status"),
        @Index(name = "idx_cert_patent_type", columnList = "patent_type"),
        @Index(name = "idx_cert_fee_due_date", columnList = "fee_due_date"),
        @Index(name = "idx_cert_asset_category", columnList = "asset_category"),
        @Index(name = "idx_cert_deleted_at", columnList = "deleted_at")
    })
@SQLRestriction("deleted_at IS NULL")
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资产状态：有效/已失效/未生效/已转让 */
    @Column(name = "asset_status", nullable = false, length = 20)
    private String assetStatus = "有效";

    /** 资产类别：专利/软著/商标 */
    @Column(name = "asset_category", nullable = false, length = 20)
    private String assetCategory = "专利";

    /** 专利类型：发明专利/实用新型/外观设计/软著 */
    @Column(name = "patent_type", length = 20)
    private String patentType;

    /** 专利/证书名称 */
    @Column(nullable = false, length = 500)
    private String name;

    /** 说明书/证书文件名 */
    @Column(name = "spec_file", length = 500)
    private String specFile;

    /** 授权证书图片文件名 */
    @Column(name = "cert_file", length = 500)
    private String certFile;

    /** 公开号/公告号，如 CN118761014A */
    @Column(name = "publication_no", length = 100)
    private String publicationNo;

    /** 申请号，如 CN202411238193.4 */
    @Column(name = "application_no", length = 100)
    private String applicationNo;

    /** 查询用申请号（纯数字），如 2024112381934 */
    @Column(name = "query_no", length = 100)
    private String queryNo;

    /** 专利号/证书号，如 ZL202411238193.4 */
    @Column(name = "patent_no", length = 100)
    private String patentNo;

    /** 申请人（权利人） */
    @Column(length = 200)
    private String applicant;

    /** 发明人，多人用分号分隔 */
    @Column(length = 500)
    private String inventors;

    /** 代理机构，含机构号 */
    @Column(length = 500)
    private String agency;

    /** 申请日/登记日 */
    @Column(name = "apply_date")
    private LocalDate applyDate;

    /** 授权日/公告日 */
    @Column(name = "grant_date")
    private LocalDate grantDate;

    /** 法定保护年限（年）：发明20/外观设计15/实用新型10/软著50 */
    @Column(name = "legal_years")
    private Integer legalYears;

    /** 最晚到期日（申请日+法定年限） */
    @Column(name = "expire_date")
    private LocalDate expireDate;

    /** 法律状态：审中待授权/已授权有效/已失效等 */
    @Column(name = "legal_status", length = 50)
    private String legalStatus;

    /** 缴费状态：待询/已支付/未支付/已失效待缴费等 */
    @Column(name = "fee_status", length = 50)
    private String feeStatus;

    /** 下次年费查询日期 */
    @Column(name = "fee_query_date")
    private LocalDate feeQueryDate;

    /** 下次年费缴费截止日 */
    @Column(name = "fee_due_date")
    private LocalDate feeDueDate;

    /** 上次缴费金额（元） */
    @Column(name = "last_fee_amount", precision = 10, scale = 2)
    private BigDecimal lastFeeAmount;

    /** 是否有插图 */
    @Column(name = "has_drawing")
    private Boolean hasDrawing = false;

    /** 缴费明细/备注 */
    @Column(name = "fee_detail", length = 500)
    private String feeDetail;

    /** 发票状态：未开票/已开票/已归档 */
    @Column(name = "invoice_status", length = 20)
    private String invoiceStatus;

    /** 代理机构联系人 */
    @Column(name = "agency_contact", length = 100)
    private String agencyContact;

    /** 当前流程阶段：需求提出/方案确认/文件撰写/提交申请/过程跟进/授权归档/已授权维护 */
    @Column(name = "process_stage", length = 20)
    private String processStage;

    /** 审查进度备注（审查意见、补正记录等） */
    @Column(name = "review_progress", columnDefinition = "TEXT")
    private String reviewProgress;

    /** 备注/其他说明 */
    @Column(columnDefinition = "TEXT")
    private String remark;

    /** 国际分类号（尼斯分类1-45），仅商标使用 */
    @Column(name = "nice_class")
    private Integer niceClass;

    /** 核定使用商品/服务项目，仅商标使用 */
    @Column(name = "nice_class_description", columnDefinition = "TEXT")
    private String niceClassDescription;

    /** 注册人地址，仅商标使用 */
    @Column(name = "applicant_address", length = 500)
    private String applicantAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Certificate() {
    }
}
