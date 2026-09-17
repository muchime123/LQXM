-- 报价单快照表：记录历史报价详情
CREATE TABLE quote_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_no VARCHAR(50) NOT NULL COMMENT '报价单号',
    product_model_id BIGINT NOT NULL COMMENT '产品ID',
    
    -- 客户信息
    customer_name VARCHAR(200) COMMENT '客户名称',
    customer_contact VARCHAR(100) COMMENT '客户联系方式',
    
    -- 报价配置
    price_type VARCHAR(20) NOT NULL COMMENT '价格类型: DEALER/RETAIL',
    shape_type VARCHAR(20) NOT NULL COMMENT '形状类型: SQUARE/ROUND/IRREGULAR',
    long_side_mm INT COMMENT '长边尺寸(mm)',
    short_side_mm INT COMMENT '短边尺寸(mm)',
    diameter_mm INT COMMENT '直径(mm)',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量',
    dimension_text VARCHAR(100) COMMENT '尺寸描述',
    
    -- 匹配的档位信息
    matched_tier_id BIGINT COMMENT '匹配的档位ID',
    matched_tier_name VARCHAR(200) COMMENT '档位名称',
    matched_tier_display_text VARCHAR(500) COMMENT '档位显示文本',
    
    -- 价格明细
    base_unit_price DECIMAL(12, 2) NOT NULL COMMENT '基础单价',
    base_price DECIMAL(12, 2) NOT NULL COMMENT '基础总价',
    addons_unit_total DECIMAL(12, 2) DEFAULT 0 COMMENT '选配加价单价合计',
    addons_total DECIMAL(12, 2) DEFAULT 0 COMMENT '选配加价总价',
    manual_adjustment DECIMAL(12, 2) DEFAULT 0 COMMENT '人工调整金额',
    manual_adjustment_reason VARCHAR(500) COMMENT '调整原因',
    final_unit_price DECIMAL(12, 2) NOT NULL COMMENT '最终单价',
    final_price DECIMAL(12, 2) NOT NULL COMMENT '最终总价',
    
    -- 方案信息快照
    price_plan_id BIGINT COMMENT '价格方案ID',
    plan_version VARCHAR(50) COMMENT '方案版本',
    currency VARCHAR(10) DEFAULT 'CNY' COMMENT '货币',
    price_unit VARCHAR(50) DEFAULT '元/台' COMMENT '计价单位',
    
    -- 状态与审核
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT/CONFIRMED/CANCELLED',
    valid_until DATE COMMENT '有效期至',
    
    -- 备注与创建信息
    remark VARCHAR(1000) COMMENT '备注',
    created_by VARCHAR(100) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_quote_snapshots_model (product_model_id),
    INDEX idx_quote_snapshots_customer (customer_name),
    INDEX idx_quote_snapshots_status (status),
    INDEX idx_quote_snapshots_created (created_at),
    UNIQUE INDEX uk_quote_snapshots_no (snapshot_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单快照表';

-- 报价单选配明细表
CREATE TABLE quote_snapshot_addons (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL COMMENT '报价单ID',
    
    addon_code VARCHAR(50) NOT NULL COMMENT '选配编码',
    addon_name VARCHAR(200) NOT NULL COMMENT '选配名称',
    addon_group VARCHAR(100) COMMENT '分组',
    calculation_type VARCHAR(20) NOT NULL COMMENT '计算类型: FIXED/PERCENTAGE/QUANTITY',
    unit_add_price DECIMAL(12, 2) NOT NULL COMMENT '单价加价',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量',
    add_price DECIMAL(12, 2) NOT NULL COMMENT '总加价',
    requires_manual_confirm TINYINT(1) DEFAULT 0 COMMENT '是否需人工确认',
    formula VARCHAR(200) COMMENT '计算公式',
    remark VARCHAR(500) COMMENT '备注',
    
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_quote_snapshot_addons_snapshot (snapshot_id),
    CONSTRAINT fk_quote_snapshot_addons_snapshot FOREIGN KEY (snapshot_id) 
        REFERENCES quote_snapshots(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单选配明细表';