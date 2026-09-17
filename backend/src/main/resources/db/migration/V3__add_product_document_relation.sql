-- ============================================
-- 阶段 3：产品 - 文档关联表
-- 执行前请备份数据库！
-- ============================================

USE product_archive;

-- 产品 - 文档关联表
CREATE TABLE IF NOT EXISTS product_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键 ID',
    model_id BIGINT NOT NULL COMMENT '产品型号 ID',
    document_id BIGINT NOT NULL COMMENT '文档 ID',
    doc_type VARCHAR(50) NOT NULL COMMENT '文档类型：operation_manual/maintenance_guide/safety_instructions...',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序（升序）',
    is_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否必选文档',
    remark VARCHAR(500) COMMENT '备注',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    CONSTRAINT uk_product_document UNIQUE (model_id, document_id),
    CONSTRAINT fk_pd_model FOREIGN KEY (model_id) REFERENCES product_models(id) ON DELETE CASCADE,
    CONSTRAINT fk_pd_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_model (model_id),
    INDEX idx_document (document_id),
    INDEX idx_doc_type (doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品 - 文档关联表';

-- 可选：插入一些测试数据（根据实际产品 ID 调整）
-- INSERT INTO product_documents (model_id, document_id, doc_type, sort_order, is_required) VALUES
-- (1, 1, 'operation_manual', 1, TRUE),
-- (1, 2, 'safety_instructions', 2, TRUE),
-- (1, 3, 'maintenance_guide', 3, FALSE);
