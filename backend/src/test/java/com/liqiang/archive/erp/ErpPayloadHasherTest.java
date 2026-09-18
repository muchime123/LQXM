package com.liqiang.archive.erp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等载荷哈希测试（M0）。
 *
 * <p>核心不变量：**同内容 → 同哈希（与字段顺序无关）**。若这条不成立，
 * 同一笔业务由不同客户端重试会被误判为 IDEMPOTENCY_PAYLOAD_CONFLICT，
 * 而这是最容易在生产上出现「明明没改却报冲突」的根因。
 */
class ErpPayloadHasherTest {

    @Test
    void sameContentDifferentKeyOrderYieldsSameHash() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("amount", 120);
        ordered.put("skuCode", "SKU-1");
        ordered.put("warehouseId", 3);

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("warehouseId", 3);
        reversed.put("skuCode", "SKU-1");
        reversed.put("amount", 120);

        Map<String, Object> sorted = new TreeMap<>(ordered);

        assertEquals(ErpPayloadHasher.sha256(ordered), ErpPayloadHasher.sha256(reversed));
        assertEquals(ErpPayloadHasher.sha256(ordered), ErpPayloadHasher.sha256(sorted));
    }

    @Test
    void differentContentYieldsDifferentHash() {
        Map<String, Object> a = Map.of("amount", 120, "skuCode", "SKU-1");
        Map<String, Object> b = Map.of("amount", 121, "skuCode", "SKU-1");
        Map<String, Object> c = Map.of("amount", "120", "skuCode", "SKU-1");

        assertNotEquals(ErpPayloadHasher.sha256(a), ErpPayloadHasher.sha256(b), "数值不同必须区分");
        assertNotEquals(ErpPayloadHasher.sha256(a), ErpPayloadHasher.sha256(c), "类型不同（数字 120 vs 字符串 \"120\"）必须区分");
    }

    @Test
    void hashIsStableSha256Hex() {
        String hash = ErpPayloadHasher.sha256(Map.of("k", "v"));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), "应为小写十六进制：" + hash);
        assertEquals(hash, ErpPayloadHasher.sha256(Map.of("k", "v")), "同输入必须稳定");
    }

    @Test
    void nullPayloadHasDeterministicHash() {
        String hash = ErpPayloadHasher.sha256(null);
        assertEquals(64, hash.length());
        assertEquals("null", ErpPayloadHasher.canonicalJson(null));
        assertEquals(hash, ErpPayloadHasher.sha256(null));
    }

    @Test
    void canonicalJsonIsCompactAndSorted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("b", 2);
        payload.put("a", 1);
        assertEquals("{\"a\":1,\"b\":2}", ErpPayloadHasher.canonicalJson(payload));
    }
}
