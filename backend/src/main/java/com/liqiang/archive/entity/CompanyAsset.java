package com.liqiang.archive.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 公司品牌资产（Logo / 印章 / 工厂实景 / 团队照片 等）
 *
 * 对应表 company_assets，文件存储在 /api/files/company?encoded=xxx
 *
 * 分类体系：
 *   brand_logo     - 品牌标识（Logo）
 *   seal_nameplate - 印章与名称
 *   factory_scene  - 工厂实景
 *   team_photo     - 团队照片
 *   office_env     - 办公环境
 *   product_showcase - 产品展示
 *   info_graphic   - 信息图表
 *   certification  - 资质证书
 *   promotion      - 宣传资料
 */
@Getter
@Setter
@Entity
@Table(name = "company_assets")
@SQLRestriction("deleted_at IS NULL")
public class CompanyAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 资产分类
     */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    /**
     * 文件名称（对应 storage/lq-files/company/ 目录下的文件名）
     */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /**
     * 逗号分隔的标签（如"横版,蓝色,2024"）
     */
    @Column(name = "tags", length = 500)
    private String tags;

    /**
     * 是否为该分类下的默认素材（如默认 Logo）
     */
    @Column(name = "default_asset")
    private Boolean defaultAsset = false;

    /**
     * 排序权重（越小越靠前）
     */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /**
     * 自定义封面图片文件名（用于视频/Office等无法自动生成缩略图的文件）
     */
    @Column(name = "cover_file_name", length = 500)
    private String coverFileName;

    /**
     * 备注
     */
    @Column(name = "description", length = 500)
    private String description;

    /**
     * 软删除时间戳（NULL = 未删除）
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Transient
    private Boolean fileExists;

    @Transient
    private Long fileSize;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CompanyAsset() {
    }
}
