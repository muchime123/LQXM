package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "office_asset_records")
public class OfficeAssetRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long assetId;
    @Column(nullable = false, length = 30) private String action;
    @Column(length = 100) private String operatorName;
    @Column(length = 100) private String department;
    @Column(length = 100) private String custodian;
    @Column(length = 160) private String location;
    @Column(length = 500) private String notes;
    @CreationTimestamp @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
}
