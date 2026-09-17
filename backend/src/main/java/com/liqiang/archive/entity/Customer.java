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

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "customers", indexes = {
        @Index(name = "idx_customers_name", columnList = "customer_name"),
        @Index(name = "idx_customers_type", columnList = "customer_type"),
        @Index(name = "idx_customers_industry", columnList = "industry")
})
@SQLRestriction("deleted_at IS NULL")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "客户编号不能为空")
    @Size(max = 20)
    @Column(name = "customer_code", nullable = false, unique = true, length = 20)
    private String customerCode;

    @NotBlank(message = "客户名称不能为空")
    @Size(max = 200, message = "客户名称不能超过200字")
    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Size(max = 100, message = "客户简称不能超过100字")
    @Column(name = "short_name", length = 100)
    private String shortName;

    /** DB 存英文枚举（CustomerType），默认 OTHER。 */
    @Size(max = 20)
    @Column(name = "customer_type", nullable = false, length = 20)
    private String customerType = "OTHER";

    @Size(max = 100)
    @Column(length = 100)
    private String industry;

    @Size(max = 30)
    @Column(name = "credit_code", length = 30)
    private String creditCode;

    @Size(max = 50)
    @Column(length = 50)
    private String province;

    @Size(max = 50)
    @Column(length = 50)
    private String city;

    @Size(max = 50)
    @Column(length = 50)
    private String district;

    @Size(max = 300)
    @Column(length = 300)
    private String address;

    @Size(max = 200)
    @Column(length = 200)
    private String website;

    @Size(max = 50)
    @Column(length = 50)
    private String source;

    /** 客户等级：KEY / COMMON（人工维护，避免过度细分）。 */
    @Size(max = 20)
    @Column(length = 20)
    private String level;

    /** DB 存英文枚举（CustomerRelationStatus），默认 NORMAL。 */
    @Size(max = 20)
    @Column(name = "relation_status", nullable = false, length = 20)
    private String relationStatus = "NORMAL";

    /** 启用状态：ACTIVE / INACTIVE。 */
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 地址整理状态：NORMAL / NO_ADDRESS / MISSING / MISMATCH / UNCLEAR / UNRECOGNIZED。 */
    @Size(max = 20)
    @Column(name = "address_tidy_status", nullable = false, length = 20)
    private String addressTidyStatus = "NORMAL";

    @Size(max = 1000, message = "备注不能超过1000字")
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

    public Customer() {
    }
}
