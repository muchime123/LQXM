package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.infra.Actor;
import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.infra.IdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结案时的节点人工确认（P2-1 写通道）行为契约。
 *
 * <p>【这套测试守的是什么】「没登记」背后是两种完全不同的事实 —— 节点压根不适用，或适用但没人填。
 * 过去这两者在库里长得一模一样（都是空），任何统计都只能含糊处理。
 * P2 让跟单员在结案那一刻四选一明确下来，本测试就是防止这套分辨再度退化：
 * <ol>
 *   <li>四种结论各自<b>写了正确的列</b>：不适用写 {@code applicability}、无日期完成写 {@code completion_source='NO_DATE'}；</li>
 *   <li>不适用或无法确认完成需要异常权限；已完成但日期未知需要依据，仍属于完成事实；</li>
 *   <li>{@code DONE_NO_DATE} <b>不得</b>写一个推测出来的日期 —— 宁可日期为空、来源为 NO_DATE；</li>
 *   <li>普通跟进 {@code add} 仍然要求 DONE 必须带日期 —— 这条口子只在结案通道打开。</li>
 * </ol>
 */
class ProductionTrackingClosureNodeConfirmationTest {

    private static final long ORDER_ID = 7L;
    private static final String WORKSHOP = "封口机车间";
    private static final String ABNORMAL = "manufacturing.followup.abnormal";

    private JdbcTemplate jdbc;
    private AuditLogService audit;
    private CurrentUserService users;
    private ProductionTrackingService service;
    private String stageCode;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditLogService.class);
        users = mock(CurrentUserService.class);
        var actors = mock(ActorResolver.class);
        var idem = mock(IdempotencyService.class);
        when(users.scopeOf("MANUFACTURING")).thenReturn("ALL");
        when(actors.current()).thenReturn(new Actor(7L, "跟单员甲"));
        when(idem.execute(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<String>) inv.getArgument(4)).get());

        stageCode = ProductionTrackingPolicy.stageCodes(WORKSHOP).get(0);
        var orderRow = new HashMap<String, Object>();
        orderRow.put("id", ORDER_ID);
        orderRow.put("orderNo", "SCGD20260006084");
        orderRow.put("workshopType", WORKSHOP);
        orderRow.put("stoppedAt", null);
        orderRow.put("deliveredDate", null);
        // 顺序不能颠倒：Mockito 里后注册的桩优先，defaultAssignee 的查询必须比通用兜底更晚声明，
        // 否则会把 HashMap 塞进 String 的位置，在泛型擦除处炸 ClassCastException。
        doReturn(List.of(orderRow)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        doReturn(List.of(WORKSHOP)).when(jdbc).query(contains("SELECT production_line"), any(RowMapper.class),
                eq(ORDER_ID));
        doReturn(List.<Map<String, Object>>of()).when(jdbc).query(contains("FROM employees"), any(RowMapper.class),
                anyString());
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(stateRow(null, null));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        service = new ProductionTrackingService(jdbc, actors, idem, new ObjectMapper(), users, audit);
    }

    @Test
    void notApplicableWritesApplicabilityColumn() {
        grantAbnormal(true);
        service.changeClosure(ORDER_ID, closure("经与车间核实", List.of(confirm("NOT_APPLICABLE", null, "本机不带这道工序"))), "k1");

        assertTrue(anyUpdateContains("applicability='NOT_APPLICABLE'"),
                "「本单不适用」必须落到 applicability 列，否则统计口径无法剔除它：" + allUpdates());
        String[] logged = capturedAudit();
        assertTrue("异常结案".equals(logged[3]), "走了节点确认的结案要在审计里标成异常结案：" + logged[3]);
        assertTrue(logged[4].contains("本单不适用"), "审计/时间线要能看出当时点了什么：" + logged[4]);
    }

    @Test
    void doneWithoutDateRecordsNoDateSourceAndNeverInventsADate() {
        // NO_DATE 虽不改变统计分母，但它跳过了「DONE 必须有日期」这条硬校验，因此同样要权限。
        grantAbnormal(true);
        service.changeClosure(ORDER_ID, closure("车间确认已做完，纸质单找不到",
                List.of(confirm("DONE_NO_DATE", null, "现场已做完，纸质单丢失"))), "k2");

        assertTrue(anyUpdateContains("completion_source='NO_DATE'"), "必须标记来源为 NO_DATE：" + allUpdates());
        assertTrue(anyUpdateContains("completed_date=NULL"),
                "查不到日期就保持为空 —— 写一个推断日期会污染 P50/P75 与交付口径：" + allUpdates());
        assertTrue("结案".equals(capturedAudit()[3]),"已确认完成但日期未知不应自动归为异常结案");
    }

    @Test
    void doneWithDateWritesManualSource() {
        service.changeClosure(ORDER_ID, closure(null,
                List.of(confirm("DONE_WITH_DATE", LocalDate.of(2026, 10, 20), "现场补录"))), "k3");

        assertTrue(anyUpdateContains("completion_source='MANUAL'"), "有日期的补全要标明来源是人工：" + allUpdates());
        assertTrue(capturedAudit()[3].contains("结案"), "补日期不算异常结案，动作名保持为结案：" + capturedAudit()[3]);
    }

    @Test
    void stillApplicableButUnregisteredIsConfirmedAsApplicable() {
        grantAbnormal(true);
        service.changeClosure(ORDER_ID, closure("确有此工序，只是漏登记",
                List.of(confirm("APPLICABLE", null, "确实有，现场漏填"))), "k4");

        assertTrue(anyUpdateContains("applicability='APPLICABLE'"),
                "「适用但未登记」必须与「不适用」分开存 —— 混在一起节点完成率就没法算：" + allUpdates());
    }

    @Test
    void abnormalResultWithoutPermissionIsForbidden() {
        grantAbnormal(false);
        var request = closure("核实过了", List.of(confirm("NOT_APPLICABLE", null, "本机不带这道工序")));

        var ex = assertThrows(ResponseStatusException.class, () -> service.changeClosure(ORDER_ID, request, "k5"));
        assertTrue(ex.getMessage().contains("异常结案权限"), "缺少权限时要说清楚缺哪个权限：" + ex.getMessage());
        assertFalse(anyUpdateContains("applicability="), "没权限就不能写任何节点列：" + allUpdates());
    }

    @Test
    void abnormalClosureRequiresOverallReason() {
        grantAbnormal(true);
        var request = closure(null, List.of(confirm("NOT_APPLICABLE", null, "本机不带这道工序")));

        assertThrows(IllegalArgumentException.class, () -> service.changeClosure(ORDER_ID, request, "k6"));
    }

    @Test
    void abnormalConfirmationRequiresItsOwnEvidence() {
        grantAbnormal(true);
        var request = closure("核实过了", List.of(confirm("NOT_APPLICABLE", null, "无")));

        assertThrows(IllegalArgumentException.class, () -> service.changeClosure(ORDER_ID, request, "k7"));
    }

    @Test
    void doneWithDateStillNeedsTheDate() {
        // 这条不需要 abnormal 权限：日期是人填的，本身有据可查。
        var request = closure(null, List.of(confirm("DONE_WITH_DATE", null, "现场补录")));

        assertThrows(IllegalArgumentException.class, () -> service.changeClosure(ORDER_ID, request, "k8"));
    }

    @Test
    void unknownResultIsRejected() {
        grantAbnormal(true);
        var request = closure("核实过了", List.of(confirm("MAYBE", null, "不确定")));

        assertThrows(IllegalArgumentException.class, () -> service.changeClosure(ORDER_ID, request, "k9"));
    }

    @Test
    void plainClosureTouchesNoMilestoneColumns() {
        service.changeClosure(ORDER_ID, new ProductionTrackingService.ClosureRequest(0L, true, "客户已收货并完成验收"), "k10");

        assertFalse(anyUpdateContains("applicability="), "不带节点确认的普通结案不应改任何节点列：" + allUpdates());
    }

    @Test
    void followUpDrawerCannotWriteNoDateWithoutTheSamePermission() {
        // A 方案的核心：跟进抽屉与结案弹窗是同一个事实的两个入口，权限必须是同一把钥匙，
        // 否则「需要有权限才能确认」这件事在抽屉里就能绕开，整套收口形同虚设。
        grantAbnormal(false);
        var request = new ProductionTrackingService.EntryRequest(0L, "", null, List.of(noDateMilestone()));

        var ex = assertThrows(ResponseStatusException.class, () -> service.add(ORDER_ID, request, "k11"));
        assertTrue(ex.getMessage().contains("异常结案权限"), "抽屉入口也要说清楚缺哪个权限：" + ex.getMessage());
    }

    @Test
    void followUpDrawerWithPermissionPersistsNoDate() {
        grantAbnormal(true);
        var request = new ProductionTrackingService.EntryRequest(0L, "", null, List.of(noDateMilestone()));

        service.add(ORDER_ID, request, "k12");

        assertTrue(anyUpdateContains("completion_source"),
                "有权限时抽屉入口要能把 NO_DATE 真正写进节点行，而不只是页面上显示：" + allUpdates());
    }

    // ====== helpers ======

    /** 「已完成但日期查不到」的一条 Milestone 提交（跟进抽屉入口）。 */
    private ProductionTrackingService.Milestone noDateMilestone() {
        return new ProductionTrackingService.Milestone(stageCode, "DONE", null, "现场已做完，纸质单丢失",
                null, null, "NO_DATE");
    }

    private void grantAbnormal(boolean granted) {
        when(users.can(ABNORMAL)).thenReturn(granted);
    }

    private ProductionTrackingService.ClosureRequest closure(String abnormalReason,
                                                            List<ProductionTrackingService.NodeConfirmation> nodes) {
        return new ProductionTrackingService.ClosureRequest(0L, true, "客户已收货并完成验收", abnormalReason, nodes);
    }

    private ProductionTrackingService.NodeConfirmation confirm(String result, LocalDate date, String note) {
        return new ProductionTrackingService.NodeConfirmation(stageCode, result, date, note);
    }

    private static Map<String, Object> stateRow(String stoppedAt, String closedAt) {
        var row = new HashMap<String, Object>();
        row.put("version", 0L);
        row.put("stopped_at", stoppedAt);
        row.put("closed_at", closedAt);
        return row;
    }

    private String[] capturedAudit() {
        var entityType = ArgumentCaptor.forClass(String.class);
        var entityId = ArgumentCaptor.forClass(Long.class);
        var modelName = ArgumentCaptor.forClass(String.class);
        var action = ArgumentCaptor.forClass(String.class);
        var changes = ArgumentCaptor.forClass(String.class);
        verify(audit).log(entityType.capture(), entityId.capture(), modelName.capture(), action.capture(),
                changes.capture());
        return new String[]{entityType.getValue(), String.valueOf(entityId.getValue()), modelName.getValue(),
                action.getValue(), changes.getValue()};
    }

    /**
     * 取所有 update 的 SQL 原文。用 {@code mockingDetails} 而不是 ArgumentCaptor + verify：
     * 权限校验失败时事务体根本没跑（一次 update 都没有），此时 verify 会直接报错，
     * 而「一次都没写」恰恰是这些用例要断言的东西。
     */
    private List<String> allUpdates() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(inv -> "update".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArgument(0) instanceof String)
                .map(inv -> (String) inv.getArgument(0))
                .toList();
    }

    private boolean anyUpdateContains(String fragment) {
        return allUpdates().stream().anyMatch(sql -> sql.contains(fragment));
    }
}
