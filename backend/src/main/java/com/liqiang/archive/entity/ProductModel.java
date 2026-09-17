package com.liqiang.archive.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "product_models", uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_models_name", columnNames = "name")
    })
@SQLRestriction("deleted_at IS NULL")
public class ProductModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "产品名称不能为空")
    @Size(max = 200, message = "产品名称不能超过200字")
    @Column(nullable = false, length = 200)
    private String name;

    /** 产品英文名称 */
    @Size(max = 200)
    @Column(length = 200)
    private String nameEn;

    @Size(max = 80)
    @Column(length = 80)
    private String modelCode;

    // ====== V2 字段（V108 起 V1 旧字段全部删除） ======

    /** V2 产品分类（合并 productLineName / equipmentCategory / itemType） */
    @Size(max = 100)
    @Column(length = 100)
    private String category;

    /** V2 中文描述（合并 equipmentAdvantages + coreConfigDescription） */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String descriptionCn;

    /** V2 英文描述（合并 equipmentAdvantagesEn + coreConfigDescriptionEn） */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String descriptionEn;

    /** V2 上架状态（替代 saleStatus，枚举 ACTIVE / DISCONTINUED / PREVIEW） */
    @Size(max = 50)
    @Column(length = 50)
    private String status;

    /**
     * 店铺链接（淘宝/天猫/京东/1688/Shopify 等电商详情页 URL）。
     * V108 删除后于 V121 重新加回；前端在产品概览页点击后新窗口跳转。
     */
    @Size(max = 500)
    @Column(name = "shop_url", length = 500)
    private String shopUrl;

    @Size(max = 500)
    @Column(length = 500)
    private String defaultImage;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Size(max = 1000, message = "备注不能超过1000字")
    @Column(length = 1000)
    private String remark;

    /** V2 应用范围（V112 改名 application_scope，原 applicable_scope 列已重命名；英文版 applicable_scope_en 已下线） */
    @Lob
    @Column(name = "application_scope", columnDefinition = "LONGTEXT")
    private String applicationScope;

    /** 产品中文简介（AI详情/直通车短描述） */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String introductionCn;

    /** 产品英文简介 */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "LONGTEXT")
    private String introductionEn;

    /** 中文搜索关键词（逗号分隔） */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String searchKeywordsCn;

    /** 英文搜索关键词（逗号分隔） */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String searchKeywordsEn;

    /** 软删除时间戳（NULL = 未删除） */
    private LocalDateTime deletedAt;

    // ====== 官网发布 ======

    /** 是否在官网发布。false 时不出现在 /api/website/products。 */
    @Column(name = "website_enabled", nullable = false)
    private Boolean websiteEnabled = false;

    /** 是否作为官网首页精选产品。true 时出现在首页核心产品系列区域。 */
    @Column(name = "website_featured", nullable = false)
    private Boolean websiteFeatured = false;

    /** 官网 URL 标识，如 automatic-map-machine。NULL 时官网用 id 兜底。 */
    @Size(max = 128)
    @Column(name = "website_slug", length = 128, unique = true)
    private String websiteSlug;

    /** 官网摘要（区别于内部 remark）。 */
    @Size(max = 500)
    @Column(name = "website_summary", length = 500)
    private String websiteSummary;

    /** 产品适用的包装对象/行业场景标准标签，可用于内部选型与官网筛选。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "application_tags", columnDefinition = "JSON")
    private List<String> applicationTags = new ArrayList<>();

    /** 产品适用的容器类型标准标签。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "container_tags", columnDefinition = "JSON")
    private List<String> containerTags = new ArrayList<>();

    /** 产品支持的包装工艺标准标签。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "packaging_process_tags", columnDefinition = "JSON")
    private List<String> packagingProcessTags = new ArrayList<>();

    /** 产品目标产能分级。 */
    @Size(max = 50)
    @Column(name = "capacity_level", length = 50)
    private String capacityLevel;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
