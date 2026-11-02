-- 导出字段选择的「全站默认字段」：管理员把当前勾选存为默认，全员打开导出弹窗即按这套预选。
-- 语义：column_key 在表里 = 管理员明确决定过该列是否默认勾选；表为空 = 未配置 ⇒ 前端兜底「核心字段」。
-- 与列表列默认（production_tracking_column_defaults）**分表**：两者 key 集合不同（如 assignee 都存在），
-- 放同一张表会撞 key，故独立。
CREATE TABLE IF NOT EXISTS production_tracking_export_column_defaults (
    column_key VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '导出列 key，与前端 EXPORT_COLUMNS 的 key 同源',
    selected TINYINT(1) NOT NULL COMMENT '1=默认勾选 / 0=默认不勾',
    updated_by BIGINT NULL COMMENT '最后修改的员工 id',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单导出默认勾选字段（全站，仅管理员可改）';

-- 不播种：表为空即「未配置」，前端回落「核心字段」，与改造前「只导核心字段」的默认一致。
-- 权限复用 manufacturing.listcolumns.manage（V387 已播种，仅 ADMIN）——两者都是「决定生产跟单默认列」。
