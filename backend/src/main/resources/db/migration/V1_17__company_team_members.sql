CREATE TABLE IF NOT EXISTS company_team_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '姓名',
    position VARCHAR(200) COMMENT '职位',
    department VARCHAR(100) COMMENT '部门',
    description TEXT COMMENT '简介',
    photo_file_name VARCHAR(500) COMMENT '头像文件名',
    is_core BOOLEAN DEFAULT FALSE COMMENT '是否核心成员',
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO company_team_members (name, position, department, description, is_core, sort_order, created_at, updated_at) VALUES
('于士强', '创始人 & CEO', '管理层', '全面负责公司战略规划与经营管理', TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('陈杰', '技术总监', '研发部', '负责产品研发与技术团队管理', TRUE, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('陈路平', '生产总监', '生产部', '负责生产制造与供应链管理', TRUE, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('陈权', '销售总监', '销售部', '负责国内及海外市场拓展', TRUE, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('周哲', '售后经理', '售后部', '负责售后服务与客户支持', FALSE, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
