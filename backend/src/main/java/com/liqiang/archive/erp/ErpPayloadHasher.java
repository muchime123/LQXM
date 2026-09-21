package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 请求载荷规范化哈希（幂等键的内容指纹）。
 *
 * <p>独立成工具类的原因：哈希必须**对字段顺序不敏感**——同一笔业务由不同客户端提交时
 * JSON 字段顺序可能不同（Map 迭代顺序、DTO 属性声明顺序），若直接哈希原始文本会把
 * 「同内容不同顺序」误判为 {@link ErpErrorCode#IDEMPOTENCY_PAYLOAD_CONFLICT}。
 * 因此先做规范序列化（Map 键排序 + 属性名排序）再取 sha256。
 *
 * <p><b>必须注册 {@link JavaTimeModule}（勿删）</b>：写动作的 payload 直接放业务入参，
 * 而入参里天然带日期（{@code LocalDate dueDate} / {@code LocalDate outboundDate} /
 * {@code LocalDateTime completedAt}）。Jackson 默认不认识 JSR-310 类型，会抛
 * {@code InvalidDefinitionException} → 被本类包装成 400
 * {@code INVALID_ARGUMENT}「请求内容无法序列化，无法计算幂等哈希」→
 * **所有带日期字段的写动作整条路 400**，且报错文案指向「序列化」而非「日期」，
 * 极易被误判为客户端传参问题。2026-09-21 端到端验证实测命中（销售出库建单、制造域建单
 * 同因）。同时关闭 {@code WRITE_DATES_AS_TIMESTAMPS}，让日期固定成
 * {@code "2026-09-21"} 这种 ISO-8601 文本：数组形态 [{@code 2026,9,21}] 虽也确定，
 * 但文本形态在排查哈希冲突时可直接肉眼比对，且不受时区/纪元设置影响。
 */
public final class ErpPayloadHasher {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private ErpPayloadHasher() {
    }

    /** 规范化 JSON 文本（Map 键与属性名均按字典序）。 */
    public static String canonicalJson(Object payload) {
        if (payload == null) {
            return "null";
        }
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ErpBusinessException(ErpErrorCode.INVALID_ARGUMENT,
                    "请求内容无法序列化，无法计算幂等哈希：" + e.getMessage());
        }
    }

    /** 规范化载荷的 sha256（十六进制小写，64 字符）。 */
    public static String sha256(Object payload) {
        byte[] bytes = canonicalJson(payload).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
