package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "media_account_snapshots")
public class MediaAccountSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "likes_count")
    private Long likesCount;

    @Column(name = "followers_count")
    private Long followersCount;

    @Column(name = "video_count")
    private Long videoCount;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MediaAccountSnapshot() {
    }

    public MediaAccountSnapshot(Long accountId, Long likesCount, Long followersCount,
                                 Long videoCount, LocalDate snapshotDate) {
        this.accountId = accountId;
        this.likesCount = likesCount;
        this.followersCount = followersCount;
        this.videoCount = videoCount;
        this.snapshotDate = snapshotDate;
    }
}