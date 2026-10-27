package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 官网专属媒体登记，存储路径始终位于 app.files.root/website 下。 */
@Entity
@Table(name = "website_media_assets")
@Getter
@Setter
public class WebsiteMediaAsset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    @Column(name = "display_name", length = 500)
    private String displayName;

    @Column(name = "media_type", nullable = false, length = 30)
    private String mediaType;

    @Column(name = "mime_type", nullable = false, length = 150)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    private Integer width;
    private Integer height;

    @Column(name = "duration_seconds")
    private java.math.BigDecimal durationSeconds;

    @Column(name = "alt_text", length = 500)
    private String altText;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
