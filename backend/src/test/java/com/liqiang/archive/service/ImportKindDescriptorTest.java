package com.liqiang.archive.service;

import com.liqiang.archive.service.ImportKindDescriptor.KindDomain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一数据源描述符契约测试：dbType/route 唯一性与清单完整性、域归属、
 * 幂等键（keyHeader）与明细子表标记——保证跨域使用单一事实源。
 *
 * <p><b>本测试是导入类的「注册表守卫」</b>：新增一类导入后必须同步更新下面的
 * {@link #LOCKED_DB_TYPES} / {@link #LOCKED_KEY_HEADERS} / {@link #KINDS_WITH_DETAIL}，
 * 否则本测试失败——失败信息会提示要一并改前端常量与路由。这是有意的：
 * 2026-09-18 之前本测试硬编码「阶段 3 的 7 类」，导入类扩到 17 类业务事实后长期红灯，
 * 使真正回归与历史噪声无法区分。改成清单式后，新增类会显式红一次（提示同步），
 * 而删除/改名任何已有类会永久红灯（那才是真事故）。
 */
class ImportKindDescriptorTest {

    /** 已上线的全部 dbType（= 前端的导入类型清单）。只增不减：删或改名都会让历史批次、台账、路由静默失效。 */
    private static final Set<String> LOCKED_DB_TYPES = Set.of(
            // A 域业务事实（17）
            "PRODUCTION", "OUTBOUND", "RECEIPT", "SETTLEMENT", "SUPPLIER", "PURCHASE_IN",
            "OTHER_IN", "OTHER_OUT", "TRANSFER", "INVOICE_PROFILE", "INVOICE_REQUEST",
            "PRODUCTION_REPORT", "INVOICE_INPUT", "INVOICE_SALES", "SUPPLIER_PAYABLE",
            "CASHFLOW_JOURNAL", "CASHIER_LOG",
            // B 域固定列位台账（3）
            "PRODUCT_INFO", "WAREHOUSE_STOCK", "DEALER");

    /**
     * 幂等键 = 表头识别键，是逐类取证后的业务决策，改错即幂等失控——
     * 轻则每轮多计 CREATE 永不收敛，重则整行串进上一单（开票申请实测尾部 18 单缺流水号）。
     */
    private static final Map<String, String> LOCKED_KEY_HEADERS = Map.ofEntries(
            Map.entry("PRODUCTION", "生产工单流水号"),
            Map.entry("OUTBOUND", "销售出库编号"),
            Map.entry("RECEIPT", "流水号"),
            Map.entry("SETTLEMENT", "流水号"),
            Map.entry("SUPPLIER", "供应商名称"),
            Map.entry("PURCHASE_IN", "采购入库编号"),
            Map.entry("OTHER_IN", "流水号"),
            Map.entry("OTHER_OUT", "其他出库编号"),
            Map.entry("TRANSFER", "流水号"),
            Map.entry("INVOICE_PROFILE", "数据ID(不可修改)"),
            Map.entry("INVOICE_REQUEST", "数据ID(不可修改)"),
            Map.entry("PRODUCTION_REPORT", "数据ID(不可修改)"),
            Map.entry("INVOICE_INPUT", "数电发票号码"),
            Map.entry("INVOICE_SALES", "数电发票号码"),
            Map.entry("SUPPLIER_PAYABLE", "月份"),
            Map.entry("CASHFLOW_JOURNAL", "日期"),
            Map.entry("CASHIER_LOG", "交易完成时间"));

    /** 声明了明细子表的 kind（childCount &gt; 0）。单表类恒 false——不能一概而论要求「业务事实均带明细」。 */
    private static final Set<String> KINDS_WITH_DETAIL = Set.of(
            "PRODUCTION", "OUTBOUND", "RECEIPT", "SETTLEMENT", "PURCHASE_IN",
            "OTHER_IN", "OTHER_OUT", "TRANSFER", "INVOICE_REQUEST", "INVOICE_SALES");

    private static final Set<String> LEDGER_DB_TYPES = Set.of("PRODUCT_INFO", "WAREHOUSE_STOCK", "DEALER");

    @Test
    void allKindsPresentWithExpectedCoverage() {
        Set<String> actual = new HashSet<>();
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) actual.add(kind.dbType());
        assertEquals(LOCKED_DB_TYPES, actual,
                "导入类清单变化：新增导入类时请同步本清单 + 前端 types/constants + ImportKindDescriptor");
        assertEquals(LOCKED_DB_TYPES.size(), ImportKindDescriptor.values().length,
                "同一 dbType 出现重复枚举项");

        long factCount = java.util.Arrays.stream(ImportKindDescriptor.values())
                .filter(ImportKindDescriptor::isBusinessFact).count();
        assertEquals(17, factCount, "A 域业务事实类数量变化");
        assertEquals(3, ImportKindDescriptor.ledgerKinds().size(), "B 域台账类数量变化");
    }

    @Test
    void routesAndDbTypesAreUnique() {
        Set<String> routes = new HashSet<>();
        Set<String> dbTypes = new HashSet<>();
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            assertTrue(routes.add(kind.route()), "route 重复: " + kind.route());
            assertTrue(dbTypes.add(kind.dbType()), "dbType 重复: " + kind.dbType());
            assertNotNull(kind.displayName(), kind + " 缺展示名");
        }
    }

    @Test
    void businessFactKindsCarryHeaderContract() {
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            if (kind.isBusinessFact()) {
                assertNotNull(kind.keyHeader(), kind + " 应声明表头识别键（幂等键来源）");
                assertFalse(kind.keyHeader().isBlank(), kind + " keyHeader 不得为空白");
            } else {
                assertNull(kind.keyHeader(), kind + " 台账固定列位，不应有表头识别键");
            }
        }
    }

    @Test
    void detailSubTableFlagsMatchExpected() {
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            assertEquals(KINDS_WITH_DETAIL.contains(kind.dbType()), kind.hasDetailSubTable(),
                    kind + " 的明细子表标记与预期不符（新增/取消子表需人工确认 childCount 口径）");
        }
    }

    @Test
    void keyHeadersArePinnedPerKind() {
        for (Map.Entry<String, String> e : LOCKED_KEY_HEADERS.entrySet()) {
            ImportKindDescriptor kind = ImportKindDescriptor.findDbType(e.getKey());
            assertNotNull(kind, "未知 dbType: " + e.getKey());
            assertEquals(e.getValue(), kind.keyHeader(),
                    kind + " 的幂等键被改动——确认是取证后的新结论，再同步本清单");
        }
    }

    @Test
    void findRouteAndFindDbType() {
        assertEquals(ImportKindDescriptor.OUTBOUND, ImportKindDescriptor.findRoute("outbound"));
        assertEquals(ImportKindDescriptor.OUTBOUND, ImportKindDescriptor.findRoute("OUTBOUND"));
        assertEquals(ImportKindDescriptor.CATALOG, ImportKindDescriptor.findRoute("catalog"));
        assertEquals(ImportKindDescriptor.CASHIER_LOG, ImportKindDescriptor.findRoute("cashierLog"));
        assertNull(ImportKindDescriptor.findRoute("不存在"));
        assertNull(ImportKindDescriptor.findRoute(null));

        assertEquals(ImportKindDescriptor.STOCK, ImportKindDescriptor.findDbType("WAREHOUSE_STOCK"));
        assertEquals(ImportKindDescriptor.CATALOG, ImportKindDescriptor.findDbType("PRODUCT_INFO"));
        assertNull(ImportKindDescriptor.findDbType("NOPE"));
        assertNull(ImportKindDescriptor.findDbType(null));

        // 清单内每个 dbType 必须可双向反查
        for (String dbType : LOCKED_DB_TYPES) {
            ImportKindDescriptor kind = ImportKindDescriptor.findDbType(dbType);
            assertNotNull(kind, "清单中的 dbType 无法反查: " + dbType);
            assertEquals(kind, ImportKindDescriptor.findRoute(kind.route()));
        }
    }

    @Test
    void ledgerRoutingOnlyCoversBundledLedgerTypes() {
        for (String dbType : LEDGER_DB_TYPES) {
            assertTrue(ImportKindDescriptor.isLedgerDbType(dbType), dbType + " 应归入台账路由");
        }
        for (String dbType : LOCKED_DB_TYPES) {
            if (!LEDGER_DB_TYPES.contains(dbType)) {
                assertFalse(ImportKindDescriptor.isLedgerDbType(dbType), dbType + " 不应归入台账路由");
            }
        }
        assertFalse(ImportKindDescriptor.isLedgerDbType(null));
        assertFalse(ImportKindDescriptor.isLedgerDbType("UNKNOWN"));
    }

    @Test
    void ledgerKindsAreBundledLedgerDomainOnly() {
        for (ImportKindDescriptor kind : ImportKindDescriptor.ledgerKinds()) {
            assertEquals(KindDomain.LEDGER, kind.domain());
            assertTrue(LEDGER_DB_TYPES.contains(kind.dbType()), kind + " 非台账类却出现在 ledgerKinds");
        }
    }

    @Test
    void orchestratesFlagOnlyOnOutbound() {
        for (ImportKindDescriptor kind : ImportKindDescriptor.values()) {
            assertEquals(kind == ImportKindDescriptor.OUTBOUND, kind.orchestrates(),
                    kind + " 的编排标记变化：新增编排点必须同步确认幂等与顺序");
        }
        assertEquals(KindDomain.BUSINESS_FACT, ImportKindDescriptor.OUTBOUND.domain());
        assertEquals(KindDomain.LEDGER, ImportKindDescriptor.CATALOG.domain());
        assertEquals(KindDomain.LEDGER, ImportKindDescriptor.STOCK.domain());
        assertEquals(KindDomain.LEDGER, ImportKindDescriptor.DEALER.domain());
    }
}
