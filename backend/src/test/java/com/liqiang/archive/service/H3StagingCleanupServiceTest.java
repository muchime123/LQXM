package com.liqiang.archive.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 暂存行清理判据单测（2026-10-20）。
 *
 * <p>清理是「删用户数据」，判据必须逐条可证伪，不能只测 happy path：
 * <ul>
 *   <li>非终态（PREVIEW_READY / PARSING …）绝不删 —— 在途批次删了就再也确认不了；</li>
 *   <li>preview_json 为空不删 —— 否则预览回退路径会读到空；</li>
 *   <li>开关关闭不删 —— 排查期需要能一键停用。</li>
 * </ul>
 */
class H3StagingCleanupServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final H3StagingCleanupService svc = new H3StagingCleanupService(jdbc);

    @BeforeEach
    void enable() {
        ReflectionTestUtils.setField(svc, "enabled", true);
    }

    /** 让批次元数据查询返回指定 status / previewJson。 */
    @SuppressWarnings("unchecked")
    private void batchIs(String status, String previewJson) {
        // 注意：不能用 List.of(new Object[]{...})——可变参数会把数组展开成多个元素，
        // 得到 List<Object> 长度 2，service 取 getFirst()[0] 会 ClassCastException。
        List<Object> rows = new ArrayList<>();
        rows.add(new Object[]{status, previewJson});
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(rows);
    }

    private void deleteReturns(int rows) {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    void terminalBatchWithPreviewIsPurged() {
        batchIs("COMPLETED", "{\"rows\":[]}");
        // 第一轮删 3 行（< 5000 分块）即结束
        deleteReturns(3);
        var result = svc.purgeAfterConfirm(285L);
        assertEquals(H3StagingCleanupService.Outcome.DELETED, result.outcome());
        assertEquals(3, result.deletedRows());
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void partialFailedIsAlsoTerminal() {
        batchIs("PARTIAL_FAILED", "{\"rows\":[]}");
        deleteReturns(0);
        assertEquals(H3StagingCleanupService.Outcome.DELETED, svc.purgeAfterConfirm(9L).outcome());
    }

    @Test
    void previewReadyMustNeverBePurged() {
        batchIs("PREVIEW_READY", "{\"rows\":[]}");
        var result = svc.purgeAfterConfirm(286L);
        assertEquals(H3StagingCleanupService.Outcome.SKIPPED_NOT_TERMINAL, result.outcome());
        assertEquals(0, result.deletedRows());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void confirmingAndParsingMustNeverBePurged() {
        for (String s : List.of("CONFIRMING", "PARSING", "UPLOADED", "FAILED")) {
            batchIs(s, "{\"rows\":[]}");
            assertEquals(H3StagingCleanupService.Outcome.SKIPPED_NOT_TERMINAL, svc.purgeAfterConfirm(1L).outcome(),
                    "状态 " + s + " 不应被清理");
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void terminalBatchWithoutPreviewKeepsStaging() {
        batchIs("COMPLETED", "");
        var result = svc.purgeAfterConfirm(1L);
        assertEquals(H3StagingCleanupService.Outcome.SKIPPED_NO_PREVIEW, result.outcome());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void nullPreviewJsonKeepsStaging() {
        batchIs("COMPLETED", null);
        assertEquals(H3StagingCleanupService.Outcome.SKIPPED_NO_PREVIEW, svc.purgeAfterConfirm(1L).outcome());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void missingBatchIsSkipped() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(Collections.emptyList());
        assertEquals(H3StagingCleanupService.Outcome.SKIPPED_MISSING, svc.purgeAfterConfirm(404L).outcome());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void disabledSwitchSkipsEverything() {
        ReflectionTestUtils.setField(svc, "enabled", false);
        assertEquals(H3StagingCleanupService.Outcome.SKIPPED_DISABLED, svc.purgeAfterConfirm(285L).outcome());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void databaseErrorIsSwallowed() {
        // 清理失败不得影响导入结果：抛异常也要正常返回
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("db down"));
        var result = svc.purgeAfterConfirm(285L);
        assertEquals(0, result.deletedRows());
    }
}
