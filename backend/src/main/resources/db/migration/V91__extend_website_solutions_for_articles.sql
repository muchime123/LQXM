-- 扩展官网解决方案内容模型：生产场景 + 可独立访问的方案文章。
ALTER TABLE website_solutions
    ADD COLUMN slug VARCHAR(120) NULL COMMENT '文章公开 URL 标识' AFTER item_key,
    ADD COLUMN category VARCHAR(50) NULL COMMENT '内容分类：生产场景/问题诊断/选型参考' AFTER title,
    ADD COLUMN cover_image_url VARCHAR(500) NULL COMMENT '官网封面图地址' AFTER summary,
    ADD COLUMN audience VARCHAR(500) NULL COMMENT '适合阅读对象' AFTER cover_image_url,
    ADD COLUMN takeaway VARCHAR(500) NULL COMMENT '读者可获得的内容' AFTER audience,
    ADD COLUMN intro TEXT NULL COMMENT '文章导语' AFTER takeaway,
    ADD COLUMN content_sections JSON NULL COMMENT '正文分段 [{title,body,points}]' AFTER points,
    ADD COLUMN related_scene_keys JSON NULL COMMENT '关联生产场景 key 列表' AFTER content_sections,
    ADD COLUMN seo_title VARCHAR(200) NULL COMMENT 'SEO 标题' AFTER related_scene_keys,
    ADD COLUMN seo_description VARCHAR(500) NULL COMMENT 'SEO 描述' AFTER seo_title,
    ADD COLUMN website_featured TINYINT(1) NOT NULL DEFAULT 0 COMMENT '官网精选' AFTER website_enabled,
    ADD UNIQUE INDEX uk_ws_slug (slug),
    ADD INDEX idx_ws_article_publish (section, website_enabled, website_featured, sort_order);

-- 用当前官网已经验证过的四个场景替换旧的泛行业入口。
UPDATE website_solutions SET website_enabled = 0 WHERE section = 'INDUSTRY';

INSERT INTO website_solutions
    (section, item_key, title, category, summary, cover_image_url, related_scene_keys, sort_order, website_enabled)
VALUES
    ('INDUSTRY', 'prepared-food', '鲜食与预制菜', '生产场景', '货架期、冷链与保鲜工艺', '/images/scene-prepared-food.webp', JSON_ARRAY('prepared-food'), 1, 1),
    ('INDUSTRY', 'fresh-meat', '生鲜肉类', '生产场景', '封合完整性与气调保鲜', '/images/scene-fresh-meat.webp', JSON_ARRAY('fresh-meat'), 2, 1),
    ('INDUSTRY', 'catering', '中央厨房与餐饮', '生产场景', '从人工操作到分阶段自动化', '/images/scene-catering.webp', JSON_ARRAY('catering'), 3, 1),
    ('INDUSTRY', 'bakery', '烘焙与多规格产品', '生产场景', '多盒型兼容与换型效率', '/images/scene-bakery.webp', JSON_ARRAY('bakery'), 4, 1);

-- 将官网现有方案文章迁入 CMS，迁移后可直接由后台维护。
INSERT INTO website_solutions
    (section, item_key, slug, title, category, summary, cover_image_url, audience, takeaway, intro,
     content_sections, related_scene_keys, seo_title, seo_description, sort_order, website_enabled, website_featured)
VALUES
('ARTICLE', 'fresh-food-shelf-life-planning', 'fresh-food-shelf-life-planning',
 '鲜食与预制菜包装：如何从货架期目标反推包装方案', '生产场景',
 '先明确产品特性、冷链条件和目标货架期，再判断普通封口、真空或气调是否适合，避免只看设备名称做决定。',
 '/images/scene-prepared-food.webp', '鲜食、熟食、预制菜生产企业', '一套从产品与流通条件反推包装工艺的判断顺序',
 '延长货架期不是单独增加一台气调设备就能解决的问题。原料状态、加工卫生、包装气体、阻隔材料、封口质量和冷链稳定性共同决定最终结果。',
 JSON_ARRAY(
   JSON_OBJECT('title','先定义目标，而不是先选择机器','body','需要先记录产品类型、初始菌落控制、包装后储存温度、配送周期和终端销售方式。相同设备在不同产品和冷链条件下，结果可能完全不同。','points',JSON_ARRAY('产品是生鲜、熟制还是混合配菜','期望货架期与实际配送周期','全程冷藏温度及波动范围','容器尺寸、装量和封口面状态')),
   JSON_OBJECT('title','判断包装路径','body','普通热封侧重密封与防污染；真空包装适合部分不易变形产品；气调包装通过调整包装内气体环境改善保鲜条件。最终路径应通过样品测试确定。','points',JSON_ARRAY('先验证密封完整性','再比较不同气体或真空条件','记录外观、汁液、气味与微生物变化','以完整储运周期验证结果')),
   JSON_OBJECT('title','设备之外必须同时确认','body','模具与盒型公差、膜材热封层、温度时间压力窗口、气源稳定性和清洁流程都属于方案的一部分。任何一个环节不匹配，都可能让设备性能无法体现。','points',JSON_ARRAY())
 ), JSON_ARRAY('prepared-food','fresh-meat'), NULL, NULL, 1, 1, 1),
('ARTICLE', 'tray-sealing-leak-diagnosis', 'tray-sealing-leak-diagnosis',
 '托盒封口不牢、漏气或容易撕开，应该从哪里排查？', '问题诊断',
 '将常见封口问题拆成盒、膜、模具、参数和操作五个方向，建立比反复升温更可靠的排查顺序。',
 '/images/scene-fresh-meat.webp', '正在使用托盒封口设备的生产现场', '一份可直接用于现场沟通的封口问题检查清单',
 '封口不良经常被简单归因于温度不足，但材料匹配、封口面污染、模具压力和停留时间同样关键。盲目升温还可能造成膜变形或热封层损伤。',
 JSON_ARRAY(
   JSON_OBJECT('title','先观察缺陷形态','body','连续整圈不牢、局部漏气、封口褶皱和冷却后脱层通常对应不同原因。保留问题样品并标记批次，比只描述“封不住”更有利于判断。','points',JSON_ARRAY()),
   JSON_OBJECT('title','按五个方向逐项排查','body','建议固定其他条件，每次只改变一个变量并记录结果。','points',JSON_ARRAY('盒：材质、法兰平整度、尺寸公差与洁净度','膜：热封层方向、适配材质、厚度与批次','模具：压合平行度、硅胶状态与受力均匀性','参数：温度、时间、压力与冷却条件','操作：放盒位置、装量、封口面油水污染')),
   JSON_OBJECT('title','什么时候需要样品试封','body','更换盒型、膜材供应商或产品配方后，应重新建立参数窗口。将实际盒、膜和产品一起送样，比只提供尺寸更容易得到可复现的结果。','points',JSON_ARRAY())
 ), JSON_ARRAY('fresh-meat'), NULL, NULL, 2, 1, 1),
('ARTICLE', 'multi-format-equipment-selection', 'multi-format-equipment-selection',
 '多种盒型共用一台设备，选型时要确认哪些条件？', '选型参考',
 '从最大外形、模具切换、膜材形式和生产节拍评估共机可行性，避免设备买回后才发现规格无法兼容。',
 '/images/scene-bakery.webp', 'SKU 较多、盒型经常切换的企业', '设备共用与分线配置的核心判断条件',
 '共用设备可以降低初期投入，但规格跨度、换模频率和不同产品的清洁要求会影响实际效率。选型不能只比较最大封口尺寸。',
 JSON_ARRAY(
   JSON_OBJECT('title','建立完整的容器清单','body','为每个盒型记录外径、高度、法兰宽度、材质、装量和日均批次，并标记未来可能新增的规格。','points',JSON_ARRAY()),
   JSON_OBJECT('title','核算切换成本','body','确认模具重量、换模方式、卷膜更换、参数配方调用和首件确认时间。频繁切换造成的停机时间可能高于设备差价。','points',JSON_ARRAY('每日换型次数','单次换模与清洁时间','不同规格的目标节拍','操作人员数量与技能要求')),
   JSON_OBJECT('title','决定共机还是分线','body','规格接近、批量较小且清洁要求一致时更适合共机；高频大批量或存在明显交叉污染风险时，应评估独立产线。','points',JSON_ARRAY())
 ), JSON_ARRAY('bakery'), NULL, NULL, 3, 1, 0),
('ARTICLE', 'central-kitchen-automation-upgrade', 'central-kitchen-automation-upgrade',
 '中央厨房从人工封口升级自动化，应该先改哪一段？', '生产场景',
 '用产能瓶颈、人员动作和前后工序节拍判断自动化切入点，而不是一次性把所有环节都替换。',
 '/images/scene-catering.webp', '准备扩产或降低人工波动的中央厨房', '一套分阶段评估自动化投入的思路',
 '自动化升级的目标应是解决明确瓶颈。灌装、上盒、封口、检测、贴标和装箱之间如果节拍不匹配，单独提高封口速度未必提升整线产出。',
 JSON_ARRAY(
   JSON_OBJECT('title','记录真实生产节拍','body','按班次记录各工位的等待、停机、返工和换型时间，找到限制整线产出的环节。','points',JSON_ARRAY()),
   JSON_OBJECT('title','优先处理稳定且重复的动作','body','规格相对固定、动作重复、人工强度高的工位更适合优先自动化。产品变化大或仍依赖人工判断的环节，可以保留人工并优化衔接。','points',JSON_ARRAY()),
   JSON_OBJECT('title','分阶段验证','body','先验证核心封口单元与前后输送接口，再逐步加入灌装、检测和贴标模块，并为未来扩展预留控制与场地接口。','points',JSON_ARRAY())
 ), JSON_ARRAY('catering'), NULL, NULL, 4, 1, 0),
('ARTICLE', 'film-tray-compatibility', 'film-tray-compatibility',
 '更换封口膜或托盒供应商后，为什么原来的参数失效了？', '问题诊断',
 '材料名称相同不代表热封窗口完全一致，理解基材、热封层和批次差异对封口结果的影响。',
 '/images/hero-machine.png', '准备替换包材或存在批次波动的采购与生产团队', '包材变更前应完成的兼容性验证项目',
 '托盒和封口膜的材料标识只能说明大类，实际热封层配方、厚度、表面处理和生产公差仍可能不同，因此原参数不能直接视为通用参数。',
 JSON_ARRAY(
   JSON_OBJECT('title','确认变更了什么','body','除材料名称外，还要对比膜厚、热封层方向、雾度、阻隔要求、盒口尺寸和法兰平整度。','points',JSON_ARRAY()),
   JSON_OBJECT('title','重新寻找稳定参数窗口','body','通过温度、时间和压力的小范围组合测试，找到既能稳定密封又不会导致膜或盒变形的区间，而不是只找到一个偶然可用的参数点。','points',JSON_ARRAY()),
   JSON_OBJECT('title','建立来料与变更管理','body','保存合格样品和参数记录，供应商、材料结构或批次发生明显变化时重新验证，减少生产现场临时调机。','points',JSON_ARRAY())
 ), JSON_ARRAY('prepared-food','fresh-meat','bakery'), NULL, NULL, 5, 1, 0),
('ARTICLE', 'sample-sealing-validation', 'sample-sealing-validation',
 '设备采购前的样品试封，怎样测试才有参考价值？', '选型参考',
 '把试封从“封上一盒看看”升级为覆盖真实材料、装量、速度和储运条件的验证过程。',
 '/images/workshop.webp', '正在比较设备或准备采购的项目负责人', '试封前的资料准备与结果记录框架',
 '一次外观良好的封口不能代表长期稳定生产。有效试封需要尽量接近真实生产条件，并对密封、外观、节拍和后续储运进行记录。',
 JSON_ARRAY(
   JSON_OBJECT('title','准备真实样品','body','提供计划量产的盒、膜和产品，说明装量、封口面油水情况、储存温度和目标产能。只提供空盒无法覆盖真实风险。','points',JSON_ARRAY()),
   JSON_OBJECT('title','约定验证项目','body','不同项目的重点不同，应提前明确检查方法。','points',JSON_ARRAY('封口完整性与开启体验','膜面平整度和盒体变形','连续运行节拍与一致性','换型、清洁和操作便利性','经过储运或冷藏后的状态')),
   JSON_OBJECT('title','形成可追溯记录','body','记录使用的材料批次、模具、设备参数和测试结果，并保留合格样品，为交付调试和后续包材变更提供基准。','points',JSON_ARRAY())
 ), JSON_ARRAY('prepared-food','fresh-meat','catering','bakery'), NULL, NULL, 6, 1, 0);
