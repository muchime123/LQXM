package com.liqiang.archive.erp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERP 公共列规范测试（M0）。
 *
 * <p>这些断言是**跨里程碑的结构护栏**：M1 建主数据表、M2 建单据表时若漏列，
 * 应当在这里先红，而不是等到 S0 影子用例或生产对账才发现。
 */
class ErpCommonColumnsTest {

    @Test
    void masterDataColumnsCoverLifecycleAndAudit() {
        var cols = ErpCommonColumns.MASTER_DATA;
        assertTrue(cols.contains("id"));
        assertTrue(cols.contains("code"));
        assertTrue(cols.contains("name"));
        assertTrue(cols.contains("lifecycle_status"), "主数据必须有生命周期列（M02 唯一性只在启用范围生效依赖它）");
        assertTrue(cols.contains("version"), "主数据必须有乐观锁列（DA5）");
        assertTrue(cols.contains("change_reason"), "停用/合并必须可留原因");
        for (String audit : new String[]{"created_by", "created_at", "updated_by", "updated_at"}) {
            assertTrue(cols.contains(audit), "主数据缺审计列：" + audit);
        }
        assertTrue(cols.size() >= 10);
    }

    @Test
    void documentColumnsSeparateVersionAndRevision() {
        var cols = ErpCommonColumns.DOCUMENT;
        assertTrue(cols.contains("version"), "单据必须有并发乐观锁列");
        assertTrue(cols.contains("revision"), "单据必须有业务修订列");
        assertFalse(cols.contains("revision_version"), "version 与 revision 必须分列，不得合并");
    }

    @Test
    void documentColumnsCarryStatusAndPostingFields() {
        var cols = ErpCommonColumns.DOCUMENT;
        for (String required : new String[]{
                "doc_no", "doc_type", "company_id", "business_date",
                "approval_status", "posting_status", "rule_version",
                "currency", "pricing_basis", "posted_at"}) {
            assertTrue(cols.contains(required), "单据缺公共列：" + required);
        }
    }

    @Test
    void masterDataDdlEncodesActiveScopeUniqueness() {
        String ddl = ErpCommonColumns.MASTER_DATA_DDL;
        assertTrue(ddl.contains("active_code"), "M02 需要 active_code 存储生成列");
        assertTrue(ddl.contains("GENERATED ALWAYS AS"), "active_code 必须是存储生成列");
        assertTrue(ddl.contains("STORED"), "生成列必须 STORED（唯一键只能建在存储列上）");
        assertTrue(ddl.contains("lifecycle_status = 'ACTIVE'"), "唯一性只在 ACTIVE 范围生效");
        assertTrue(ddl.contains("utf8mb4_0900_as_cs"), "编码列必须显式大小写敏感，不依赖库默认 collation");
        assertTrue(ddl.contains("change_reason"), "停用/合并的原因列必须落到 DDL");
    }

    @Test
    void documentDdlKeepsPolicyVersionAtPostingTime() {
        String ddl = ErpCommonColumns.DOCUMENT_DDL;
        assertTrue(ddl.contains("rule_version"), "过账时固定的政策版本必须落列（B17 依据）");
        assertTrue(ddl.contains("occurred_at") && ddl.contains("posted_at"),
                "实际发生时间与系统过账时间必须分开（契约 §2）");
        assertTrue(ddl.contains("evidence_refs"), "证据引用列缺失");
    }
}
