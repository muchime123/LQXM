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

@Entity
@Table(name = "website_company_milestones")
@Getter
@Setter
public class WebsiteCompanyMilestone {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "milestone_year", nullable = false)
    private Integer year;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
    @Column(nullable = false, length = 30)
    private String status = "DRAFT";
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
