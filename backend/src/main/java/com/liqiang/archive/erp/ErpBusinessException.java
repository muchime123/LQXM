package com.liqiang.archive.erp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ERP 业务异常：携带业务错误码 + 字段定位 + 可修正数据。
 *
 * <p>返回体形态（技术方案映射 §5.1）：
 * <pre>
 * { "code": 422,                                  ← HTTP 状态码（前端现有判断不变）
 *   "message": "可用库存不足：当前可用 1，请求 4",   ← 中文修正说明
 *   "data": { "errorCode": "INSUFFICIENT_AVAILABLE_STOCK",
 *             "field": "lines[0].quantity",
 *             "detail": { "available": 1, "requested": 4 } } }
 * </pre>
 *
 * <p>注意：业务码放在 {@code data.errorCode} 而不是顶替 {@code code}，是为了保持
 * {@code ApiResponse} 外壳与前端零改造（契约 §8「业务码 + 字段定位 + 可修正数据」）。
 */
public class ErpBusinessException extends RuntimeException {

    private final ErpErrorCode errorCode;
    /** 出错字段定位，如 {@code lines[0].quantity}；无字段级定位时为 null。 */
    private final String field;
    /** 可修正数据（可用量、请求量、超额数等）；无则为空 Map。 */
    private final Map<String, Object> detail;

    public ErpBusinessException(ErpErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, Map.of());
    }

    public ErpBusinessException(ErpErrorCode errorCode, String message) {
        this(errorCode, message, null, Map.of());
    }

    public ErpBusinessException(ErpErrorCode errorCode, String message, String field) {
        this(errorCode, message, field, Map.of());
    }

    public ErpBusinessException(ErpErrorCode errorCode, String message, String field, Map<String, Object> detail) {
        super(message == null || message.isBlank() ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.field = field;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public ErpErrorCode errorCode() {
        return errorCode;
    }

    public String field() {
        return field;
    }

    public Map<String, Object> detail() {
        return detail;
    }

    /** 附加字段定位（链式，便于在服务内逐层补充上下文）。 */
    public ErpBusinessException atField(String fieldName) {
        return new ErpBusinessException(errorCode, getMessage(), fieldName, detail);
    }

    /** 附加可修正数据（链式）。 */
    public ErpBusinessException withDetail(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(detail);
        merged.put(key, value);
        return new ErpBusinessException(errorCode, getMessage(), field, merged);
    }
}
