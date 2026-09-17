-- 地区写法别名：把历史/业务写法显式登记到官方行政区名（ChinaRegionDictionary 兜底映射的来源）。
-- 由客户档案「地区缺口治理」维护，仅影响客户域归一与分布聚合，不做存量批量改名。

CREATE TABLE customer_region_aliases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alias_kind VARCHAR(20) NOT NULL COMMENT '级别：PROVINCE/CITY/DISTRICT',
    alias_text VARCHAR(60) NOT NULL COMMENT '待识别写法（登记时归一后存储）',
    scope_province VARCHAR(50) NULL COMMENT '生效省份（官方名，CITY/DISTRICT 必填；PROVINCE 为 NULL）',
    target_name VARCHAR(100) NOT NULL COMMENT '映射到的官方行政区名',
    remark VARCHAR(200) NULL COMMENT '备注',
    created_by VARCHAR(50) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_region_alias (alias_kind, alias_text, scope_province)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='地区写法别名（字典持久化扩展）';
