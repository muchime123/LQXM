package com.liqiang.archive.service;

import com.liqiang.archive.dto.ExpenseDtos;
import com.liqiang.archive.entity.ExpenseClaim;
import com.liqiang.archive.entity.ExpenseItem;
import com.liqiang.archive.repository.ExpenseClaimRepository;
import com.liqiang.archive.repository.ExpenseItemRepository;
import com.liqiang.archive.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 日常报销查询服务（只读视图层）。
 * <p>
 * 本服务只提供：
 * <ul>
 *   <li>顶部指标卡统计</li>
 *   <li>报销单列表分页搜索</li>
 *   <li>报销单 + 明细详情</li>
 *   <li>批量编辑字段</li>
 * </ul>
 * 所有写入由钉钉同步任务 / Excel 导入负责。费用归单/分配链路已于 2026-10-07 下线。
 */
@Service
@Transactional(readOnly = true)
public class ExpenseService {

    private final ExpenseClaimRepository claimRepo;
    private final ExpenseItemRepository itemRepo;
    private final Function<String, String> translate;
    private final CurrentUserService currentUsers;
    private final AuditLogService auditLogService;

    /** 批量编辑允许修改的字段白名单（前端 camelCase 字段名）。 */
    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "expenseType", "expenseDate", "departmentName", "settlementStatus", "remark"
    );

    /** 中 ↔ 英 翻译表（DB 英文 → 前端中文）。与 H3Business 各 Service 模式一致。 */
    private static final Map<String, String> DICT = new HashMap<>();
    static {
        // approvalStatus
        DICT.put("APPROVED", "已通过");
        DICT.put("REJECTED", "已拒绝");
        DICT.put("PENDING", "审批中");
        DICT.put("CANCELED", "已撤销");
        DICT.put("TERMINATED", "终止");
        // expenseCategory
        DICT.put("TRAVEL", "差旅费");
        DICT.put("OFFICE", "办公费");
        DICT.put("ENTERTAIN", "业务招待");
        DICT.put("TRANSPORT", "交通费");
        DICT.put("MEAL", "餐饮费");
        DICT.put("OTHER", "其他");
        DICT.put("CORP_PAY", "对公付款");
    }

    public ExpenseService(ExpenseClaimRepository claimRepo,
                          ExpenseItemRepository itemRepo,
                          CurrentUserService currentUsers,
                          AuditLogService auditLogService) {
        this.claimRepo = claimRepo;
        this.itemRepo = itemRepo;
        this.currentUsers = currentUsers;
        this.auditLogService = auditLogService;
        this.translate = (v) -> {
            if (v == null || v.isBlank()) return "-";
            return DICT.getOrDefault(v, v);
        };
    }

    /** 顶部指标：累计有效单数 + 本月核定 + 待员工映射数。 */
    public ExpenseDtos.ExpenseStats getStats() {
        if (!currentUsers.can("expense.manage")) {
            long own = claimRepo.searchPage(null, currentUsers.current().employeeId(), null, null, null, null, PageRequest.of(0, 1)).getTotalElements();
            return new ExpenseDtos.ExpenseStats(own, BigDecimal.ZERO, 0);
        }
        long totalValid = claimRepo.countValidClaims();
        BigDecimal monthAmt = claimRepo.sumThisMonthApprovedAmount();
        long awaitingMapping = claimRepo.countAwaitingMapping();
        return new ExpenseDtos.ExpenseStats(
                totalValid,
                monthAmt == null ? BigDecimal.ZERO : monthAmt,
                awaitingMapping
        );
    }

    public Page<ExpenseDtos.ClaimRow> searchClaims(ExpenseDtos.SearchParams p) {
        Pageable pageable = PageRequest.of(Math.max(0, p.page() - 1), Math.max(1, Math.min(200, p.size())));
        Long visibleEmployee = currentUsers.can("expense.manage") ? p.employeeId() : currentUsers.current().employeeId();
        Page<ExpenseClaim> page = claimRepo.searchPage(
                p.keyword(), visibleEmployee, p.status(), p.category(), p.dateFrom(), p.dateTo(),
                pageable);
        List<ExpenseClaim> content = page.getContent();

        List<ExpenseDtos.ClaimRow> rows = new ArrayList<>(content.size());
        for (ExpenseClaim c : content) rows.add(ExpenseDtos.ClaimRow.from(c, translate));

        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /** 详情（含明细）。找不到返回 null，调用方自己抛 404。 */
    @Transactional(readOnly = true)
    public ExpenseDtos.ClaimDetail getDetail(Long id) {
        ExpenseClaim c = claimRepo.findById(id).orElse(null);
        if (c == null) return null;
        if (!currentUsers.can("expense.manage") && !java.util.Objects.equals(c.getEmployeeId(), currentUsers.current().employeeId())) {
            throw new SecurityException("只能查看本人的费用单");
        }
        List<ExpenseItem> items = itemRepo.findByExpenseClaimIdOrderBySequenceNoAsc(id);
        return ExpenseDtos.ClaimDetail.from(c, items, translate);
    }

    // ==================== 批量编辑 ====================

    /**
     * 通用批量更新：按字段白名单 + 类型转换，将选中报销单的指定字段统一修改。
     * 前端 camelCase 字段名映射到 ExpenseClaim 实体属性：
     *   expenseType → expenseCategory
     *   expenseDate → claimDate (LocalDate)
     *   departmentName → departmentName
     *   settlementStatus → approvalStatus
     *   remark → remark
     */
    @Transactional
    public int batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("未指定要修改的字段");
        }
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("不支持的批量修改字段: " + key);
            }
        }
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("未选择要修改的报销单");
        }
        if (!currentUsers.can("expense.manage")) {
            throw new SecurityException("无权批量修改报销单");
        }
        List<ExpenseClaim> claims = claimRepo.findAllById(ids);
        for (ExpenseClaim c : claims) {
            fields.forEach((key, value) -> {
                String v = value == null ? null : value.toString();
                switch (key) {
                    case "expenseType" -> c.setExpenseCategory(v == null || v.isBlank() ? null : v);
                    case "expenseDate" -> c.setClaimDate(v == null || v.isBlank() ? null : LocalDate.parse(v));
                    case "departmentName" -> c.setDepartmentName(v == null || v.isBlank() ? null : v);
                    case "settlementStatus" -> c.setApprovalStatus(v == null || v.isBlank() ? null : v);
                    case "remark" -> c.setRemark(v);
                    default -> { }
                }
            });
        }
        if (!claims.isEmpty()) {
            claimRepo.saveAll(claims);
        }
        int count = claims.size();
        auditLogService.log("ExpenseClaim", null, "批量",
                "EXPENSE_BATCH_UPDATE", "批量修改 " + count + " 个报销单字段：" + String.join("、", fields.keySet()));
        return count;
    }
}
