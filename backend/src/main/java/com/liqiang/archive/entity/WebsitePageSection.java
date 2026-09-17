package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 官网页面区块内容。
 * 将官网页面（首页/关于我们等）的标题、副标题、描述等展示文案纳入后台管理，
 * 替代前端硬编码的展示内容。一个 (pageKey, sectionKey) 对应官网页面上的一个区块。
 */
@Getter
@Setter
@Entity
@Table(name = "website_page_sections")
public class WebsitePageSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 页面标识：HOME / ABOUT / CONTACT。 */
    @Column(name = "page_key", nullable = false, length = 50)
    private String pageKey;

    /** 区块标识：页面内唯一，如 products / culture。 */
    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(length = 300)
    private String title;

    /** 副标题 / 区块描述。 */
    @Column(length = 1000)
    private String subtitle;

    /** 结构化内容（JSON），供列表型/复合型区块使用。 */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 区块主图地址（相对 /images/ 或完整 URL）。 */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 是否在官网展示。 */
    @Column(nullable = false)
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public WebsitePageSection() {
    }
}