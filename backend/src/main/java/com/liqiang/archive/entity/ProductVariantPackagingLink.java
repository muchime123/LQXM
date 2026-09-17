package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "product_variant_packaging_links", uniqueConstraints = {
        @UniqueConstraint(name = "uk_pvpl_variant_packaging", columnNames = {"product_variant_id", "packaging_variant_id"})
})
public class ProductVariantPackagingLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_variant_id", nullable = false)
    private ProductVariant productVariant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "packaging_variant_id", nullable = false)
    private PackagingVariant packagingVariant;

    /** V2 关系类型（V111 改名 relation_type，原 compatibility_status；枚举 COMPATIBLE / RECOMMENDED / COMMON） */
    @Column(name = "relation_type", nullable = false, length = 20)
    private String relationType = "COMPATIBLE";

    @Column(nullable = false, length = 20)
    private String matchSource = "MANUAL";

    /** V2 备注（V111 改名 remark，原 override_reason） */
    @Column(name = "remark", length = 500)
    private String remark;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
