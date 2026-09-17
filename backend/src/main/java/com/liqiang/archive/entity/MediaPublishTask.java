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
@Table(name = "media_publish_tasks", indexes = {
        @Index(name = "idx_publish_task_date", columnList = "publish_date"),
        @Index(name = "idx_publish_task_platform", columnList = "platform")
})
public class MediaPublishTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_name", nullable = false, length = 200)
    private String taskName;

    @Column(name = "publish_date", nullable = false)
    private LocalDate publishDate;

    @Column(length = 50)
    private String platform;

    @Column(name = "publish_software", length = 100)
    private String publishSoftware;

    @Column(name = "content_title", length = 200)
    private String contentTitle;

    @Column(name = "content_topic", length = 200)
    private String contentTopic;

    @Column(name = "content_url", length = 500)
    private String contentUrl;

    @Column(name = "editor_name", length = 50)
    private String editorName;

    @Column(name = "publisher_name", length = 50)
    private String publisherName;

    @Column(name = "publish_owner", length = 100)
    private String publishOwner;

    @Column(length = 500)
    private String note;

    @Column(name = "account_count")
    private Integer accountCount;

    @Column(name = "created_update_count")
    private Integer createdUpdateCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
