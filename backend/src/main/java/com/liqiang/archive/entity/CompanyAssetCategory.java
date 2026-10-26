package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 品牌资产子分类（品牌标识 / 印章与名称 / 工厂实景 等）
 *
 * 对应表 company_asset_categories，可在后台动态管理。
 * 文件存储路径为 company/<code>/<fileName>。
 */
@Getter
@Setter
@Entity
@Table(name = "company_asset_categories")
@SQLRestriction("deleted_at IS NULL")
public class CompanyAssetCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分类英文 slug（唯一），如 brand_logo */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    /** 分类显示名，如 品牌标识 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 排序权重，越小越靠前 */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /** 可选图标标识 */
    @Column(name = "icon", length = 100)
    private String icon;

    /** 软删除时间戳 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
