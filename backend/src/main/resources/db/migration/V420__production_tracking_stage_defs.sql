-- 制程节点定义表：把「车间有哪些跟单节点、什么顺序、哪些是按需工序」从 Java 常量搬到数据库。
--
-- 背景：节点序列原先硬编码在 ProductionTrackingPolicy.SEALER_STAGES / MACHINE_STAGES，
-- 每加一个节点都要改常量 + 写迁移 + 改测试 + 发版。落表后由「生产参考工期」页的制程管理维护。
--
-- 【粒度】按**车间类别**（workshop_kind）配置，不是按车间。原因：列表统计的 ms 派生表
-- 按「每个类别一套聚合列」统计（sealer_done_count / machine_done_count），按车间展开会让
-- 派生表列数随车间数增长，且完成判据要做成动态列。类别将来可从 SEALER/MACHINE 扩到多档。
--
-- 【不可改字段】stage_code 一经产生里程碑记录就不可修改——历史 milestone 行靠 code 关联，
-- 改 code 等于把所有历史跟单记录变成孤儿。改名请改 stage_label。
--
-- 【删除策略】有里程碑记录的节点只允许停用（enabled=0），不允许删除；零记录的节点可删。
-- 停用后不参与展示、不进完成分母，历史记录原样保留。

CREATE TABLE IF NOT EXISTS production_tracking_stage_defs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  workshop_kind VARCHAR(20) NOT NULL COMMENT 'SEALER=封口机 / MACHINE=气调机等机器车间',
  stage_code VARCHAR(30) NOT NULL COMMENT '内部编码；一经产生里程碑记录不可修改',
  stage_label VARCHAR(50) NOT NULL COMMENT '展示名，同时是进度表默认表头写法',
  sort_no INT NOT NULL COMMENT '制程顺序（升序）；决定节点条顺序与链式推算顺序',
  optional TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=按需工序：不计入完成分母，按关键词判定本单是否适用',
  match_keywords VARCHAR(200) NULL COMMENT '按需工序：可选配置/生产要求命中该正则即本单适用',
  exclude_keywords VARCHAR(200) NULL COMMENT '按需工序：命中该正则（否定表达）则本单不适用',
  header_aliases VARCHAR(200) NULL COMMENT '进度表表头别名，逗号分隔；stage_label 之外还要认的写法',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '0=停用：不参与展示与统计，历史记录保留',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_stage_def_kind_code (workshop_kind, stage_code),
  KEY idx_stage_def_kind_sort (workshop_kind, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单制程节点定义（车间类别级）';

-- 播种：与 ProductionTrackingPolicy 内置的默认序列完全一致（含 V419 新增的物料配齐），
-- 保证落表前后行为不变；内置常量退化为「表还没加载时的兜底」。
-- INSERT IGNORE：上线后管理员改过的名字/顺序不会被重跑覆盖。
INSERT IGNORE INTO production_tracking_stage_defs(workshop_kind,stage_code,stage_label,sort_no,optional,
        match_keywords,exclude_keywords,header_aliases,enabled) VALUES
    -- 封口机：备料 → 模具生产 → 氧化/喷特氟龙(按需) → 物料配齐 → 组装 → 入库 → 木箱(按需)
    ('SEALER','MATERIAL','备料',1,0,NULL,NULL,NULL,1),
    ('SEALER','MOLD','模具生产',2,0,NULL,NULL,'模具',1),
    ('SEALER','OXIDATION','氧化/喷特氟龙',3,1,'氧化|特氟龙|特富龙',
        '(不含|无需|不用|不需要|无)(氧化|特氟龙|特富龙)',NULL,1),
    ('SEALER','MATERIAL_READY','物料配齐',4,0,NULL,NULL,'物料齐套',1),
    ('SEALER','ASSEMBLY','组装',5,0,NULL,NULL,NULL,1),
    ('SEALER','WAREHOUSE','入库',6,0,NULL,NULL,NULL,1),
    ('SEALER','WOODEN_CRATE','木箱',7,1,'木箱','(不含|无需|不用|不需要|无)木箱','出口木箱',1),
    -- 其他机器车间：画图 → 备料 → 钣金 → 模具 → 组装 → 调试 → 入库 → 木箱(按需)
    ('MACHINE','DRAWING','画图',1,0,NULL,NULL,NULL,1),
    ('MACHINE','MATERIAL','备料',2,0,NULL,NULL,NULL,1),
    ('MACHINE','SHEET_METAL','钣金',3,0,NULL,NULL,NULL,1),
    ('MACHINE','MOLD','模具',4,0,NULL,NULL,'模具生产',1),
    ('MACHINE','ASSEMBLY','组装',5,0,NULL,NULL,NULL,1),
    ('MACHINE','TESTING','调试',6,0,NULL,NULL,NULL,1),
    ('MACHINE','WAREHOUSE','入库',7,0,NULL,NULL,NULL,1),
    ('MACHINE','WOODEN_CRATE','木箱',8,1,'木箱','(不含|无需|不用|不需要|无)木箱','出口木箱',1);
