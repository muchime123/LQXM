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
@Table(name = "product_model_packaging_links", uniqueConstraints =
        @UniqueConstraint(name = "uk_pmpl_model_packaging", columnNames = {"product_model_id", "packaging_variant_id"}))
public class ProductModelPackagingLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_model_id", nullable = false)
    private ProductModel productModel;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "packaging_variant_id", nullable = false)
    private PackagingVariant packagingVariant;
    @Column(nullable = false, length = 20)
    private String relationType = "COMPATIBLE";
    @Column(nullable = false, length = 20)
    private String matchSource = "MANUAL";
    @Column(length = 500)
    private String remark;
    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}
