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

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "packaging_assets", indexes = {
        @Index(name = "idx_pkg_asset_model_id", columnList = "model_id"),
        @Index(name = "idx_pkg_asset_category", columnList = "category")
    })
@SQLRestriction("deleted_at IS NULL")
public class PackagingAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private PackagingModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private PackagingVariant variant;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(length = 500)
    private String tags;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "default_asset", nullable = false)
    private Boolean defaultAsset = false;

    /** 自定义封面图片文件名（用于视频/Office等无法自动生成缩略图的文件） */
    @Column(name = "cover_file_name", length = 500)
    private String coverFileName;

    // ====== 导入来源追溯 ======

    @Size(max = 500)
    @Column(length = 500)
    private String sourceFile;

    @Size(max = 200)
    @Column(length = 200)
    private String sourceSheet;

    private Long importBatchId;

    // ====== 软删除 ======

    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
