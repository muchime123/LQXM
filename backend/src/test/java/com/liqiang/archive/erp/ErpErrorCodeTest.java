package com.liqiang.archive.erp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERP 错误码契约测试（M0）。
 *
 * <p>守护两件事：① 每个业务码都带可用的 HTTP 映射与中文默认文案（否则前端只能看到 null 消息）；
 * ② 映射与 {@code docs/ERP技术方案映射-20260917.md §5.2} 一致——改表就要改枚举，反之亦然。
 */
class ErpErrorCodeTest {

    @Test
    void everyCodeCarriesHttpStatusAndMessage() {
        for (ErpErrorCode code : ErpErrorCode.values()) {
            assertNotNull(code.httpStatus(), code.name() + " 缺 HTTP 映射");
            assertNotNull(code.defaultMessage(), code.name() + " 缺默认文案");
            assertFalse(code.defaultMessage().isBlank(), code.name() + " 默认文案为空白");
            assertTrue(code.httpStatusCode() >= 400 && code.httpStatusCode() < 600,
                    code.name() + " 的 HTTP 状态码不在错误区间：" + code.httpStatusCode());
        }
    }

    @Test
    void conflictCodesMapTo409() {
        assertEquals(HttpStatus.CONFLICT, ErpErrorCode.INVALID_STATE.httpStatus());
        assertEquals(HttpStatus.CONFLICT, ErpErrorCode.STALE_VERSION.httpStatus());
        assertEquals(HttpStatus.CONFLICT, ErpErrorCode.CODE_CONFLICT.httpStatus());
        assertEquals(HttpStatus.CONFLICT, ErpErrorCode.EFFECTIVE_RANGE_OVERLAP.httpStatus());
        assertEquals(HttpStatus.CONFLICT, ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT.httpStatus());
    }

    @Test
    void ruleViolationCodesMapTo422() {
        HttpStatus expected = HttpStatus.UNPROCESSABLE_ENTITY;
        assertEquals(expected, ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK.httpStatus());
        assertEquals(expected, ErpErrorCode.SOURCE_QUANTITY_EXCEEDED.httpStatus());
        assertEquals(expected, ErpErrorCode.ALLOCATION_EXCEEDED.httpStatus());
        assertEquals(expected, ErpErrorCode.REFUND_EXCEEDED.httpStatus());
        assertEquals(expected, ErpErrorCode.UNIT_CONVERSION_MISSING.httpStatus());
        assertEquals(expected, ErpErrorCode.PERIOD_CLOSED.httpStatus());
        assertEquals(expected, ErpErrorCode.DOWNSTREAM_DEPENDENCY.httpStatus());
        assertEquals(expected, ErpErrorCode.REQUIRED_FOR_ACTIVATION.httpStatus());
        assertEquals(expected, ErpErrorCode.REFERENCE_INACTIVE.httpStatus());
        assertEquals(expected, ErpErrorCode.OWNERSHIP_CONFLICT.httpStatus());
    }

    @Test
    void contractChecklistCodesAllPresent() {
        // 契约 §8 / 字典 §7 必须落地的业务码清单：漏一个就等于前端无法区分该失败原因
        String[] required = {
                "INVALID_STATE", "STALE_VERSION", "CODE_CONFLICT", "EFFECTIVE_RANGE_OVERLAP",
                "REQUIRED_FOR_ACTIVATION", "REFERENCE_INACTIVE", "OWNERSHIP_CONFLICT",
                "UNIT_CONVERSION_MISSING", "SOURCE_QUANTITY_EXCEEDED", "ALLOCATION_EXCEEDED",
                "REFUND_EXCEEDED", "INSUFFICIENT_AVAILABLE_STOCK", "DOWNSTREAM_DEPENDENCY",
                "PERIOD_CLOSED", "IDEMPOTENCY_PAYLOAD_CONFLICT"
        };
        for (String name : required) {
            assertTrue(Arrays.stream(ErpErrorCode.values()).anyMatch(c -> c.name().equals(name)),
                    "缺失契约要求的业务码：" + name);
        }
    }
}
