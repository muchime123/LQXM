package com.liqiang.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 钉钉报销 Excel 导入相关 DTO（2026-08-26）。
 * <p>
 * 数据源为钉钉 OA 审批后台导出的「日常报销」Excel：
 * 两级表头（第 1 行分组、第 2 行明细列名），数据从第 3 行开始，
 * 每行 = 一条报销明细；同一「审批编号」的多行合并为一条报销单。
 */
public final class ExpenseImportDtos {

    /** 预览分组行（一个审批编号 = 一条报销单） */
    public record ImportGroupRow(
            /** 审批编号（= 外部实例 id，DB external_id/claim_no） */
            String approvalNo,
            /** 审批单标题 */
            String title,
            /** 归属人（提单人） */
            String submitterName,
            /** 创建人部门 */
            String department,
            /** 审批状态（APPROVED/PENDING/REJECTED/CANCELED/TERMINATED） */
            String approvalStatus,
            /** 费用分类（TRAVEL/OFFICE/...） */
            String expenseCategory,
            /** 明细条数 */
            int itemCount,
            /** 明细金额合计 */
            BigDecimal totalAmount,
            /** 提单日期（取创建时间） */
            LocalDate claimDate,
            /** 审批完成时间（可为空） */
            LocalDate approvalFinishedDate,
            /** 动作：CREATE / UPDATE / UNCHANGED / ERROR */
            String action,
            /** 该组解析错误信息（action=ERROR 时有值） */
            String error,
            /** 归属人未能映射到系统员工 */
            boolean mappingMissed
    ) {}

    /** 导入预览结果 */
    public record ImportPreview(
            String fileName,
            int totalRows,
            int totalGroups,
            int createCount,
            int updateCount,
            int unchangedCount,
            int errorCount,
            int mappingMissedCount,
            List<ImportGroupRow> groups
    ) {}

    /** 导入确认结果 */
    public record ImportResult(
            int totalGroups,
            int createdClaims,
            int updatedClaims,
            int unchangedClaims,
            int errorCount,
            int mappingMissedCount,
            int itemCount,
            List<String> errors
    ) {}

    private ExpenseImportDtos() {
    }
}
