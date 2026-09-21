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
    final ErpActor actor = new ErpActor(23L, "李强");

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        tx = mock(PlatformTransactionManager.class);
        status = new SimpleTransactionStatus();
        when(tx.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        service = new ErpIdempotencyService(jdbc, tx);
    }

    @Test void invalidTokensNeverExecuteOrOpenTransaction() {
        for (String key : new String[]{null, "", "  ", " x", "x\n", "x".repeat(101)}) {
            var e = assertThrows(ErpBusinessException.class, () -> service.execute(actor,key,"post",payload,()->fail("must not run")));
            assertEquals(ErpErrorCode.INVALID_ARGUMENT,e.errorCode());
        }
        assertThrows(ErpBusinessException.class, () -> service.execute(actor,"k"," ",payload,()->fail("must not run")));
        verifyNoInteractions(jdbc,tx);
    }

    /** M0 收口：写动作不得匿名提交，缺操作人在读库/开事务之前就被拒绝。 */
    @Test void missingActorIsRejectedBeforeAnyWork() {
        var e = assertThrows(ErpBusinessException.class,
                () -> service.execute(null,"k","post",payload,()->fail("must not run")));
        assertEquals(ErpErrorCode.INVALID_ARGUMENT, e.errorCode());
        verifyNoInteractions(jdbc,tx);
    }

    /** M0 收口：操作人对象自身拒绝占位值，避免用 0 / 空格姓名绕过必填。 */
    @Test void actorRejectsPlaceholderIdentity() {
        assertEquals(ErpErrorCode.INVALID_ARGUMENT,
                assertThrows(ErpBusinessException.class, () -> new ErpActor(0L, "李强")).errorCode());
        assertEquals(ErpErrorCode.INVALID_ARGUMENT,
                assertThrows(ErpBusinessException.class, () -> new ErpActor(23L, "  ")).errorCode());
        assertEquals("李强", new ErpActor(23L, "  李强  ").name(), "姓名应去首尾空白后作为快照");
    }

    void stored(String action, String hash) {
        stored(action, hash, 23L);
    }

    void stored(String action, String hash, Long createdBy) {
        var row = new java.util.HashMap<String, Object>();
        row.put("action", action);
        row.put("payload_hash", hash);
        row.put("result_ref", "doc-7");
        row.put("created_by", createdBy);
        when(jdbc.queryForList(anyString(), eq("k"))).thenReturn(List.of(row));
    }

    @Test void sameActionAndPayloadReplayWithoutWork() {
        stored("post",service.hash(payload));
        assertEquals("doc-7", service.execute(actor,"k","post",payload,()->fail("must not rerun")));
        verifyNoInteractions(tx);
    }

    /** M0 收口：换人重放不拒绝（授权是调用方职责），但仍须回放原结果、不执行业务体。 */
    @Test void replayByAnotherActorStillReturnsFirstResultWithoutWork() {
        stored("post", service.hash(payload), 23L);
        assertEquals("doc-7", service.execute(new ErpActor(99L, "王五"),"k","post",payload,()->fail("must not rerun")));
        verifyNoInteractions(tx);
    }

    /** M0 收口：首次发起人未知（历史行 created_by 为空）时重放不得抛异常。 */
    @Test void replayWithUnknownFirstActorIsTolerated() {
        stored("post", service.hash(payload), null);
        assertEquals("doc-7", service.execute(actor,"k","post",payload,()->fail("must not rerun")));
    }

    @Test void samePayloadDifferentActionIsConflict() {
        stored("approve",service.hash(payload));
        var e=assertThrows(ErpBusinessException.class,()->service.execute(actor,"k","post",payload,()->fail("must not run")));
        assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,e.errorCode());
    }

    @Test void changedPayloadIsConflict() {
        stored("post",service.hash(Map.of("id",8)));
        assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                assertThrows(ErpBusinessException.class,()->service.execute(actor,"k","post",payload,()->fail())).errorCode());
    }

    @Test void workRunsInsideTransactionAndCommitsWithKey() {
        var begun=new AtomicBoolean();
        when(tx.getTransaction(any(TransactionDefinition.class))).thenAnswer(call->{begun.set(true);return status;});
        assertEquals("doc-7",service.execute(actor,"k","post",payload,()->{assertTrue(begun.get());return "doc-7";}));
        var order=inOrder(jdbc,tx);
        order.verify(jdbc).queryForList(anyString(),eq("k"));
        order.verify(tx).getTransaction(any(TransactionDefinition.class));
        // M0 收口：created_by 必须随请求键落库，否则「谁发起的」无法追溯
        order.verify(jdbc).update(anyString(),eq("k"),eq("post"),eq(service.hash(payload)),eq("doc-7"),eq(23L));
        order.verify(tx).commit(status);
    }

    @Test void businessFailureRollsBackWithoutKey() {
        assertThrows(IllegalStateException.class,()->service.execute(actor,"k","post",payload,()->{throw new IllegalStateException("injected");}));
        verify(tx).rollback(status);
        verify(tx,never()).commit(any());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void concurrentDuplicateRollsBackLosingWork() {
        when(jdbc.update(anyString(),eq("k"),eq("post"),anyString(),eq("doc-7"),eq(23L)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                assertThrows(ErpBusinessException.class,()->service.execute(actor,"k","post",payload,()->"doc-7")).errorCode());
        verify(tx).rollback(status);
        assertTrue(status.isRollbackOnly());
    }
}
