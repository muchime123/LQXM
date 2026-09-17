CREATE TABLE IF NOT EXISTS company_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_name VARCHAR(200) NOT NULL,
    company_name_en VARCHAR(300),
    brand_name VARCHAR(100),
    brand_name_en VARCHAR(100),
    unified_social_code VARCHAR(50),
    legal_representative VARCHAR(100),
    company_type VARCHAR(100),
    registered_capital VARCHAR(100),
    establishment_date DATE,
    business_term VARCHAR(100),
    business_scope TEXT,
    registered_address VARCHAR(500),
    office_address VARCHAR(500),
    phone VARCHAR(50),
    email VARCHAR(100),
    website VARCHAR(200),
    core_positioning VARCHAR(500),
    main_business TEXT,
    target_industries TEXT,
    advantages TEXT,
    license_file_name VARCHAR(500),
    seal_file_name VARCHAR(500),
    logo_file_name VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS company_certifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    name_en VARCHAR(300),
    category VARCHAR(50) NOT NULL,
    cert_number VARCHAR(100),
    issue_date DATE,
    expiry_date DATE,
    issuing_authority VARCHAR(200),
    file_name VARCHAR(500),
    description TEXT,
    status VARCHAR(20) DEFAULT '有效',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS company_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_name VARCHAR(100) NOT NULL,
    account_type VARCHAR(50) NOT NULL,
    bank_name VARCHAR(200),
    account_number VARCHAR(100),
    account_holder VARCHAR(100),
    currency VARCHAR(20) DEFAULT 'CNY',
    swift_code VARCHAR(50),
    qr_code_file VARCHAR(500),
    is_primary BOOLEAN DEFAULT FALSE,
    notes TEXT,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    name_en VARCHAR(100),
    description TEXT,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_articles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content LONGTEXT,
    tags VARCHAR(500),
    author VARCHAR(100),
    source VARCHAR(200),
    sort_order INT DEFAULT 0,
    view_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'published',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (category_id) REFERENCES knowledge_categories(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO knowledge_categories (name, sort_order, created_at) VALUES
('1. 资料总览与知识域', 1, CURRENT_TIMESTAMP),
('2. 公司与品牌基础资料', 2, CURRENT_TIMESTAMP),
('3. 外贸业务标准流程', 3, CURRENT_TIMESTAMP),
('4. 产品知识库', 4, CURRENT_TIMESTAMP),
('5. 报价与成本核算', 5, CURRENT_TIMESTAMP),
('6. 国际物流与报关', 6, CURRENT_TIMESTAMP),
('7. 认证与合规', 7, CURRENT_TIMESTAMP),
('8. 内部协作流程', 8, CURRENT_TIMESTAMP),
('9. 售后与故障处理', 9, CURRENT_TIMESTAMP),
('10. 外贸沟通模板', 10, CURRENT_TIMESTAMP),
('11. 客户案例库', 11, CURRENT_TIMESTAMP),
('12. 常用术语对照', 12, CURRENT_TIMESTAMP),
('13. 知识库建设建议', 13, CURRENT_TIMESTAMP),
('附录A. 订单执行检查清单', 14, CURRENT_TIMESTAMP),
('附录B. 客户需求信息收集表', 15, CURRENT_TIMESTAMP),
('附录C. 资料归档命名规则', 16, CURRENT_TIMESTAMP);

INSERT INTO company_info (
    company_name, company_name_en, brand_name, brand_name_en,
    unified_social_code, legal_representative, company_type,
    registered_capital, establishment_date, business_term,
    business_scope, registered_address,
    core_positioning, main_business, target_industries, advantages,
    license_file_name, logo_file_name, seal_file_name,
    created_at, updated_at
) VALUES (
    '浙江利强包装科技有限公司',
    'Zhejiang Liqiang Packing Technology Co., Ltd.',
    '利强',
    'Liqiang Packing',
    '9133030207622289XP',
    '于士强',
    '有限责任公司(自然人独资)',
    '1000万元人民币',
    '2013-08-12',
    '2013年08月12日至长期',
    '包装科技研发;制造、加工、销售：包装机械、包装耗材、食品机械、办公用品;销售：酒店用品、铝箔制品、铝箔餐盒;计算机软硬件技术开发、技术转让、技术咨询、技术服务;计算机、手机系统集成设计;网络推广服务;品牌推广、市场营销策划、企业管理服务、企业形象策划;网页设计;设计、制作、代理、发布国内各类广告;货物进出口、技术进出口。（依法须经批准的项目，经相关部门批准后方可开展经营活动）',
    '浙江省温州市瓯海区南白象街道金洋路77号温州启迪未来产业园D号楼第二层',
    'One-stop packaging partner / 一站式包装合作伙伴',
    '封口机、气调包装机、真空包装机、热收缩设备、包装容器、封口膜、模具及配件',
    '餐饮连锁、预制菜、生鲜肉类、烘焙、航空餐、食品工厂、医用/日化包装等',
    '设备 + 耗材 + 模具一体化；支持定制；响应快；可提供样品测试与方案建议',
    '营业执照20130812.png',
    'logo.jpg',
    '公司中英文章.jpg',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

INSERT INTO company_accounts (account_name, account_type, bank_name, account_number, account_holder, currency, is_primary, notes, sort_order, created_at, updated_at) VALUES
('利强包装对公账户', '对公账户', '招商银行温州分行', '5779 0415 8410 101', '浙江利强包装科技有限公司', 'CNY', true, '对公账户', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('利强包装对公账户（美元）', '对公账户', '招商银行温州分行', '5779 0415 8510 201', '浙江利强包装科技有限公司', 'USD', false, '美元账户', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO company_accounts (account_name, account_type, qr_code_file, notes, sort_order, created_at, updated_at) VALUES
('于总-支付宝', '支付宝', '于总-扫描付（私人支付宝）.jpg', '于总支付宝收款码', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('于总-微信', '微信', 'boss-于士强-wechat.jpg', '于总微信收款码', 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('于总-支付宝备用', '支付宝', 'boss-shiqiang-yu-alipay.jpg', '备用支付宝码', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('于总-支付宝(1-1)', '支付宝', '1-1.png', '支付宝码', 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('于总-支付宝(1-2)', '支付宝', '1-2.png', '支付宝码', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('于总-微信(1-3)', '微信', '1-3.png', '微信码', 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('于总-微信(1-4)', '微信', '1-4.png', '微信码', 9, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 管理制度归入文档表（如已存在 documents 表，请手动执行以下 INSERT）
-- INSERT INTO documents (title, category, file_name, original_name, status) VALUES
-- ('邀请函', '管理制度', '邀请函.docx', '邀请函.docx', 'published'),
-- ('公司宿舍管理制度', '管理制度', '公司宿舍管理制度.docx', '公司宿舍管理制度.docx', 'published'),
-- ('满勤奖管理制度', '管理制度', '满勤奖管理制度.docx', '满勤奖管理制度.docx', 'published'),
-- ('员工签字.docx', '管理制度', '员工签字.docx', '员工签字.docx', 'published');
