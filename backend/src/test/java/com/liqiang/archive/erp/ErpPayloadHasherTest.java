package com.liqiang.archive.erp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // ==================== 日期类型（2026-09-21 端到端实测回归） ====================

    /**
     * 写动作 payload 直接放业务入参，入参天然带 {@code LocalDate}。
     * 若未注册 {@code JavaTimeModule}，这里会抛
     * 「请求内容无法序列化，无法计算幂等哈希」→ 整条写路径 400。
     */
    @Test
    void localDateIsHashableAsIsoText() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outboundDate", LocalDate.of(2026, 9, 21));
        payload.put("warehouseId", 3);

        assertEquals("{\"outboundDate\":\"2026-09-21\",\"warehouseId\":3}",
                ErpPayloadHasher.canonicalJson(payload), "日期须固定为 ISO-8601 文本");
        assertEquals(64, ErpPayloadHasher.sha256(payload).length(), "带日期的载荷必须能算出哈希");
    }

    @Test
    void localDateTimeIsHashableAndStable() {
        Map<String, Object> a = Map.of("completedAt", LocalDateTime.of(2026, 9, 21, 14, 30, 5));
        Map<String, Object> b = Map.of("completedAt", LocalDateTime.of(2026, 9, 21, 14, 30, 5));
        Map<String, Object> c = Map.of("completedAt", LocalDateTime.of(2026, 9, 21, 14, 30, 6));

        assertEquals(ErpPayloadHasher.sha256(a), ErpPayloadHasher.sha256(b), "同值必须稳定");
        assertNotEquals(ErpPayloadHasher.sha256(a), ErpPayloadHasher.sha256(c), "秒级差异必须区分");
    }

    /** 业务键不可为空时，入参允许为 null（如 dueDate 未填）——不得因此哈希失败。 */
    @Test
    void nullValueInsidePayloadIsHashable() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dueDate", null);
        payload.put("specification", null);
        payload.put("salesOrderRef", "SO-1");

        assertEquals("{\"dueDate\":null,\"salesOrderRef\":\"SO-1\",\"specification\":null}",
                ErpPayloadHasher.canonicalJson(payload));
    }

    /** 金额是 BigDecimal：必须按数值文本序列化，且不同标度不得被合并（4990 vs 4990.00 是两次提交）。 */
    @Test
    void bigDecimalIsHashable() {
        Map<String, Object> a = Map.of("unitPrice", new BigDecimal("4990"));
        Map<String, Object> b = Map.of("unitPrice", new BigDecimal("4990.00"));

        assertEquals("{\"unitPrice\":4990}", ErpPayloadHasher.canonicalJson(a));
        assertEquals("{\"unitPrice\":4990.00}", ErpPayloadHasher.canonicalJson(b));
        assertNotEquals(ErpPayloadHasher.sha256(a), ErpPayloadHasher.sha256(b),
                "标度不同属内容不同，必须区分（与数据库 DECIMAL 比较口径一致）");
    }
}
