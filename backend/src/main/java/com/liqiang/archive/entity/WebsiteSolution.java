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
@Table(name = "website_solutions")
public class WebsiteSolution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private SolutionSection section;

    @Column(name = "item_key", length = 50)
    private String itemKey;

    @Column(length = 120, unique = true)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 50)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(length = 500)
    private String audience;

    @Column(length = 500)
    private String takeaway;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(length = 50)
    private String icon;

    @Column(columnDefinition = "JSON")
    private String points;

    @Column(name = "content_sections", columnDefinition = "JSON")
    private String contentSections;

    @Column(name = "content_html", columnDefinition = "TEXT")
    private String contentHtml;

    @Column(name = "related_scene_keys", columnDefinition = "JSON")
    private String relatedSceneKeys;

    @Column(name = "seo_title", length = 200)
    private String seoTitle;

    @Column(name = "seo_description", length = 500)
    private String seoDescription;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "website_enabled", nullable = false)
    private Boolean websiteEnabled = true;

    @Column(name = "website_featured", nullable = false)
    private Boolean websiteFeatured = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public WebsiteSolution() {
    }

    public enum SolutionSection {
        SOLUTION,
        INDUSTRY,
        PROCESS,
        ARTICLE
    }
}
