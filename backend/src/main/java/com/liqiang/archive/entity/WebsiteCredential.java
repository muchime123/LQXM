package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 官网专属资质展示记录。 */
@Entity
@Table(name = "website_credentials")
@Getter @Setter
public class WebsiteCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 300)
    private String name;
    @Column(length = 100)
    private String category;
    @Column(name = "public_description", columnDefinition = "TEXT")
    private String publicDescription;
    @Column(length = 200)
    private String issuer;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "media_id")
    private Long mediaId;
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
    @Column(nullable = false, length = 30)
    private String status = "DRAFT";
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
