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

import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 产品的实际机器配置档位。
 *
 * <p>同一台机器（如"利强手压片膜封口机"）按物理尺寸划分多个机型档位
 * （330 / 390 / 500），每个机型档位决定能配套的模具封口口径范围。
 * 口径范围用于推荐和校验适配包材，不生成产品规格。
 *
 * <p>口径范围字段全部 nullable：null 表示该维度无约束（如下限/上限之一为空），
 * min 与 max 同时为 null 表示该机型对该形状（圆/方）不做限制。
 */
@Getter
@Setter
@Entity
@Table(name = "product_machine_tiers", indexes = {
        @Index(name = "idx_pmt_model_id", columnList = "model_id")
    })
@SQLRestriction("deleted_at IS NULL")
public class ProductMachineTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private ProductModel model;

    /** 机型档位编码，如 "330" / "390" / "500"。同一产品下唯一（不区分大小写）。 */
    @Column(nullable = false, length = 40)
    private String tierCode;

    /** 机型展示名，如 "330机型"。 */
    @Column(length = 80)
    private String tierLabel;

    /** 圆形封口口径下限 mm（含）。null 表示无下限。 */
    @Column
    private BigDecimal roundDiameterMin;

    /** 圆形封口口径上限 mm（含）。null 表示无上限。 */
    @Column
    private BigDecimal roundDiameterMax;

    /** 方形封口长边下限 mm（含）。 */
    @Column
    private BigDecimal rectLengthMin;

    /** 方形封口长边上限 mm（含）。 */
    @Column
    private BigDecimal rectLengthMax;

    /** 方形封口短边下限 mm（含）。 */
    @Column
    private BigDecimal rectWidthMin;

    /** 方形封口短边上限 mm（含）。 */
    @Column
    private BigDecimal rectWidthMax;

    /** 机器尺寸，如 "420×420×700mm"。跟随机型档位走。 */
    @Column(length = 100)
    private String machineSize;

    /** 净重(kg)。跟随机型档位走。 */
    @Column(length = 100)
    private String netWeightKg;

    /** 纸箱包装尺寸，如 "500×500×800mm"。跟随机型档位走。 */
    @Column(length = 100)
    private String cartonSize;

    /** 木箱包装尺寸，如 "520×520×820mm"。跟随机型档位走。 */
    @Column(length = 100)
    private String woodBoxSize;

    /** 毛重(kg)。跟随机型档位走。 */
    @Column(length = 100)
    private String grossWeightKg;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Size(max = 500)
    @Column(length = 500)
    private String remark;

    /** 软删除时间戳（NULL = 未删除） */
    private LocalDateTime deletedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
