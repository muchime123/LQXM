-- V86: 为company_info表添加制造实力字段
ALTER TABLE company_info 
ADD COLUMN manufacturing_strengths TEXT NULL COMMENT '制造实力列表(JSON格式, 包含标题/描述/图标)' AFTER advantages;

-- 初始化制造实力数据
UPDATE company_info 
SET manufacturing_strengths = '[
  {
    "title": "源头研发制造",
    "description": "核心设备自主研发与制造，从设计到装配全流程可控。",
    "icon": "factory",
    "sortOrder": 1
  },
  {
    "title": "非标定制能力",
    "description": "根据客户容器、产能与产线布局进行定制化设计与调试。",
    "icon": "customize",
    "sortOrder": 2
  },
  {
    "title": "样品试封服务",
    "description": "提供样品测试与方案验证，确保设备适配实际生产需求。",
    "icon": "test",
    "sortOrder": 3
  },
  {
    "title": "长期技术服务",
    "description": "从交付培训到持续维护，保障设备长期稳定运行。",
    "icon": "service",
    "sortOrder": 4
  }
]'
WHERE manufacturing_strengths IS NULL OR manufacturing_strengths = '';
