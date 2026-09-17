package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 官网询盘记录。
 * 由官网 Contact 页表单提交，进入档案后台供员工处理。
 * 字段与 Contact.vue 表单一致：联系人、公司、电话、邮箱、关注产品、需求描述。
 */
@Getter
@Setter
@Entity
@Table(name = "website_inquiries")
public class WebsiteInquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String company;

    @Column(nullable = false, length = 50)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(name = "product_focus", length = 100)
    private String productFocus;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 处理状态：pending / processed / closed。默认 pending，由后台员工手动流转。 */
    @Column(nullable = false, length = 20)
    private String status = "pending";

    /** 来源页面路径，例如 /contact，便于后台追溯。 */
    @Column(name = "source_page", length = 200)
    private String sourcePage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 后台员工处理时间，由后台手动流转时写入。 */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** 负责人（后台员工姓名）。 */
    @Column(length = 100)
    private String assignee;

    /** 跟进记录，支持多次追加。 */
    @Column(name = "follow_up_note", columnDefinition = "TEXT")
    private String followUpNote;

    /** 下次联系时间，用于提醒跟进。 */
    @Column(name = "next_contact_at")
    private LocalDateTime nextContactAt;

    /** 关闭原因（status=closed 时填写）。 */
    @Column(name = "closed_reason", length = 500)
    private String closedReason;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public WebsiteInquiry() {
    }
}
