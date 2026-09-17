-- ============================================
-- V62: 文档管理 → 文件库重构
-- 1. documents 表精简（去除文档管理特有字段，加封面）
-- 2. product_documents 表简化（去除 is_required/remark）
-- 3. 素材表加 cover_file_name（封面功能）
-- ============================================

-- ====== 1. documents 表：加封面字段 ======
ALTER TABLE documents ADD COLUMN cover_file_name VARCHAR(500) COMMENT '自定义封面图片文件名';

-- ====== 2. documents 表：迁移分类到素材分类体系 ======
UPDATE documents SET category = 'manual' WHERE category IN ('操作手册', '使用说明');
UPDATE documents SET category = 'other' WHERE category IN ('制度文件', '登记表', '培训资料', '其他');
ALTER TABLE documents MODIFY COLUMN category VARCHAR(50) NOT NULL DEFAULT 'other';

-- ====== 3. documents 表：删除不需要的列 ======
-- 先删除 department 索引
ALTER TABLE documents DROP INDEX idx_doc_department;
-- 删除文档管理特有字段
ALTER TABLE documents DROP COLUMN content;
ALTER TABLE documents DROP COLUMN summary;
ALTER TABLE documents DROP COLUMN status;
ALTER TABLE documents DROP COLUMN department;
ALTER TABLE documents DROP COLUMN version;
ALTER TABLE documents DROP COLUMN author;
ALTER TABLE documents DROP COLUMN doc_date;

-- ====== 4. product_documents 表：迁移 doc_type 到素材分类 ======
UPDATE product_documents SET doc_type = 'manual' WHERE doc_type IN ('operation_manual', 'quick_start', 'maintenance_guide', 'safety_instructions', 'parts_catalog', 'troubleshooting');
UPDATE product_documents SET doc_type = 'operation_video' WHERE doc_type = 'video_tutorial';
UPDATE product_documents SET doc_type = 'certificate' WHERE doc_type = 'certification';
UPDATE product_documents SET doc_type = 'other' WHERE doc_type = 'warranty_card';

-- ====== 5. product_documents 表：简化（删除不需要的列） ======
ALTER TABLE product_documents DROP COLUMN is_required;
ALTER TABLE product_documents DROP COLUMN remark;

-- ====== 6. 素材表：加封面字段 ======
ALTER TABLE product_assets ADD COLUMN cover_file_name VARCHAR(500) COMMENT '自定义封面图片文件名';
ALTER TABLE packaging_assets ADD COLUMN cover_file_name VARCHAR(500) COMMENT '自定义封面图片文件名';
ALTER TABLE company_assets ADD COLUMN cover_file_name VARCHAR(500) COMMENT '自定义封面图片文件名';
