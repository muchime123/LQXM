package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "company_accounts")
public class CompanyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Column(name = "account_type", nullable = false, length = 50)
    private String accountType = "对公账户";

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "account_number", length = 100)
    private String accountNumber;

    @Column(name = "account_holder", length = 100)
    private String accountHolder;

    @Column(length = 20)
    private String currency = "CNY";

    @Column(name = "swift_code", length = 50)
    private String swiftCode;

    @Column(name = "iban", length = 50)
    private String iban;

    @Column(name = "routing_number", length = 50)
    private String routingNumber;

    @Column(name = "bank_address", length = 500)
    private String bankAddress;

    @Column(name = "bank_location", length = 100)
    private String bankLocation;

    @Column(name = "domestic_bank_code", length = 50)
    private String domesticBankCode;

    @Column(name = "qr_code_file", length = 500)
    private String qrCodeFile;

    @Column(name = "is_primary")
    private Boolean isPrimary = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CompanyAccount() {
    }
}
