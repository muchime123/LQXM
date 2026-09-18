package com.liqiang.archive.erp;

import java.util.List;

/**
 * ERP 公共列规范（技术方案映射 §3 / M0 交付清单的「公共列基类」）。
 *
 * <p>为什么是常量 + DDL 片段而不是 JPA {@code @MappedSuperclass}：M0 阶段 erp schema 的访问层
 * 采用独立 {@code erpJdbcTemplate}（见 {@code ErpDataSourceConfig}），尚未引入第二个
 * EntityManagerFactory。M1 若为 ERP 实体启用 JPA，本类可直接转成 {@code @MappedSuperclass}，
 * 列清单与 DDL 片段作为单一事实来源保留。
 *
 * <p>两条硬约束（M0 起生效，M1 建表必须遵守）：
 * <ol>
 *   <li>{@code version}（并发乐观锁）与 {@code revision}（业务修订）**必须分列**——S0 骨架把两者
 *       合一，生产表要分开（契约 §2）。</li>
 *   <li>编码列用 {@code utf8mb4_0900_as_cs} 显式声明大小写敏感，不依赖库默认 collation；
 *       「唯一性只在启用范围生效」靠存储生成列 + 唯一键实现（M02）。</li>
 * </ol>
 */
public final class ErpCommonColumns {

    private ErpCommonColumns() {
    }

    /** 主数据公共列（§3.1）。 */
    public static final List<String> MASTER_DATA = List.of(
            "id", "code", "name", "lifecycle_status", "version", "change_reason",
            "created_by", "created_at", "updated_by", "updated_at",
            "activated_at", "activated_by", "disabled_at", "disabled_by");

    /** 单据公共列（§3.2）。 */
    public static final List<String> DOCUMENT = List.of(
            "id", "doc_no", "doc_type", "company_id", "business_date", "occurred_at", "posted_at",
            "approval_status", "posting_status", "revision", "version",
            "party_id", "company_role_id", "currency", "pricing_basis", "rule_version",
            "owner_employee_id", "department_id", "reason_code", "remark", "evidence_refs",
            "created_by", "created_at", "approved_by", "approved_at", "posted_by", "updated_at");

    /** 主数据公共列 DDL 片段（追加到 CREATE TABLE 的列定义区）。 */
    public static final String MASTER_DATA_DDL = """
            id               BIGINT        NOT NULL AUTO_INCREMENT,
            code             VARCHAR(64)   COLLATE utf8mb4_0900_as_cs NULL COMMENT '启用时必填；唯一性只在 ACTIVE 范围生效',
            name             VARCHAR(200)  NOT NULL COMMENT '名称不是身份键',
            lifecycle_status VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/INACTIVE',
            version          INT           NOT NULL DEFAULT 0 COMMENT '并发乐观锁（与 revision 分列）',
            change_reason    VARCHAR(1000) NULL COMMENT '停用/合并/关键变更必填',
            created_by       BIGINT        NULL,
            created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_by       BIGINT        NULL,
            updated_at       DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP,
            activated_at     DATETIME      NULL,
            activated_by     BIGINT        NULL,
            disabled_at      DATETIME      NULL,
            disabled_by      BIGINT        NULL,
            active_code      VARCHAR(64)   COLLATE utf8mb4_0900_as_cs
                             GENERATED ALWAYS AS (IF(lifecycle_status = 'ACTIVE', code, NULL)) STORED
                             COMMENT 'M02 唯一性只在启用范围生效：UNIQUE(active_code)'
            """;

    /** 单据公共列 DDL 片段（追加到 CREATE TABLE 的列定义区）。 */
    public static final String DOCUMENT_DDL = """
            id                BIGINT        NOT NULL AUTO_INCREMENT,
            doc_no            VARCHAR(64)   NULL COMMENT '单号（uk: company_id + doc_type + doc_no，作废号不复用）',
            doc_type          VARCHAR(30)   NOT NULL,
            company_id        BIGINT        NOT NULL COMMENT '过账后不可变更（应用校验）',
            business_date     DATE          NOT NULL,
            occurred_at       DATETIME      NULL COMMENT '实际发生时间',
            posted_at         DATETIME      NULL COMMENT '系统过账时间',
            approval_status   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/APPROVED/CANCELLED',
            posting_status    VARCHAR(20)   NOT NULL DEFAULT 'UNPOSTED' COMMENT 'UNPOSTED/POSTED（执行单族用）',
            revision          INT           NOT NULL DEFAULT 0 COMMENT '业务修订',
            version           INT           NOT NULL DEFAULT 0 COMMENT '并发乐观锁',
            party_id          BIGINT        NULL,
            company_role_id   BIGINT        NULL,
            currency          VARCHAR(10)   NOT NULL DEFAULT 'CNY',
            pricing_basis     VARCHAR(20)   NOT NULL DEFAULT 'TAX_INCL',
            rule_version      VARCHAR(32)   NOT NULL DEFAULT 'v0' COMMENT '过账时固定的政策版本',
            owner_employee_id BIGINT        NULL,
            department_id     BIGINT        NULL,
            reason_code       VARCHAR(50)   NULL,
            remark            VARCHAR(1000) NULL,
            evidence_refs     JSON          NULL,
            created_by        BIGINT        NULL,
            created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
            approved_by       BIGINT        NULL,
            approved_at       DATETIME      NULL,
            posted_by         BIGINT        NULL,
            updated_at        DATETIME      NULL ON UPDATE CURRENT_TIMESTAMP
            """;
}
