package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次管线骨架单测（阶段 3）：进度步进收敛、失败打标截断、哈希幂等查询、
 * 暂存行落库与预览就绪的参数契约——与重构前 A/B 两域各自实现完全等价的收口行为。
 */
class BatchImportPipelineServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BatchImportPipelineService svc = new BatchImportPipelineService(jdbc);

    private Object[] lastArgs(String expectedSql) {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq(expectedSql), captor.capture());
        return captor.getValue();
    }

    @Test
    void progressClampsOverHundred() {
        svc.updateProgress(5L, "PARSING", 150, "STAGING");
        Object[] args = lastArgs("UPDATE h3_import_batches SET status=?, progress_pct=?, progress_stage=? WHERE id=?");
        assertEquals("PARSING", args[0]);
        assertEquals(100, args[1]);
        assertEquals("STAGING", args[2]);
        assertEquals(5L, args[3]);
    }

    @Test
    void progressClampsBelowZero() {
        svc.updateProgress(5L, "PARSING", -3, "STAGING");
        Object[] args = lastArgs("UPDATE h3_import_batches SET status=?, progress_pct=?, progress_stage=? WHERE id=?");
        assertEquals(0, args[1]);
    }

    @Test
    void batchProgressReturnsNullWhenBatchAbsent() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(42L))).thenReturn(Collections.emptyList());
        assertNull(svc.batchProgress(42L));
    }

    @Test
    void findPreviewReadyHitAndMiss() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("PRODUCT_INFO"), eq("hashA")))
                .thenReturn(Collections.singletonList(88L));
        assertEquals(88L, svc.findPreviewReadyBatchId("PRODUCT_INFO", "hashA"));

        when(jdbc.query(anyString(), any(RowMapper.class), eq("OUTBOUND"), eq("hashX")))
                .thenReturn(Collections.emptyList());
        assertNull(svc.findPreviewReadyBatchId("OUTBOUND", "hashX"));
    }

    @Test
    void markFailedCapsErrorMessage() {
        String longMsg = "A".repeat(2000);
        svc.markFailed(7L, "PARSING", longMsg, null);
        Object[] args = lastArgs("""
                UPDATE h3_import_batches SET status='FAILED', progress_stage=?, error_message=?, error_detail=?
                 WHERE id=?
                """);
        assertEquals("PARSING", args[0]);
        assertEquals(1000, String.valueOf(args[1]).length());
        assertEquals(2000, String.valueOf(args[2]).length());
        assertEquals(7L, args[3]);
    }

    @Test
    void markFailedWithExceptionDoesNotFail() {
        // 异常堆栈转 detail，不二次抛出；error_message 仍取摘要前 1000
        svc.markFailed(7L, "STAGING", "暂存落库失败：boom", new IllegalStateException("boom"));
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), captor.capture());
        assertEquals("STAGING", captor.getValue()[0]);
        assertEquals(7L, captor.getValue()[3]);
    }

    @Test
    void markPreviewReadyPassesThroughContract() {
        svc.markPreviewReady(9L, "{\"rows\":[]}", 3, "ver123");
        Object[] args = lastArgs("""
                UPDATE h3_import_batches
                   SET status='PREVIEW_READY', progress_stage='PREVIEW', progress_pct=100,
                       field_version=?, preview_json=?, total_count=?
                 WHERE id=?
                """);
        assertEquals("ver123", args[0]);
        assertEquals("{\"rows\":[]}", args[1]);
        assertEquals(3, args[2]);
        assertEquals(9L, args[3]);
    }

    @Test
    void insertStagingRowPassesThroughContract() {
        svc.insertStagingRow(9L, 3, "K", "EXT", "HASH", "{\"main\":1}", "[]");
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertEquals(7, args.length);
        assertEquals(9L, args[0]);
        assertEquals(3, args[1]);
        assertEquals("K", args[2]);
        assertEquals("EXT", args[3]);
        assertEquals("HASH", args[4]);
        assertEquals("{\"main\":1}", args[5]);
        assertEquals("[]", args[6]);
    }

    @Test
    void createBatchIssuesInsertIntoBatchesTable() {
        // GeneratedKeyHolder 无真实数据库键会抛异常，属预期：只断言登记 SQL 已通过 update 发出
        try {
            svc.createBatch("a.xlsx", 100L, "h1", "OUTBOUND", null);
        } catch (RuntimeException expected) {
            // 空 KeyHolder 取键抛异常即证明已走到取键步骤，登记调用已发生
        }
        verify(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }
}
