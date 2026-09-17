package com.liqiang.archive.service;

/**
 * P6.1 钉钉集成未启用或未配置凭证时抛出。
 * 控制器层捕获后返回 HTTP 503 Service Unavailable + 明确中文提示。
 */
public class DingtalkNotEnabledException extends RuntimeException {

    private final String reason;

    public DingtalkNotEnabledException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
