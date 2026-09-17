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

/**
 * 包材模具主表。
 * <p>对应"利强模具规格.xlsx"中的模具资产层：模号、盒型代码、出模数、格数、
 * 表面结构、杯底刻字等。一个模具可生产多个规格（通过 mold_variant_links 关联）。
 */
@Getter
@Setter
@Entity
@Table(name = "packaging_molds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_packaging_mold_code", columnNames = "mold_code")
    }, indexes = {
        @Index(name = "idx_pkg_mold_model_id", columnList = "model_id"),
        @Index(name = "idx_pkg_mold_group_id", columnList = "group_id"),
        @Index(name = "idx_pkg_mold_base_code", columnList = "base_mold_code"),
        @Index(name = "idx_pkg_mold_status", columnList = "status"),
        @Index(name = "idx_pkg_mold_role", columnList = "mold_role"),
        @Index(name = "idx_pkg_mold_category", columnList = "mold_category")
    })
@SQLRestriction("deleted_at IS NULL")
public class PackagingMold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private PackagingModel model;

    /** 所属模具规格族，nullable 以兼容历史数据。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "group_id", nullable = true)
    private PackagingMoldGroup group;

    /** 完整模号，如 M20910-3F。唯一。 */
    @NotBlank(message = "模号不能为空")
    @Size(max = 80)
    @Column(name = "mold_code", nullable = false, length = 80)
    private String moldCode;

    /** 基础模号，如 M20910（解析尾部 -3F 后得到，便于按模具族聚合）。 */
    @Size(max = 60)
    @Column(name = "base_mold_code", length = 60)
    private String baseMoldCode;

    /** 盒型代码 F/B/W/G/WJ/FB，存原始值不强译，待业务确认字典。 */
    @Size(max = 20)
    @Column(name = "mold_type_code", length = 20)
    private String moldTypeCode;

    /** 出模数（一出几），从 -3F 解析数字部分。 */
    @Column(name = "cavity_count")
    private Integer cavityCount;

    /** 模具角色: main(主模) | accessory(配件模) | unknown。 */
    @Size(max = 20)
    @Column(name = "mold_role", nullable = false, length = 20)
    private String moldRole = "unknown";

    /** 业务分类: 方盒/圆杯/碗/桶/盖。 */
    @Size(max = 40)
    @Column(name = "mold_category", length = 40)
    private String moldCategory;

    /** 格数: 单格/双格/数字。 */
    @Size(max = 20)
    @Column(name = "compartment_count", length = 20)
    private String compartmentCount;

    /** 表面结构: 光面/圆筋/方筋/瓦楞/腐蚀。 */
    @Size(max = 60)
    @Column(name = "surface_structure", length = 60)
    private String surfaceStructure;

    /** 杯底刻字原文。 */
    @Size(max = 300)
    @Column(name = "bottom_engraving", length = 300)
    private String bottomEngraving;

    /** 刻字类型: blank | generic | brand_custom | functional。 */
    @Size(max = 20)
    @Column(name = "engraving_type", length = 20)
    private String engravingType;

    /** 配件原始整格文本（追溯用，拆分后仍保留原文）。 */
    @Size(max = 500)
    @Column(name = "raw_accessories_text", length = 500)
    private String rawAccessoriesText;

    /** 导入时原始口径（如 141×141 / Ø120 / C178.7），未匹配规格时仍可显示并辅助匹配。 */
    @Size(max = 80)
    @Column(name = "raw_caliber", length = 80)
    private String rawCaliber;

    /** 导入时原始高度（如 25+35+45+55 / 50），未匹配规格时仍可显示并辅助匹配。 */
    @Size(max = 200)
    @Column(name = "raw_height", length = 200)
    private String rawHeight;

    /** 状态: in_use(在用) | stopped(停用) | repair(维修) | unverified(待核实)。 */
    @Size(max = 20)
    @Column(name = "status", nullable = false, length = 20)
    private String status = "in_use";

    @Column(name = "website_enabled", nullable = false)
    private Boolean websiteEnabled = false;

    @Column(name = "website_sort_order", nullable = false)
    private Integer websiteSortOrder = 0;

    @Size(max = 1000)
    @Column(length = 1000)
    private String remark;

    // ====== 导入来源追溯 ======

    @Size(max = 500)
    @Column(name = "source_file", length = 500)
    private String sourceFile;

    @Size(max = 200)
    @Column(name = "source_sheet", length = 200)
    private String sourceSheet;

    @Column(name = "source_row_number")
    private Integer sourceRowNumber;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    // ====== 软删除 ======

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ====== 关联关系 ======

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "mold")
    @JsonIgnore
    private List<MoldVariantLink> variantLinks = new ArrayList<>();

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "mold")
    @JsonIgnore
    private List<MoldAccessory> accessories = new ArrayList<>();
}
