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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结案 / 停止 / 跟进保存的<b>统一割面审计</b>行为契约（P1-b）。
 *
 * <p>【为什么必须钉死】这三个动作过去只有 {@code production_tracking_entries} 一条时间线，
 * 没有走 {@code audit.log} —— 而同模块的 {@code ProductionTrackingStageService} 每个写动作都写了。
 * 后果是：工单被谁结案、什么时候结的，<b>只能从时间线的 content 文本里猜</b>，
 * 按人 / 按时间查询与统计都做不到。
 *
 * <p>本轮修复之后，将来（P2）一旦允许在结案时改动节点（标记适用性、确认完成、写日期来源），
 * 「谁点的」这件事就只能靠这条割面审计回答。所以这里钉死四件事：
 * <ol>
 *   <li>三个写动作都会调 {@code audit.log}，且 entityType / entityId / modelName 口径一致；</li>
 *   <li>审计内容带上<b>结案说明原文</b>，不是空泛的动作名；</li>
 *   <li>是否登记交付要写进审计 —— 「没记录入库就结案」是需要被追问的情形，不能只在代码里校验一次就丢掉；</li>
 *   <li>幂等重放不重复记审计（业务体不执行 ⇒ 不记），由 IdempotencyService 保证。</li>
 * </ol>
 */
class ProductionTrackingClosureAuditTest {

    private static final long ORDER_ID = 7L;
    private static final String ORDER_NO = "SCGD20260006084";

    private JdbcTemplate jdbc;
    private AuditLogService audit;
    private ProductionTrackingService service;
    private Map<String, Object> orderRow;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditLogService.class);
        var actors = mock(ActorResolver.class);
        var idem = mock(IdempotencyService.class);
        var users = mock(CurrentUserService.class);
        when(users.scopeOf("MANUFACTURING")).thenReturn("ALL");
        when(actors.current()).thenReturn(new Actor(7L, "跟单员甲"));
        // 幂等模板在这里只透传：业务体必须真的跑起来，审计才可能发生。
        when(idem.execute(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<String>) inv.getArgument(4)).get());

        orderRow = new HashMap<>();
        orderRow.put("id", ORDER_ID);
        orderRow.put("orderNo", ORDER_NO);
        orderRow.put("stoppedAt", null);
        orderRow.put("deliveredDate", null);
        // 顺序不能颠倒：Mockito 里「后注册的桩」优先于「先注册的桩」，
        // 所以通用兜底必须先写、更具体的两条后写，否则 defaultAssignee 的那次查询会被兜底命中，
        // 把 HashMap 塞进 String 的位置，在泛型擦除处炸 ClassCastException。
        // 另外这里必须用 doReturn(...).when(...) 的写法，绕开变长参数的泛型推断。
        doReturn(List.of(orderRow)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        doReturn(List.of("封口机车间")).when(jdbc).query(contains("SELECT production_line"), any(RowMapper.class),
                eq(ORDER_ID));
        doReturn(List.<Map<String, Object>>of()).when(jdbc).query(contains("FROM employees"), any(RowMapper.class),
                anyString());
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(stateRow(null, null));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);

        service = new ProductionTrackingService(jdbc, actors, idem, new ObjectMapper(), users, audit);
    }

    @Test
    void closureWritesUnifiedAuditLogWithOperatorReason() {
        service.changeClosure(ORDER_ID, new ProductionTrackingService.ClosureRequest(0L, true, "客户已收货并完成验收"), "k1");

        var log = capturedLog();
        assertEquals("TRACKING_STATE", log.entityType, "entityType 必须与本模块其它审计同一套大写命名");
        assertEquals(ORDER_ID, log.entityId);
        assertEquals(ORDER_NO, log.modelName, "按单号才能追到人看的单据");
        assertEquals("结案", log.action);
        assertTrue(log.changes.contains("客户已收货并完成验收"),
                "审计内容必须带结案说明原文，否则事后查不到为什么结案：" + log.changes);
    }

    @Test
    void closureAuditRecordsWhetherDeliveryWasRegistered() {
        assertMissingDeliveryFlag(null, "（未登记交付）", 1);
        assertMissingDeliveryFlag("2026-10-20", "（已登记交付）", 2);
    }

    private void assertMissingDeliveryFlag(String deliveredDate, String expected, int expectedCalls) {
        orderRow.put("deliveredDate", deliveredDate);
        service.changeClosure(ORDER_ID, new ProductionTrackingService.ClosureRequest(0L, true, "客户已收货并完成验收"),
                "key-" + expected);
        String changes = capturedLogTimes(expectedCalls).changes;
        assertTrue(changes.contains(expected),
                "「是否登记交付」必须写进审计 —— 没有入库记录就结案是需要追问的情形：" + changes);
    }

    @Test
    void revokeClosureWritesItsOwnAuditEntry() {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(stateRow(null, "2026-10-20"));

        service.changeClosure(ORDER_ID, new ProductionTrackingService.ClosureRequest(0L, false, "误结案，现恢复跟进"), "k2");

        var log = capturedLog();
        assertEquals("撤销结案", log.action);
        assertTrue(log.changes.contains("误结案，现恢复跟进"), "撤销原因必须留痕：" + log.changes);
    }

    @Test
    void stopDispositionWritesUnifiedAuditLog() {
        service.changeDisposition(ORDER_ID, new ProductionTrackingService.DispositionRequest(0L, true, "客户取消订单"), "k3");
        var stopped = capturedLog();
        assertEquals("TRACKING_STATE", stopped.entityType);
        assertEquals("停止跟单", stopped.action);
        assertTrue(stopped.changes.contains("客户取消订单"), "停止原因必须留痕：" + stopped.changes);
    }

    @Test
    void resumeDispositionWritesUnifiedAuditLog() {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(stateRow("2026-10-01", null));

        service.changeDisposition(ORDER_ID, new ProductionTrackingService.DispositionRequest(0L, false, "客户恢复生产"), "k4");

        var resumed = capturedLog();
        assertEquals("恢复跟单", resumed.action);
        assertTrue(resumed.changes.contains("客户恢复生产"));
    }

    @Test
    void followUpThatChangesNodesWritesAuditLog() {
        service.add(ORDER_ID, new ProductionTrackingService.EntryRequest(0L, "跟进：木箱已完成", null, new ArrayList<>()), "k5");

        var log = capturedLog();
        assertEquals("TRACKING_STATE", log.entityType);
        assertEquals("保存跟进", log.action);
        assertTrue(log.changes.contains("木箱已完成"),
                "节点/状态修改也必须进割面审计，否则「谁改了这个节点」只有时间线文本可猜：" + log.changes);
    }

    /** {@code production_tracking_state} 的行：version 必须有值（对象校验要读它）。 */
    private static Map<String, Object> stateRow(String stoppedAt, String closedAt) {
        var row = new HashMap<String, Object>();
        row.put("version", 0L);
        row.put("stopped_at", stoppedAt);
        row.put("closed_at", closedAt);
        return row;
    }

    private record Logged(String entityType, Long entityId, String modelName, String action, String changes) {}

    private Logged capturedLog() { return capturedLogTimes(1); }

    /** 同一个用例里连做两次动作时，取<b>最后一次</b>审计内容（getValue 返回末次）。 */
    private Logged capturedLogTimes(int expectedCalls) {
        var entityType = ArgumentCaptor.forClass(String.class);
        var entityId = ArgumentCaptor.forClass(Long.class);
        var modelName = ArgumentCaptor.forClass(String.class);
        var action = ArgumentCaptor.forClass(String.class);
        var changes = ArgumentCaptor.forClass(String.class);
        verify(audit, times(expectedCalls)).log(entityType.capture(), entityId.capture(), modelName.capture(),
                action.capture(), changes.capture());
        return new Logged(entityType.getValue(), entityId.getValue(), modelName.getValue(), action.getValue(),
                changes.getValue());
    }
}
