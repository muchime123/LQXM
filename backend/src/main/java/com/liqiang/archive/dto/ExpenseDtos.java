package com.liqiang.archive.dto;

import com.liqiang.archive.entity.ExpenseClaim;
import com.liqiang.archive.entity.ExpenseItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * P6.1 费用 & 钉钉同步 DTO。
 * <p>
 * 枚举翻译（Service 层统一做中文 ↔ DB 英文）：
 * <ul>
 *   <li>approvalStatus: APPROVED 已通过 / REJECTED 已拒绝 / PENDING 审批中 / CANCELED 已撤销 / TERMINATED 终止</li>
 *   <li>expenseCategory: TRAVEL 差旅 / OFFICE 办公 / ENTERTAIN 业务招待 / TRANSPORT 交通 / MEAL 餐饮 / OTHER 其他 / CORP_PAY 对公付款</li>
 * </ul>
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
            int size,
            boolean unallocatedOnly // true=仅看无任何 CONFIRMED 归集的单（对公付款待核对）
    ) {
    }

    // ==================== 顶部指标卡 ====================

    public record ExpenseStats(
            long totalValidClaims,      // 累计有效报销单数（排除拒绝/撤销）
            BigDecimal thisMonthAmount, // 本月核定报销总额
            long awaitingMappingCount,  // 待员工身份映射数量（P6.1 同步未匹配员工的）
            long pendingAllocationCount, // P6.2 待归集数量
            BigDecimal pendingAllocationAmount // P6.2 待归集核定金额合计
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
            LocalDateTime updatedAt,
            // ===== P6.2 归集进度聚合（列表页批量一次 JOIN 计算；非列表展示接口可传 null）=====
            BigDecimal confirmedAllocationSum,   // 已生效分配合计金额
            Long confirmedAllocationCount,       // 已生效分配条数
            BigDecimal allocationPct             // 归集进度%（0~100，2 位小数；approved=0 时传 null）
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
                    c.getUpdatedAt(),
                    null, null, null
            );
        }
        /** 列表批量聚合后回填：clone 一份 record，把 3 个归集字段补上。 */
        public ClaimRow withAllocation(BigDecimal confirmedSum, Long confirmedCount, BigDecimal pct) {
            return new ClaimRow(
                    id, claimNo, title, approvalStatus, expenseCategory,
                    claimDate, approvalFinishedDate, employeeId, submitterName, submitterDepartment,
                    totalAmount, approvedAmount, remark, createdAt, updatedAt,
                    confirmedSum, confirmedCount, pct
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

    // ==================== P6.2 费用归集（当前仅销售出库订单费用链路） ====================

    /** 目标类型常量（UI 下拉 & 后端校验共用）。 */
    public static final String[] ALLOCATION_TARGET_TYPES = {
            "DEPARTMENT", "COMPANY", "CORP_PAYMENT", "SALES_OUTBOUND"
    };
    public static final String ALLOC_STATUS_CONFIRMED = "CONFIRMED";
    public static final String ALLOC_STATUS_REVERSED  = "REVERSED";
    public static final String ALLOC_STATUS_DRAFT     = "DRAFT";
    public static final String ALLOC_STATUS_PENDING   = "PENDING";
    public static final String ALLOC_STATUS_REJECTED  = "REJECTED";

    /** 分配行（详情页展示 / 分页列表行）。 */
    public record AllocationRow(
            Long id,
            Long expenseClaimId,
            Long expenseItemId,
            String targetType,        // SALES_OUTBOUND / DEPARTMENT / COMPANY / CORP_PAYMENT
            Long targetId,
            String targetNoSnapshot,
            String targetNameSnapshot,
            BigDecimal allocationRatioPct,
            BigDecimal allocatedAmount,
            String allocationStatus,  // DRAFT / PENDING / CONFIRMED / REJECTED / REVERSED
            // ===== P2 自动归集溯源（历史数据展示用；P2 引擎已下线不再产生新行）=====
            String allocationSource,  // MANUAL / AUTO_RULE / ADJUSTMENT
            Long allocationRuleId,    // 命中规则 ID（AUTO_RULE 时非空）
            Long allocationRunId,     // 运行记录 ID（AUTO_RULE 时非空）
            String hitRuleName,       // 命中的规则名快照（便于前端展示，AUTO_RULE 时非空）
            // ===== V205 订单费用归单溯源（SALES_OUTBOUND） =====
            String matchMethod,       // MANUAL / EXACT_OUTBOUND_NO / RECOMMENDED
            Integer confidence,       // 推荐置信度 0~100
            String confirmedBy,       // 确认人
            LocalDateTime confirmedAt, // 确认时间
            String remark,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /** 分配合计（claim 概览：已分配 / 未分配 / 核定金额）。 */
    public record AllocationSummary(
            BigDecimal approvedAmount,
            BigDecimal confirmedSum,        // 已确认分配合计
            BigDecimal remainingToAllocate, // 剩余可分配 = approved - confirmed（≤0 时显示"已分完"）
            long confirmedAllocationCount,
            BigDecimal reversedSum,         // 撤销历史合计（展示用；不计入剩余）
            long reversedAllocationCount
    ) {
    }

    /** 反向查询：某目标对象的费用归集列表（P7.1 业务详情页 Tab）。 */
    public record AllocationForTarget(
            List<AllocationRow> items,
            long total,
            BigDecimal totalAllocatedAmount
    ) {
    }

    /**
     * 目标搜索候选：前端点击"选择目标"时按关键字分页查询的下拉项。
     * 当前主流程按 SALES_OUTBOUND 销售出库搜索；
     *   DEPARTMENT / COMPANY / CORP_PAYMENT 暂无独立候选查询。
     */
    public record TargetCandidate(
            String targetType,
            Long targetId,
            String targetNo,
            String targetName,
            String extraInfo  // 业务单会带客户名、生命周期；公司带主体名；展示辅助
    ) {
    }
}
