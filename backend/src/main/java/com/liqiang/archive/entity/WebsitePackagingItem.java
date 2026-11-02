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

/** 官网独立包材系列；不引用内部包材档案 ID。 */
@Entity
@Table(name = "website_packaging_items")
@Getter
@Setter
public class WebsitePackagingItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String slug;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "packaging_kind", length = 80)
    private String packagingKind;

    @Column(length = 200)
    private String material;

    @Column(name = "shape_type", length = 120)
    private String shapeType;

    @Column(name = "capacity_ml", length = 100)
    private String capacityMl;

    @Column(columnDefinition = "TEXT")
    private String applicable;

    @Column(nullable = false)
    private Boolean customizable = false;

    @Column(name = "cover_media_id")
    private Long coverMediaId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
