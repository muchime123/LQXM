package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** 官网拥有的知识产权展示内容，与内部 Certificate 不建立关系。 */
@Entity
@Table(name = "website_ip_items")
@Getter @Setter
public class WebsiteIpItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 300)
    private String name;
    @Column(nullable = false, length = 50)
    private String category;
    @Column(name = "patent_type", length = 100)
    private String patentType;
    @Column(name = "public_number", length = 150)
    private String publicNumber;
    @Column(length = 300)
    private String applicant;
    @Column(name = "legal_status", length = 100)
    private String legalStatus;
    @Column(name = "media_id")
    private Long mediaId;
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
    @Column(nullable = false, length = 30)
    private String status = "DRAFT";
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
