package com.liqiang.archive.entity;

import jakarta.persistence.*;
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

@Getter
@Setter
@Entity
@Table(name = "office_assets")
@SQLRestriction("deleted_at IS NULL")
public class OfficeAsset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_code", nullable = false, unique = true, length = 40)
    private String assetCode;

    @NotBlank(message = "物品名称不能为空") @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @NotBlank(message = "物品分类不能为空") @Size(max = 60)
    @Column(nullable = false, length = 60)
    private String category;

    @Size(max = 80) private String brand;
    @Size(max = 100) private String model;
    @Size(max = 120) private String serialNumber;
    @Size(max = 100) private String department;
    @Size(max = 100) private String custodian;
    @Size(max = 160) private String location;
    @Size(max = 30) @Column(nullable = false) private String status = "在库";
    private LocalDate purchaseDate;
    @Column(precision = 12, scale = 2) private BigDecimal purchasePrice;
    @Size(max = 160) private String supplier;
    private LocalDate warrantyUntil;
    @Size(max = 1000) private String notes;
    private LocalDateTime lastInventoryAt;
    private LocalDateTime deletedAt;
    @CreationTimestamp @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @UpdateTimestamp @Column(nullable = false) private LocalDateTime updatedAt;
}
