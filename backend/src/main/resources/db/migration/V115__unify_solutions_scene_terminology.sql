-- 统一解决方案页与首页"按应用场景选择方案"的术语，保持两页场景关联一致。
-- 仅在标题仍为 V104 种子值时更新，避免覆盖运营人员后续的编辑内容。
UPDATE website_page_sections
SET title = '从应用场景出发，找到可借鉴的包装方案',
    subtitle = '按产品与应用场景进入，查看问题诊断、工艺判断与设备选型参考。'
WHERE page_key = 'SOLUTIONS' AND section_key = 'hero'
  AND title = '从生产场景出发，找到可借鉴的包装方案';

UPDATE website_page_sections
SET title = '按应用场景查找'
WHERE page_key = 'SOLUTIONS' AND section_key = 'scenes'
  AND title = '按生产场景查找';