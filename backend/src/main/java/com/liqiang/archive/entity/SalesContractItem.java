package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "sales_contract_items", indexes = {
        @Index(name = "idx_sci_contract", columnList = "contract_id")
})
public class SalesContractItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;


    @Column(name = "line_no", nullable = false)
    private Integer lineNo = 0;

    @Size(max = 300)
    @Column(name = "product_name", length = 300)
    private String productName;

    @Size(max = 300)
    @Column(length = 300)
    private String spec;

    @Column(precision = 14, scale = 4)
    private BigDecimal quantity;

    @Size(max = 20)
    @Column(length = 20)
    private String unit;

    @Column(name = "unit_price", precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_total", precision = 14, scale = 4)
    private BigDecimal lineTotal;

    @Size(max = 50)
    @Column(length = 50)
    private String category;

    @Size(max = 50)
    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "purchase_price", precision = 14, scale = 4)
    private BigDecimal purchasePrice;


    @Column(precision = 14, scale = 4)
    private BigDecimal profit;

    @Column(name = "stock_qty", precision = 14, scale = 4)
    private BigDecimal stockQty;

    @Size(max = 300)
    @Column(length = 300)
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public SalesContractItem() {
    }
}
