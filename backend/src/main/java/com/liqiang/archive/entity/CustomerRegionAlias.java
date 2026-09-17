package com.liqiang.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 地区写法别名：把历史/业务中无法被 ChinaRegionDictionary 官方字典识别的写法，
 * 显式登记到官方行政区名。登记后客户域分布聚合与写入口归一会即时按别名兜底识别。
 * kind ∈ PROVINCE / CITY / DISTRICT。
 */
@Getter
@Setter
@Entity
@Table(name = "customer_region_aliases")
public class CustomerRegionAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "级别不能为空")
    @Size(max = 20)
    @Column(name = "alias_kind", nullable = false, length = 20)
    private String kind;

    @NotBlank(message = "写法不能为空")
    @Size(max = 60)
    @Column(name = "alias_text", nullable = false, length = 60)
    private String aliasText;

    /** 生效省份（CITY/DISTRICT 必填）；PROVINCE 级别为 NULL。 */
    @Size(max = 50)
    @Column(name = "scope_province", length = 50)
    private String scopeProvince;

    @NotBlank(message = "目标行政区不能为空")
    @Size(max = 100)
    @Column(name = "target_name", nullable = false, length = 100)
    private String targetName;

    @Size(max = 200)
    @Column(length = 200)
    private String remark;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
