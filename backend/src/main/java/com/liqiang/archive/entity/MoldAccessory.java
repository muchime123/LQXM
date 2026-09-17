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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 模具配件清单。
 * <p>原表"杯胆+底+拉伸头"拆分为多条配件记录，原始整格文本保留在 PackagingMold.rawAccessoriesText。
 * 不按高度分组（配件不严格对应单一高度，多高度同模换配件通过 variant 关系表达）。
 */
@Getter
@Setter
@Entity
@Table(name = "mold_accessories", indexes = {
        @Index(name = "idx_mold_acc_mold_id", columnList = "mold_id"),
        @Index(name = "idx_mold_acc_type", columnList = "accessory_type")
    })
@SQLRestriction("deleted_at IS NULL")
public class MoldAccessory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mold_id", nullable = false)
    private PackagingMold mold;

    /** 配件类型: cup_liner(杯胆) | cup_bottom(杯底) | drawing_head(拉伸头) | press_ring(压料环) | other。 */
    @NotBlank(message = "配件类型不能为空")
    @Size(max = 40)
    @Column(name = "accessory_type", nullable = false, length = 40)
    private String accessoryType;

    /** 配件编号（如有）。 */
    @Size(max = 80)
    @Column(name = "accessory_code", length = 80)
    private String accessoryCode;

    /** 数量。 */
    @Column(nullable = false)
    private Integer quantity = 1;

    /** 共用范围: dedicated(专用) | series_shared(系列通用) | global_shared(全局通用)。 */
    @Size(max = 20)
    @Column(name = "shared_scope", nullable = false, length = 20)
    private String sharedScope = "dedicated";

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
