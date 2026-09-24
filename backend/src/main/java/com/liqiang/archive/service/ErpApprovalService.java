package com.liqiang.archive.service;

import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用审批（决策登记 #35）。三条链——销售订单 / 生产任务单 / 采购订单——共用这一套状态机，
 * 而不是各写一遍：单据类型差异全部收敛在 {@link DocType} 注册表里。
 *
 * <p><b>流转</b>：{@code 草稿 → 待审批 → 通过（落各自的目标状态）／ 驳回（退回草稿）}。
 * 提交与审批是**两个不同权限**（提交 = 建单人/管理岗，审批 = {@code *.approve}），
 * 所以「谁能开单」与「谁能批」是分开授权的。
 *
 * <p><b>三层留痕</b>：
 * <ol>
 *   <li>{@code erp_approvals}（append-only 流水）—— 完整审批历史，驳回几次都看得到；</li>
 *   <li>单据表上冗余的 {@code approval_status/approval_*_at/by} —— 只是当前态，供列表筛选；</li>
 *   <li>{@code erp.row_change_log} —— 状态变更审计，与业务写入同事务（#14/#20）。</li>
 * </ol>
 * ⚠️ 按 #19 纪律：幂等与审计组件必须与业务写入**同一 DataSource + 同一事务管理器**，
 * 故注入 {@code archiveIdempotencyService} / {@code archiveAuditWriter}。
 *
 * <p>⚠️ <b>审批人不做行级可见性过滤</b>：单据的 {@code requireVisible}（按建单人 / 部门）是
 * 给「看自己那摊」的人用的；老板审别人的单必然不在自己名下。若把可见性加在审批上，
 * 审批入口会对审批人永久 403。审批的准入只由 {@code *.approve} 权限决定。
 *
 * <p><b>本类不写 {@code audit_logs}</b>（#29：ERP 域审计单通道）。
 */
@Service
public class ErpApprovalService {

    /** 单据「待审批」状态。三张表共用同一个字面量，避免各单各起一个名字。 */
    public static final String PENDING_STATUS = "PENDING_APPROVAL";

    public static final String APPROVAL_NONE = "NONE";
    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_APPROVED = "APPROVED";
    public static final String APPROVAL_REJECTED = "REJECTED";

    /**
     * 单据类型注册表 —— 单据之间的**全部**差异都在这里，新链只需加一行枚举。
     *
     * @param table         物理表名（限定在 {@code product_archive}）
     * @param noColumn      单据编号列（写进流水快照）
     * @param label         中文名（错误文案用；不用 "单" 结尾，调用方自己接）
     * @param permission    审批权限码
     * @param draftStatus   草稿状态（提交的来源、驳回的归处）
     * @param approvedStatus 通过后落到的状态
     * @param approveExtraSet 通过时附加的 SET 片段（恰含一个 `?` = 审批人），无则 null
     */
    public enum DocType {
        SALES_ORDER("erp_sales_orders", "order_no", "销售订单", "sales_order.approve",
                "DRAFT", "CONFIRMED", "confirmed_at=NOW(),confirmed_by_employee_id=?"),
        MFG_ORDER("manufacturing_orders", "order_no", "生产任务单", "manufacturing.approve",
                "DRAFT", "RELEASED", null),
        PURCHASE_ORDER("erp_purchase_orders", "po_no", "采购订单", "purchase_order.approve",
                "DRAFT", "CONFIRMED", "confirmed_at=NOW(),confirmed_by_employee_id=?");

        private final String table;
        private final String noColumn;
        private final String label;
        private final String permission;
        private final String draftStatus;
        private final String approvedStatus;
        private final String approveExtraSet;

        DocType(String table, String noColumn, String label, String permission,
                String draftStatus, String approvedStatus, String approveExtraSet) {
            this.table = table;
            this.noColumn = noColumn;
            this.label = label;
            this.permission = permission;
            this.draftStatus = draftStatus;
            this.approvedStatus = approvedStatus;
            this.approveExtraSet = approveExtraSet;
        }

        public String table() { return table; }
        public String noColumn() { return noColumn; }
        public String label() { return label; }
        public String permission() { return permission; }
        public String draftStatus() { return draftStatus; }
        public String approvedStatus() { return approvedStatus; }
        public String approveExtraSet() { return approveExtraSet; }

        /** 幂等动作名：同一单据类型的提交/通过/驳回各占一个动作，互不串键。 */
        public String action(String verb) { return name().toLowerCase() + "." + verb; }

        /** 提交 / 通过 / 驳回 的审计摘要（用业务语言，不暴露状态码）。 */
        public String summary(String action) {
            return switch (action) {
                case "SUBMIT" -> "提交" + label + "审批";
                case "APPROVE" -> "审批通过" + label;
                case "REJECT" -> "驳回" + label;
                default -> label + "审批动作";
            };
        }

        public static DocType of(String code) {
            if (code == null) throw new IllegalArgumentException("缺少单据类型");
            for (DocType t : values()) if (t.name().equalsIgnoreCase(code.trim())) return t;
            throw new IllegalArgumentException("不支持的单据类型：" + code);
        }
    }

    private final JdbcTemplate jdbc;
    private final ErpActorResolver actors;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter log;

    public ErpApprovalService(JdbcTemplate jdbc, ErpActorResolver actors,
                              @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
                              @Qualifier("archiveAuditWriter") ErpChangeLogWriter log) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.idem = idem;
        this.log = log;
    }

    // ==================== 提交 / 通过 / 驳回 ====================

    public void submit(DocType type, long docId, String key) {
        submit(type, docId, key, null);
    }

    /**
     * 提交审批。
     *
     * @param precondition 业务前置校验（在**同一事务内**、状态机之前执行）；
     *                     单据特有约束（如「采购订单没有明细不能提交」）由调用方传进来，
     *                     这样「必须先有明细」与状态流转共享同一个事务与幂等键。
     */
    public void submit(DocType type, long docId, String key, Runnable precondition) {
        ErpActor actor = actors.current();
        idem.execute(actor, key, type.action("submit"), Map.of("docType", type.name(), "docId", docId),
                () -> {
                    if (precondition != null) precondition.run();
                    transition(type, docId, "SUBMIT", actor, null, key);
                    return "ok";
                });
    }

    public void approve(DocType type, long docId, String comment, String key) {
        ErpActor actor = actors.current();
        idem.execute(actor, key, type.action("approve"), Map.of("docType", type.name(), "docId", docId),
                () -> {
                    transition(type, docId, "APPROVE", actor, comment, key);
                    return "ok";
                });
    }

    public void reject(DocType type, long docId, String comment, String key) {
        ErpActor actor = actors.current();
        idem.execute(actor, key, type.action("reject"), Map.of("docType", type.name(), "docId", docId),
                () -> {
                    transition(type, docId, "REJECT", actor, comment, key);
                    return "ok";
                });
    }

    /** 状态机本体：加锁读现状 → 校验 → 改状态 → 落流水 → 写审计（全在同一事务内）。 */
    private void transition(DocType type, long docId, String action, ErpActor actor, String comment, String key) {
        Map<String, Object> head = lock(type, docId);
        String status = str(head.get("status"));

        String from;
        String to;
        String approvalStatus;
        switch (action) {
            case "SUBMIT" -> {
                if (!type.draftStatus().equals(status))
                    throw new IllegalStateException(type.label() + "当前状态为「" + status + "」，只有草稿可以提交审批");
                from = status;
                to = PENDING_STATUS;
                approvalStatus = APPROVAL_PENDING;
            }
            case "APPROVE", "REJECT" -> {
                if (!PENDING_STATUS.equals(status))
                    throw new IllegalStateException(type.label() + "不在待审批状态（当前「" + status + "」），无法审批");
                from = status;
                boolean pass = "APPROVE".equals(action);
                to = pass ? type.approvedStatus() : type.draftStatus();
                approvalStatus = pass ? APPROVAL_APPROVED : APPROVAL_REJECTED;
                if (!pass) requireRejectReason(comment);
            }
            default -> throw new IllegalArgumentException("不支持的审批动作：" + action);
        }

        // ⚠️ SET 子句按动作分开写：提交只该动「提交人/提交时间」，
        // 若在提交时也写 approval_decided_*，待审批的单上就会挂一个**并不存在的审批决定**
        // （提交人被显示成审批人）。这句在契约测试里被当场抓到过。
        StringBuilder sql = new StringBuilder("UPDATE ").append(type.table())
                .append(" SET status=?,approval_status=?");
        List<Object> args = new ArrayList<>();
        args.add(to);
        args.add(approvalStatus);
        if ("SUBMIT".equals(action)) {
            // 提交：写提交人/时间，并**清空上一轮的审批结论**（驳回后重提不能还挂着旧审批人）。
            sql.append(",approval_submitted_at=NOW(),approval_submitted_by_employee_id=?")
                    .append(",approval_decided_at=NULL,approval_decided_by_employee_id=NULL");
            args.add(actor.employeeId());
        } else {
            sql.append(",approval_decided_at=NOW(),approval_decided_by_employee_id=?");
            args.add(actor.employeeId());
            if ("APPROVE".equals(action) && type.approveExtraSet() != null) {
                sql.append(",").append(type.approveExtraSet());
                args.add(actor.employeeId());
            }
        }
        sql.append(" WHERE id=?");
        args.add(docId);
        jdbc.update(sql.toString(), args.toArray());

        record(type, docId, str(head.get("docNo")), action, from, to, actor, comment, key);
        log.write(ErpChangeLogWriter.of(actor, "product_archive." + type.table(), docId, "UPDATE",
                ErpChangeLogWriter.changes("status", from + "->" + to, "approvalStatus", approvalStatus,
                        "approvalComment", comment),
                null, type.summary(action), key, null));
    }

    private void requireRejectReason(String comment) {
        if (comment == null || comment.isBlank())
            throw new IllegalArgumentException("驳回必须填写原因");
    }

    private Map<String, Object> lock(DocType type, long docId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status," + type.noColumn() + " docNo FROM " + type.table() + " WHERE id=? FOR UPDATE", docId);
        if (rows.isEmpty()) throw new IllegalArgumentException(type.label() + "不存在");
        return rows.getFirst();
    }

    /** 写审批流水（append-only）。 */
    private void record(DocType type, long docId, String docNo, String action, String from, String to,
                        ErpActor actor, String comment, String key) {
        jdbc.update("INSERT INTO erp_approvals(doc_type,doc_id,doc_no,action,from_status,to_status,"
                        + "actor_employee_id,actor_name,comment,request_key) VALUES(?,?,?,?,?,?,?,?,?,?)",
                type.name(), docId, docNo == null ? "" : docNo, action, from, to,
                actor.employeeId(), actor.name(), clean(comment), key);
    }

    // ==================== 读：审批历史 / 待我审批 ====================

    /** 某张单据的完整审批历史（含驳回）。 */
    public List<Map<String, Object>> history(DocType type, long docId) {
        return jdbc.queryForList("SELECT id,doc_type docType,doc_id docId,doc_no docNo,action,"
                + "from_status fromStatus,to_status toStatus,actor_employee_id actorEmployeeId,"
                + "actor_name actorName,comment,created_at createdAt"
                + " FROM erp_approvals WHERE doc_type=? AND doc_id=? ORDER BY id", type.name(), docId);
    }

    /** 全类型待审批清单（审批人的待办）。 */
    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (DocType type : DocType.values()) {
            all.addAll(jdbc.queryForList("SELECT '" + type.name() + "' docType,'" + type.label() + "' docLabel,"
                    + "id docId," + type.noColumn() + " docNo,status,approval_submitted_at submittedAt,"
                    + "approval_submitted_by_employee_id submittedByEmployeeId,"
                    + "(SELECT e.name FROM employees e WHERE e.id=approval_submitted_by_employee_id) submittedByName"
                    + " FROM " + type.table() + " WHERE status='" + PENDING_STATUS + "'"));
        }
        all.sort((a, b) -> String.valueOf(b.get("submittedAt")).compareTo(String.valueOf(a.get("submittedAt"))));
        return all;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : (t.length() > 500 ? t.substring(0, 500) : t);
    }
}
