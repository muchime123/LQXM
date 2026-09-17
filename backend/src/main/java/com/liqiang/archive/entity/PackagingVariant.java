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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "packaging_variants", indexes = {
        @Index(name = "idx_pkg_variant_model_id", columnList = "model_id")
    })
@SQLRestriction("deleted_at IS NULL")
public class PackagingVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private PackagingModel model;

    @Size(max = 220)
    @Column(length = 220)
    private String variantName;

    // ====== 规格字段 ======

    @Column(name = "capacity_ml", length = 60)
    private String capacityMl;

    @Column(name = "shape_type", length = 60)
    private String shapeType;

    @Column(name = "length_mm", length = 60)
    private String lengthMm;

    @Column(name = "width_mm", length = 60)
    private String widthMm;

    @Column(name = "height_mm", length = 60)
    private String heightMm;

    @Column(name = "top_diameter_mm", length = 60)
    private String topDiameterMm;

    @Column(name = "bottom_diameter_mm", length = 60)
    private String bottomDiameterMm;

    @Column(name = "weight_g", length = 60)
    private String weightG;

    /** 封口膜专用: 5kg/卷可封1200盒 */
    @Column(name = "boxes_per_roll", length = 60)
    private String boxesPerRoll;

    /** 纸箱尺寸 (如 58×36×44) */
    @Column(name = "carton_size", length = 60)
    private String cartonSize;

    /** 整箱重量 kg */
    @Column(name = "carton_weight", length = 60)
    private String cartonWeight;

    /** 整箱体积 m³ */
    @Column(name = "carton_volume", length = 60)
    private String cartonVolume;

    /** 单规格起订量 */
    @Column(name = "variant_moq", length = 60)
    private String variantMoq;

    /** 详细材质说明 */
    @Column(name = "material_detail", length = 120)
    private String materialDetail;

    @Column(name = "film_material", length = 120)
    private String filmMaterial;

    @Column(name = "film_supply_type", length = 60)
    private String filmSupplyType;

    @Column(name = "film_features", length = 300)
    private String filmFeatures;

    @Column(name = "price_unit", length = 40)
    private String priceUnit;

    @Column(name = "pricing_notes", length = 1000)
    private String pricingNotes;

    // ====== 通用价格 ======

    @Column(name = "dealer_price", length = 100)
    private String dealerPrice;

    @Column(name = "retail_price", length = 100)
    private String retailPrice;

    @Column(name = "dealer_price_usd", length = 100)
    private String dealerPriceUsd;

    @Column(name = "retail_price_usd", length = 100)
    private String retailPriceUsd;

    // ====== 卷膜阶梯价（仅封口膜-卷膜使用） ======

    /** 易揭性: 易揭 / 不易揭 / 半易揭 */
    @Column(length = 60)
    private String peelability;

    /** 厚度: 8丝 / 10丝 */
    @Column(name = "thickness_um", length = 60)
    private String thicknessUm;

    /** 50-99 公斤 */
    @Column(name = "tier_50_99")
    private BigDecimal tier5099;

    /** 100-299 公斤 */
    @Column(name = "tier_100_299")
    private BigDecimal tier100299;

    /** 300-499 公斤 */
    @Column(name = "tier_300_499")
    private BigDecimal tier300499;

    /** 500-999 公斤 */
    @Column(name = "tier_500_999")
    private BigDecimal tier500999;

    /** 1000-1999 公斤 */
    @Column(name = "tier_1000_1999")
    private BigDecimal tier10001999;

    /** 2000 公斤以上 */
    @Column(name = "tier_2000_up")
    private BigDecimal tier2000Up;

    /** 版费 */
    private BigDecimal plateFee;

    // ====== 展示 ======

    @Column(nullable = false)
    private Integer sortOrder = 0;

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

    private Long importBatchId;

    // ====== 软删除 ======

    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
