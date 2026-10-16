package com.liqiang.archive.dto;

import com.liqiang.archive.entity.ExpenseClaim;
import com.liqiang.archive.entity.ExpenseItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 日常报销 & 钉钉同步 DTO。
 * <p>
 * 枚举翻译（Service 层统一做中文 ↔ DB 英文）：
 * <ul>
 *   <li>approvalStatus: APPROVED 已通过 / REJECTED 已拒绝 / PENDING 审批中 / CANCELED 已撤销 / TERMINATED 终止</li>
 *   <li>expenseCategory: TRAVEL 差旅 / OFFICE 办公 / ENTERTAIN 业务招待 / TRANSPORT 交通 / MEAL 餐饮 / OTHER 其他 / CORP_PAY 对公付款</li>
 * </ul>
 * <p>2026-10-07：费用归单/分配（allocation）链路已下线，相关 DTO 已移除。
 */
public class ExpenseDtos {

    // ==================== 分页参数 ====================

    public record SearchParams(
            String keyword,
            Long employeeId,
            String status,
            String category,
            LocalDate dateFrom,
            LocalDate dateTo,
            int page,
            int size
    ) {
    }

    // ==================== 顶部指标卡 ====================

    public record ExpenseStats(
            long totalValidClaims,      // 累计有效报销单数（排除拒绝/撤销）
            BigDecimal thisMonthAmount, // 本月核定报销总额
            long awaitingMappingCount   // 待员工身份映射数量（同步未匹配员工的）
    ) {
    }

    // ==================== 列表行 ====================

    public record ClaimRow(
            Long id,
            String claimNo,
            String title,
            String approvalStatus,
            String expenseCategory,
            LocalDate claimDate,
            LocalDate approvalFinishedDate,
            Long employeeId,
            String submitterName,
            String submitterDepartment,
            BigDecimal totalAmount,
            BigDecimal approvedAmount,
            String remark,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static ClaimRow from(ExpenseClaim c, Function<String, String> translate) {
            return new ClaimRow(
                    c.getId(),
                    c.getClaimNo(),
                    c.getTitle(),
                    translate.apply(c.getApprovalStatus()),
                    translate.apply(c.getExpenseCategory()),
                    c.getClaimDate(),
                    c.getApprovalFinishedDate(),
                    c.getEmployeeId(),
                    c.getSubmitterNameSnapshot(),
                    c.getSubmitterDeptSnapshot(),
                    c.getTotalAmount(),
                    c.getApprovedAmount(),
                    c.getRemark(),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }

    // ==================== 详情（含明细） ====================

    public record ClaimDetail(
            Long id,
            String externalSource,
            String externalId,
            String claimNo,
            String title,
            String approvalStatus,
            String expenseCategory,
            LocalDate claimDate,
            LocalDate approvalFinishedDate,
            Long employeeId,
            String dingtalkUserId,
            String submitterName,
            String submitterDepartment,
            BigDecimal totalAmount,
            BigDecimal approvedAmount,
            String currency,
            String remark,
            String attachmentInfo,
            LocalDateTime sourceCreatedAt,
            LocalDateTime sourceUpdatedAt,
            LocalDateTime lastSyncedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<ItemRow> items
    ) {
        public static ClaimDetail from(ExpenseClaim c, List<ExpenseItem> items, Function<String, String> translate) {
            return new ClaimDetail(
                    c.getId(),
                    c.getExternalSource(),
                    c.getExternalId(),
                    c.getClaimNo(),
                    c.getTitle(),
                    translate.apply(c.getApprovalStatus()),
                    translate.apply(c.getExpenseCategory()),
                    c.getClaimDate(),
                    c.getApprovalFinishedDate(),
                    c.getEmployeeId(),
                    c.getDingtalkUserId(),
                    c.getSubmitterNameSnapshot(),
                    c.getSubmitterDeptSnapshot(),
                    c.getTotalAmount(),
                    c.getApprovedAmount(),
                    c.getCurrency(),
                    c.getRemark(),
                    c.getAttachmentInfo(),
                    c.getSourceCreatedAt(),
                    c.getSourceUpdatedAt(),
                    c.getLastSyncedAt(),
                    c.getCreatedAt(),
                    c.getUpdatedAt(),
                    items.stream().map(ItemRow::from).toList()
            );
        }
    }

    public record ItemRow(
            Long id,
            Integer sequenceNo,
            String expenseType,
            LocalDate itemDate,
            String itemDesc,
            BigDecimal amount,
            BigDecimal taxAmount,
            String invoiceNo,
            String receiptType,
            String remark
    ) {
        public static ItemRow from(ExpenseItem it) {
            return new ItemRow(
                    it.getId(),
                    it.getSequenceNo(),
                    it.getExpenseType(),
                    it.getItemDate(),
                    it.getItemDesc(),
                    it.getAmount(),
                    it.getTaxAmount(),
                    it.getInvoiceNo(),
                    it.getReceiptType(),
                    it.getRemark()
            );
        }
    }

    // ==================== 同步状态 & 结果 ====================

    public record DingtalkStatus(
            boolean enabled,              // 后端是否配置了钉钉启用开关
            boolean configured,           // AppKey + AppSecret + CorpId 是否全部非空
            boolean connected,            // 连通性测试：真实调用 /gettoken 成功才为 true
            String connectError,          // 连通性失败原因（connected=false 时非空；成功时为 null）
            String appKeyMasked,          // 掩码展示，绝不返回真实 Secret
            String lastSyncStatus,        // 最近一次运行 RUNNING / SUCCESS / PARTIAL / FAILED
            LocalDateTime lastSyncAt,     // 最近一次运行时间
            Stats stats,                  // 数据概览
            // ====== 诊断字段：定位 processCode 路由 / access_token 生效 / 版本 ======
            String processCodeMasked,     // 报销模板 processCode 掩码（前 10 … 后 6，可核实 PROC- 前缀）
            boolean useV2Api,             // 是否进入 v2 分支（api.dingtalk.com + header）
            String accessTokenStatus,     // OK / EMPTY / FAIL
            String accessTokenMasked,     // access_token 前 3 后 2，仅证明非空，绝不展示完整
            String apiVersionHint         // 写死代码版本号，便于确认后端是不是加载了最新 class（如 "dingtalk-v2.2"）
    ) {
        public record Stats(
                long totalClaims,              // 本地报销单总数
                BigDecimal thisMonthApproved,  // 本月核定金额
                long totalRuns,                // 累计同步次数
                long failedRuns                // 失败同步次数
        ) {
        }
    }

    public record SyncRunRow(
            Long id,
            String syncType,
            String status,
            String triggeredBy,
            String triggeredByName,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMs,
            int fetchedCount,
            int createdCount,
            int updatedCount,
            int unchangedCount,
            int failedCount,
            int mappingMissed,
            int deptMappingsCreated,
            int deptMappingsUpgraded,
            String errorSummary,
            LocalDate rangeDateFrom,
            LocalDate rangeDateTo
    ) {
    }

    public record SyncRunDetail(
            SyncRunRow summary,
            String errorDetails   // JSON 数组，可空
    ) {
    }

    /** 手动触发同步返回。 */
    public record SyncTriggered(
            long runId,
            String message
    ) {
    }
}
