package com.liqiang.archive.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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

/**
 * 模具规格族：从 Excel 产品名（如"1414方盒"）归一化而来，
 * 归属于一个包材大系列（PackagingModel），下含多个模具。
 * <p>
 * 层级：PackagingModel → PackagingMoldGroup → PackagingMold → MoldVariantLink → PackagingVariant
 */
@Getter
@Setter
@Entity
@Table(name = "packaging_mold_groups", indexes = {
        @Index(name = "idx_pkg_mold_group_model", columnList = "model_id"),
        @Index(name = "idx_pkg_mold_group_name", columnList = "name"),
        @Index(name = "idx_pkg_mold_group_normalized_key", columnList = "normalized_key")
})
@SQLRestriction("deleted_at IS NULL")
public class PackagingMoldGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private PackagingModel model;

    @NotBlank(message = "规格族名称不能为空")
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    /** 稳定规格族标识，如 RECT:140x140、ROUND:120；用于消除名称别名。 */
    @Size(max = 160)
    @Column(name = "normalized_key", length = 160)
    private String normalizedKey;

    @Size(max = 40)
    @Column(name = "shape_type", length = 40)
    private String shapeType;

    @Size(max = 40)
    @Column(name = "length_mm", length = 40)
    private String lengthMm;

    @Size(max = 40)
    @Column(name = "width_mm", length = 40)
    private String widthMm;

    @Size(max = 40)
    @Column(name = "diameter_mm", length = 40)
    private String diameterMm;

    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "group")
    @JsonIgnore
    private List<PackagingMold> molds = new ArrayList<>();
}
