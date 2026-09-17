package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "website_banners")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebsiteBanner {

    public enum BannerPosition {
        HERO,
        SIDE,
        ANNOUNCEMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "position", nullable = false, length = 30)
    private BannerPosition position;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "subtitle", length = 200)
    private String subtitle;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "image_asset_id")
    private Long imageAssetId;

    @Column(name = "button_text", length = 100)
    private String buttonText;

    @Column(name = "button_url", length = 500)
    private String buttonUrl;

    @Column(name = "secondary_button_text", length = 100)
    private String secondaryButtonText;

    @Column(name = "secondary_button_url", length = 500)
    private String secondaryButtonUrl;

    @Column(name = "badge", length = 100)
    private String badge;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "website_enabled", nullable = false)
    @Builder.Default
    private Boolean websiteEnabled = true;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Transient
    public boolean isActiveNow() {
        LocalDate today = LocalDate.now();
        if (startDate != null && today.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && today.isAfter(endDate)) {
            return false;
        }
        return Boolean.TRUE.equals(websiteEnabled) && deletedAt == null;
    }
}
