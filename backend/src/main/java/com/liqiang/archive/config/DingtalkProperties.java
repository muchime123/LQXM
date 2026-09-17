package com.liqiang.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P6.1 钉钉集成配置。
 * <p>
 * 安全原则：AppSecret 等凭证只放后端（.env / application.yml），绝不通过任何 API 返给前端。
 * 配置位置：application.yml 的 app.dingtalk 子段；默认 enabled=false，避免未配置的环境启动时报 API 连通性错误。
 * 生产建议：敏感值（AppSecret / CorpId）放在 .env 文件通过 ${DINGTALK_APP_SECRET} 环境变量注入，不要写回本文件。
 */
@Component
@ConfigurationProperties(prefix = "app.dingtalk")
public class DingtalkProperties {

    /** 钉钉集成是否启用。默认 false — 未启用时同步端点返回 503 + 明确中文提示。 */
    private boolean enabled = false;

    /** 钉钉旧版 AppKey（企业内部应用）。空则视为未配置。 */
    private String appKey = "";

    /** 钉钉旧版 AppSecret，仅后端可见。 */
    private String appSecret = "";

    /** 企业 CorpId（若使用新版套件模式可能需要，P6.1 先保留）。 */
    private String corpId = "";

    /** AgentId（若使用工作通知推送则需要）。 */
    private Long agentId;

    /** 日常报销审批模板 ProcessCode（不填时按"所有审批单"过滤）。 */
    private String reimbursementProcessCode = "";

    /** 钉钉网关基址。默认官方生产网关，可通过配置覆盖方便测试。 */
    private String baseUrl = "https://oapi.dingtalk.com";

    /** 拉取审批实例的默认天数范围（从今天向前回溯）。默认 7（一周）——财务处理报销不会拖太久，且能控制钉钉 API 调用频次；手动触发时可由前端按次指定。 */
    private int syncDaysBack = 7;

    /** 单次同步处理的最大审批单条数（防 runaway）。默认 500。 */
    private int syncMaxItems = 500;

    /** 调用钉钉 API 的 connect/read 超时（毫秒）。默认 10s。 */
    private int httpTimeoutMs = 10_000;

    // ===== getter / setter =====

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }

    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

    public String getCorpId() { return corpId; }
    public void setCorpId(String corpId) { this.corpId = corpId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getReimbursementProcessCode() { return reimbursementProcessCode; }
    public void setReimbursementProcessCode(String reimbursementProcessCode) { this.reimbursementProcessCode = reimbursementProcessCode; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getSyncDaysBack() { return syncDaysBack; }
    public void setSyncDaysBack(int syncDaysBack) { this.syncDaysBack = syncDaysBack; }

    public int getSyncMaxItems() { return syncMaxItems; }
    public void setSyncMaxItems(int syncMaxItems) { this.syncMaxItems = syncMaxItems; }

    public int getHttpTimeoutMs() { return httpTimeoutMs; }
    public void setHttpTimeoutMs(int httpTimeoutMs) { this.httpTimeoutMs = httpTimeoutMs; }

    /** AppKey + AppSecret + 任意一项企业身份标识（CorpId / AgentId 二者任一，或者两者都空但 KeySecret 成对）是否齐全。 */
    public boolean isCredentialsConfigured() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    /** AppKey 掩码 — 只留前 3 位和后 2 位，其余 *。用于前端状态页显示，绝不泄露完整值。 */
    public String getMaskedAppKey() {
        if (appKey == null || appKey.isBlank()) return "";
        if (appKey.length() <= 5) return "*".repeat(appKey.length());
        return appKey.substring(0, 3) + "*".repeat(Math.max(0, appKey.length() - 5)) + appKey.substring(appKey.length() - 2);
    }
}
