package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "knowledge_articles")
public class KnowledgeArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(length = 500)
    private String tags;

    @Column(length = 100)
    private String author;

    @Column(length = 200)
    private String source;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "view_count")
    private Integer viewCount = 0;

    @Column(length = 20)
    private String status = "published";

    /** 是否展示到官网（仅 status=published 且该字段为 true 时才会在官网出现）。 */
    @Column(name = "website_visible", nullable = false)
    private Boolean websiteVisible = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public KnowledgeArticle() {
    }
}
