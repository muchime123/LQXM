-- 修正首页 Hero Banner：
-- 原 V85 种子数据仅提供一张 image_url='/images/hero-machine.png'，该文件不存在，
-- 导致 CMS Banner 虽然有记录但显示破图，同时又因 Home.vue "CMS 优先" 逻辑而屏蔽了
-- 前端默认的三张产品图。本迁移清除无效 HERO 记录，改为匹配官网产品定位的三张实景图。

-- 1) 先清理既有无效 HERO Banner（包括历史上可能通过后台编辑追加的）
DELETE FROM website_banners WHERE position = 'HERO';

-- 2) 重新注入三张产品实景图，对应：全自动连续式 / 气调封口 / 气调保鲜 三条产品线
INSERT INTO website_banners (
    position, title, subtitle, description,
    image_url, button_text, button_url,
    secondary_button_text, secondary_button_url,
    badge, sort_order, website_enabled
) VALUES
(
    'HERO',
    '包装设备与模具，<em>由我们自己制造</em>',
    'PACKAGING EQUIPMENT & MOLD MANUFACTURER',
    '从封口设备、气调包装机到配套模具与耗材，利强围绕您的产品、容器和产能目标完成整套包装配置。',
    '/images/20220728153033303324.webp',
    '探索产品', '/products',
    '获取包装方案', NULL,
    '自营工厂 · 整线交付',
    1, 1
),
(
    'HERO',
    '气调封口工艺，<em>让每一盒都稳定可靠</em>',
    'MAP SEALING · 食品级密封方案',
    '针对托盒封口、异形盒与多种包装材料，提供从样品试封到量产落地的完整工艺验证与设备配置。',
    '/images/20240713113411341198.webp',
    '查看封口设备', '/products',
    '获取包装方案', NULL,
    '稳定封合 · 工艺支持',
    2, 1
),
(
    'HERO',
    '气调保鲜包装，<em>让产品新鲜到家</em>',
    '气调保鲜 · 锁鲜封装',
    '围绕气体置换、封合效果与生产节拍，为生鲜、熟食、预制菜等企业匹配设备、模具与包装材料。',
    '/images/20240713133113311363.webp',
    '查看气调设备', '/products',
    '获取包装方案', NULL,
    '锁鲜配送 · 延长货架期',
    3, 1
);
