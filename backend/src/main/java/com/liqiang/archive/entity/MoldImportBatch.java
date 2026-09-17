package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "mold_import_batches")
public class MoldImportBatch {
    @Id
    private Long id;
    @Column(name = "source_file", length = 500)
    private String sourceFile;
    @Column(name = "source_sheet", length = 200)
    private String sourceSheet;
    @Column(nullable = false, length = 20)
    private String status = "running";
    @Column(name = "mold_count", nullable = false)
    private int moldCount;
    @Column(name = "variant_link_count", nullable = false)
    private int variantLinkCount;
    @Column(name = "accessory_count", nullable = false)
    private int accessoryCount;
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
