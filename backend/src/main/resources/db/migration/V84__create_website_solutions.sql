-- V84: 创建 website_solutions 表
-- 统一存储官网 Solutions 页面的解决方案/行业场景/服务流程数据

CREATE TABLE website_solutions (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    section         VARCHAR(30)  NOT NULL COMMENT '分区: SOLUTION(解决方案) / INDUSTRY(行业场景) / PROCESS(服务流程)',
    item_key        VARCHAR(50)  COMMENT '唯一键(用于解决方案锚点, 如 fresh/sealing)',
    title           VARCHAR(200) NOT NULL COMMENT '标题',
    summary         TEXT         COMMENT '摘要/描述',
    icon            VARCHAR(50)  COMMENT '图标标识 (AppIcon 名称)',
    points          JSON         COMMENT '要点列表 ["高效气体置换", ...]',
    sort_order      INT          NOT NULL DEFAULT 0 COMMENT '排序(升序)',
    website_enabled TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否在官网展示',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ws_section (section, website_enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='官网解决方案内容';

-- 初始化数据: 4 大解决方案方向
INSERT INTO website_solutions (section, item_key, title, summary, icon, points, sort_order, website_enabled) VALUES
('SOLUTION', 'fresh', '锁鲜与气调包装', '针对生鲜、熟食与预制菜，通过气体置换与可靠封合，兼顾货架期、品相与运输稳定性。', 'fresh',
 JSON_ARRAY('高效气体置换', '可靠封合工艺', '支持多规格托盒模具', '适配连续式产线'), 1, 1),
('SOLUTION', 'sealing', '封口包装', '覆盖手动、半自动至全自动设备，适配多种杯、盒、罐与异形容器。', 'sealing',
 JSON_ARRAY('手动 / 气动 / 连续式', '多口径模具可选', '支持片膜与卷膜', '样品试封服务'), 2, 1),
('SOLUTION', 'automation', '自动化产线', '围绕灌装、封口、贴标与输送环节组合，提升节拍与生产一致性。', 'automation',
 JSON_ARRAY('模块化产线设计', 'PLC + 触摸屏控制', '连续式生产能力', '支持产线联动与非标定制'), 3, 1),
('SOLUTION', 'consumables', '耗材与模具', '封口膜、包装容器与定制模具协同匹配，让设备更快投入生产。', 'consumables',
 JSON_ARRAY('封口膜 / 包装容器', '定制模具开发', '耗材供应链保障', '一站式配套'), 4, 1);

-- 初始化数据: 6 个行业应用场景
INSERT INTO website_solutions (section, item_key, title, summary, icon, points, sort_order, website_enabled) VALUES
('INDUSTRY', 'meat', '生鲜肉类', '气调保鲜包装延长货架期，保持色泽与口感，适配商超配送冷链。', NULL, NULL, 1, 1),
('INDUSTRY', 'cooked', '熟食制品', '锁鲜包装防止氧化变质，兼顾展示效果与食品安全。', NULL, NULL, 2, 1),
('INDUSTRY', 'precooked', '预制菜', '规模化连续封口产线，支持多规格盒型与高节拍生产。', NULL, NULL, 3, 1),
('INDUSTRY', 'bakery', '烘焙食品', '防潮封口包装保持酥脆口感，适配异形托盒与定制模具。', NULL, NULL, 4, 1),
('INDUSTRY', 'airline', '航空餐', '标准化封口包装确保餐食安全与配送效率。', NULL, NULL, 5, 1),
('INDUSTRY', 'daily', '日化 / 医用', '合规包装方案，满足洁净度与密封性要求。', NULL, NULL, 6, 1);

-- 初始化数据: 5 步服务流程
INSERT INTO website_solutions (section, item_key, title, summary, icon, points, sort_order, website_enabled) VALUES
('PROCESS', 'p1', '需求沟通', '了解产品、容器、产能目标与样品信息', NULL, NULL, 1, 1),
('PROCESS', 'p2', '方案设计', '匹配设备、模具、膜材与工艺参数', NULL, NULL, 2, 1),
('PROCESS', 'p3', '样品试封', '提供样品测试，验证封合效果与适配性', NULL, NULL, 3, 1),
('PROCESS', 'p4', '交付调试', '设备交付、安装调试与操作培训', NULL, NULL, 4, 1),
('PROCESS', 'p5', '长期服务', '持续技术支持、耗材供应与维护保养', NULL, NULL, 5, 1);
