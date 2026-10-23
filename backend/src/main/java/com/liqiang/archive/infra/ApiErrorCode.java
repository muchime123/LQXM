package com.liqiang.archive.infra;

import org.springframework.http.HttpStatus;

/**
 * 通用业务错误码。
 *
 * <p>{@link GlobalExceptionHandler} 把 HTTP 状态码当业务码用，前端无法区分
 * 「同一个 422」到底是哪类问题。本枚举给出可判定的业务语义，由 {@link ApiException}
 * 携带，经 GlobalExceptionHandler 输出为 {@code data.errorCode}
 * （HTTP 状态码保留在 {@code code}，前端现有判断零改造）。
 *
 * <p>2026-10-23：随 ERP 独立 schema 下线，从 {@code ErpErrorCode} 收敛为系统通用错误码，
 * 仅保留当前仍在使用（幂等 / 参数校验）的两个取值。
 */
public enum ApiErrorCode {

    // ---------- 400 参数不合法 ----------

    /** 参数不合法（结构正确但取值非法）。 */
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "参数不合法"),

    // ---------- 409 冲突：状态/并发/唯一性 ----------

    /** 同一请求键、不同请求内容（请求重试防护）。 */
    IDEMPOTENCY_PAYLOAD_CONFLICT(HttpStatus.CONFLICT, "同一请求键的内容与首次提交不一致");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ApiErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int httpStatusCode() {
        return httpStatus.value();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
