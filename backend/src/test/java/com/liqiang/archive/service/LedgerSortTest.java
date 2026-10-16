package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账排序契约测试（2026-10-08）。纯 JUnit，不碰 Spring / 数据库。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li><b>白名单命中的键</b>：主键带方向，兜底键方向固定 DESC（方向只作用于主键）；</li>
 *   <li><b>空键 / 未知键</b>：静默回落该台账默认序 —— 前端持有的值可能已过期，
 *       这里若抛错会在页面上表现为「点了排序报错」，若当成「不排序」又会与表头箭头不符；</li>
 *   <li><b>每个键都真的能产生不同的 ORDER BY</b>：防止某个键被写错/漏登记而静默回落到默认列
 *       （那正是「表头有箭头、服务端却忽略」的成因）。</li>
 * </ol>
 */
class LedgerSortTest {

    /** 全部台账白名单。新增台账必须登记在此，否则 {@link #specRegistryIsComplete()} 失败。 */
    private static Map<String, LedgerSort.Spec> allSpecs() {
        Map<String, LedgerSort.Spec> m = new LinkedHashMap<>();
        m.put("outbound", H3BusinessQueryService.OUTBOUND_SORT);
        m.put("production", H3BusinessQueryService.PRODUCTION_SORT);
        m.put("receipt", H3BusinessQueryService.RECEIPT_SORT);
        m.put("supplier", H3BusinessQueryService.SUPPLIER_SORT);
        m.put("purchase", H3BusinessQueryService.PURCHASE_SORT);
        m.put("otherMovement", H3BusinessQueryService.OTHER_MOVEMENT_SORT);
        m.put("transfer", H3BusinessQueryService.TRANSFER_SORT);
        m.put("invoiceProfile", H3BusinessQueryService.INVOICE_PROFILE_SORT);
        m.put("invoiceRequest", H3BusinessQueryService.INVOICE_REQUEST_SORT);
        m.put("productionReport", H3BusinessQueryService.PRODUCTION_REPORT_SORT);
        m.put("invoiceInput", H3BusinessQueryService.INVOICE_INPUT_SORT);
        m.put("invoiceSales", H3BusinessQueryService.INVOICE_SALES_SORT);
        m.put("supplierPayable", H3BusinessQueryService.SUPPLIER_PAYABLE_SORT);
        m.put("cashflowJournal", H3BusinessQueryService.CASHFLOW_JOURNAL_SORT);
        m.put("cashierLog", H3BusinessQueryService.CASHIER_LOG_SORT);
        m.put("product", ProductInventoryMergeService.PRODUCT_SORT);
        m.put("stock", ProductInventoryMergeService.STOCK_SORT);
        m.put("dealer", ProductInventoryMergeService.DEALER_SORT);
        return m;
    }

    @Test
    void specRegistryIsComplete() {
        assertEquals(18, allSpecs().size(), "台账白名单数量变了：新增/删除台账时同步前端 SORTABLE_COLUMNS");
        for (var e : allSpecs().entrySet()) {
            LedgerSort.Spec spec = e.getValue();
            assertFalse(spec.keys().isEmpty(), e.getKey() + " 未声明任何可排序键");
            assertFalse(spec.defaultOrder() == null || spec.defaultOrder().isBlank(),
                    e.getKey() + " 默认序为空");
            assertFalse(spec.tieBreak() == null || spec.tieBreak().isBlank(),
                    e.getKey() + " 兜底键为空");
            assertFalse(spec.defaultOrder().startsWith(" "), e.getKey() + " 默认序带前导空白");
        }
    }

    @Test
    void blankOrUnknownKeyFallsBackToDefaultOrder() {
        for (var e : allSpecs().entrySet()) {
            String expected = " ORDER BY " + e.getValue().defaultOrder();
            assertEquals(expected, LedgerSort.orderBy(e.getValue(), null, null), e.getKey() + " null 键");
            assertEquals(expected, LedgerSort.orderBy(e.getValue(), "", "desc"), e.getKey() + " 空键");
            assertEquals(expected, LedgerSort.orderBy(e.getValue(), "  ", "asc"), e.getKey() + " 空白键");
            assertEquals(expected, LedgerSort.orderBy(e.getValue(), "bogusKey", "desc"),
                    e.getKey() + " 未知键必须静默回落（前端值可能过期）");
        }
    }

    @Test
    void whitelistedKeyCarriesDirectionAndFixedTieBreak() {
        for (var e : allSpecs().entrySet()) {
            for (String key : LedgerSort.sortableKeys(e.getValue())) {
                assertTrue(LedgerSort.accepts(e.getValue(), key), e.getKey() + " 自身键应被接受");
                String asc = LedgerSort.orderBy(e.getValue(), key, "asc");
                String desc = LedgerSort.orderBy(e.getValue(), key, "desc");

                // 兜底键方向固定：升序降序都必须是 <tieBreak> DESC
                String tie = "," + e.getValue().tieBreak() + " DESC";
                assertTrue(asc.endsWith(tie), e.getKey() + "/" + key + " 升序兜底键方向不对: " + asc);
                assertTrue(desc.endsWith(tie), e.getKey() + "/" + key + " 降序兜底键方向不对: " + desc);

                // 方向确实作用在主键上
                assertNotEquals(asc, desc, e.getKey() + "/" + key + " 升/降序结果相同（方向没生效）");
                assertTrue(asc.replace(tie, "").endsWith(" ASC"), e.getKey() + "/" + key + " 升序主键缺 ASC: " + asc);
                assertTrue(desc.replace(tie, "").endsWith(" DESC"), e.getKey() + "/" + key + " 降序主键缺 DESC: " + desc);

                // 不能等于默认序，否则该键形同不存在（静默回落）
                assertNotEquals(" ORDER BY " + e.getValue().defaultOrder(), asc,
                        e.getKey() + "/" + key + " 与默认序相同 —— 该键实际上没接上");
            }
        }
    }

    @Test
    void directionDefaultsToDescending() {
        assertTrue(LedgerSort.orderBy(H3BusinessQueryService.OUTBOUND_SORT, "time", null).contains(" DESC"));
        assertTrue(LedgerSort.orderBy(H3BusinessQueryService.OUTBOUND_SORT, "time", "  ").contains(" DESC"));
        assertTrue(LedgerSort.orderBy(H3BusinessQueryService.OUTBOUND_SORT, "time", "DESC").contains(" DESC"));
        // 大小写不敏感
        assertTrue(LedgerSort.orderBy(H3BusinessQueryService.OUTBOUND_SORT, "time", "ASC").contains(" ASC"));
    }

    /** 关键台账的 SQL 形状钉死（改口径时这里必须同步，防止静默改语义）。 */
    @Test
    void pinnedOrders() {
        assertEquals(" ORDER BY COALESCE(o.ship_date,o.business_date) DESC,o.id DESC",
                LedgerSort.orderBy(H3BusinessQueryService.OUTBOUND_SORT, null, null));
        assertEquals(" ORDER BY o.outbound_no ASC,o.id DESC",
                LedgerSort.orderBy(H3BusinessQueryService.OUTBOUND_SORT, "code", "asc"));
        assertEquals(" ORDER BY r.tx_time ASC,r.id DESC",
                LedgerSort.orderBy(H3BusinessQueryService.CASHIER_LOG_SORT, "time", "asc"));
        // 应付/流水日记账的默认仍是 sheet 维度分组（未改成时间序）
        assertEquals(" ORDER BY r.supplier_name,r.month_no,r.id",
                LedgerSort.orderBy(H3BusinessQueryService.SUPPLIER_PAYABLE_SORT, null, null));
        assertEquals(" ORDER BY r.account_name,r.entry_date,r.id",
                LedgerSort.orderBy(H3BusinessQueryService.CASHFLOW_JOURNAL_SORT, null, null));
    }
}
