-- 六层架构落地 · 第1步收尾：新建 h3_import_staging_rows，使"Excel 只解析一次，预览/确认均从暂存读取"
--
-- 设计原则（与 H3FactImportService.insertStagingSlice / loadStagingGroups 严格对齐）：
--   · row_seq        = 组在 Excel 解析结果中的有序序号（0..N-1），同一批次 UK 保证顺序稳定；
--   · group_key      = Group.key()（用于前端分组标题）；
--   · external_id    = 氚云原始单据 ID（与 action 判定、落库匹配用）；
--   · source_hash    = Group.hash()（main 的内容哈希，用于 CREATE/UPDATE/UNCHANGED 判定）；
--   · main_json      = 单张主单据 JSON（sales_outbound / receipt / production_order / settlement）；
--   · rows_json      = 单据对应的明细行 JSON 数组（明细/分配/工序等）；
--   · parsed_at      = 最后一次写入时间（ON DUPLICATE KEY UPDATE 时刷新）。
--
-- 与 h3_import_rows（原始 V126）的区别：
--   h3_import_rows  = 已经确认落库后的"原始快照 + 处理结果"审计表（永久保留，一条行一条业务单据），
--   h3_import_staging_rows = 预览阶段的"临时暂存 + 解析内存镜像"（预览后确认写入用，完成后可清理）。

CREATE TABLE h3_import_staging_rows (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'PK',
    batch_id    BIGINT       NOT NULL COMMENT '批次 h3_import_batches.id',
    row_seq     INT          NOT NULL COMMENT '组序号 0..N-1；同批次下保证顺序稳定',
    group_key   VARCHAR(255) NOT NULL COMMENT 'Group.key() 前端分组标题',
    external_id VARCHAR(100) NOT NULL COMMENT '氚云原始单据 ID；与落库匹配用',
    source_hash CHAR(64)     NOT NULL COMMENT 'Group.hash()；main 内容 SHA-256，用于判定 CREATE/UPDATE/UNCHANGED',
    main_json   LONGTEXT     NOT NULL COMMENT '主单据 JSON（单张主档）',
    rows_json   LONGTEXT     NOT NULL COMMENT '明细 JSON 数组（明细/分配/工序等，允许空数组 []）',
    parsed_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本次解析/写入时间；重试 ON DUPLICATE KEY 时刷新',
    UNIQUE KEY uk_h3isr_batch_seq (batch_id, row_seq)
        COMMENT '同批次序号唯一；保证顺序稳定 + 驱动 ON DUPLICATE KEY UPDATE',
    KEY idx_h3isr_batch_external (batch_id, external_id)
        COMMENT '同批次按原始单据 ID 快速定位',
    KEY idx_h3isr_parsed_at (parsed_at)
        COMMENT '按解析时间清理过期暂存行'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云导入暂存行（Excel 仅解析一次；预览/确认均从此表读取）';
