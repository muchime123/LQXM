package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "h3_import_batches")
public class H3ImportBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @Column(name = "data_type", nullable = false, length = 30)
    private String dataType;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "total_count", nullable = false) private Integer totalCount = 0;
    @Column(name = "created_count", nullable = false) private Integer createdCount = 0;
    @Column(name = "updated_count", nullable = false) private Integer updatedCount = 0;
    @Column(name = "unchanged_count", nullable = false) private Integer unchangedCount = 0;
    @Column(name = "failed_count", nullable = false) private Integer failedCount = 0;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
}
