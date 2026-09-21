package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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

    /**
     * {@code java.time} 值**不得**导致审计载荷降级为 {@code _serializationError} 摘要。
     *
     * <p>背景（2026-09-21）：ERP 写路径的 changed_fields 普遍带 {@code java.time} 值
     * （建生产任务 {@code LocalDate dueDate}、建销售出库单 {@code LocalDate outboundDate}、
     * 过账 {@code LocalDateTime postedAt}）。本类构造器已调用
     * {@code objectMapper.findAndRegisterModules()}，因此即便调用方传的是
     * {@code new ObjectMapper()}（未显式注册 JavaTimeModule），也不会走到降级分支。
     *
     * <p>这条断言守的是「**别把 findAndRegisterModules() 删掉**」——删了它，审计行还在，
     * 但「改前改后是什么」会静默丢成一条错误桩，而制造执行段真库验证时正是这样踩过一次。
     *
     * <p>注：日期在此路径下序列化为组件数组（{@code [2026,9,21]}），因为没有关掉
     * {@code WRITE_DATES_AS_TIMESTAMPS}。这与 {@link ErpPayloadHasher}（哈希，需文本形态）
     * 是不同用途，故不强行统一；断言只要求「日期值确实落进了 JSON」。
     */
    @Test void javaTimeValueDoesNotDegradeAuditContent() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outboundDate", LocalDate.of(2026, 9, 21));
        fields.put("dueDate", null); // 未填的日期字段，不得让整条审计失败
        writer.write(ErpChangeLogWriter.of(actor, "m0_probe", 7L, "INSERT",
                fields, "R1", "建单", "k", "t"));
        verify(jdbc).update(anyString(), eq("m0_probe"), eq(7L), eq("INSERT"),
                argThat((String json) -> json != null
                        && !json.contains(ErpChangeLogWriter.SERIALIZATION_ERROR_KEY)
                        && json.contains("outboundDate")
                        && json.contains("2026")),
                eq("R1"), eq("建单"), eq(23L), eq("李强"), eq("k"), eq("t"));
    }
}
