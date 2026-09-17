package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "h3_import_rows")
public class H3ImportRow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "batch_id", nullable = false) private Long batchId;
    @Column(name = "external_id", length = 100) private String externalId;
    @Column(name = "source_row_number") private Integer rowNumber;
    @Lob @Column(name = "raw_data", nullable = false, columnDefinition = "LONGTEXT") private String rawData;
    @Column(name = "source_hash", length = 64, columnDefinition = "CHAR(64)") private String sourceHash;
    @Column(nullable = false, length = 20) private String action;
    @Column(name = "business_id") private Long businessId;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "processed_at") private LocalDateTime processedAt;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
