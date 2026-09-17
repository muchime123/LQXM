package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 操作审计日志 — 记录产品档案的关键增删改操作。
 * 单人本地系统，不记录操作人，只记录实体类型、ID、动作、变更摘要和时间。
 */
@Getter
@Setter
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_action", columnList = "action")
    })
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 实体类型：model / variant / asset */
    @Column(nullable = false, length = 30)
    private String entityType;

    /** 实体主键 ID */
    @Column(nullable = false)
    private Long entityId;

    /** 关联产品名称（便于日志检索） */
    @Column(length = 200)
    private String modelName;

    /** 操作动作：create / update / delete / move / publish / batch-delete 等 */
    @Column(nullable = false, length = 64)
    private String action;

    /** 变更摘要（JSON 或简短文字描述） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String changes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ---- 工厂方法 ----

    public static AuditLog of(String entityType, Long entityId, String modelName, String action, String changes) {
        AuditLog log = new AuditLog();
        log.setEntityType(entityType);
        // 批量操作无单一实体 ID，用 0 占位（entity_id 列 NOT NULL）；
        // 历史数据也使用 0 表示批量操作，保持一致
        log.setEntityId(entityId != null ? entityId : 0L);
        log.setModelName(modelName);
        log.setAction(action);
        log.setChanges(changes);
        return log;
    }
}
