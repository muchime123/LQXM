package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "import_records")
public class ImportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(nullable = false)
    private int models;

    @Column(nullable = false)
    private int variants;

    @Column(nullable = false)
    private int assets;

    @Column(name = "skipped_rows", nullable = false)
    private int skippedRows;

    @Column(length = 20, nullable = false)
    private String mode;  // 'full_rebuild' | 'incremental'

    @Column(length = 20, nullable = false)
    private String status;  // 'success' | 'partial' | 'error'

    /** 模块来源: 'product'(产品导入) */
    @Column(length = 20, nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'product'")
    private String module = "product";

    @Column(length = 2000)
    private String message;

    /** 导入批次ID（自增，用于关联产品/配置/素材的 import_batch_id） */
    @Column(name = "batch_id")
    private Long batchId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ImportRecord() {
    }

    public ImportRecord(String fileName, int models, int variants, int assets,
                        int skippedRows, String mode, String status, String message) {
        this.fileName = fileName;
        this.models = models;
        this.variants = variants;
        this.assets = assets;
        this.skippedRows = skippedRows;
        this.mode = mode;
        this.status = status;
        this.message = message;
    }
}
