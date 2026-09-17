package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
@Table(name = "product_variants", indexes = {
        @Index(name = "idx_variant_model_id", columnList = "model_id")
    })
@SQLRestriction("deleted_at IS NULL")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private ProductModel model;

    @Column(length = 220)
    private String variantName;

    /** Canonical identity for structured specifications; null for legacy/free-form copies. */
    @Column(length = 240)
    private String specKey;

    // ====== V2 字段（V109 删除 65 个 V1 旧字段，V110 新增 sort_order） ======

    /** V2 排序序号：variant 在 machine_tier 内的排序，默认 0（V110 添加 DB 列） */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean baseline = false;

    /**
     * 机型档位：标识该规格型号所属的机器物理尺寸档位（如 330 / 390 / 500）。
     * null 表示未关联机型（遗留数据或机型无关的规格）。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_tier_id")
    private ProductMachineTier machineTier;

    /** Canonical mold opening geometry. Height and capacity are deliberately excluded. */
    @Column(length = 20)
    private String moldSizeType;

    @Column(length = 40)
    private String moldLengthMm;

    @Column(length = 40)
    private String moldWidthMm;

    @Column(length = 40)
    private String moldDiameterMm;

    @Column(length = 100)
    private String moldSizeLabel;

    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;

    /** 软删除时间戳（NULL = 未删除） */
    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}