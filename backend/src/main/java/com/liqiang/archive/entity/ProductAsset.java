package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "product_assets", indexes = {
        @Index(name = "idx_asset_model_id", columnList = "model_id"),
        @Index(name = "idx_asset_category", columnList = "category")
    })
@SQLRestriction("deleted_at IS NULL")
public class ProductAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private ProductModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariant variant;

    /** 新版素材适用范围：null 为产品通用素材，否则仅适用于指定机型档位。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_tier_id")
    private ProductMachineTier machineTier;

    /** 素材适用的机型集合；空集合表示适用于产品全部机型。 */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "product_asset_machine_tier_links",
            joinColumns = @JoinColumn(name = "asset_id"),
            inverseJoinColumns = @JoinColumn(name = "machine_tier_id"))
    private Set<ProductMachineTier> machineTiers = new LinkedHashSet<>();

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false, length = 500)
    private String fileName;

    @Column(length = 500)
    private String originalFileName;

    @Column(length = 700)
    private String storageKey;

    /**
     * 物理文件的兼容存储分类。通常为空并使用 category；历史文件库资料迁入产品素材后为 document，
     * 从而在不复制大文件的前提下继续从原目录读取。新上传素材不写入该字段。
     */
    @Column(name = "storage_category", length = 60)
    private String storageCategory;

    /** 历史存储来源标识，仅用于文件定位和迁移追溯。 */
    @Column(name = "storage_owner_id")
    private Long storageOwnerId;

    /** 原文件库记录 ID；为空表示原生产品素材。 */
    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Column(length = 1000)
    private String fileUrl;

    @Column(length = 120)
    private String mimeType;

    private Long fileSize;

    @Column(length = 500)
    private String tags;

    /** 视频适用对象，如豆腐、酸奶；结构化多值，逗号分隔，不混入普通内容标签。 */
    @Column(name = "application_tags", length = 500)
    private String applicationTags;

    /** 视频内容类型，如设备运行、客户现场、封口效果、操作教学。 */
    @Column(name = "video_content_type", length = 40)
    private String videoContentType;

    /** 视频设备/工艺特征，如按钮自动、卷膜、气动。 */
    @Column(name = "feature_tags", length = 500)
    private String featureTags;

    /** 视频中展示的模具或包装参数，仅用于检索，不构成产品规格。 */
    @Column(name = "demonstration_tags", length = 500)
    private String demonstrationTags;

    /** 视频展示标题：默认由文件名清洗（去扩展名、去导入时间戳）生成；卡片与搜索优先显示，原文件名保留追溯。 */
    @Column(name = "video_title", length = 500)
    private String videoTitle;

    /** 设备视频适用范围：默认即「确认适用于整个产品（所有机型通用）」。true 且未关联机型 = 产品通用；关联机型 = 指定机型。 */
    @Column(name = "machine_scope_confirmed", nullable = false)
    private Boolean machineScopeConfirmed = true;

    /** 文件 SHA-256 摘要（十六进制），用于精确重复检测；由维护任务异步补齐。 */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    /** 视频时长（秒，四舍五入），由 ffprobe 提取；仅展示与筛选用。 */
    @Column(name = "video_duration_seconds")
    private Integer videoDurationSeconds;

    /** 视频宽度（像素），由 ffprobe 提取。 */
    @Column(name = "video_width")
    private Integer videoWidth;

    /** 视频高度（像素），由 ffprobe 提取。 */
    @Column(name = "video_height")
    private Integer videoHeight;

    /** 收藏标记：在视频库中可一键星标并按「只看收藏」筛选。 */
    @Column(name = "video_favorite", nullable = false)
    private Boolean videoFavorite = false;


    /** 视频使用状态，如可发客户、销售演示、内部参考、待剪辑。 */
    @Column(name = "video_usage_status", length = 40)
    private String videoUsageStatus;

    /** 可使用渠道，多值：可发客户、销售演示、官网可用、内部参考。 */
    @Column(name = "video_usage_channels", length = 500)
    private String videoUsageChannels;

    /**
     * 内容质量/生命周期状态：受控词表见 {@link com.liqiang.archive.util.VideoGovernanceRules#ALLOWED_STATUSES}。
     * 状态机：待处理 → 待确认 → 可用 → 精选 / 停用；异常为旁路状态。
     * 新上传的视频默认进入「待处理」，由 @PrePersist 兜底赋值。
     */
    @Column(name = "video_content_status", length = 40)
    private String videoContentStatus;

    @Column(length = 120)
    private String sourceColumn;

    private Integer sourceRowNumber;

    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean defaultAsset = false;

    /** 自定义封面图片文件名（用于视频/Office等无法自动生成缩略图的文件） */
    @Column(name = "cover_file_name", length = 500)
    private String coverFileName;

    /** 是否在官网图库可见。false 时不出现在 /api/website/products/{slug} 的 gallery 中。 */
    @Column(name = "website_visible", nullable = false)
    private Boolean websiteVisible = true;

    /** 官网图库独立排序（升序），与通用 sortOrder 解耦，便于官网单独控制展示顺序。 */
    @Column(name = "website_sort_order", nullable = false)
    private Integer websiteSortOrder = 0;

    /** 软删除时间戳（NULL = 未删除） */
    private LocalDateTime deletedAt;

    // ====== 导入来源追溯 ======

    @Size(max = 500)
    @Column(length = 500)
    private String sourceFile;

    @Size(max = 200)
    @Column(length = 200)
    private String sourceSheet;

    private Long importBatchId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 新视频上传兜底：未显式指定 content_status 时按类目自动设默认值。
     * - device_video / operation_video → 「待处理」（待识别与人工确认）
     * - 其他类目：保持 NULL（状态机仅对视频生效）
     * 与 V150 迁移互补——迁移治理历史脏数据，@PrePersist 防止新上传再生 NULL。
     * 适用范围默认为产品通用（已确认）：设备视频默认适用整个产品所有机型，存在机型差异时再由人工绑定机型档位调整。
     */
    @PrePersist
    void prePersistVideoDefaults() {
        if (category != null && (category.equals("device_video") || category.equals("operation_video"))) {
            if (videoContentStatus == null || videoContentStatus.isBlank()) {
                videoContentStatus = "待处理";
            }
            if (machineScopeConfirmed == null) {
                machineScopeConfirmed = true;
            }
        }
    }
}
