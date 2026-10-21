package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 时间核实「修改」的契约（10-20）。
 *
 * <p>为什么必须有这组测试：整条链路（列表徽标、详情「已记录原因」、导出「逾期原因」列、分析看板原文榜）
 * 读的都是 {@code MAX(id)} 那条记录。过去只能追加，「改原因」只能靠再录一遍，于是真库里堆出
 * 「入库延迟」+「入库延迟7天」这类同义记录，原文榜被旧写法污染。修好之后必须钉死三件事：
 * <ol>
 *   <li>走 UPDATE、不新增历史；</li>
 *   <li>快照列（source_*）一律不进 SET —— 否则改原因会把「这条记录本来就该失效」的状态一并抹掉；</li>
 *   <li>类型不匹配要拒绝，不能悄悄改到别的核实类型上。</li>
 * </ol>
 */
class ProductionTrackingTimeReviewUpdateTest {

    private JdbcTemplate jdbc;
    private ErpIdempotencyService idem;
    private ErpChangeLogWriter audit;
    private ProductionTrackingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        idem = mock(ErpIdempotencyService.class);
        audit = mock(ErpChangeLogWriter.class);
        var actors = mock(ErpActorResolver.class);
        when(actors.current()).thenReturn(new ErpActor(160L, "陈苏平"));
        // 幂等体直接执行，便于断言 SQL 本身
        when(idem.execute(any(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            Supplier<String> work = inv.getArgument(4);
            return work.get();
        });
        service = new ProductionTrackingService(jdbc, actors, idem, audit, new ObjectMapper(),
                currentUser(160L));
        // 可见性校验（order(id)）：返回一行「能定位到工单」的壳，够让 order() 通过
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(java.util.Map.of("id", 16723L))));
    }

    /** visible() 会按当前用户的车间数据范围拼条件，所以 current() 必须给一个真实可用的用户。 */
    private static CurrentUserService currentUser(long employeeId) {
        var users = mock(CurrentUserService.class);
        doReturn(new CurrentUserService.CurrentUser(employeeId, "陈苏平", "生产部",
                java.util.Set.of(), java.util.Set.of("manufacturing.followup"), "ALL",
                false, "LOCAL", 1L, null, java.util.Map.of())).when(users).current();
        return users;
    }

    private static ProductionTrackingService.TimeReviewRequest overdue(String reason, String status) {
        return new ProductionTrackingService.TimeReviewRequest("OVERDUE", status, reason, null, null, null);
    }

    @Test
    void updatingReasonRunsUpdateNeverInsert() {
        when(jdbc.query(contains("FROM production_tracking_time_reviews WHERE id=? AND production_order_id=?"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "id", 71L, "productionOrderId", 16723L, "kind", "OVERDUE",
                        "status", "OPEN", "reason", "入库原因：入库延迟"))));

        long id = service.updateTimeReview(16723L, 71L, overdue("外采时间：等输送带", "OPEN"), "k-1");

        assertEquals(71L, id, "改的是同一条记录，返回的 id 不能变");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        boolean hasInsert = sql.getAllValues().stream().anyMatch(s -> s.toUpperCase().contains("INSERT"));
        assertFalse(hasInsert, "修改不允许追加新记录：" + sql.getAllValues());
        boolean hasUpdate = sql.getAllValues().stream()
                .anyMatch(s -> s.startsWith("UPDATE production_tracking_time_reviews SET status=?"));
        assertTrue(hasUpdate, "必须走 UPDATE：" + sql.getAllValues());
    }

    @Test
    void updateNeverTouchesSourceSnapshotColumns() {
        when(jdbc.query(contains("FROM production_tracking_time_reviews WHERE id=? AND production_order_id=?"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "id", 71L, "productionOrderId", 16723L, "kind", "OVERDUE",
                        "status", "OPEN", "reason", "旧原因"))));

        service.updateTimeReview(16723L, 71L, overdue("新原因", "OPEN"), "k-2");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        String updateSql = sql.getAllValues().stream()
                .filter(s -> s.startsWith("UPDATE production_tracking_time_reviews")).findFirst().orElse("");
        assertFalse(updateSql.isBlank(), "应有一条 UPDATE");
        assertFalse(updateSql.contains("source_"), "快照列不能被改写（否则失效判定会被一并抹掉）：" + updateSql);
        assertTrue(updateSql.contains("reason=?"), updateSql);
    }

    @Test
    void kindMustMatchTheStoredReview() {
        when(jdbc.query(contains("FROM production_tracking_time_reviews WHERE id=? AND production_order_id=?"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "id", 71L, "productionOrderId", 16723L, "kind", "ACCEPTANCE",
                        "status", "OPEN", "reason", "接单原因"))));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateTimeReview(16723L, 71L, overdue("换个原因", "OPEN"), "k-3"));
        assertTrue(ex.getMessage().contains("类型不匹配"), ex.getMessage());
    }

    @Test
    void blankReasonIsRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateTimeReview(16723L, 71L, overdue("   ", "OPEN"), "k-4"));
        assertTrue(ex.getMessage().contains("请填写核实原因"), ex.getMessage());
    }

    @Test
    void negotiatedAgreedDateOnlyAppliesToCommitment() {
        when(jdbc.query(contains("FROM production_tracking_time_reviews WHERE id=? AND production_order_id=?"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "id", 71L, "productionOrderId", 16723L, "kind", "OVERDUE",
                        "status", "OPEN", "reason", "旧原因"))));

        // 传了 agreedDate 但单子不是 COMMITMENT：必须被清空，不能存进去
        service.updateTimeReview(16723L, 71L, new ProductionTrackingService.TimeReviewRequest(
                "OVERDUE", "OPEN", "新原因", null, LocalDate.of(2026, 11, 1), null), "k-5");

        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains("UPDATE production_tracking_time_reviews SET status=?"),
                params.capture());
        Object agreed = params.getValue()[3];
        assertNull(agreed, "非协商交期的核实类型不应保存协商交期");
    }
}
