package com.liqiang.archive.service;

import com.liqiang.archive.dto.ExpenseDtos;
import com.liqiang.archive.entity.ExpenseAllocation;
import com.liqiang.archive.entity.ExpenseClaim;
import com.liqiang.archive.repository.ExpenseAllocationRepository;
import com.liqiang.archive.repository.ExpenseClaimRepository;
import com.liqiang.archive.security.CurrentUserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 历史费用归集审计服务（独立于只读的 ExpenseService）。
 * <p>
 * 当前主流程只支持费用归集到销售出库（SALES_OUTBOUND）；DEPARTMENT /
 * COMPANY / CORP_PAYMENT 目标已冻结。P2 自动归集引擎与旧费用迁移（生成推荐 / 规则 CRUD /
 * 运行记录）已于 2026-09 整体下线。遗留分配记录可继续核对或撤销，但无论何种状态均不参与
 * 销售出库利润和月度提成；销售核算唯一读取氚云销售出库及其「收款详细」子表。
 * <p>
 * 核心约束（Service 层执行，DB CHECK 做粗拦截）：
 * <ol>
 *   <li>单条 allocated_amount ≥ 0。</li>
 *   <li>同 claim 下，所有 CONFIRMED 分配的 allocated_amount 合计
 *       ≤ COALESCE(claim.approved_amount, claim.total_amount, 0)。</li>
 *   <li>比例（ratio_pct）若传入，则 |ratio_pct × claim_approved / 100 - allocated_amount| ≤ 0.02；
 *       否则由 Service 基于金额回算 ratio_pct（approved_amount > 0 时）。</li>
 *   <li>撤销分配是软删除：allocation_status=REVERSED + remark 加撤销原因 + updated_at。
 *       已 CONFIRMED 的 allocation 绝不允许直接 DELETE；避免破坏归集审计链。</li>
 * </ol>
 */
@Service
public class ExpenseAllocationService {

    private static final int TARGET_SEARCH_PAGE_SIZE = 20;

    private final ExpenseAllocationRepository allocRepo;
    private final ExpenseClaimRepository claimRepo;
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUsers;

    public ExpenseAllocationService(ExpenseAllocationRepository allocRepo,
                                    ExpenseClaimRepository claimRepo,
                                    JdbcTemplate jdbc,
                                    CurrentUserService currentUsers) {
        this.allocRepo = allocRepo;
        this.claimRepo = claimRepo;
        this.jdbc = jdbc;
        this.currentUsers = currentUsers;
    }

    // ========== 读 ==========

    public ExpenseDtos.AllocationSummary getSummary(long claimId) {
        requireClaimVisible(claimId);
        ExpenseClaim c = claimRepo.findById(claimId).orElse(null);
        BigDecimal approved = approvedOf(c);

        BigDecimal confirmedSum = zeroIfNull(allocRepo.sumConfirmedByClaim(claimId));
        long confirmedCount = allocRepo
                .findByExpenseClaimIdAndAllocationStatusOrderByIdAsc(claimId, ExpenseDtos.ALLOC_STATUS_CONFIRMED)
                .size();

        List<ExpenseAllocation> reversed = allocRepo
                .findByExpenseClaimIdAndAllocationStatusOrderByIdAsc(claimId, ExpenseDtos.ALLOC_STATUS_REVERSED);
        BigDecimal reversedSum = reversed.stream()
                .map(ExpenseAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(zeroIfNull(b)));

        BigDecimal remaining = approved.subtract(confirmedSum);
        return new ExpenseDtos.AllocationSummary(
                approved,
                confirmedSum,
                remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining,
                confirmedCount,
                reversedSum,
                reversed.size()
        );
    }

    public List<ExpenseDtos.AllocationRow> listAllocations(long claimId, boolean includeReversed) {
        requireClaimVisible(claimId);
        List<ExpenseAllocation> all = allocRepo.findByExpenseClaimIdOrderByIdAsc(claimId);
        if (!includeReversed) {
            all = all.stream().filter(a -> !ExpenseDtos.ALLOC_STATUS_REVERSED.equals(a.getAllocationStatus())).toList();
        }
        List<ExpenseDtos.AllocationRow> rows = new ArrayList<>(all.size());
        for (ExpenseAllocation a : all) rows.add(toRow(a));
        return rows;
    }

    public ExpenseDtos.AllocationForTarget listByTarget(String targetType, Long targetId) {
        validateTargetType(targetType);
        List<ExpenseAllocation> list = allocRepo
                .findByTargetTypeAndTargetIdAndAllocationStatusOrderByIdDesc(targetType, targetId, ExpenseDtos.ALLOC_STATUS_CONFIRMED);
        BigDecimal sum = list.stream()
                .map(ExpenseAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(zeroIfNull(b)));
        List<ExpenseDtos.AllocationRow> rows = list.stream().map(this::toRow).toList();
        return new ExpenseDtos.AllocationForTarget(rows, rows.size(), sum);
    }

    /** 历史遗留、尚待裁决的销售出库费用；仅供审计，不影响利润或提成。 */
    public Map<String, Object> pendingOutboundAllocations(int page, int size) {
        int sz = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        int normalizedPage = Math.max(1, page);
        int offset = (normalizedPage - 1) * sz;
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*) FROM expense_allocations
            WHERE target_type='SALES_OUTBOUND' AND allocation_status IN ('PENDING','DRAFT')
            """, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
            SELECT a.id allocation_id,a.expense_claim_id,c.claim_no,c.title claim_title,c.submitter_name,
                   a.target_id outbound_id,a.target_no_snapshot outbound_no,a.target_name_snapshot customer_name,
                   a.allocated_amount,a.allocation_status,a.allocation_source,a.match_method,a.confidence,
                   a.remark,a.created_at
            FROM expense_allocations a
            JOIN expense_claims c ON c.id=a.expense_claim_id
            WHERE a.target_type='SALES_OUTBOUND' AND a.allocation_status IN ('PENDING','DRAFT')
            ORDER BY a.created_at,a.id LIMIT ? OFFSET ?
            """, this::targetRow, sz, offset);
        return Map.of("items", items, "total", total == null ? 0L : total,
                "page", normalizedPage, "size", sz);
    }

    /**
     * 当前仅销售出库提供目标候选；其他通用费用目标暂无独立候选数据源。
     */
    @SuppressWarnings("unchecked")
    public Page<ExpenseDtos.TargetCandidate> searchTargetCandidates(String targetType, String keyword, int page, int size) {
        validateTargetType(targetType);
        if ("SALES_OUTBOUND".equals(targetType)) {
            return searchSalesOutboundCandidates(keyword, page, size);
        }
        return Page.empty();
    }

    /**
     * V205 订单费用归单候选：按销售出库编号/客户名分页搜索（仅 CONFIRMED 出库）。
     * extraInfo 带业务员与出库日期，便于人工核对归属。
     */
    private Page<ExpenseDtos.TargetCandidate> searchSalesOutboundCandidates(String keyword, int page, int size) {
        int pageIdx = Math.max(0, page - 1);
        int sz = Math.max(1, Math.min(TARGET_SEARCH_PAGE_SIZE, Math.max(size, 100)));
        Pageable pr = PageRequest.of(pageIdx, sz);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : "";
        StringBuilder where = new StringBuilder(" WHERE o.outbound_status='CONFIRMED'");
        List<Object> params = new ArrayList<>();
        if (!kw.isEmpty()) {
            where.append(" AND (o.outbound_no LIKE ? OR COALESCE(o.customer_name,'') LIKE ? OR COALESCE(o.salesperson_name,'') LIKE ?)");
            String like = "%" + kw + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        List<Object> countParams = new ArrayList<>(params);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_outbounds o" + where, Long.class, countParams.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(sz);
        pageParams.add((long) pageIdx * sz);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT o.id, o.outbound_no, o.customer_name, o.salesperson_name, o.ship_date,
                       COALESCE(o.sales_amount,0) sales_amount
                FROM sales_outbounds o
                """ + where + " ORDER BY o.ship_date DESC, o.id DESC LIMIT ? OFFSET ?", this::targetRow, pageParams.toArray());
        List<ExpenseDtos.TargetCandidate> items = rows.stream().map(r -> new ExpenseDtos.TargetCandidate(
                "SALES_OUTBOUND",
                ((Number) r.get("id")).longValue(),
                String.valueOf(r.get("outboundNo")),
                r.get("customerName") == null ? "" : r.get("customerName").toString(),
                "业务员:" + (r.get("salespersonName") == null ? "—" : r.get("salespersonName"))
                        + "，出库日:" + (r.get("shipDate") == null ? "—" : r.get("shipDate"))
                        + "，销售额:¥" + r.get("salesAmount"))).toList();
        return new PageImpl<>(items, pr, total == null ? 0L : total);
    }

    private Map<String, Object> targetRow(ResultSet rs, int n) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            m.put(camelCase(md.getColumnLabel(i)), rs.getObject(i));
        }
        return m;
    }

    private String camelCase(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : value.toLowerCase().toCharArray()) {
            if (c == '_') upper = true;
            else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    // ========== 历史审计状态维护（不联动销售核算） ==========

    /** 单条撤销（软删除 -> REVERSED）。 */
    @Transactional
    public ExpenseDtos.AllocationRow reverseOne(long allocationId, String reason, String operator) {
        ExpenseAllocation a = allocRepo.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("分配记录不存在（id=" + allocationId + "）"));
        if (ExpenseDtos.ALLOC_STATUS_REVERSED.equals(a.getAllocationStatus())) {
            throw new IllegalArgumentException("该分配已经是撤销状态，无需重复操作。");
        }
        String newRemark = appendRemark(a.getRemark(),
                (StringUtils.hasText(reason) ? "撤销原因：" + reason + "；" : "撤销；")
                        + "操作人：" + (operator == null ? "(anonymous)" : operator));
        a.setAllocationStatus(ExpenseDtos.ALLOC_STATUS_REVERSED);
        a.setRemark(newRemark);
        // updatedAt 由 @PreUpdate 自动刷新；flush 必须，否则下方重算用 JdbcTemplate 直读 DB 时看不到 JPA 一级缓存里这次修改
        ExpenseAllocation saved = allocRepo.save(a);
        allocRepo.flush();
        return toRow(saved);
    }

    /**
     * 历史订单费用确认：PENDING/DRAFT → CONFIRMED（附确认人/时间）。
     * 该状态只服务历史归集审计，不影响销售出库利润与月度提成。
     */
    @Transactional
    public ExpenseDtos.AllocationRow confirmOne(long allocationId, String operator) {
        ExpenseAllocation a = allocRepo.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException("分配记录不存在（id=" + allocationId + "）"));
        if (ExpenseDtos.ALLOC_STATUS_CONFIRMED.equals(a.getAllocationStatus())) {
            throw new IllegalArgumentException("该分配已经是确认状态，无需重复操作。");
        }
        if (ExpenseDtos.ALLOC_STATUS_REVERSED.equals(a.getAllocationStatus())) {
            throw new IllegalArgumentException("已撤销的分配不能确认，请重新归单。");
        }
        if (ExpenseDtos.ALLOC_STATUS_REJECTED.equals(a.getAllocationStatus())) {
            throw new IllegalArgumentException("已驳回的分配不能确认，请重新归单。");
        }
        if (!"SALES_OUTBOUND".equals(a.getTargetType())) {
            throw new IllegalArgumentException("旧费用目标已冻结，只能确认关联销售出库的订单费用。");
        }
        ExpenseClaim claim = claimRepo.findById(a.getExpenseClaimId())
                .orElseThrow(() -> new IllegalArgumentException("报销单不存在"));
        BigDecimal confirmed = zeroIfNull(allocRepo.sumConfirmedByClaim(a.getExpenseClaimId()));
        if (confirmed.add(zeroIfNull(a.getAllocatedAmount())).compareTo(approvedOf(claim)) > 0) {
            throw new IllegalArgumentException("确认后将超过报销核定金额，请刷新推荐或调整现有分配。");
        }
        a.setAllocationStatus(ExpenseDtos.ALLOC_STATUS_CONFIRMED);
        a.setConfirmedBy(truncate(operator, 100));
        a.setConfirmedAt(java.time.LocalDateTime.now());
        ExpenseAllocation saved = allocRepo.save(a);
        allocRepo.flush();
        return toRow(saved);
    }

    // ========== 内部工具 ==========

    private void requireClaimVisible(long claimId) {
        if (currentUsers.can("expense.manage")) return;
        ExpenseClaim claim = claimRepo.findById(claimId).orElseThrow(() -> new IllegalArgumentException("报销单不存在"));
        if (!java.util.Objects.equals(claim.getEmployeeId(), currentUsers.current().employeeId())) throw new SecurityException("只能查看本人的费用归集");
    }

    private void validateTargetType(String t) {
        if (!Arrays.asList(ExpenseDtos.ALLOCATION_TARGET_TYPES).contains(t)) {
            throw new IllegalArgumentException("targetType 必须是 " + Arrays.toString(ExpenseDtos.ALLOCATION_TARGET_TYPES) + " 之一。");
        }
    }

    private BigDecimal approvedOf(ExpenseClaim c) {
        if (c == null) return BigDecimal.ZERO;
        BigDecimal v = c.getApprovedAmount();
        if (v == null) v = c.getTotalAmount();
        if (v == null) v = BigDecimal.ZERO;
        return v;
    }

    private static BigDecimal zeroIfNull(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String appendRemark(String oldRemark, String append) {
        String base = oldRemark == null ? "" : oldRemark;
        return base.isEmpty() ? append : base + " | " + append;
    }

    private ExpenseDtos.AllocationRow toRow(ExpenseAllocation a) {
        String ruleName = null;
        if (a.getAllocationRuleId() != null) {
            try {
                List<Map<String, Object>> rows = jdbc().queryForList(
                        "SELECT rule_name FROM expense_allocation_rules WHERE id = ? LIMIT 1",
                        a.getAllocationRuleId());
                if (!rows.isEmpty() && rows.get(0).get("rule_name") != null) {
                    ruleName = rows.get(0).get("rule_name").toString();
                }
            } catch (Throwable ignore) { /* 规则被删除不影响归集行展示 */ }
        }
        return new ExpenseDtos.AllocationRow(
                a.getId(),
                a.getExpenseClaimId(),
                a.getExpenseItemId(),
                a.getTargetType(),
                a.getTargetId(),
                a.getTargetNoSnapshot(),
                a.getTargetNameSnapshot(),
                a.getAllocationRatioPct(),
                a.getAllocatedAmount(),
                a.getAllocationStatus(),
                a.getAllocationSource(),
                a.getAllocationRuleId(),
                a.getAllocationRunId(),
                ruleName,
                a.getMatchMethod(),
                a.getConfidence(),
                a.getConfirmedBy(),
                a.getConfirmedAt(),
                a.getRemark(),
                a.getCreatedBy(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }

    private JdbcTemplate jdbc() { return jdbc; }
}
