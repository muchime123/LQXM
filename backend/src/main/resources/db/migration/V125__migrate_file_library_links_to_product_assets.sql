-- 文件库不再作为一级业务模块。把已关联到产品的资料转为产品素材记录，
-- 同时保留 document 物理目录作为只读兼容来源，避免迁移大文件时造成丢失。

ALTER TABLE product_assets
    ADD COLUMN storage_category VARCHAR(60) NULL COMMENT '兼容物理存储分类；NULL 时使用 category',
    ADD COLUMN storage_owner_id BIGINT NULL COMMENT '兼容物理存储来源 ID',
    ADD COLUMN source_document_id BIGINT NULL COMMENT '迁移前 documents.id';

-- 归档任务使用 INSERT ... SELECT *，归档表必须保持完全相同的列结构与顺序。
ALTER TABLE product_assets_archive
    ADD COLUMN storage_category VARCHAR(60) NULL COMMENT '兼容物理存储分类；NULL 时使用 category',
    ADD COLUMN storage_owner_id BIGINT NULL COMMENT '兼容物理存储来源 ID',
    ADD COLUMN source_document_id BIGINT NULL COMMENT '迁移前 documents.id';

CREATE UNIQUE INDEX uk_product_asset_source_document
    ON product_assets (model_id, source_document_id);

INSERT INTO product_assets (
    model_id,
    variant_id,
    category,
    file_name,
    original_file_name,
    mime_type,
    file_size,
    tags,
    sort_order,
    default_asset,
    cover_file_name,
    website_visible,
    website_sort_order,
    storage_category,
    storage_owner_id,
    source_document_id,
    created_at
)
SELECT
    pd.model_id,
    NULL,
    COALESCE(NULLIF(pd.doc_type, ''), NULLIF(d.category, ''), 'other'),
    d.file_name,
    d.original_name,
    NULL,
    d.file_size,
    d.tags,
    COALESCE(pd.sort_order, 0),
    FALSE,
    d.cover_file_name,
    FALSE,
    0,
    'document',
    d.id,
    d.id,
    d.created_at
FROM (
    -- 同一文件可能曾分别关联多个产品规格；迁入后收口为一条产品通用素材。
    SELECT
        model_id,
        document_id,
        MAX(NULLIF(doc_type, '')) AS doc_type,
        MIN(COALESCE(sort_order, 0)) AS sort_order
    FROM product_documents
    GROUP BY model_id, document_id
) pd
JOIN documents d ON d.id = pd.document_id
WHERE d.deleted_at IS NULL
  AND d.file_name IS NOT NULL
  AND d.file_name <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM product_assets pa
      WHERE pa.model_id = pd.model_id
        AND pa.source_document_id = d.id
  );
