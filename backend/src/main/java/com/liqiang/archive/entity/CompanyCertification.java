package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "company_certifications")
public class CompanyCertification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_en", length = 300)
    private String nameEn;

    @Column(nullable = false, length = 50)
    private String category = "其他";

    @Column(name = "cert_number", length = 100)
    private String certNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** 是否长期有效（营业执照等无明确到期日的资质）。为 true 时 expiryDate 可忽略。 */
    @Column(name = "is_permanent", nullable = false)
    private Boolean isPermanent = false;

    @Column(name = "issuing_authority", length = 200)
    private String issuingAuthority;

    @Column(name = "file_name", length = 500)
    private String fileName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 20)
    private String status = "有效";

    /** 是否在官网展示。false 时不出现在官网资质列表。默认 false，需手动开启。 */
    @Column(name = "website_enabled", nullable = false)
    private Boolean websiteEnabled = false;

    /** 是否在官网首页重点展示。 */
    @Column(name = "website_featured", nullable = false)
    private Boolean websiteFeatured = false;

    /** 官网排序（升序）。 */
    @Column(name = "website_sort_order", nullable = false)
    private Integer websiteSortOrder = 0;

    /** 官网公开描述，可与内部 description 不同。 */
    @Column(name = "public_description", columnDefinition = "TEXT")
    private String publicDescription;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CompanyCertification() {
    }
}
