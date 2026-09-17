package com.liqiang.archive.entity;

import jakarta.persistence.*;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "media_account_updates", indexes = {
        @Index(name = "idx_update_account_id", columnList = "account_id"),
        @Index(name = "idx_update_date", columnList = "update_date"),
        @Index(name = "idx_update_account_date", columnList = "account_id, update_date")
    })
public class MediaAccountUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 更新日期 */
    @Column(name = "update_date", nullable = false)
    private LocalDate updateDate;

    /** 更新类型：发视频、发图文、发笔记、直播 */
    @Column(name = "update_type", length = 20)
    private String updateType;

    /** 更新标题 */
    @Column(name = "title", length = 200)
    private String title;

    /** 内容链接 */
    @Column(name = "content_url", length = 500)
    private String contentUrl;

    /** 备注 */
    @Column(name = "note", length = 500)
    private String note;

    /** 操作人 */
    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "publish_task_id")
    private Long publishTaskId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public MediaAccountUpdate() {
    }
}
