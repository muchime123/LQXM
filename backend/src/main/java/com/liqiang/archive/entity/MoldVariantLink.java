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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 模具↔规格 关联表（N:N）。
 * <p>一个模具可生产多个规格（多高度同模换配件），一个规格也可由多个模具生产（可替换备选产能）。
 * relation_type 区分 main(主模)/backup(备用)/paired(配套)。
 */
@Getter
@Setter
@Entity
@Table(name = "mold_variant_links", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mold_variant", columnNames = {"mold_id", "variant_id"})
    }, indexes = {
        @Index(name = "idx_mvl_mold_id", columnList = "mold_id"),
        @Index(name = "idx_mvl_variant_id", columnList = "variant_id")
    })
@SQLRestriction("deleted_at IS NULL")
public class MoldVariantLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mold_id", nullable = false)
    private PackagingMold mold;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private PackagingVariant variant;

    /** 关系类型: main(主模) | backup(备用可替换) | paired(配套协同)。 */
    @Size(max = 20)
    @Column(name = "relation_type", nullable = false, length = 20)
    private String relationType = "main";

    /** 是否默认模具（一个规格可标记一个默认模具）。 */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Size(max = 500)
    @Column(length = 500)
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
}
