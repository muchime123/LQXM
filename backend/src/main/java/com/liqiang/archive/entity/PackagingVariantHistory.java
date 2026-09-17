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

@Getter
@Setter
@Entity
@Table(name = "packaging_variant_history")
public class PackagingVariantHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private PackagingVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private PackagingModel model;

    @Column(name = "field_key", nullable = false, length = 80)
    private String fieldKey;

    @Column(name = "field_label", length = 120)
    private String fieldLabel;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "change_type", nullable = false, length = 40)
    private String changeType = "update";

    @Column(name = "change_source", nullable = false, length = 80)
    private String changeSource = "manual";

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "source_file", length = 500)
    private String sourceFile;

    @Column(name = "source_sheet", length = 200)
    private String sourceSheet;

    @Column(name = "changed_by", length = 120)
    private String changedBy;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
