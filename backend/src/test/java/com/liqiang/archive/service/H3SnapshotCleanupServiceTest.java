package com.liqiang.archive.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快照治理判据单测（2026-10-20）。
 *
 * <p>治理是「删用户历史数据」，判据必须逐条可证伪。四条不可退让的约束：
 * <ol>
 *   <li><b>每单最新一条永不删</b> —— {@code loadPreviousSnapshots} 靠它做新旧字段对照；
 *       实测有单据（0e4b5337…）只存在于 2026-10-12 批 253，只按「保留最近 N 批」会把它删光。</li>
 *   <li><b>最近 N 批整批不动</b> —— 批次告警明细要读整批 raw_data。</li>
 *   <li><b>只碰 PURCHASE_IN / PRODUCTION</b> —— OUTBOUND 是不同时段切片，不是重复。</li>
 *   <li><b>在途批次不参与</b> —— PREVIEW_READY 的快照删了，确认就再也做不了。</li>
 * </ol>
 *
 * <p>⚠️ 四个 JdbcTemplate / Mockito 坑（本测试逐条踩过，改测试前先看
 * {@code javap -cp <spring-jdbc.jar> org.springframework.jdbc.core.JdbcTemplate}）：
 * <ul>
 *   <li><b>query 走 varargs 重载，参数被摊平</b>：service 写 {@code query(sql, mapper, args.toArray())}，
 *       编译器<b>不会</b>选中 {@code query(String, Object[], RowMapper)}，而是选中
 *       {@code query(String, RowMapper, Object...)} 并把数组<b>再摊平</b>。
 *       用 {@code mockingDetails} 实测调用记录是 {@code query argc=4 types=[String, lambda, Long, String]}。
 *       ⇒ 桩必须是 {@code (sql, any(RowMapper.class), any(Object[].class))}，
 *       写成 {@code (sql, any(Object[].class), any(RowMapper.class))} 永远匹配不上。</li>
 *   <li><b>update 单参重载</b>：DELETE 的 id 拼进 SQL（无占位符），走 {@code update(String)} 单参。
 *       桩成两参会永不命中，Mockito 返回默认 0，表现为「静默返回 0 而不报错」。</li>
 *   <li><b>{@code update(sql, any())} 编译歧义</b>：{@code any()} 同时匹配
 *       {@code PreparedStatementSetter} 与 {@code Object...}，编译器直接报
 *       「对 update 的引用不明确」。必须写 {@code any(Object[].class)}。</li>
 *   <li><b>SQL 前缀要看实际拼接</b>：{@code deleteByTarget} 里是
 *       {@code "SELECT r.id " + targetSql}，而 targetSql 以 {@code " FROM ..."} 开头 ⇒
 *       实际 SQL 是 {@code "SELECT r.id  FROM ..."}（<b>两个空格</b>）。
 *       {@code contains("SELECT r.id FROM")} 匹配不上，用 {@code contains("SELECT r.id ")}。</li>
 * </ul>
 */
class H3SnapshotCleanupServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final H3SnapshotCleanupService svc = new H3SnapshotCleanupService(jdbc);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(svc, "enabled", true);
        ReflectionTestUtils.setField(svc, "keepBatches", 3);
    }

    /**
     * 造一个「批次类型 = dataType」的 service。
     *
     * @param candidateIds 待删候选 id；空列表表示无待删（返回 0）
     */
    private H3SnapshotCleanupService svcWithType(String dataType, List<Long> candidateIds) {
        JdbcTemplate fresh = mock(JdbcTemplate.class);
        when(fresh.query(contains("SELECT data_type FROM h3_import_batches"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(dataType)));
        when(fresh.query(contains("SELECT r.id "), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ArrayList<>(candidateIds));
        // DELETE 走单参重载
        when(fresh.update(contains("DELETE FROM h3_import_rows WHERE id IN")))
                .thenAnswer(inv -> candidateIds.size());
        H3SnapshotCleanupService s = new H3SnapshotCleanupService(fresh);
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "keepBatches", 3);
        return s;
    }

    private H3SnapshotCleanupService svcWithType(String dataType) {
        return svcWithType(dataType, List.of());
    }

    /** 取出 service 里注入的 mock，便于对「按类型新建的 service」做 verify。 */
    private JdbcTemplate mockOf(H3SnapshotCleanupService s) {
        return (JdbcTemplate) ReflectionTestUtils.getField(s, "jdbc");
    }

    // ---------------------------------------------------------------- 开关

    @Test
    void disabledDoesNothing() {
        ReflectionTestUtils.setField(svc, "enabled", false);
        assertEquals(0, svc.pruneAfterConfirm(300L));
        assertEquals(0, svc.pruneAll(false).deletedRows());
        verify(jdbc, never()).update(anyString());
        verify(jdbc, never()).execute(anyString());
    }

    // ------------------------------------------------- 范围：只碰两类

    @Test
    void outboundBatchIsSkipped() {
        JdbcTemplate fresh = mock(JdbcTemplate.class);
        when(fresh.query(contains("SELECT data_type FROM h3_import_batches"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of("OUTBOUND")));
        H3SnapshotCleanupService s = new H3SnapshotCleanupService(fresh);
        ReflectionTestUtils.setField(s, "enabled", true);
        assertEquals(0, s.pruneAfterConfirm(240L));
        // OUTBOUND 是不同时段切片，一行都不许删
        verify(fresh, never()).update(anyString());
        verify(fresh, never()).query(contains("SELECT r.id "), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void productionAndPurchaseAreManaged() {
        for (String t : List.of("PRODUCTION", "PURCHASE_IN")) {
            H3SnapshotCleanupService s = svcWithType(t, List.of(11L, 12L));
            assertEquals(2, s.pruneAfterConfirm(300L), t + " 应参与治理");
        }
    }

    @Test
    void missingBatchIsSkipped() {
        when(jdbc.query(contains("SELECT data_type FROM h3_import_batches"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ArrayList<>());
        assertEquals(0, svc.pruneAfterConfirm(999L));
        verify(jdbc, never()).update(anyString());
    }

    // ------------------------------------- 自动清理：只删更早的重复快照

    @Test
    void deletesOnlyOlderDuplicates() {
        H3SnapshotCleanupService s = svcWithType("PURCHASE_IN", List.of(11L, 12L));
        assertEquals(2, s.pruneAfterConfirm(300L));
    }

    @Test
    void sqlKeepsLatestPerExternalId() {
        H3SnapshotCleanupService s = svcWithType("PRODUCTION");
        s.pruneAfterConfirm(300L);
        // 判据必须在 SQL 层表达为 r.id < m.latest_id（只删更早的），而不是「删掉这批全部」
        verify(mockOf(s)).query(contains("r.id < m.latest_id"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void sqlRestrictedToTerminalBatches() {
        H3SnapshotCleanupService s = svcWithType("PRODUCTION");
        s.pruneAfterConfirm(300L);
        // 在途批次（PREVIEW_READY/PARSING/UPLOADED/CONFIRMING）绝不能出现在删除候选里
        verify(mockOf(s)).query(contains("b.status IN ('COMPLETED','PARTIAL_FAILED')"),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void deleteFailureIsSwallowed() {
        JdbcTemplate fresh = mock(JdbcTemplate.class);
        when(fresh.query(contains("SELECT data_type FROM h3_import_batches"),
                any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of("PRODUCTION")));
        when(fresh.query(contains("SELECT r.id "), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));
        H3SnapshotCleanupService s = new H3SnapshotCleanupService(fresh);
        ReflectionTestUtils.setField(s, "enabled", true);
        // 清理失败不能把异常抛给导入流程
        assertEquals(0, s.pruneAfterConfirm(300L));
    }

    // ------------------------------------------- 全库治理：两条保护并存

    @Test
    void pruneAllBuildsBothKeepTables() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(10);
        svc.pruneAll(true);
        // 保护名单一：每单最新一条（必须不带 join，否则退化 20 倍）
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> argCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(sqlCap.capture(), argCap.capture());
        // 上面这条 verify 会抓到「第一个两参 update」即 _h3sc_keep 的插入语句
        // （_h3sc_latest 的插入是单参 update，不会命中）
        assertEquals(true, sqlCap.getValue().contains("t.rn <= ?"), "应物化最近 N 批保护名单");
        Object captured = argCap.getValue();
        int keep = captured instanceof Object[] arr ? (int) arr[0] : (int) captured;
        assertEquals(3, keep, "keepBatches=3 应作为参数绑进 t.rn <= ?");

        verify(jdbc).update(contains("SELECT external_id, MAX(id) FROM h3_import_rows"));
        // 临时表用完必须清理（build 开头 + finally 各一次，故 atLeastOnce）
        verify(jdbc, atLeastOnce()).execute(contains("DROP TABLE IF EXISTS `_h3sc_latest`"));
        verify(jdbc, atLeastOnce()).execute(contains("DROP TABLE IF EXISTS `_h3sc_keep`"));
    }

    @Test
    void pruneAllDryRunDoesNotDelete() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(244_371)   // scanned
                .thenReturn(192_133);  // targets
        H3SnapshotCleanupService.CleanupResult r = svc.pruneAll(true);
        assertEquals(192_133, r.deletedRows());
        assertEquals(52_238, r.keptRows());
        assertEquals(244_371, r.scannedRows());
        verify(jdbc, never()).update(contains("DELETE FROM h3_import_rows WHERE id IN"));
    }

    @Test
    void pruneAllTargetSqlCoversBothProtections() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        svc.pruneAll(true);
        // 待删口径必须同时排除「每单最新一条」与「保留批次」
        verify(jdbc).queryForObject(contains("LEFT JOIN `_h3sc_keep`"), eq(Integer.class));
        verify(jdbc).queryForObject(contains("(m.latest_id IS NULL OR r.id < m.latest_id)"), eq(Integer.class));
    }

    @Test
    void pruneAllSwallowsException() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("boom"));
        H3SnapshotCleanupService.CleanupResult r = svc.pruneAll(false);
        assertEquals(0, r.deletedRows());
        // 异常也要把临时表清掉，避免污染后续会话
        verify(jdbc, atLeastOnce()).execute(contains("DROP TABLE IF EXISTS `_h3sc_latest`"));
    }

    @Test
    void pruneAllDeletesInChunks() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(10);
        when(jdbc.query(contains("SELECT r.id "), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new ArrayList<>(List.of(1L, 2L, 3L)))  // < 5000 即结束
                .thenReturn(new ArrayList<>());
        when(jdbc.update(contains("DELETE FROM h3_import_rows WHERE id IN"))).thenReturn(3);
        H3SnapshotCleanupService.CleanupResult r = svc.pruneAll(false);
        assertEquals(3, r.deletedRows());
        verify(jdbc, atLeastOnce()).update(contains("DELETE FROM h3_import_rows WHERE id IN"));
    }

    @Test
    void pruneAllNoTargetsSkipsDelete() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        H3SnapshotCleanupService.CleanupResult r = svc.pruneAll(false);
        assertEquals(0, r.deletedRows());
        verify(jdbc, never()).update(contains("DELETE FROM h3_import_rows WHERE id IN"));
    }
}
