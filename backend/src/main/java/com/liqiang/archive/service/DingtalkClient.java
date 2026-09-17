package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.config.DingtalkProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * P6.1 钉钉开放平台 HTTP 客户端。
 * <p>
 * 边界设计：
 * <ol>
 *   <li>凭证绝不打印日志、绝不返回上层；通过 {@link DingtalkProperties} 注入。</li>
 *   <li>未启用或未配置凭证时，抛出 {@link DingtalkNotEnabledException}（控制器层转 503）。</li>
 *   <li>所有 API 返回原始 {@code JsonNode}，业务映射在 DingtalkSyncService 层做；这样钉钉响应字段有细微差异时无需改 Client 代码。</li>
 *   <li>真实环境下使用 JDK 11+ HttpClient（避免引入额外 OkHttp 依赖；POM 已继承 Spring Boot）。</li>
 * </ol>
 * 审批流程文档参考（钉钉开放平台 2024 版）：
 * <ul>
 *   <li>获取企业 access_token：GET /gettoken?appkey=X&appsecret=Y</li>
 *   <li>获取审批实例 ID 列表：POST /topapi/processinstance/listids 参数 process_code + start_time + end_time + size + cursor</li>
 *   <li>获取单个审批实例详情：POST /topapi/processinstance/get 参数 process_instance_id</li>
 * </ul>
 */
@Component
public class DingtalkClient {

    private final DingtalkProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DingtalkClient(DingtalkProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .build();
    }

    // ===================== 基础检查 =====================

    /** 检查启用状态 & 凭证齐全，不通过则抛 DingtalkNotEnabledException。 */
    public void ensureEnabled() throws DingtalkNotEnabledException {
        if (!props.isEnabled()) {
            throw new DingtalkNotEnabledException("钉钉集成未启用。请在后端配置 app.dingtalk.enabled=true（同时配置 AppKey / AppSecret），或联系管理员。");
        }
        if (!props.isCredentialsConfigured()) {
            throw new DingtalkNotEnabledException("钉钉集成已启用但凭证未齐全。请在后端 .env 配置 DINGTALK_APP_KEY 与 DINGTALK_APP_SECRET，或联系管理员。");
        }
    }

    // ===================== 开放平台 API 封装 =====================

    /**
     * 获取企业应用 access_token。
     * 返回有效期（钉钉 7200 秒）内的原始 token 字符串；调用方自行缓存。
     * 额外保证：返回一定非空；否则抛异常（附调用 URL 掩码，便于排查环境变量是否生效）。
     */
    public String fetchAccessToken() {
        ensureEnabled();
        try {
            final String urlMasked = props.getBaseUrl() + "/gettoken?"
                    + "appkey=" + mask(props.getAppKey(), 3, 2) + "&appsecret=" + mask(props.getAppSecret(), 3, 2);
            String url = props.getBaseUrl() + "/gettoken?"
                    + "appkey=" + enc(props.getAppKey()) + "&appsecret=" + enc(props.getAppSecret());
            JsonNode node = getJson(url);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode != 0) {
                String raw = safeToString(node, 1500);
                throw new RuntimeException("钉钉 access_token 获取失败：errcode=" + errcode + "，errmsg=" + node.path("errmsg").asText("(空)") + "；请求URL=" + urlMasked + "；原始响应=" + raw);
            }
            String token = node.path("access_token").asText();
            if (token == null || token.isBlank()) {
                String raw = safeToString(node, 1500);
                throw new RuntimeException("钉钉 access_token 返回为空：errcode=0 但 access_token 字段缺失或空字符串。请求URL=" + urlMasked + "；原始响应=" + raw);
            }
            return token;
        } catch (DingtalkNotEnabledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("钉钉 access_token 调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拉取指定日期范围内的审批实例 ID 列表。
     *
     * @param accessToken 有效的 access_token
     * @param from        起始日期（含）
     * @param to          结束日期（含）
     * @param maxItems    本次最多拉取条数（由调用方按需放大）
     * @return 审批实例 ID 列表（可能空，但不会 null）
     */
    /**
     * 拉取指定日期范围内的审批实例 ID 列表。
     * <p>
     * 钉钉审批实例列表接口（v1/v2 均如此）限制单次查询时间范围不能超过 {@link #MAX_QUERY_WINDOW_DAYS} 天；
     * 当查询范围超过该限制时，此处自动按窗口分段拉取后合并去重。
     *
     * @param accessToken 有效的 access_token
     * @param from        起始日期（含）
     * @param to          结束日期（含）
     * @param maxItems    每个查询窗口内最多拉取条数（由调用方按需放大）
     * @return 审批实例 ID 列表（可能空，但不会 null）
     */
    public List<String> listProcessInstanceIds(String accessToken, LocalDate from, LocalDate to, int maxItems) {
        ensureEnabled();
        return listIdsByWindows(accessToken, from, to, maxItems, true);
    }

    /**
     * 钉钉审批实例列表接口单次查询的时间范围上限（天）。
     * <p>
     * 钉钉官方限制为 120 天，但实测按 120 天整（含端点）切窗会被拒绝（invalidEndTime），
     * 因此留出安全余量，使用 90 天窗口，任何口径（毫秒跨度 / 日期差 / 含端点天数）均远低于限制。
     */
    private static final int MAX_QUERY_WINDOW_DAYS = 90;

    /**
     * 按 120 天窗口分段拉取审批实例 ID 并合并去重；范围不足一个窗口时直接单次查询。
     */
    private List<String> listIdsByWindows(String accessToken, LocalDate from, LocalDate to, int maxItems, boolean v1) {
        if (ChronoUnit.DAYS.between(from, to) + 1 <= MAX_QUERY_WINDOW_DAYS) {
            return v1 ? listProcessInstanceIdsWindow(accessToken, from, to, maxItems)
                      : listProcessInstanceIdsV2Window(accessToken, from, to, maxItems);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (LocalDate[] w : splitRange(from, to, MAX_QUERY_WINDOW_DAYS)) {
            List<String> part = v1 ? listProcessInstanceIdsWindow(accessToken, w[0], w[1], maxItems)
                                   : listProcessInstanceIdsV2Window(accessToken, w[0], w[1], maxItems);
            merged.addAll(part);
        }
        return new ArrayList<>(merged);
    }

    private List<LocalDate[]> splitRange(LocalDate from, LocalDate to, int maxDays) {
        List<LocalDate[]> windows = new ArrayList<>();
        LocalDate cur = from;
        while (!cur.isAfter(to)) {
            LocalDate end = cur.plusDays(maxDays - 1L);
            if (end.isAfter(to)) end = to;
            windows.add(new LocalDate[]{cur, end});
            cur = end.plusDays(1);
        }
        return windows;
    }

    /**
     * 拉取单个查询窗口内的审批实例 ID 列表（窗口跨度不超过 120 天）。
     *
     * @param accessToken 有效的 access_token
     * @param from        起始日期（含）
     * @param to          结束日期（含）
     * @param maxItems    本次最多拉取条数
     * @return 审批实例 ID 列表（可能空，但不会 null）
     */
    private List<String> listProcessInstanceIdsWindow(String accessToken, LocalDate from, LocalDate to, int maxItems) {
        try {
            long startMs = from.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            long endMs = to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() - 1L;
            final int size = Math.min(100, Math.max(1, maxItems));
            final String processCodeRaw = props.getReimbursementProcessCode() == null ? "" : props.getReimbursementProcessCode().trim();

            List<String> all = new ArrayList<>();
            long cursor = 0L;
            boolean hasMore;
            int guard = 0;
            do {
                var body = objectMapper.createObjectNode();
                body.put("process_code", firstNonBlank(processCodeRaw, null));
                body.put("start_time", startMs);
                body.put("end_time", endMs);
                body.put("size", size);
                body.put("cursor", cursor);
                final String requestSnapshot = safeToString(body, 1000);

                // 诊断：access_token 是否非空 + 是否成功拼到了 URL 的 query string
                if (accessToken == null || accessToken.isBlank()) {
                    throw new RuntimeException("传入的 access_token 为 null 或空字符串（fetchAccessToken 已校验，一般不会发生；若出现请报告代码版本号）。processCode=\"" + mask(processCodeRaw, 10, 6) + "\"");
                }
                final String urlMasked = props.getBaseUrl() + "/topapi/processinstance/listids?access_token=" + mask(accessToken, 3, 2);
                String url = props.getBaseUrl() + "/topapi/processinstance/listids?access_token=" + enc(accessToken);

                JsonNode node = postJson(url, body);
                int errcode = node.path("errcode").asInt(-1);
                if (errcode != 0) {
                    String raw = safeToString(node, 2000);
                    throw new RuntimeException(
                            "钉钉审批实例列表失败：errcode=" + errcode
                                    + " errmsg=" + node.path("errmsg").asText("(空)")
                                    + "；URL(带token掩码)=" + urlMasked
                                    + "；processCode=" + mask(processCodeRaw, 10, 6)
                                    + "；请求体=" + requestSnapshot
                                    + "；原始响应=" + raw);
                }
                JsonNode listNode = node.path("result").path("list");
                if (listNode.isArray()) {
                    for (JsonNode idNode : listNode) {
                        if (all.size() >= maxItems) break;
                        all.add(idNode.asText());
                    }
                }
                JsonNode next = node.path("result").path("next_cursor");
                hasMore = node.path("result").path("more_matches").asBoolean(false) && next.canConvertToLong();
                cursor = next.canConvertToLong() ? next.asLong() : cursor + 1;
                guard++;
            } while (hasMore && all.size() < maxItems && guard < Math.max(200, maxItems / size + 10));

            return all;
        } catch (DingtalkNotEnabledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("钉钉审批实例列表调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拉取单个审批实例详情（含表单值明细、审批流程、附件等）。
     * @return 钉钉原始 JSON（业务字段解析放在 DingtalkSyncService）。
     */
    public JsonNode getProcessInstanceDetail(String accessToken, String processInstanceId) {
        ensureEnabled();
        try {
            var body = objectMapper.createObjectNode();
            body.put("process_instance_id", processInstanceId);
            String url = props.getBaseUrl() + "/topapi/processinstance/get?access_token=" + enc(accessToken);
            JsonNode node = postJson(url, body);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode != 0) {
                String raw = safeToString(node, 2000);
                throw new RuntimeException("钉钉审批实例详情失败[instance=" + processInstanceId + "]：errcode=" + errcode + " errmsg=" + node.path("errmsg").asText("(空)") + "；原始响应=" + raw);
            }
            return node.path("process_instance");
        } catch (DingtalkNotEnabledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("钉钉审批实例详情调用失败[instance=" + processInstanceId + "]：" + e.getMessage(), e);
        }
    }

    // ===================== 新版 OA 审批（api.dingtalk.com，header 鉴权）接口族 =====================

    private static final String NEW_API_BASE = "https://api.dingtalk.com";

    /**
     * 是否应该走新版 v2 接口：当 processCode 以 "PROC-" 开头时。
     * 新版 API 在 api.dingtalk.com 上，使用 x-acs-dingtalk-access-token header 鉴权。
     */
    public boolean isV2ProcessCode(String processCode) {
        return processCode != null && processCode.toUpperCase().startsWith("PROC-");
    }

    /**
     * 新版接口：POST /v1.0/workflow/processes/instanceIds/query
     * Host: api.dingtalk.com，鉴权方式：x-acs-dingtalk-access-token header。
     * 文档：https://open.dingtalk.com/document/orgapp/obtain-an-approval-list-of-instance-ids
     */
    /**
     * 新版接口：拉取指定日期范围内的审批实例 ID 列表。
     * 范围超过 120 天时自动按窗口分段拉取后合并去重（钉钉限制单次查询时间范围不超过 120 天）。
     *
     * @param accessToken 有效的 access_token
     * @param from        起始日期（含）
     * @param to          结束日期（含）
     * @param maxItems    每个查询窗口内最多拉取条数
     * @return 审批实例 ID 列表（可能空，但不会 null）
     */
    public List<String> listProcessInstanceIdsV2(String accessToken, LocalDate from, LocalDate to, int maxItems) {
        ensureEnabled();
        return listIdsByWindows(accessToken, from, to, maxItems, false);
    }

    /**
     * 新版接口：POST /v1.0/workflow/processes/instanceIds/query
     * Host: api.dingtalk.com，鉴权方式：x-acs-dingtalk-access-token header。
     * 文档：https://open.dingtalk.com/document/orgapp/obtain-an-approval-list-of-instance-ids
     * 单个查询窗口内执行（窗口跨度不超过 120 天）。
     */
    private List<String> listProcessInstanceIdsV2Window(String accessToken, LocalDate from, LocalDate to, int maxItems) {
        final long startMs = from.atStartOfDay(ZoneIdCN).toInstant().toEpochMilli();
        final long endMs = to.plusDays(1).atStartOfDay(ZoneIdCN).toInstant().toEpochMilli() - 1L;
        try {
            final int maxResults = Math.min(20, Math.max(1, maxItems));
            final String processCode = props.getReimbursementProcessCode();

            List<String> all = new ArrayList<>();
            long nextToken = 0L;
            boolean hasMore;
            int guard = 0;
            do {
                var body = objectMapper.createObjectNode();
                body.put("processCode", processCode);
                body.put("startTime", startMs);
                body.put("endTime", endMs);
                body.put("maxResults", maxResults);
                body.put("nextToken", nextToken);
                final String requestSnapshot = safeToString(body, 1000);

                String url = NEW_API_BASE + "/v1.0/workflow/processes/instanceIds/query";
                JsonNode node = postJsonWithHeaderAuth(url, body, accessToken);
                // 新版 API 错误格式：HTTP 400 + {"code":"xxx","message":"xxx"}（不是 errcode/errmsg）
                // 成功时 HTTP 200，body 为 {"result":{"list":[...],"nextToken":"...","success":true}}
                JsonNode resultNode = node.path("result");
                boolean success = resultNode.path("success").asBoolean(true);
                if (!success || node.has("code")) {
                    String code = node.path("code").asText("(无)");
                    String message = node.path("message").asText("(空)");
                    String raw = safeToString(node, 2000);
                    throw new RuntimeException(
                            "钉钉审批实例列表[v2]失败：code=" + code
                                    + " message=" + message
                                    + "；请求体=" + requestSnapshot
                                    + "；原始响应=" + raw);
                }
                JsonNode listNode = resultNode.path("list");
                if (listNode.isArray()) {
                    for (JsonNode idNode : listNode) {
                        if (all.size() >= maxItems) break;
                        all.add(idNode.asText());
                    }
                }
                JsonNode next = resultNode.path("nextToken");
                String nextStr = next.asText("");
                hasMore = !nextStr.isBlank();
                if (hasMore) {
                    try { nextToken = Long.parseLong(nextStr); }
                    catch (NumberFormatException nfe) { hasMore = false; }
                }
                guard++;
            } while (hasMore && all.size() < maxItems && guard < Math.max(200, maxItems / maxResults + 10));

            return all;
        } catch (DingtalkNotEnabledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("钉钉审批实例列表[v2]调用失败（窗口 " + from + " ~ " + to
                    + "，startMs=" + startMs + "，endMs=" + endMs
                    + "，spanDays=" + String.format("%.3f", (endMs - startMs) / 86_400_000.0) + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 新版接口：GET /v1.0/workflow/processInstances?processInstanceId=xxx
     * Host: api.dingtalk.com，鉴权方式：x-acs-dingtalk-access-token header。
     * 文档：https://open.dingtalk.com/document/development/obtains-the-details-of-a-single-approval-instance-pop
     * 返回前使用 {@link #adaptV2DetailToV1Shape} 把新版 result 字段转换为老版扁平格式。
     */
    public JsonNode getProcessInstanceDetailV2(String accessToken, String processInstanceId) {
        ensureEnabled();
        try {
            String url = NEW_API_BASE + "/v1.0/workflow/processInstances?processInstanceId=" + enc(processInstanceId);
            JsonNode node = getJsonWithHeaderAuth(url, accessToken);
            JsonNode resultNode = node.path("result");
            boolean success = resultNode.path("success").asBoolean(true);
            if (!success || node.has("code")) {
                String code = node.path("code").asText("(无)");
                String message = node.path("message").asText("(空)");
                String raw = safeToString(node, 2000);
                throw new RuntimeException("钉钉审批实例详情[v2]失败[instance=" + processInstanceId + "]：code=" + code + " message=" + message + "；原始响应=" + raw);
            }
            return adaptV2DetailToV1Shape(resultNode);
        } catch (DingtalkNotEnabledException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("钉钉审批实例详情[v2]调用失败[instance=" + processInstanceId + "]：" + e.getMessage(), e);
        }
    }

    private static final java.time.ZoneId ZoneIdCN = java.time.ZoneId.of("Asia/Shanghai");

    /**
     * 把新版 v2 result JSON（字段驼峰）转为老版扁平 JSON（字段下划线、form_component_values 数组）。
     * 新版 API 响应字段（参考官方文档 2026-08 版）：
     * - title / businessId / status / result / createTime / finishTime
     * - originatorUserId / originatorDeptId / originatorDeptName
     * - formComponentValues: [{id, name, value, extValue, componentType, bizAlias}]
     * - operationRecords / attachments
     * 注意：新版 API 不返回 originatorUserName，submitterName 将 fallback 到 title。
     */
    private JsonNode adaptV2DetailToV1Shape(JsonNode v2Result) {
        var o = objectMapper.createObjectNode();
        // 主字段
        o.put("title", text(v2Result, "title"));
        o.put("business_id", text(v2Result, "businessId", "business_id"));
        o.put("process_instance_id", text(v2Result, "processInstanceId", "process_instance_id"));
        o.put("status", text(v2Result, "status"));
        o.put("result", text(v2Result, "result"));
        // 时间字段：新版返回 ISO8601 字符串（如 "2024-07-10T09:40Z"），直接透传，toDateTime 已支持解析
        putStrIfPresent(o, "create_time", v2Result.path("createTime"));
        putStrIfPresent(o, "finish_time", v2Result.path("finishTime"));
        // 提单人
        o.put("originator_userid", text(v2Result, "originatorUserId", "originator_userid"));
        o.put("originator_dept_name", text(v2Result, "originatorDeptName", "dept_name"));
        o.put("originator_dept_id", text(v2Result, "originatorDeptId", "originator_dept_id"));
        // 新版 API 不返回 originatorUserName，留 null 让 Service 层 fallback 到 title
        // form 组件数组
        JsonNode v2Form = v2Result.path("formComponentValues");
        if (v2Form.isMissingNode() || v2Form.isNull()) v2Form = v2Result.path("form_component_values");
        if (v2Form.isArray()) {
            var arr = objectMapper.createArrayNode();
            for (JsonNode v2Item : v2Form) {
                var item = objectMapper.createObjectNode();
                item.put("id", text(v2Item, "id", "bizAliasId"));
                item.put("name", text(v2Item, "name", "label"));
                item.put("label", text(v2Item, "name", "label"));
                item.put("value", text(v2Item, "value"));
                item.put("ext_value", text(v2Item, "extValue"));
                item.put("component_name", text(v2Item, "componentType", "componentName"));
                arr.add(item);
            }
            o.set("form_component_values", arr);
        }
        // 附件 / 审批记录
        JsonNode v2Att = v2Result.path("attachments");
        if (v2Att.isArray()) o.set("attachments", v2Att);
        JsonNode ops = v2Result.path("operationRecords");
        if (ops.isArray()) o.set("operation_records", ops);
        // 从 operationRecords 中提取最后一条备注
        String remark = extractRemarkFromOps(ops);
        if (remark != null) o.put("remark", remark);
        return o;
    }

    private static String extractRemarkFromOps(JsonNode ops) {
        if (ops == null || !ops.isArray()) return null;
        String lastRemark = null;
        for (JsonNode op : ops) {
            String r = text(op, "remark");
            if (r != null && !r.isBlank()) lastRemark = r;
        }
        return lastRemark;
    }

    private static String text(JsonNode n, String... keys) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        for (String k : keys) {
            JsonNode c = n.path(k);
            if (!c.isNull() && !c.isMissingNode() && !c.asText("").isBlank()) return c.asText().trim();
        }
        return null;
    }

    private static void putStrIfPresent(com.fasterxml.jackson.databind.node.ObjectNode out, String key, JsonNode val) {
        if (val == null || val.isNull() || val.isMissingNode()) return;
        String s = val.isTextual() ? val.asText() : val.asText("");
        if (!s.isBlank()) out.put(key, s);
    }

    // ===================== 基础 HTTP 工具 =====================

    /** 新版 API GET：x-acs-dingtalk-access-token header 鉴权（api.dingtalk.com）。 */
    private JsonNode getJsonWithHeaderAuth(String url, String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .header("x-acs-dingtalk-access-token", accessToken)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        // 新版 API 错误时可能返回 400/500，body 为 {"code":"xxx","message":"xxx"}
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + "（钉钉新版 API GET） body=" + truncate(resp.body(), 600));
        }
        return objectMapper.readTree(resp.body());
    }

    /** 新版 API POST：x-acs-dingtalk-access-token header 鉴权（api.dingtalk.com）。 */
    private JsonNode postJsonWithHeaderAuth(String url, Object json, String accessToken) throws Exception {
        String jsonStr = objectMapper.writeValueAsString(json);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("x-acs-dingtalk-access-token", accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(jsonStr, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + "（钉钉新版 API POST） body=" + truncate(resp.body(), 600));
        }
        return objectMapper.readTree(resp.body());
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + "（钉钉网关）");
        }
        return objectMapper.readTree(resp.body());
    }

    private JsonNode postJson(String url, Object json) throws Exception {
        String jsonStr = objectMapper.writeValueAsString(json);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttpTimeoutMs()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonStr, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + "（钉钉网关 POST） body=" + truncate(resp.body(), 400));
        }
        return objectMapper.readTree(resp.body());
    }

    private static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... vs) {
        if (vs == null) return null;
        for (String v : vs) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    private String safeToString(JsonNode node, int max) {
        if (node == null) return "(null)";
        try { return truncate(objectMapper.writeValueAsString(node), max); }
        catch (Exception ignored) { return truncate(node.toString(), max); }
    }

    private static String mask(String s, int head, int tail) {
        if (s == null || s.isEmpty()) return "(空)";
        int n = s.length();
        if (n <= head + tail) return "*".repeat(n);
        return s.substring(0, head) + "*".repeat(Math.max(0, n - head - tail)) + s.substring(n - tail);
    }
}
