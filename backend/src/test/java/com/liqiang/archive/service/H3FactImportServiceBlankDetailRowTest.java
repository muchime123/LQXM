package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验氚云出库单「误点空行」兜底判定 {@link H3FactImportService#blankDetailRow(Map)}。
 *
 * <p>背景（2026-09-17）：业务员在出库表单多点出的空明细行仍带数据ID（GUID），且「销售总价」
 * 可能带 0——第一版兜底要求金额字段为空串，拦不住，导致 CK20260824107938 / CK20260817107371
 * 每次确认导入都在销售出库关联工作台复活为「缺库存产品」。本测试钉死语义级判定。
 */
class H3FactImportServiceBlankDetailRowTest {

    private Map<String, String> row(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final String P = "出库产品明细.";

    @Test
    void fullyEmptyRowIsBlank() {
        assertTrue(H3FactImportService.blankDetailRow(new HashMap<>()));
        assertTrue(H3FactImportService.blankDetailRow(null));
        assertTrue(H3FactImportService.blankDetailRow(row(P + "数据ID(不可修改)", "", P + "选择产品", "  ")));
    }

    @Test
    void guidWithZeroTotalIsBlank_pinnedRegression() {
        // 实测复活案例：CK20260824107938 序号4 / CK20260817107371 序号2——
        // 带 GUID、销售总价 "0.00"，产品四要素与数量全空
        assertTrue(H3FactImportService.blankDetailRow(row(
                P + "数据ID(不可修改)", "6c6f3fa9-8079-4be2-aba7-7aa8f567b01b",
                P + "销售总价", "0.00")));
        assertTrue(H3FactImportService.blankDetailRow(row(
                P + "数据ID(不可修改)", "cbd4cd05-c38e-4274-b750-833015aec7d0",
                P + "销售总价", "0")));
    }

    @Test
    void zeroTotalWithCurrencyOrCommaStillBlank() {
        assertTrue(H3FactImportService.blankDetailRow(row(P + "销售总价", "¥0.00")));
        assertTrue(H3FactImportService.blankDetailRow(row(P + "销售总价", "￥0,00".replace(",", ""))));
    }

    @Test
    void anyProductIdentityIsNotBlank() {
        assertFalse(H3FactImportService.blankDetailRow(row(P + "选择产品", "打包带")));
        assertFalse(H3FactImportService.blankDetailRow(row(P + "产品名称", "打包带")));
        assertFalse(H3FactImportService.blankDetailRow(row(P + "类目", "包材")));
        assertFalse(H3FactImportService.blankDetailRow(row(P + "产品编号", "YX415")));
        assertFalse(H3FactImportService.blankDetailRow(row(P + "出库数量", "0")));
    }

    @Test
    void nonzeroTotalIsNotBlank() {
        // 金额非 0 的无产品行不是空行——保留给下游解析去提示，不静默丢弃
        assertFalse(H3FactImportService.blankDetailRow(row(P + "销售总价", "12.5")));
    }

    @Test
    void unparseableTotalIsNotBlank() {
        // 解析不了就当真实数据，交给下游提示
        assertFalse(H3FactImportService.blankDetailRow(row(P + "销售总价", "见备注")));
    }
}
