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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "customer_contacts", indexes = {
        @Index(name = "idx_cc_customer", columnList = "customer_id")
})
public class CustomerContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @NotBlank(message = "联系人姓名不能为空")
    @Size(max = 50, message = "联系人姓名不能超过50字")
    @Column(nullable = false, length = 50)
    private String name;

    @Size(max = 50)
    @Column(length = 50)
    private String department;

    @Size(max = 50)
    @Column(length = 50)
    private String position;

    @Size(max = 30)
    @Column(length = 30)
    private String mobile;

    @Size(max = 50)
    @Column(length = 50)
    private String wechat;

    @Size(max = 100)
    @Column(length = 100)
    private String email;

    /** 联系人类型：决策人/采购/技术/财务/其他。 */
    @Size(max = 20)
    @Column(name = "contact_type", length = 20)
    private String contactType;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Size(max = 500)
    @Column(length = 500)
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CustomerContact() {
    }
}