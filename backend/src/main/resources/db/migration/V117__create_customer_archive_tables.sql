-- V117: 客户档案主模块（P1）
-- 设计定位：客户 = 系统一级业务实体。
-- P1 落地 customers + customer_contacts + customer_salesperson_relations 三张核心表。
-- 合同表 sales_contracts 与客户案例/素材将在 P2/P3 引入；
-- 合同数/累计金额/首购/最近采购等统计字段一律不冗余、从合同实时聚合。

CREATE TABLE customers (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    customer_code   VARCHAR(20) NOT NULL COMMENT '客户编号，格式 CUS000001',
    customer_name   VARCHAR(200) NOT NULL COMMENT '客户正式名称',
    short_name      VARCHAR(100) NULL COMMENT '客户简称',
    customer_type   VARCHAR(20) NOT NULL DEFAULT 'OTHER' COMMENT '客户类型：ENTERPRISE/COOPERATIVE/FARM/INDIVIDUAL/PERSONAL/OTHER',
    industry        VARCHAR(100) NULL COMMENT '所属行业（自由文本）',
    credit_code     VARCHAR(30) NULL COMMENT '统一社会信用代码',
    province        VARCHAR(50) NULL COMMENT '省',
    city            VARCHAR(50) NULL COMMENT '市',
    district        VARCHAR(50) NULL COMMENT '区县',
    address         VARCHAR(300) NULL COMMENT '公司地址',
    website         VARCHAR(200) NULL COMMENT '官网',
    source          VARCHAR(50) NULL COMMENT '客户来源',
    level           VARCHAR(20) NULL COMMENT '客户等级：KEY/COMMON',
    relation_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '关系状态：NORMAL/KEY_INTEREST/STOPPED/NO_CONTACT',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '启用状态：ACTIVE/INACTIVE',
    remark          VARCHAR(1000) NULL COMMENT '备注',
    deleted_at      DATETIME NULL COMMENT '软删除时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    UNIQUE KEY uk_customers_code (customer_code),
    KEY idx_customers_name (customer_name),
    KEY idx_customers_type (customer_type),
    KEY idx_customers_industry (industry),
    KEY idx_customers_relation_status (relation_status),
    KEY idx_customers_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户档案主表';

CREATE TABLE customer_contacts (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    customer_id  BIGINT NOT NULL COMMENT '客户ID',
    name         VARCHAR(50) NOT NULL COMMENT '联系人姓名',
    department   VARCHAR(50) NULL COMMENT '部门',
    position     VARCHAR(50) NULL COMMENT '职位',
    mobile       VARCHAR(30) NULL COMMENT '手机号',
    wechat       VARCHAR(50) NULL COMMENT '微信号',
    email        VARCHAR(100) NULL COMMENT '邮箱',
    contact_type VARCHAR(20) NULL COMMENT '联系人类型：决策人/采购/技术/财务/其他',
    is_primary   TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否主要联系人',
    remark       VARCHAR(500) NULL COMMENT '备注',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    KEY idx_cc_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户联系人';

CREATE TABLE customer_salesperson_relations (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    customer_id       BIGINT NOT NULL COMMENT '客户ID',
    salesperson_id    BIGINT NULL COMMENT '关联公司成员ID（company_team_members.id）',
    salesperson_name  VARCHAR(50) NOT NULL COMMENT '业务员姓名快照',
    role              VARCHAR(20) NOT NULL DEFAULT 'COLLABORATOR' COMMENT '角色：PRIMARY当前负责人/HISTORY历史/COLLABORATOR协同',
    start_date        DATE NULL COMMENT '开始日期',
    end_date          DATE NULL COMMENT '结束日期',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    KEY idx_csr_customer (customer_id),
    KEY idx_csr_salesperson (salesperson_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户-业务员关系';
