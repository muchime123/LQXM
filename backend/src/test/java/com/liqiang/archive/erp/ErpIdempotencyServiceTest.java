package com.liqiang.archive.erp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErpIdempotencyServiceTest {
    JdbcTemplate jdbc;
    PlatformTransactionManager tx;
    SimpleTransactionStatus status;
    ErpIdempotencyService service;
    final Map<String, Object> payload = Map.of("id", 7);

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        tx = mock(PlatformTransactionManager.class);
        status = new SimpleTransactionStatus();
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        service = new ErpIdempotencyService(jdbc, tx);
    }

    @Test void invalidTokensNeverExecuteOrOpenTransaction() {
        for (String key : new String[]{null, "", "  ", " x", "x\n", "x".repeat(101)}) {
            var e = assertThrows(ErpBusinessException.class, () -> service.execute(key,"post",payload,()->fail("must not run")));
            assertEquals(ErpErrorCode.INVALID_ARGUMENT,e.errorCode());
        }
        assertThrows(ErpBusinessException.class, () -> service.execute("k"," ",payload,()->fail("must not run")));
        verifyNoInteractions(jdbc,tx);
    }

    void stored(String action, String hash) {
        when(jdbc.queryForList(anyString(), eq("k"))).thenReturn(List.of(Map.of(
                "action", action, "payload_hash", hash, "result_ref", "doc-7")));
    }

    @Test void sameActionAndPayloadReplayWithoutWork() {
        stored("post",service.hash(payload));
        assertEquals("doc-7", service.execute("k","post",payload,()->fail("must not rerun")));
        verifyNoInteractions(tx);
    }

    @Test void samePayloadDifferentActionIsConflict() {
        stored("approve",service.hash(payload));
        var e=assertThrows(ErpBusinessException.class,()->service.execute("k","post",payload,()->fail("must not run")));
        assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,e.errorCode());
    }

    @Test void changedPayloadIsConflict() {
        stored("post",service.hash(Map.of("id",8)));
        assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                assertThrows(ErpBusinessException.class,()->service.execute("k","post",payload,()->fail())).errorCode());
    }

    @Test void workRunsInsideTransactionAndCommitsWithKey() {
        var begun=new AtomicBoolean();
        when(tx.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->{begun.set(true);return status;});
        assertEquals("doc-7",service.execute("k","post",payload,()->{assertTrue(begun.get());return "doc-7";}));
        var order=inOrder(jdbc,tx);
        order.verify(jdbc).queryForList(anyString(),eq("k"));
        order.verify(tx).getTransaction(any(TransactionDefinition.class));
        order.verify(jdbc).update(anyString(),eq("k"),eq("post"),eq(service.hash(payload)),eq("doc-7"));
        order.verify(tx).commit(status);
    }

    @Test void businessFailureRollsBackWithoutKey() {
        assertThrows(IllegalStateException.class,()->service.execute("k","post",payload,()->{throw new IllegalStateException("injected");}));
        verify(tx).rollback(status);
        verify(tx,never()).commit(any());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void concurrentDuplicateRollsBackLosingWork() {
        when(jdbc.update(anyString(),eq("k"),eq("post"),anyString(),eq("doc-7")))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                assertThrows(ErpBusinessException.class,()->service.execute("k","post",payload,()->"doc-7")).errorCode());
        verify(tx).rollback(status);
        assertTrue(status.isRollbackOnly());
    }
}
