package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售订单（合同订单 + 普通订单，统一表）。
 * 合同订单：签订时间/金额大写/买方地址；普通订单：发货/仓库/付款/开票/利润等出库字段。
 */
@Getter
@Setter
@Entity
@Table(name = "sales_contracts", indexes = {
        @Index(name = "idx_sc_customer", columnList = "customer_id"),
        @Index(name = "idx_sc_signed_date", columnList = "signed_date"),
        @Index(name = "idx_sc_salesperson", columnList = "salesperson_name")
})
@SQLRestriction("deleted_at IS NULL")
public class SalesContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单编号（合同订单=ZJLQ...，普通订单=CK...）。 */
    @NotBlank(message = "订单编号不能为空")
    @Size(max = 50)
    @Column(name = "order_no", nullable = false, unique = true, length = 50)
    private String orderNo;

    /** 订单类型：CONTRACT（合同订单）/ NORMAL（普通订单）。 */
    @Size(max = 20)
    @Column(name = "order_type", nullable = false, length = 20)
    private String orderType = "CONTRACT";

    @Column(name = "external_source", nullable = false, length = 30)
    private String externalSource = "MANUAL";

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "source_hash", length = 64, columnDefinition = "CHAR(64)")
    private String sourceHash;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** 关联客户（无买方或未匹配时为空）。 */
    @Column(name = "customer_id")
    private Long customerId;

    /** 客户/买方名称快照，列表/筛选依赖它。 */
    @Size(max = 200)
    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Size(max = 300)
    @Column(name = "buyer_address", length = 300)
    private String buyerAddress;

    /** 关联公司成员 company_team_members.id。 */
    @Column(name = "salesperson_id")
    private Long salespersonId;

    @Size(max = 50)
    @Column(name = "salesperson_name", length = 50)
    private String salespersonName;


    /** 签订时间（合同订单）；普通订单用发货日期。 */
    private LocalDate signedDate;

    /** 发货日期（普通订单）。 */
    @Column(name = "ship_date")
    private LocalDate shipDate;

    @Size(max = 50)
    @Column(length = 50)
    private String region;

    @Size(max = 50)
    @Column(name = "shipping_method", length = 50)
    private String shippingMethod;

    @Column(name = "is_free_shipping")
    private Boolean isFreeShipping;

    @Column(precision = 14, scale = 2)
    private BigDecimal freight;

    @Size(max = 50)
    @Column(length = 50)
    private String warehouse;


    @Size(max = 20)
    @Column(name = "payment_status", length = 20)
    private String paymentStatus;

    @Column(name = "unreceived_amount", precision = 14, scale = 2)
    private BigDecimal unreceivedAmount;

    @Column(name = "total_purchase_amount", precision = 14, scale = 2)
    private BigDecimal totalPurchaseAmount;

    @Column(name = "total_sales_amount", precision = 14, scale = 2)
    private BigDecimal totalSalesAmount;

    @Column(name = "profit_amount", precision = 14, scale = 2)
    private BigDecimal profitAmount;

    @Column(name = "received_amount", precision = 14, scale = 2)
    private BigDecimal receivedAmount;

    @Column(name = "invoiced_amount", precision = 14, scale = 2)
    private BigDecimal invoicedAmount;

    @Column(name = "uninvoiced_amount", precision = 14, scale = 2)
    private BigDecimal uninvoicedAmount;

    @Size(max = 50)
    @Column(length = 50)
    private String invoicer;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Size(max = 50)
    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Size(max = 300)
    @Column(name = "customer_address", length = 300)
    private String customerAddress;

    /** 订单金额（合同订单=合同金额；普通订单=销售金额合计）。 */
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Size(max = 200)
    @Column(name = "amount_in_words", length = 200)
    private String amountInWords;

    /** 状态：EFFECTIVE（已生效）/ DRAFT（草稿）/ PROCESSING（进行中）。 */
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String status = "EFFECTIVE";

    @Column(name = "line_count", nullable = false)
    private Integer lineCount = 0;

    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;


    /** 软删除时间戳（NULL = 未删除）。 */
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public SalesContract() {
    }
}
