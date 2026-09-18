package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 请求载荷规范化哈希（幂等键的内容指纹）。
 *
 * <p>独立成工具类的原因：哈希必须**对字段顺序不敏感**——同一笔业务由不同客户端提交时
 * JSON 字段顺序可能不同（Map 迭代顺序、DTO 属性声明顺序），若直接哈希原始文本会把
 * 「同内容不同顺序」误判为 {@link ErpErrorCode#IDEMPOTENCY_PAYLOAD_CONFLICT}。
 * 因此先做规范序列化（Map 键排序 + 属性名排序）再取 sha256。
 */
public final class ErpPayloadHasher {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
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
