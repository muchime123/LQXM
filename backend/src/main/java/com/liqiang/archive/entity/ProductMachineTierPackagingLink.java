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
@Table(name = "product_machine_tier_packaging_links", uniqueConstraints =
        @UniqueConstraint(name = "uk_pmtpl_tier_packaging", columnNames = {"machine_tier_id", "packaging_variant_id"}))
public class ProductMachineTierPackagingLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "machine_tier_id", nullable = false)
    private ProductMachineTier machineTier;
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
