package com.liqiang.archive.service;

import com.liqiang.archive.service.ImportKindDescriptor.KindDomain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一数据源描述符契约测试（阶段 3）：7 类元数据唯一性、双向查找、
 * 台账归属与表头识别键的完整性——保证后续跨域统一使用单一事实源。
 */
class ImportKindDescriptorTest {

    @Test
    void allKindsPresentWithExpectedCoverage() {
        assertEquals(7, ImportKindDescriptor.values().length);
        // A 域业务事实 4 类、B 域台账 3 类（含经销商）
        List<ImportKindDescriptor> ledger = ImportKindDescriptor.ledgerKinds();
        assertEquals(3, ledger.size());
        long factCount = java.util.Arrays.stream(ImportKindDescriptor.values())
                .filter(k -> k.isBusinessFact()).count();
        assertEquals(4, factCount);
    }

    @Test
    void routesAndDbTypesAreUnique() {
        Set<String> routes = new HashSet<>();
        Set<String> dbTypes = new HashSet<>();
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            assertTrue(routes.add(kind.route()), "route 重复: " + kind.route());
            assertTrue(dbTypes.add(kind.dbType()), "dbType 重复: " + kind.dbType());
        }
    }

    @Test
    void businessFactKindsCarryHeaderContract() {
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            if (kind.isBusinessFact()) {
                assertNotNull(kind.keyHeader(), kind + " 应声明表头识别键");
                assertNotNull(kind.displayName());
                assertTrue(kind.hasDetailSubTable(), kind + " 业务事实均带行内明细");
            } else {
                assertNull(kind.keyHeader(), kind + " 台账固定列位，不应有表头识别键");
                assertFalse(kind.hasDetailSubTable());
            }
        }
        // 关键业务键逐一锁定，防止模板演进误删
        assertEquals("生产工单流水号", ImportKindDescriptor.PRODUCTION.keyHeader());
        assertEquals("销售出库编号", ImportKindDescriptor.OUTBOUND.keyHeader());
        assertEquals("流水号", ImportKindDescriptor.RECEIPT.keyHeader());
        assertEquals("流水号", ImportKindDescriptor.SETTLEMENT.keyHeader());
    }

    @Test
    void findRouteAndFindDbType() {
        assertEquals(ImportKindDescriptor.OUTBOUND, ImportKindDescriptor.findRoute("outbound"));
        assertEquals(ImportKindDescriptor.OUTBOUND, ImportKindDescriptor.findRoute("OUTBOUND"));
        assertEquals(ImportKindDescriptor.CATALOG, ImportKindDescriptor.findRoute("catalog"));
        assertNull(ImportKindDescriptor.findRoute("不存在"));
        assertNull(ImportKindDescriptor.findRoute(null));

        assertEquals(ImportKindDescriptor.STOCK, ImportKindDescriptor.findDbType("WAREHOUSE_STOCK"));
        assertEquals(ImportKindDescriptor.CATALOG, ImportKindDescriptor.findDbType("PRODUCT_INFO"));
        assertNull(ImportKindDescriptor.findDbType("NOPE"));
        assertNull(ImportKindDescriptor.findDbType(null));
    }

    @Test
    void ledgerRoutingOnlyCoversBundledLedgerTypes() {
        assertTrue(ImportKindDescriptor.isLedgerDbType("PRODUCT_INFO"));
        assertTrue(ImportKindDescriptor.isLedgerDbType("WAREHOUSE_STOCK"));
        assertTrue(ImportKindDescriptor.isLedgerDbType("DEALER"));
        assertFalse(ImportKindDescriptor.isLedgerDbType("OUTBOUND"));
        assertFalse(ImportKindDescriptor.isLedgerDbType("PRODUCTION"));
        assertFalse(ImportKindDescriptor.isLedgerDbType("RECEIPT"));
        assertFalse(ImportKindDescriptor.isLedgerDbType("SETTLEMENT"));
        assertFalse(ImportKindDescriptor.isLedgerDbType(null));
        assertFalse(ImportKindDescriptor.isLedgerDbType("UNKNOWN"));
    }

    @Test
    void orchestratesFlagOnlyOnOutbound() {
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            assertEquals(kind == ImportKindDescriptor.OUTBOUND, kind.orchestrates());
        }
        assertEquals(KindDomain.BUSINESS_FACT, ImportKindDescriptor.OUTBOUND.domain());
        assertEquals(KindDomain.LEDGER, ImportKindDescriptor.CATALOG.domain());
        assertEquals(KindDomain.LEDGER, ImportKindDescriptor.STOCK.domain());
        assertEquals(KindDomain.LEDGER, ImportKindDescriptor.DEALER.domain());
    }
}
