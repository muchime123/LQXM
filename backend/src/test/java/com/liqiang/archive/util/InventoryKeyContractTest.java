package com.liqiang.archive.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存链路口径的契约测试：单位归一 / 费用名单 / 台账表头指纹。
 *
 * <p>这三处都是「一旦漂移就静默错数据」的地方——单位写法不统一会把同物拆成多行，
 * 费用名单两侧不一致会虚增库存，列位错位会把数量写成价格。它们的共同点是**不会报错**，
 * 只会把脏数据安静地写进台账，所以用测试钉住。
 */
class InventoryKeyContractTest {

    // ===== 单位归一 =====

    @Test
    void unitNormalizeFoldsAsciiCaseAndAliases() {
        assertEquals("kg", UnitNormalizer.normalize("Kg"));
        assertEquals("kg", UnitNormalizer.normalize("KG"));
        assertEquals("kg", UnitNormalizer.normalize("kg"));
        assertEquals("kg", UnitNormalizer.normalize("公斤"));
        assertEquals("kg", UnitNormalizer.normalize("千克"));
    }

    @Test
    void unitNormalizeStripsTrailingNoiseAndInnerSpaces() {
        assertEquals("箱", UnitNormalizer.normalize("箱··"));
        assertEquals("台", UnitNormalizer.normalize("台·"));
        assertEquals("易撕", UnitNormalizer.normalize("易 撕"));
        assertEquals("个", UnitNormalizer.normalize("  个  "));
    }

    @Test
    void unitNormalizeKeepsChineseUnitsAsIs() {
        assertEquals("台", UnitNormalizer.normalize("台"));
        assertEquals("平方", UnitNormalizer.normalize("平方米"));
        assertEquals("平方", UnitNormalizer.normalize("平米"));
    }

    @Test
    void unitKeyIsNullSafeForBusinessKey() {
        assertNull(UnitNormalizer.normalize(null));
        assertNull(UnitNormalizer.normalize("   "));
        assertEquals("", UnitNormalizer.key(null));
        assertEquals("箱", UnitNormalizer.key("箱"));
    }

    // ===== 费用口径 =====

    @Test
    void feeFilterMatchesCategoryOrCodeFallback() {
        assertTrue(ProductFeeFilter.isFee("费用", null));
        assertTrue(ProductFeeFilter.isFee(" 制版费 ", null));
        assertTrue(ProductFeeFilter.isFee(null, "运费"));
        assertTrue(ProductFeeFilter.isFee("", "代发运费"));
        assertFalse(ProductFeeFilter.isFee("pet盒", "001DDZPJ"));
        assertFalse(ProductFeeFilter.isFee(null, null));
    }

    @Test
    void feeSqlListsAreRenderedFromSameSource() {
        String categories = ProductFeeFilter.categoryInList();
        assertTrue(categories.contains("'费用'"));
        assertTrue(categories.contains("'运费'"));
        // 编号兜底名单（类目为空的历史行靠它识别）
        assertTrue(ProductFeeFilter.codeInList().contains("'加工费'"));
    }

    // ===== 台账表头指纹 =====

    @Test
    void headerGuardAcceptsRealHeaderRows() {
        // 与氚云真实导出（2026-09-15 批次）一致的第 2 行中文表头
        List<String> productInfo = List.of("产品类目", "产品明细.产品编号", "产品明细.产品名称",
                "产品明细.规格", "产品明细.单位", "产品明细.产品备注", "产品明细.产品状态", "产品明细.采购价格");
        LedgerHeaderGuard.validate("产品信息", LedgerHeaderGuard.PRODUCT_INFO, productInfo::get);

        List<String> stock = List.of("仓库信息", "产品类目", "产品编号", "产品名称", "规格", "单位",
                "库存数量", "采购价格", "数据ID(不可修改)", "数据标题");
        LedgerHeaderGuard.validate("仓库明细", LedgerHeaderGuard.WAREHOUSE_STOCK, stock::get);
    }

    @Test
    void headerGuardRejectsShiftedColumns() {
        // 少了一列「产品类目」→ 列位整体左移，必须拒绝而不是静默错位
        List<String> shifted = List.of("产品明细.产品编号", "产品明细.产品名称", "产品明细.规格",
                "产品明细.单位", "产品明细.产品备注", "产品明细.产品状态", "产品明细.采购价格", "产品明细.序号");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LedgerHeaderGuard.validate("产品信息", LedgerHeaderGuard.PRODUCT_INFO, shifted::get));
        assertTrue(e.getMessage().contains("表头与预期不符"));
    }

    @Test
    void headerGuardRejectsMissingHeaderRow() {
        List<String> empty = List.of();
        assertThrows(IllegalArgumentException.class,
                () -> LedgerHeaderGuard.validate("仓库明细", LedgerHeaderGuard.WAREHOUSE_STOCK,
                        col -> empty.isEmpty() ? null : empty.get(col)));
    }
}
