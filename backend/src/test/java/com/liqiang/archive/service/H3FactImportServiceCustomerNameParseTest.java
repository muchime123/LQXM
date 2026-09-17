package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 校验 H3FactImportService 中销售出库「客户名称」缺失场景下的回退解析逻辑。
 * <p>
 * 氚云里客户信息常被合并填在「联系方式」或自动合成字段「客户信息（输入名字电话地址自动生成）」
 * 中，导致「客户名称」输入框为空，下游销售出库分析找不到客户。本测试覆盖静态拆分函数
 * {@link H3FactImportService#extractCustomerNameFromComposite(String)} 与实例级
 * fallback 顺序的取值逻辑 {@link H3FactImportService#resolveOutboundCustomerName(Map)}（通过 mock 实例走真实方法）。
 */
class H3FactImportServiceCustomerNameParseTest {

    private H3FactImportService newSpyService() {
        // resolveOutboundCustomerName 不依赖 jdbc/spring，只读取静态 logger，可直接 spy 走真实方法
        return org.mockito.Mockito.mock(H3FactImportService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    }

    // ====== extractCustomerNameFromComposite ======

    @Test
    void extractRejectsNullAndBlank() {
        assertNull(H3FactImportService.extractCustomerNameFromComposite(null));
        assertNull(H3FactImportService.extractCustomerNameFromComposite(""));
        assertNull(H3FactImportService.extractCustomerNameFromComposite("   \n\r  "));
    }

    @Test
    void extractTakesNameBeforePhone() {
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite("周守岭，18912620958"));
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite("周守岭,18912620958"));
        assertEquals("张三", H3FactImportService.extractCustomerNameFromComposite("张三; 0512-12345678"));
    }

    @Test
    void extractSkipsLeadingPhoneSegment() {
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite("18912620958,周守岭"));
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite("18912620958，周守岭"));
    }

    @Test
    void extractReturnsSingleNameWhenNoPhone() {
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite("周守岭"));
        assertEquals("Alice Wang", H3FactImportService.extractCustomerNameFromComposite("Alice Wang"));
    }

    @Test
    void extractReturnsNullWhenOnlyPhone() {
        assertNull(H3FactImportService.extractCustomerNameFromComposite("18912620958"));
        assertNull(H3FactImportService.extractCustomerNameFromComposite("+86 189-1262-0958"));
        assertNull(H3FactImportService.extractCustomerNameFromComposite("0512-12345678"));
    }

    @Test
    void extractHandlesMultipleDelimitersAndNewlines() {
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite(
                "周守岭，18912620958，苏州华承食品有限公司，张家港市塘桥镇刘村村委西300米"));
        assertEquals("周守岭", H3FactImportService.extractCustomerNameFromComposite(
                "周守岭\n18912620958\n地址"));
    }

    @Test
    void extractIgnoresAddressOnlySegment() {
        // 真实场景：合并字段可能只剩公司名+地址段，没有个人姓名，仍可作为兜底客户名
        String composite = "苏州华承食品有限公司，张家港市塘桥镇刘村村委西300米";
        String name = H3FactImportService.extractCustomerNameFromComposite(composite);
        assertEquals(composite.split("[,，]")[0].trim(), name);
    }

    // ====== resolveOutboundCustomerName ======

    @Test
    void prefersExplicitCustomerName() {
        H3FactImportService svc = newSpyService();
        Map<String, String> m = new HashMap<>();
        m.put("客户名称", "江苏鼎业机械");
        m.put("客户信息（输入名字电话地址自动生成）", "周守岭，18912620958");
        m.put("联系方式", "18912620958");
        assertEquals("江苏鼎业机械", svc.resolveOutboundCustomerName(m));
    }

    @Test
    void fallsBackToDealerWhenCustomerNameBlank() {
        H3FactImportService svc = newSpyService();
        Map<String, String> m = new HashMap<>();
        m.put("客户名称", "   ");
        m.put("选择经销商", "上海经销商甲");
        m.put("联系方式", "18912620958");
        assertEquals("上海经销商甲", svc.resolveOutboundCustomerName(m));
    }

    @Test
    void resolvesFromCompositeWhenAllDirectFieldsBlank() {
        H3FactImportService svc = newSpyService();
        Map<String, String> m = new HashMap<>();
        m.put("客户信息（输入名字电话地址自动生成）",
                "周守岭，18912620958，苏州华承食品有限公司，张家港市塘桥镇刘村村委西300米");
        m.put("联系方式", "18912620958");
        assertEquals("周守岭", svc.resolveOutboundCustomerName(m));
    }

    @Test
    void resolvesFromContactFieldWhenCompositeMissing() {
        H3FactImportService svc = newSpyService();
        Map<String, String> m = new HashMap<>();
        m.put("联系方式", "周守岭，18912620958");
        assertEquals("周守岭", svc.resolveOutboundCustomerName(m));
    }

    @Test
    void returnsNullWhenNothingExtractable() {
        H3FactImportService svc = newSpyService();
        Map<String, String> m = new HashMap<>();
        m.put("联系方式", "0512-12345678");
        m.put("客户详细地址", "江苏苏州");
        assertNull(svc.resolveOutboundCustomerName(m));
    }

    @Test
    void respectsAllCompositeAliasKeyVariants() {
        // 氚云不同表单的字段名可能略有不同（括号全/半角、是否带修饰），确保 alias 全部命中
        H3FactImportService svc = newSpyService();
        Map<String, String> m = new HashMap<>();
        m.put("客户信息(输入名字电话地址自动生成)", "Alice Wang, 18912620958");
        assertEquals("Alice Wang", svc.resolveOutboundCustomerName(m));
    }

    @Test
    void avoidsNullPointerOnEmptyMap() {
        H3FactImportService svc = newSpyService();
        assertNull(svc.resolveOutboundCustomerName(new HashMap<>()));
        assertNotEquals("周守岭", svc.resolveOutboundCustomerName(new HashMap<>()));
    }
}
