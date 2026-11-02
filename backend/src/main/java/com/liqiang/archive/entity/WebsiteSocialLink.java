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
@Table(name = "website_social_links")
@Getter
@Setter
public class WebsiteSocialLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 50)
    private String platform;
    @Column(name = "account_name", nullable = false, length = 200)
    private String accountName;
    @Column(nullable = false, length = 1000)
    private String link;
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
    @Column(nullable = false, length = 30)
    private String status = "DRAFT";
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
