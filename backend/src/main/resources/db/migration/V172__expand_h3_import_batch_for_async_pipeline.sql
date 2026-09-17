-- 六层架构落地 · 第1步收尾：扩 h3_import_batches 支撑"大文件异步导入 + Excel 只解析一次"管线
-- 与 H3FactImportService.createBatchRecord / asyncParseAndStage / updateProgress / failBatch 对齐
--
-- 新增内容（相对 V126 基线）：
--   · file_size       字节数，与 file_hash 组成上传审计；
--   · file_hash       SHA-256 十六进制 64 位，作为 (data_type, file_hash) 幂等命中键；
--   · progress_pct    0~100 整数进度，与 progress_stage 文案组合给前端进度条；
--   · progress_stage  UPLOAD / PARSING / STAGING / PREVIEW / WRITING / ORCHESTRATING ...
--   · field_version   字段版本（main.keys + 子表 keys 采样哈希 16 位），表头变化时 version 变化；
--   · preview_json    预览 JSON 缓存（buildPreviewFromGroups 产物），命中时直接返回避免从暂存表二次拼装；
--   · error_detail    完整堆栈（最多 64KB），与短消息 error_message 搭配供开发定位。

ALTER TABLE h3_import_batches
    ADD COLUMN file_size       BIGINT       NOT NULL DEFAULT 0
        COMMENT '文件字节数；与 file_hash 共同组成上传审计'
    AFTER file_name,
    ADD COLUMN file_hash       CHAR(64)     NULL
        COMMENT '文件内容 SHA-256 十六进制 64 位；(data_type, file_hash) 幂等命中'
    AFTER file_size,
    ADD COLUMN progress_pct    INT          NOT NULL DEFAULT 0
        COMMENT '0~100 整数进度；完成 PREVIEW_READY/COMPLETED 时 = 100'
    AFTER status,
    ADD COLUMN progress_stage  VARCHAR(30)  NOT NULL DEFAULT 'UPLOAD'
        COMMENT '进度阶段：UPLOAD / PARSING / STAGING / PREVIEW / WRITING / ORCHESTRATING / FAILED'
    AFTER progress_pct,
    ADD COLUMN field_version   VARCHAR(30)  NULL
        COMMENT '字段版本哈希 16 位；main.keys + 子表 keys 采样；表头变化则 version 变化'
    AFTER data_type,
    ADD COLUMN preview_json    LONGTEXT     NULL
        COMMENT '预览 JSON 缓存（buildPreviewFromGroups 产物）；命中时跳过暂存表二次拼装'
    AFTER error_message,
    ADD COLUMN error_detail    TEXT         NULL
        COMMENT '失败完整堆栈（最多 64KB）；error_message 为摘要；供开发定位'
    AFTER preview_json,
    ADD KEY idx_h3ib_data_hash_status (data_type, file_hash, status)
        COMMENT '幂等命中查询：同类型同哈希 + 已解析状态';
