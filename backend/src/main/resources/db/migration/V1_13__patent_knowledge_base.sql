-- 专利操作知识库（参考专利工作操作手册 V1.0）
-- 将手册中的常见问题、操作流程、检查清单等整合为系统内嵌知识

-- 创建知识分类表
CREATE TABLE IF NOT EXISTS knowledge_categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '分类名称',
    parent_id BIGINT COMMENT '父分类ID',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 补充缺失字段（兼容已有表）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_categories' AND column_name = 'parent_id');
SET @sql = IF(@exists = 0, 'ALTER TABLE knowledge_categories ADD COLUMN parent_id BIGINT COMMENT ''父分类ID''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_categories' AND column_name = 'updated_at');
SET @sql = IF(@exists = 0, 'ALTER TABLE knowledge_categories ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 创建知识文章表
CREATE TABLE IF NOT EXISTS knowledge_articles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL COMMENT '所属分类ID',
    title VARCHAR(200) NOT NULL COMMENT '标题',
    content TEXT NOT NULL COMMENT '内容（Markdown格式）',
    tags VARCHAR(500) COMMENT '标签，逗号分隔',
    sort_order INT DEFAULT 0 COMMENT '排序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (category_id) REFERENCES knowledge_categories(id)
);

-- 补充缺失字段（兼容已有表）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_articles' AND column_name = 'tags');
SET @sql = IF(@exists = 0, 'ALTER TABLE knowledge_articles ADD COLUMN tags VARCHAR(500) COMMENT ''标签，逗号分隔''', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 插入专利操作知识分类
INSERT INTO knowledge_categories (name, sort_order, created_at, updated_at) VALUES
('专利基础知识', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('年费缴纳操作', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('新专利申请对接', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('常见问题与处理', 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('检查清单', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 捕获刚插入的分类 ID（避免与已有数据 ID 冲突）
SET @cat_basic = (SELECT id FROM knowledge_categories WHERE name = '专利基础知识' ORDER BY id DESC LIMIT 1);
SET @cat_fee = (SELECT id FROM knowledge_categories WHERE name = '年费缴纳操作' ORDER BY id DESC LIMIT 1);
SET @cat_apply = (SELECT id FROM knowledge_categories WHERE name = '新专利申请对接' ORDER BY id DESC LIMIT 1);
SET @cat_faq = (SELECT id FROM knowledge_categories WHERE name = '常见问题与处理' ORDER BY id DESC LIMIT 1);
SET @cat_checklist = (SELECT id FROM knowledge_categories WHERE name = '检查清单' ORDER BY id DESC LIMIT 1);

-- 插入专利基础知识文章
INSERT INTO knowledge_articles (category_id, title, content, tags, sort_order, created_at, updated_at) VALUES
(@cat_basic, '专利类型与保护期限', '## 专利类型与保护期限\n\n| 类型 | 常见期限 | 起算时间 | 管理重点 |\n|------|---------|---------|----------|\n| 发明专利 | 20年 | 自申请日起计算 | 审查周期较长；A类公开不等于授权；授权后每年关注年费 |\n| 实用新型专利 | 10年 | 自申请日起计算 | 设备结构类常见；授权较快；年费到期较容易遗漏 |\n| 外观设计专利 | 15年（2021-06-01前申请的仍按旧规则10年） | 自申请日起计算 | 主图、外观、造型类常见；注意保护期限新旧规则 |\n\n> **提醒**：专利年度不是自然年度。年费一般围绕"申请日对应日"计算和提醒，不要简单按每年1月1日或12月31日判断。', '专利类型,保护期限,发明专利,实用新型,外观设计', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_basic, '角色分工', '## 角色分工\n\n| 角色 | 主要职责 | 交付物/确认点 |\n|------|---------|-------------|\n| 内部专利负责人 | 维护专利清单；跟进代理机构；登录专利业务办理系统查询并发起缴费；保存凭证 | 专利清单、缴费截图、订单号、发票记录、下一次缴费提醒 |\n| 财务人员 | 确认缴费金额；完成支付宝/微信/网银付款；留存付款凭证并配合入账 | 付款凭证、报销/记账信息、发票归档 |\n| 专利代理机构 | 负责新专利申请文件撰写、提交、审查过程答复、证书交接及到期提醒 | 申请号、受理通知书、授权证书、官费/代理费说明 |\n| 老板/部门负责人 | 确认是否申请新专利；确认费用预算；必要时审批付款 | 申请决策、费用审批 |', '角色分工,负责人,财务,代理机构', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_fee, '专利年费缴纳操作流程', '## 专利年费缴纳操作流程\n\n### 5.1 登录系统并进入功能\n\n1. 打开国家知识产权局"专利业务办理系统"：https://interactive.cponline.cnipa.gov.cn\n2. 使用公司账号登录。账号和密码以内部《密码清单汇总》为准\n3. 顶部菜单选择"缴费服务"\n4. 左侧进入"网上缴费" → "在线填写"\n5. 进入在线填写页面后，点击右上角"增加"\n\n### 5.2 新增费用并查询应缴项目\n\n6. 在弹窗中填写"申请号/专利号"\n7. 确认"票据抬头"为公司全称，确认"统一社会信用代码"无误\n8. 点击"应缴费用查询"\n9. 系统会显示该专利在当前日期需要缴纳的费用项目、缴费期限届满日和金额\n10. 勾选需要缴纳的项目，例如：年费、滞纳金等\n11. 确认无误后点击"保存"；如果还有下一件专利，点击"保存并添加"\n\n> **判断原则**：如果查询结果提示"费用已缴纳可忽略"，不要重复缴费；如果系统显示年费及滞纳金，通常说明已接近或超过缴费期限，需要一起缴纳。最终以系统实时查询结果为准。\n\n### 5.3 多件专利合并付款\n\n12. 所有专利添加完成后，返回在线填写列表\n13. 逐条展开检查：申请号/专利号、费用种类、金额、票据抬头、统一社会信用代码\n14. 核对页面底部"总额"和"总笔数"\n15. 确认全部费用无误后，点击"下一步"\n16. 选择支付宝或微信完成支付\n17. 付款成功后，系统显示"已缴费成功"。保存成功页面截图，记录订单金额、订单编号、订单交易号', '年费缴纳,操作流程,缴费,网上缴费', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_fee, '费用核对规则', '## 费用核对规则\n\n| 核对项 | 正确做法 | 错误风险 |\n|--------|---------|----------|\n| 专利号/申请号 | 从专利清单复制后再次肉眼核对 | 输错号会给错误专利缴费，后续难排查 |\n| 费用名称 | 确认是否为对应专利的年费、滞纳金或其他费用 | 只缴年费不缴滞纳金，可能仍显示欠费 |\n| 缴费期限届满日 | 重点看是否已临近或逾期 | 逾期可能产生滞纳金，严重会影响专利权维持 |\n| 金额 | 以系统应缴费用查询结果为准 | 不要按以前经验手动改金额 |\n| 票据抬头 | 必须为公司全称 | 抬头错误会影响财务入账和归档 |\n| 总金额 | 付款前与每条明细合计核对 | 多件合并缴费时容易漏选或多选 |', '费用核对,缴费规则,核对清单', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_apply, '新专利申请对接流程', '## 新专利申请对接流程\n\n新专利申请目前主要仍通过专利代理机构完成，公司内部负责人负责对接和过程跟进。\n\n| 阶段 | 公司内部动作 | 代理机构动作 | 需要保存的文件 |\n|------|------------|------------|-------------|\n| 1. 需求提出 | 收集产品结构、功能创新点、图片、视频、图纸、应用场景 | 判断适合发明/实用新型/外观设计 | 技术交底资料、产品图片、初步说明 |\n| 2. 方案确认 | 确认是否申请、申请类型、费用预算、是否加急 | 给出申请方案、报价、合同 | 报价单、合同、付款记录 |\n| 3. 文件撰写 | 配合补充结构说明、使用场景、差异点 | 撰写申请文件、权利要求、附图 | 申请稿、附图、确认版本 |\n| 4. 提交申请 | 确认最终稿后授权代理提交 | 提交至国家知识产权局并反馈申请号 | 受理通知书、申请号 |\n| 5. 过程跟进 | 定期询问进度，处理补正/审查意见 | 答复审查意见，跟进授权 | 审查意见、答复文件、进度记录 |\n| 6. 授权归档 | 收到证书后加入专利台账，建立缴费提醒 | 交付电子/纸质证书并提示后续年费 | 专利证书、授权公告、缴费期限 |', '专利申请,对接流程,代理机构,申请流程', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_apply, '资料准备清单', '## 资料准备清单\n\n| 资料/信息 | 用途 | 注意事项 |\n|----------|------|----------|\n| 专利清单（如：专利清单 5.28.xls） | 确认公司现有专利、申请号/专利号、名称、缴费期限 | 每次缴费完成后必须更新，不要只保存截图 |\n| 国家知识产权局专利业务办理系统账号 | 登录系统查询和缴费 | 账号密码统一查《密码清单汇总》，不要在群聊或普通文档里散发 |\n| 申请号/专利号 | 在"应缴费用查询"中查询应缴项目 | 复制时不要带空格、中文符号或多余字符 |\n| 票据抬头/公司名称 | 系统生成缴费订单和发票信息 | 应填写公司全称：浙江利强包装科技有限公司 |\n| 统一社会信用代码 | 付款及票据归属信息 | 以公司证照信息为准，避免输错 |\n| 付款方式 | 完成线上缴费 | 可使用支付宝或微信，付款前核对总金额、专利号、费用项目 |', '资料准备,专利清单,账号,票据抬头', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_faq, '常见问题与处理建议', '## 常见问题与处理建议\n\n| 问题 | 处理建议 |\n|------|----------|\n| 查不到应缴费用 | 先确认专利号是否输入正确；再进入"费用查询"查看明细；如仍不确定，联系代理机构确认 |\n| 显示滞纳金 | 说明可能已过缴费期限或处于滞纳期。年费和滞纳金应一起缴纳，避免仍欠费 |\n| 多件专利一起缴费怕弄错 | 每添加一件先保存，全部添加后展开逐条检查，再核对总额和总笔数 |\n| 已经付款但发票未出 | 先保存成功页面、订单号和交易号；后续进入票据/电子缴费清单相关功能查看 |\n| 代理机构也提醒缴费 | 内部缴完后及时反馈给代理，避免代理重复代缴或继续催缴 |\n| 同一专利有多个费用项目 | 不要只看第一条，展开全部项目，确认是否年费、滞纳金或其他费用都需缴 |', '常见问题,FAQ,滞纳金,发票,代理机构', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_faq, '付款完成后的归档动作', '## 付款完成后的归档动作\n\n1. 保存系统"已缴费成功"页面截图\n2. 保存或导出"专利清单/电子缴费清单"，文件名建议：专利缴费清单_YYYYMMDD.xlsx\n3. 将订单金额、订单编号、订单交易号记录到专利台账\n4. 等待系统/国家局开具票据或发票；发票出来后，将发票文件与本次缴费清单放在同一文件夹\n5. 更新每件专利的"本次缴费日期、本次缴费金额、本次缴费项目、下次缴费期限、经办人"\n6. 如果由代理机构提醒或转交信息，同步反馈已完成缴费，避免重复代缴\n\n> **最终完成标准**：一次专利缴费工作不以"付款成功"作为结束，而是以"台账已更新、凭证已归档、发票状态已跟进、下一次缴费提醒已设置"为结束。', '归档,付款完成,发票,缴费清单', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_faq, '推荐文件夹归档结构', '## 推荐文件夹归档结构\n\n建议按年度建立文件夹，避免以后找不到付款记录、发票或证书。\n\n示例：\n- 2026_专利管理/\n  - 01_专利清单台账/\n  - 02_年费缴纳记录/\n    - 2026-05-28_专利年费缴纳_390元/\n      - 缴费成功截图\n      - 电子缴费清单\n      - 发票\n      - 付款凭证\n  - 03_专利证书/\n  - 04_代理机构合同与报价/', '归档结构,文件夹,年度归档', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_checklist, '每次缴费前后的检查清单', '## 每次缴费前后的检查清单\n\n| 节点 | 检查事项 |\n|------|----------|\n| 缴费前 | 专利清单已更新，确认本次需要缴费的专利 |\n| 缴费前 | 申请号/专利号、公司名称、统一社会信用代码已核对 |\n| 查询后 | 每件专利的费用名称、期限、金额已核对 |\n| 付款前 | 总金额、总笔数与明细一致 |\n| 付款后 | 已保存缴费成功截图、订单编号、交易号 |\n| 付款后 | 已更新专利台账和下一次缴费提醒 |\n| 归档 | 缴费清单、付款凭证、发票已放入同一归档文件夹 |\n| 同步 | 如代理机构参与提醒，已反馈缴费完成 |', '检查清单,缴费检查,归档检查', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(@cat_checklist, '账号安全提醒', '## 账号安全提醒\n\n> **重要**：登录账号、密码、统一社会信用代码等信息请统一存放在公司《密码清单汇总》中。本手册正文不直接展示密码，避免转发后泄露。\n\n常用系统入口：\n- [专利业务办理系统](https://interactive.cponline.cnipa.gov.cn/public-app-zljffw/onlinePay/fillInList) - 专利年限缴费\n- [常规检索](https://pss-system.cponline.cnipa.gov.cn/conventionalSearch) - 可以查看下载详情\n- [中国及多国专利审查信息查询](https://cpquery.cponline.cnipa.gov.cn/chinesepatent/index) - 可以查看详情', '账号安全,密码,系统入口', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);