package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.*;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.DataScopeSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 外采采购订单（BUY 履约）的**真库契约测试** —— 2026-09-24 活体验证暴露的两个「必修级」缺陷的回归防线。
 *
 * <p>为什么需要它：这两个缺陷在当时的 339 条单测里**全绿通过**，却在第一次真实 HTTP 调用下必然失败——
 * 因为它们只存在于「拼出来的 SQL 字符串」里，纯单测不碰 SQL、端到端又不会每次都跑。它们分别是：
 * <ol>
 *   <li><b>建单 INSERT 的列数与值数错位</b>：{@code VALUES(?,?,'DRAFT',?,…)} 比列清单多一个占位符，
 *       MySQL 直接报 1136，采购订单**一张也建不出来**。← 用例 1 的 create 断言即为其回归防线；</li>
 *   <li><b>销售明细的关联列写错了表</b>：{@code erp_sales_outbounds} <b>没有</b> {@code sales_order_item_id}
 *       列（该列在**明细表** {@code erp_sales_outbound_items} 上，见 {@code ErpSalesOrderService} 既有写法）。
 *       写它 → 代发建单抛 SQL 异常；读它 → 采购单详情接口在解析阶段就整条 500。
 *       ← 用例 2 同时钉住「写」与「读」两个方向。</li>
 * </ol>
 *
 * <p><b>隔离边界</b>：只操作专用测试 schema {@code po_contract_test}（建/删由本测试自管），
 * 表结构用 {@code CREATE TABLE … LIKE product_archive.*} 复制真 DDL（含唯一键 {@code uk_epo_sales_order_item}，
 * 不含外键）。**不触碰 product_archive 与 erp 的任何业务行**；用例在事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ErpPurchaseOrderContractMysqlTest -Derp.foundation.mysql=true}
 * （不带 {@code -Derp.foundation.mysql=true} 会被静默跳过）。
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ErpPurchaseOrderContractMysqlTest {

    private static final String SCHEMA = "po_contract_test";
    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "契约验证员";

    private static final String SUPPLIER_NAME = "契约验证供应商";
    private static final String PRODUCT_NAME = "契约验证泵";
    private static final String PRODUCT_SPEC = "10*10";
    private static final String PRODUCT_UNIT = "只";

    private static final List<String> TABLES = List.of(
            "warehouses", "employees", "sales_products",
            "product_inventory_balances", "product_inventory_transactions",
            "erp_sales_orders", "erp_sales_order_items",
            "erp_purchase_orders", "erp_purchase_order_items",
            "erp_purchase_receipts", "erp_purchase_receipt_items",
            "erp_sales_outbounds", "erp_sales_outbound_items",
            "h3_supplier_catalog");

    private static String baseUrl;
    private static String dbUser;
    private static String dbPassword;
    private static Connection conn;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager txm;
    private ErpPurchaseOrderService service;
    private SalesOutboundService outbounds;
    private long warehouseId;
    private long productId;
    private long supplierId;
    private int lineSeq;

    // ==================== 测试环境：专用 schema ====================

    @BeforeAll
    static void prepareSchema() throws Exception {
        var env = new HashMap<String, String>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int i = line.indexOf('=');
            if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        var uri = java.net.URI.create(env.getOrDefault("SPRING_DATASOURCE_URL",
                "jdbc:mysql://localhost:3306/product_archive").substring(5));
        dbUser = env.getOrDefault("DB_USERNAME", "root");
        dbPassword = env.getOrDefault("DB_PASSWORD", "");
        baseUrl = "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort()) + "/";
        conn = DriverManager.getConnection(baseUrl, dbUser, dbPassword);
        try (Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " DEFAULT CHARSET utf8mb4");
            for (String t : TABLES) st.execute("CREATE TABLE " + SCHEMA + "." + t + " LIKE product_archive." + t);
            // M0 幂等/审计契约表在 erp schema，按同款 DDL 复制（含唯一键，重放分支才可测）
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");

            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active)"
                    + " VALUES('PO-CTL-WH','采购契约验证仓',1)");
            st.execute("INSERT INTO " + SCHEMA + ".sales_products(product_code,product_name,specification,unit,source,status)"
                    + " VALUES('PO-CTL-SKU','" + PRODUCT_NAME + "','" + PRODUCT_SPEC + "','" + PRODUCT_UNIT + "','H3',1)");
            st.execute("UPDATE " + SCHEMA + ".sales_products SET canonical_product_id=id WHERE product_code='PO-CTL-SKU'");
            st.execute("INSERT INTO " + SCHEMA + ".h3_supplier_catalog(external_id,name,normalized_name)"
                    + " VALUES('PO-CTL-SUP','" + SUPPLIER_NAME + "','" + SUPPLIER_NAME + "')");
            st.execute("USE " + SCHEMA);
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
        conn.close();
    }

    @BeforeEach
    void setUp() {
        var source = new SingleConnectionDataSource(conn, true);
        jdbc = new JdbcTemplate(source);
        txm = new DataSourceTransactionManager(source);
        var users = mock(CurrentUserService.class);
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(
                ACTOR_ID, ACTOR_NAME, "采购部", Set.of(), Set.of("purchase_order.manage", "sales_order.manage"),
                "ALL", false, "COMPAT", null, null, Map.of()));
        // 数据范围过滤用 mock 置空：本测试只验证契约与落账，范围过滤另有归属
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        var inventory = new ProductInventoryService(jdbc, users);
        outbounds = new SalesOutboundService(jdbc, inventory, users, scope, idem, changeLog, actors);
        service = new ErpPurchaseOrderService(jdbc, actors, scope, idem, changeLog, inventory, outbounds);

        warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='PO-CTL-WH'", Long.class);
        productId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code='PO-CTL-SKU'", Long.class);
        supplierId = jdbc.queryForObject("SELECT id FROM h3_supplier_catalog WHERE external_id='PO-CTL-SUP'", Long.class);
        lineSeq = 0;
    }

    // ==================== 用例 1：建单→确认→分次到货→冲正，列口径与账实一致 ====================

    @Test
    void createConfirmReceiveReverseKeepsColumnsAndLedgerConsistent() {
        runInRollbackTransaction(() -> {
            // ---- 1) 建单：这条断言就是「INSERT 列数必须等于值数」的回归防线 ----
            //      列/值错位时 MySQL 报 1136，下面任何一行都到不了。
            long poId = service.create(warehouseRequest(new BigDecimal("10"), new BigDecimal("5"), null), "po-ctl-create");
            assertEquals(poId, (long) service.create(warehouseRequest(new BigDecimal("10"), new BigDecimal("5"), null), "po-ctl-create"),
                    "同键重放必须复用首次结果，不得重复建单");
            assertEquals(1, count("SELECT COUNT(*) FROM erp_purchase_orders"), "重放不得产生第二张采购订单");

            Map<String, Object> head = jdbc.queryForMap("SELECT po_no,status,supplier_id,supplier_name_snapshot,"
                    + "warehouse_id,fulfillment_mode,total_amount,created_by_employee_id"
                    + " FROM erp_purchase_orders WHERE id=?", poId);
            assertEquals("CGDD" + String.format("%08d", poId), head.get("po_no"), "单号格式 CGDD%08d");
            assertEquals("DRAFT", head.get("status"), "建单只能是草稿");
            assertEquals(supplierId, ((Number) head.get("supplier_id")).longValue());
            assertEquals(SUPPLIER_NAME, head.get("supplier_name_snapshot"), "供应商名必须落快照");
            assertEquals(warehouseId, ((Number) head.get("warehouse_id")).longValue());
            assertEquals("WAREHOUSE", head.get("fulfillment_mode"), "未指定时按入库型");
            assertEquals(0, ((BigDecimal) head.get("total_amount")).compareTo(new BigDecimal("50")), "金额 = 10 × 5");
            assertEquals(ACTOR_ID, ((Number) head.get("created_by_employee_id")).longValue(), "建单人必填（行级数据范围靠它）");

            Map<String, Object> item = jdbc.queryForMap("SELECT line_no,sales_product_id,product_name_snapshot,"
                    + "specification_snapshot,unit,quantity,purchase_price,amount,received_quantity"
                    + " FROM erp_purchase_order_items WHERE po_id=?", poId);
            assertEquals(1, ((Number) item.get("line_no")).intValue(), "行号从 1 起");
            assertEquals(productId, ((Number) item.get("sales_product_id")).longValue());
            assertEquals(PRODUCT_UNIT, item.get("unit"), "业务键必须含单位");
            assertEquals(0, ((BigDecimal) item.get("received_quantity")).compareTo(BigDecimal.ZERO));

            // 同键换内容 → 必须报冲突，不能借旧键拿旧结果
            var conflict = assertThrows(ErpBusinessException.class,
                    () -> service.create(warehouseRequest(new BigDecimal("9"), new BigDecimal("5"), null), "po-ctl-create"),
                    "同一请求键提交不同内容必须报冲突");
            assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, conflict.errorCode());
            assertEquals(1, count("SELECT COUNT(*) FROM erp_purchase_orders"), "冲突请求不得落第二张单");

            // ---- 2) 确认 ----
            service.confirm(poId, "po-ctl-confirm");
            assertEquals("CONFIRMED", status(poId));
            assertNotNull(jdbc.queryForObject("SELECT confirmed_at FROM erp_purchase_orders WHERE id=?", Object.class, poId));
            assertThrows(IllegalStateException.class, () -> service.confirm(poId, "po-ctl-confirm-again"),
                    "已确认不能重复确认");

            // ---- 3) 分次到货：余额与累计到货量同步，全到即结案 ----
            long poItemId = poItemId(poId);
            long receipt1 = service.receive(poId, new ErpPurchaseOrderService.ReceiveRequest(warehouseId,
                    List.of(new ErpPurchaseOrderService.ReceiveItem(poItemId, new BigDecimal("6"))), "首批"),
                    "po-ctl-recv1");
            assertTrue(receipt1 > 0);
            assertEquals("RECEIVING", status(poId), "部分到货 = 在途");
            assertEquals(0, balanceQty().compareTo(new BigDecimal("6")), "到货必须写本地库存账");
            assertEquals(0, receivedQty(poId).compareTo(new BigDecimal("6")), "累计到货量必须回填明细");

            service.receive(poId, new ErpPurchaseOrderService.ReceiveRequest(warehouseId,
                    List.of(new ErpPurchaseOrderService.ReceiveItem(poItemId, new BigDecimal("4"))), "尾批"),
                    "po-ctl-recv2");
            assertEquals("CLOSED", status(poId), "全部到货自动结案");
            assertEquals(0, balanceQty().compareTo(new BigDecimal("10")));

            // 超量到货被拦（未到货量已归零）
            var over = assertThrows(IllegalStateException.class,
                    () -> service.receive(poId, new ErpPurchaseOrderService.ReceiveRequest(warehouseId,
                            List.of(new ErpPurchaseOrderService.ReceiveItem(poItemId, BigDecimal.ONE)), null), "po-ctl-recv-over"),
                    "超过未到货量必须拒绝");
            assertTrue(String.valueOf(over.getMessage()).contains("未到货") || String.valueOf(over.getMessage()).contains("可以办理"),
                    "提示应点明到货量或单据状态：" + over.getMessage());

            // ---- 4) 冲正：反向过账、累计量回退、单回到在途 ----
            long receipt2 = lastReceiptId(poId);
            service.reverseReceipt(poId, receipt2, "契约验证冲正", "po-ctl-rev2");
            assertEquals("REVERSED", jdbc.queryForObject(
                    "SELECT status FROM erp_purchase_receipts WHERE id=?", String.class, receipt2));
            assertEquals("契约验证冲正", jdbc.queryForObject(
                    "SELECT reversal_reason FROM erp_purchase_receipts WHERE id=?", String.class, receipt2));
            assertEquals("RECEIVING", status(poId), "还有一张有效到货单，应回到在途");
            assertEquals(0, balanceQty().compareTo(new BigDecimal("6")), "冲正必须回退库存");
            assertEquals(0, receivedQty(poId).compareTo(new BigDecimal("6")), "冲正必须回退累计到货量");

            // ---- 5) 流水：正负配对，来源类型可追溯 ----
            assertEquals(3, count("SELECT COUNT(*) FROM product_inventory_transactions"
                    + " WHERE canonical_product_id=" + productId), "到货2笔 + 冲正1笔");
            assertEquals(2, count("SELECT COUNT(*) FROM product_inventory_transactions"
                    + " WHERE source_type='ERP_PURCHASE_RECEIPT'"));
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_transactions"
                    + " WHERE source_type='ERP_PURCHASE_RECEIPT_REVERSAL'"));
            // 审计单通道：只写 row_change_log，不写 audit_logs
            assertTrue(count("SELECT COUNT(*) FROM row_change_log WHERE target_table='product_archive.erp_purchase_orders'") >= 2,
                    "采购订单动作必须在 row_change_log 留行（身份=物理表名）");
        });
    }

    // ==================== 用例 2：代发（DROP_SHIP）关联落在明细行且过账不动库存 ====================

    @Test
    void dropShipLinksSalesItemOnItemRowAndSkipsLocalStock() {
        runInRollbackTransaction(() -> {
            long orderId = insertOrder("XSDD-POCTL-1", "CONFIRMED");
            long salesItemId = insertSalesItem(orderId, "BUY");
            seedBalance(new BigDecimal("100"));

            long poId = service.create(new ErpPurchaseOrderService.Request(supplierId, SUPPLIER_NAME, null,
                    "DROP_SHIP", salesItemId, null, "代发契约验证",
                    List.of(new ErpPurchaseOrderService.Line(PRODUCT_NAME, PRODUCT_SPEC, productId, PRODUCT_UNIT,
                            new BigDecimal("2"), new BigDecimal("3"), null))), "po-ctl-ds-create");
            assertEquals("DROP_SHIP", jdbc.queryForObject(
                    "SELECT fulfillment_mode FROM erp_purchase_orders WHERE id=?", String.class, poId));
            service.confirm(poId, "po-ctl-ds-confirm");

            // 代发单不能走到货入库
            var rejected = assertThrows(IllegalStateException.class,
                    () -> service.receive(poId, new ErpPurchaseOrderService.ReceiveRequest(warehouseId,
                            List.of(new ErpPurchaseOrderService.ReceiveItem(poItemId(poId), BigDecimal.ONE)), null),
                            "po-ctl-ds-recv"),
                    "代发单不办理到货入库");
            assertTrue(String.valueOf(rejected.getMessage()).contains("代发"), "提示应点明代发：" + rejected.getMessage());

            // ---- 生成代发出库单：销售明细必须落在**明细行**（主表没有这一列） ----
            long outboundId = service.createDropShipOutbound(poId, "po-ctl-ds-out");
            Map<String, Object> out = jdbc.queryForMap("SELECT status,fulfillment_source,warehouse_id,sales_order_id"
                    + " FROM erp_sales_outbounds WHERE id=?", outboundId);
            assertEquals("DRAFT", out.get("status"));
            assertEquals("DROP_SHIP", out.get("fulfillment_source"), "过账口径由单据自身标记");
            assertNull(out.get("warehouse_id"), "代发不进本地仓，允许无仓库");
            assertEquals(orderId, ((Number) out.get("sales_order_id")).longValue(), "主表记销售订单");

            Long linkedItemId = jdbc.queryForObject(
                    "SELECT sales_order_item_id FROM erp_sales_outbound_items WHERE outbound_id=?",
                    Long.class, outboundId);
            assertNotNull(linkedItemId, "代发出库明细必须回指销售订单明细");
            assertEquals(salesItemId, linkedItemId.longValue(),
                    "⚠️ 关联列在 erp_sales_outbound_items 上，不是在主表上");

            // 详情接口的读取方向：修复前它会因主表无该列而在解析阶段整条 500
            @SuppressWarnings("unchecked")
            var detail = service.detail(poId);
            assertNotNull(detail.get("dropShipOutbound"), "采购单详情必须能查到代发出库单");

            // ---- 过账：只落状态，既不扣库存也不写库存流水 ----
            outbounds.post(outboundId, "po-ctl-ds-post");
            assertEquals("POSTED", jdbc.queryForObject(
                    "SELECT status FROM erp_sales_outbounds WHERE id=?", String.class, outboundId));
            assertEquals(0, balanceQty().compareTo(new BigDecimal("100")), "代发过账不得扣本地库存");
            assertEquals(0, count("SELECT COUNT(*) FROM product_inventory_transactions"
                    + " WHERE source_type='ERP_SALES_OUTBOUND'"), "代发过账不得写库存流水（不留伪行）");

            // ---- 冲正：只退状态，不回补库存（否则会凭空多出一批货） ----
            outbounds.reverse(outboundId, "契约验证冲销", "po-ctl-ds-rev");
            assertEquals("REVERSED", jdbc.queryForObject(
                    "SELECT status FROM erp_sales_outbounds WHERE id=?", String.class, outboundId));
            assertEquals(0, balanceQty().compareTo(new BigDecimal("100")), "代发冲正不得回补库存");
        });
    }

    // ==================== 用例 3：待采购需求只认 BUY，且销售明细与采购单 1:1 ====================

    @Test
    void pendingDemandsOnlyBuyAndOneToOneGuard() {
        runInRollbackTransaction(() -> {
            long orderId = insertOrder("XSDD-POCTL-2", "CONFIRMED");
            long buyItemId = insertSalesItem(orderId, "BUY");
            long makeItemId = insertSalesItem(orderId, "MAKE");

            var demands = service.pendingPurchaseDemands();
            assertEquals(1, demands.size(), "只有 BUY 明细进待采购");
            assertEquals(buyItemId, ((Number) demands.getFirst().get("itemId")).longValue());

            long poId = service.create(new ErpPurchaseOrderService.Request(supplierId, SUPPLIER_NAME, warehouseId,
                    "WAREHOUSE", buyItemId, null, "契约验证",
                    List.of(new ErpPurchaseOrderService.Line(PRODUCT_NAME, PRODUCT_SPEC, productId, PRODUCT_UNIT,
                            BigDecimal.ONE, new BigDecimal("5"), null))), "po-ctl-d1-create");
            assertEquals(0, service.pendingPurchaseDemands().size(), "已挂采购单的明细不再进待采购");

            // 1:1：唯一约束 + 前置校验，两层都要拦
            var dup = assertThrows(RuntimeException.class,
                    () -> service.create(new ErpPurchaseOrderService.Request(supplierId, SUPPLIER_NAME, warehouseId,
                            "WAREHOUSE", buyItemId, null, "契约验证",
                            List.of(new ErpPurchaseOrderService.Line(PRODUCT_NAME, PRODUCT_SPEC, productId, PRODUCT_UNIT,
                                    BigDecimal.ONE, new BigDecimal("5"), null))), "po-ctl-d1-dup"),
                    "同一销售明细不能挂第二张有效采购订单");
            assertTrue(String.valueOf(dup.getMessage()).contains("已有关联采购订单"), "提示应点明已有采购单：" + dup.getMessage());

            // 非 BUY 明细不得建采购单
            var notBuy = assertThrows(IllegalArgumentException.class,
                    () -> service.create(new ErpPurchaseOrderService.Request(supplierId, SUPPLIER_NAME, warehouseId,
                            "WAREHOUSE", makeItemId, null, "契约验证",
                            List.of(new ErpPurchaseOrderService.Line(PRODUCT_NAME, PRODUCT_SPEC, productId, PRODUCT_UNIT,
                                    BigDecimal.ONE, new BigDecimal("5"), null))), "po-ctl-d1-make"),
                    "只有外采明细可以建采购单");
            assertTrue(String.valueOf(notBuy.getMessage()).contains("外采"), "提示应点明外采：" + notBuy.getMessage());

            // 取消后释放 1:1 名额（CANCELLED 不占坑）
            service.cancel(poId, "契约验证取消", "po-ctl-d1-cancel");
            assertEquals("CANCELLED", status(poId));
            assertEquals(1, service.pendingPurchaseDemands().size(), "取消后该明细应重新可采购");
        });
    }

    // ==================== 辅助 ====================

    private ErpPurchaseOrderService.Request warehouseRequest(BigDecimal qty, BigDecimal price, Long salesItemId) {
        return new ErpPurchaseOrderService.Request(supplierId, SUPPLIER_NAME, warehouseId, "WAREHOUSE",
                salesItemId, LocalDate.now().plusDays(3), "契约验证",
                List.of(new ErpPurchaseOrderService.Line(PRODUCT_NAME, PRODUCT_SPEC, productId, PRODUCT_UNIT,
                        qty, price, null)));
    }

    private long insertOrder(String orderNo, String status) {
        jdbc.update("INSERT INTO erp_sales_orders(order_no,status,customer_name_snapshot,salesperson_id,"
                + "salesperson_name_snapshot,order_date) VALUES(?,?,?,?,?,?)",
                orderNo, status, "契约验证客户", ACTOR_ID, ACTOR_NAME, LocalDate.now());
        return jdbc.queryForObject("SELECT id FROM erp_sales_orders WHERE order_no=?", Long.class, orderNo);
    }

    private long insertSalesItem(long orderId, String supplyMode) {
        jdbc.update("INSERT INTO erp_sales_order_items(order_id,line_no,product_name_snapshot,supply_mode,"
                + "unit,quantity,sales_product_id,unit_price) VALUES(?,?,?,?,?,?,?,?)",
                orderId, ++lineSeq, PRODUCT_NAME, supplyMode, PRODUCT_UNIT, BigDecimal.ONE, productId,
                new BigDecimal("9"));
        return jdbc.queryForObject("SELECT id FROM erp_sales_order_items WHERE order_id=? AND line_no=?",
                Long.class, orderId, lineSeq);
    }

    private void seedBalance(BigDecimal qty) {
        jdbc.update("INSERT INTO product_inventory_balances(warehouse_id,sales_product_id,canonical_product_id,unit,quantity)"
                + " VALUES(?,?,?,?,?)", warehouseId, productId, productId, PRODUCT_UNIT, qty);
    }

    private long poItemId(long poId) {
        return jdbc.queryForObject("SELECT id FROM erp_purchase_order_items WHERE po_id=? ORDER BY line_no LIMIT 1",
                Long.class, poId);
    }

    private long lastReceiptId(long poId) {
        return jdbc.queryForObject("SELECT id FROM erp_purchase_receipts WHERE po_id=? ORDER BY id DESC LIMIT 1",
                Long.class, poId);
    }

    private String status(long poId) {
        return jdbc.queryForObject("SELECT status FROM erp_purchase_orders WHERE id=?", String.class, poId);
    }

    private BigDecimal receivedQty(long poId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(received_quantity),0) FROM erp_purchase_order_items WHERE po_id=?",
                BigDecimal.class, poId);
    }

    private BigDecimal balanceQty() {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM product_inventory_balances"
                + " WHERE warehouse_id=? AND canonical_product_id=?", BigDecimal.class, warehouseId, productId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private void runInRollbackTransaction(Runnable body) {
        var def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var status = txm.getTransaction(def);
        try {
            body.run();
        } finally {
            txm.rollback(status);
        }
    }
}
