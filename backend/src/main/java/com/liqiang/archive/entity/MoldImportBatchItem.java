package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "mold_import_batch_items")
public class MoldImportBatchItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "batch_id", nullable = false)
    private Long batchId;
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;
    @Column(name = "entity_id")
    private Long entityId;
    @Column(name = "operation_type", nullable = false, length = 20)
    private String operationType;
    @Column(name = "entity_label", length = 300)
    private String entityLabel;
    @Lob
    @Column(name = "before_snapshot", columnDefinition = "LONGTEXT")
    private String beforeSnapshot;
    @Lob
    @Column(name = "after_snapshot", columnDefinition = "LONGTEXT")
    private String afterSnapshot;
    @Column(name = "rollback_status", nullable = false, length = 20)
    private String rollbackStatus = "not_requested";
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
