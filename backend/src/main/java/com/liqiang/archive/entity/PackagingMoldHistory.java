package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 模具资产变更历史。
 * <p>记录 {@link PackagingMold} 关键字段（status / cavityCount / engravingType 等）
 * 的变更轨迹，对齐 PackagingVariantHistory 的设计，补齐模具状态机与审计闭环。
 */
@Getter
@Setter
@Entity
@Table(name = "packaging_mold_histories")
public class PackagingMoldHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mold_id", nullable = false)
    private PackagingMold mold;

    /** 变更字段键: status / cavityCount / engravingType / moldCategory / surfaceStructure / bottomEngraving / moldRole / moldTypeCode。 */
    @Column(name = "field_key", nullable = false, length = 80)
    private String fieldKey;

    /** 字段中文标签，便于前端直接展示。 */
    @Column(name = "field_label", length = 120)
    private String fieldLabel;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    /** status_change | field_update。 */
    @Column(name = "change_type", nullable = false, length = 40)
    private String changeType = "field_update";

    /** manual | import | migration。 */
    @Column(name = "change_source", nullable = false, length = 80)
    private String changeSource = "manual";

    /** 变更原因（状态流转时建议填写）。 */
    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "changed_by", length = 120)
    private String changedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
