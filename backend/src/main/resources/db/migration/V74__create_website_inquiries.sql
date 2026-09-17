-- 官网询盘表：由官网 Contact 页表单提交，进入档案后台供员工处理。
-- 字段与 Contact.vue 表单一致：联系人、公司、电话、邮箱、关注产品、需求描述。
-- 处理状态 status：pending（待处理）/ processed（已处理）/ closed（已关闭），默认 pending。
CREATE TABLE website_inquiries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '联系人姓名',
    company VARCHAR(200) COMMENT '公司名称',
    phone VARCHAR(50) NOT NULL COMMENT '联系电话',
    email VARCHAR(100) COMMENT '邮箱',
    product_focus VARCHAR(100) COMMENT '关注产品',
    description TEXT COMMENT '需求描述',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '处理状态：pending/processed/closed',
    source_page VARCHAR(200) COMMENT '来源页面路径',
    created_at DATETIME NOT NULL COMMENT '提交时间',
    processed_at DATETIME COMMENT '后台处理时间',
    PRIMARY KEY (id),
    INDEX idx_inquiry_status (status),
    INDEX idx_inquiry_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官网询盘记录';
