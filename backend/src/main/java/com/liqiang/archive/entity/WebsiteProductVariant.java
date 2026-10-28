package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 官网公开规格草稿，不复用内部 ProductVariant ID 或尺寸。 */
@Entity
@Table(name = "website_product_variants")
@Getter
@Setter
public class WebsiteProductVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "public_sku", length = 200)
    private String publicSku;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 300)
    private String dimensions;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
