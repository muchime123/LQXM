package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 审计写入器的纯单测（不开 Spring、不连库）：默认 {@code mvn test} 即可覆盖必填契约。 */
class ErpChangeLogWriterTest {

    JdbcTemplate jdbc;
    ErpChangeLogWriter writer;
    final ErpActor actor = new ErpActor(23L, "李强");

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        writer = new ErpChangeLogWriter(jdbc, new ObjectMapper());
    }

    @Test void missingOperatorIsRejectedAndNothingIsWritten() {
        var e = assertThrows(ErpBusinessException.class, () -> writer.write(new ErpChangeLogWriter.Entry(
                "m0_probe", 1L, "UPDATE", Map.of("note", "x"), null, "改口径", null, null, "k", "t")));
        assertEquals(ErpErrorCode.INVALID_ARGUMENT, e.errorCode());
        verifyNoInteractions(jdbc);
    }

    @Test void placeholderOperatorIsRejected() {
        assertEquals(ErpErrorCode.INVALID_ARGUMENT, assertThrows(ErpBusinessException.class,
                () -> writer.write(new ErpChangeLogWriter.Entry(
                        "m0_probe", 1L, "UPDATE", Map.of("note", "x"), null, "改口径", 0L, "系统", "k", "t")))
                .errorCode());
    }

    @Test void malformedTargetIsRejected() {
        assertEquals(ErpErrorCode.INVALID_ARGUMENT, assertThrows(ErpBusinessException.class,
                () -> writer.write(ErpChangeLogWriter.of(actor, " m0_probe", 1L, "UPDATE",
                        Map.of("note", "x"), null, null, "k", "t"))).errorCode());
        assertEquals(ErpErrorCode.INVALID_ARGUMENT, assertThrows(ErpBusinessException.class,
                () -> writer.write(ErpChangeLogWriter.of(actor, "m0_probe", 0L, "UPDATE",
                        Map.of("note", "x"), null, null, "k", "t"))).errorCode());
        assertEquals(ErpErrorCode.INVALID_ARGUMENT, assertThrows(ErpBusinessException.class,
                () -> writer.write(ErpChangeLogWriter.of(actor, "m0_probe", 1L, "INSERT_OR_UPDATE_EXTENDED",
                        Map.of("note", "x"), null, null, "k", "t"))).errorCode());
        verifyNoInteractions(jdbc);
    }

    /** 已确认的设计：序列化失败不得吞掉业务结果，降级为可检索的标记行。 */
    @Test void serializationFailureDegradesToQueryableMarker() {
        writer.write(ErpChangeLogWriter.of(actor, "m0_probe", 1L, "UPDATE",
                Map.of("note", new Object() {
                    @SuppressWarnings("unused")
                    public String getBroken() { throw new IllegalStateException("boom"); }
                }), null, null, "k", "t"));
        verify(jdbc).update(anyString(), eq("m0_probe"), eq(1L), eq("UPDATE"),
                contains(ErpChangeLogWriter.SERIALIZATION_ERROR_KEY), isNull(), isNull(),
                eq(23L), eq("李强"), eq("k"), eq("t"));
    }

    @Test void operatorIsCarriedFromActorHelper() {
        writer.write(ErpChangeLogWriter.of(actor, "m0_probe", 5L, "INSERT",
                Map.of("note", Map.of("to", "x")), "R1", "初建", "k", "t"));
        verify(jdbc).update(anyString(), eq("m0_probe"), eq(5L), eq("INSERT"),
                contains("\"note\""), eq("R1"), eq("初建"), eq(23L), eq("李强"), eq("k"), eq("t"));
    }
}
