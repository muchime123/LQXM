-- V43: 创建产品导入临时表（staging），支持分阶段导入预览与 UPSERT
-- 背景：当前导入流程一次性写入主表，无法预览和回滚。
--       引入 staging 表后，导入流程改为：
--       1) 解析 Excel → 写入 staging 表（行级 diff）
--       2) 前端预览 staging 数据
--       3) 用户确认 → 按 model_name + source_row_number 业务键 UPSERT 到主表
--       4) 清空 staging

CREATE TABLE IF NOT EXISTS product_import_staging (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT       NOT NULL COMMENT '导入批次ID',
    source_file     VARCHAR(500) NOT NULL COMMENT '来源文件名',
    source_sheet    VARCHAR(200) COMMENT '来源工作表名',
    source_row_number INT        NOT NULL COMMENT 'Excel行号',
    model_name      VARCHAR(200) NOT NULL COMMENT '产品名称（业务键1）',
    action          VARCHAR(20)  NOT NULL DEFAULT 'INSERT' COMMENT 'INSERT/UPDATE/NOOP/INVALID',
    row_data        JSON         COMMENT '解析后的行数据 JSON',
    validation_errors VARCHAR(2000) COMMENT '校验错误信息',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_staging_batch (batch_id),
    INDEX idx_staging_model (model_name),
    INDEX idx_staging_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='产品导入临时表，每次导入完成后清空';
