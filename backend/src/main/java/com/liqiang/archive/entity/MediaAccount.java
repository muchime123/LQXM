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
@Table(name = "media_accounts", indexes = {
        @Index(name = "idx_media_platform", columnList = "platform"),
        @Index(name = "idx_media_last_updated", columnList = "last_updated_at")
    })
public class MediaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String platform;  // 抖音/视频号/小红书/快手

    @Column(name = "account_name", nullable = false, length = 200)
    private String accountName;

    @Column(name = "account_id", length = 200)
    private String accountId;

    @Column(length = 500)
    private String link;

    @Column(length = 500)
    private String note;

    @Column(length = 100)
    private String operator;

    @Column(name = "publish_owner", length = 100)
    private String publishOwner;

    @Column(name = "likes_count")
    private Long likesCount;

    @Column(name = "followers_count")
    private Long followersCount;

    @Column(name = "video_count")
    private Long videoCount;

    @Column(length = 500)
    private String tags;  // 逗号分隔的标签列表

    @Column(name = "last_updated_at")
    private LocalDate lastUpdatedAt;

    /** 是否作为官网官方联系方式展示 */
    @Column(name = "website_official")
    private Boolean websiteOfficial;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public MediaAccount() {
    }
}
