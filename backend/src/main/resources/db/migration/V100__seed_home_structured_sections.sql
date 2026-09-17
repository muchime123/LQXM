-- 将首页仍由前端常量提供的复合内容迁入页面区块 CMS。
-- 仅在 content 为空时写入，避免覆盖运营人员已经维护的数据。
UPDATE website_page_sections
SET content = '[{"title":"包装设备制造","description":"","image":"/images/workshop.webp","link":"/products","icon":""},{"title":"模具设计与生产","description":"","image":"/images/manual-sealer.jpg","link":"/molds","icon":""},{"title":"工厂现场与交付","description":"","image":"/images/factory.webp","link":"/about","icon":""}]'
WHERE page_key = 'HOME' AND section_key = 'showcase' AND (content IS NULL OR TRIM(content) = '');

UPDATE website_page_sections
SET content = '[{"title":"了解产品与容器","description":"沟通产品形态、容器尺寸与产能目标","image":"","link":"","icon":""},{"title":"方案匹配与样品验证","description":"匹配设备、模具和膜材，并验证封合效果","image":"","link":"","icon":""},{"title":"生产装配与整机测试","description":"自有工厂完成装配，出厂前进行运行测试","image":"","link":"","icon":""},{"title":"交付培训与长期支持","description":"完成安装调试、操作培训与后续技术支持","image":"","link":"","icon":""}]'
WHERE page_key = 'HOME' AND section_key = 'process' AND (content IS NULL OR TRIM(content) = '');

UPDATE website_page_sections
SET content = '[{"title":"交付前验证","description":"围绕样品、容器与目标产能完成试封和配置确认","image":"","link":"","icon":"check"},{"title":"安装调试与培训","description":"完成设备调试、运行验证和操作人员培训","image":"","link":"","icon":"machine"},{"title":"长期配套支持","description":"持续提供维护支持、模具适配与耗材配套","image":"","link":"","icon":"shield"}]'
WHERE page_key = 'HOME' AND section_key = 'service' AND (content IS NULL OR TRIM(content) = '');
