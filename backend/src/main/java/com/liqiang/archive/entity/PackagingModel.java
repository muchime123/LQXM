package com.liqiang.archive.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "packaging_models", uniqueConstraints = {
        @UniqueConstraint(name = "uk_packaging_name", columnNames = "name")
    }, indexes = {
        @Index(name = "idx_packaging_kind", columnList = "packaging_kind"),
        @Index(name = "idx_packaging_material", columnList = "material"),
        @Index(name = "idx_packaging_status", columnList = "status")
    })
@SQLRestriction("deleted_at IS NULL")
public class PackagingModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "包材名称不能为空")
    @Size(max = 200, message = "包材名称不能超过200字")
    @Column(nullable = false, length = 200)
    private String name;

    @Size(max = 200)
    @Column(name = "name_en", length = 200)
    private String nameEn;

    @Size(max = 80)
    @Column(name = "model_code", length = 80)
    private String modelCode;

    // ====== 分类字段 ======

    /** 包材类型: container(打包装) | bowl(铝箔碗) | sealing_film(封口膜) */
    @Size(max = 60)
    @Column(name = "packaging_kind", length = 60)
    private String packagingKind;

    /** 膜形态: roll(卷膜) | sheet(片膜)，仅封口膜使用 */
    @Size(max = 60)
    @Column(name = "film_form", length = 60)
    private String filmForm;

    /** 形状: round | square | rectangle | oval */
    @Size(max = 60)
    @Column(name = "shape_type", length = 60)
    private String shapeType;

    /** 材质: PP / PET / 铝箔 / 镀铝 / 纯铝 / 万能膜 */
    @Size(max = 100)
    @Column(length = 100)
    private String material;

    /** 颜色/风格: 透明 / 黑色 / 白色 / 彩印 / 磨砂 */
    @Size(max = 100)
    @Column(name = "color_style", length = 100)
    private String colorStyle;

    /** 容量 (如 500ml) */
    @Size(max = 60)
    @Column(name = "capacity_ml", length = 60)
    private String capacityMl;

    /** 适用产品 */
    @Size(max = 300)
    @Column(length = 300)
    private String applicable;

    /** 产品亮点 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String advantages;

    // ====== 商业字段 ======

    /** 起订量 */
    @Size(max = 60)
    @Column(length = 60)
    private String moq;

    /** 参考单价 */
    @Size(max = 60)
    @Column(name = "unit_price", length = 60)
    private String unitPrice;

    /** 交期 */
    @Size(max = 60)
    @Column(name = "lead_time", length = 60)
    private String leadTime;

    /** 是否可定制 */
    @Column(nullable = false)
    private Boolean customizable = false;

    /** 状态: 在售 / 停产 / 预售 */
    @Size(max = 60)
    @Column(length = 60)
    private String status;

    /** 标签（逗号分隔） */
    @Size(max = 500)
    @Column(length = 500)
    private String tags;

    // ====== 展示字段 ======

    @Size(max = 500)
    @Column(length = 500)
    private String defaultImage;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "website_enabled", nullable = false)
    private Boolean websiteEnabled = false;

    @Column(name = "website_sort_order", nullable = false)
    private Integer websiteSortOrder = 0;

    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;

    // ====== 导入来源追溯 ======

    @Size(max = 500)
    @Column(length = 500)
    private String sourceFile;

    @Size(max = 200)
    @Column(length = 200)
    private String sourceSheet;

    private Integer sourceRowNumber;

    private Long importBatchId;

    // ====== 软删除 ======

    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ====== 关联关系 ======

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "model", orphanRemoval = true)
    @JsonIgnore
    private List<PackagingVariant> variants = new ArrayList<>();
}
