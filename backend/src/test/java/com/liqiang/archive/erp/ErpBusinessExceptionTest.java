package com.liqiang.archive.erp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERP 业务异常契约测试（M0）。
 *
 * <p>守护返回体三要素：业务码（前端可判定）、字段定位（可指出改哪里）、
 * 可修正数据（能直接生成提示文案，如「可用 1，请求 4」）。
 */
class ErpBusinessExceptionTest {

    @Test
    void fallsBackToDefaultMessage() {
        ErpBusinessException ex = new ErpBusinessException(ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK);
        assertEquals(ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK.defaultMessage(), ex.getMessage());
        assertEquals(ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK, ex.errorCode());
        assertNull(ex.field());
        assertTrue(ex.detail().isEmpty());
    }

    @Test
    void blankMessageAlsoFallsBack() {
        ErpBusinessException ex = new ErpBusinessException(ErpErrorCode.PERIOD_CLOSED, "   ", null, null);
        assertEquals(ErpErrorCode.PERIOD_CLOSED.defaultMessage(), ex.getMessage());
        assertTrue(ex.detail().isEmpty(), "detail 传 null 应为空 Map 而非 NPE");
    }

    @Test
    void keepsCustomMessageAndField() {
        ErpBusinessException ex = new ErpBusinessException(
                ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK, "可用库存不足：当前可用 1，请求 4", "lines[0].quantity");
        assertEquals("可用库存不足：当前可用 1，请求 4", ex.getMessage());
        assertEquals("lines[0].quantity", ex.field());
    }

    @Test
    void chainedEnrichmentDoesNotMutateOriginal() {
        ErpBusinessException base = new ErpBusinessException(ErpErrorCode.ALLOCATION_EXCEEDED, "核销超额");
        ErpBusinessException enriched = base.atField("allocations[2].amount").withDetail("open", 100).withDetail("requested", 120);

        assertNull(base.field(), "链式补充不得修改原异常对象");
        assertTrue(base.detail().isEmpty());

        assertEquals("allocations[2].amount", enriched.field());
        assertEquals(100, enriched.detail().get("open"));
        assertEquals(120, enriched.detail().get("requested"));
        assertEquals(base.errorCode(), enriched.errorCode());
        assertEquals(base.getMessage(), enriched.getMessage());
    }

    @Test
    void detailIsImmutableCopy() {
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("available", 1);
        ErpBusinessException ex = new ErpBusinessException(ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK, "缺货", null, source);
        source.put("available", 99);
        assertEquals(1, ex.detail().get("available"), "detail 应做防御性拷贝");
        assertThrows(UnsupportedOperationException.class, () -> ex.detail().put("x", 1));
    }

    @Test
    void httpStatusComesFromErrorCode() {
        assertEquals(422, new ErpBusinessException(ErpErrorCode.INSUFFICIENT_AVAILABLE_STOCK).errorCode().httpStatusCode());
        assertEquals(409, new ErpBusinessException(ErpErrorCode.STALE_VERSION).errorCode().httpStatusCode());
        assertTrue(ErpErrorCode.NOT_FOUND.httpStatus().is4xxClientError());
    }
}
