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
 * 销售订单审批关口（决策 #35）的**真库契约测试**。
 *
 * <p><b>为什么要它</b>：LQ 的要求是「销售订单需要审批，审价格、审填得对不对」，而改造前
 * 销售订单的 {@code confirm} 是**单步动作**（草稿直接跳 CONFIRMED）—— 也就是说，
 * 只要 {@code POST /{id}/confirm} 还在，审批就是可绕过的：不审也能推进下游。
 * 本测试钉死三件事：
 * <ol>
 *   <li><b>提交前的校验不许松</b>：每行都得定下供货方式；自产行还要把产品配置填全填对 ——
 *       不合格的单子在**离开业务员之前**就挡住，而不是先发给老板再退回；</li>
 *   <li><b>未审批 ⇒ 下游不可用</b>：草稿/待审批状态一律不能生成出库草稿（审批是前置关口，不是事后补签）；</li>
 *   <li><b>驳回后重提必须清掉上一轮审批决定</b>：这条在采购订单上**真实发生过缺陷**
 *       （提交时把提交人写进了 {@code approval_decided_by_employee_id}，待审批的单上挂着一个
 *       并不存在的审批人）。销售订单走同一个 {@code ErpApprovalService}，同款断言必须一起钉住。</li>
 * </ol>
 *
 * <p><b>隔离边界</b>：只操作专用测试 schema {@code so_contract_test}（建/删由本测试自管），
 * 表结构用 {@code CREATE TABLE … LIKE product_archive.*} 复制真 DDL。
 * <b>不触碰 product_archive 与 erp 的任何业务行</b>；用例在事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ErpSalesOrderApprovalContractMysqlTest -Derp.foundation.mysql=true}
 * （不带 {@code -Derp.foundation.mysql=true} 会被静默跳过）。
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ErpSalesOrderApprovalContractMysqlTest {

    private static final String SCHEMA = "so_contract_test";
    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "契约验证员";

    private static final String CUSTOMER_NAME = "契约验证客户";
    private static final String PRODUCT_NAME = "契约验证泵";
    private static final String PRODUCT_UNIT = "只";

    private static final List<String> TABLES = List.of(
            "employees", "sales_products", "warehouses",
            "product_inventory_balances", "product_inventory_transactions",
            "erp_sales_orders", "erp_sales_order_items",
            "erp_purchase_orders", "erp_purchase_order_items",
            "erp_sales_outbounds", "erp_sales_outbound_items",
            "manufacturing_orders", "erp_approvals");

    private static String baseUrl;
    private static String dbUser;
    private static String dbPassword;
    private static Connection conn;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager txm;
    private ErpSalesOrderService service;
    private ErpApprovalService approvals;
    private long productId;
    private long warehouseId;

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

            st.execute("INSERT INTO " + SCHEMA + ".sales_products(product_code,product_name,specification,unit,source,status)"
                    + " VALUES('SO-CTL-SKU','" + PRODUCT_NAME + "','10*10','" + PRODUCT_UNIT + "','H3',1)");
            st.execute("UPDATE " + SCHEMA + ".sales_products SET canonical_product_id=id WHERE product_code='SO-CTL-SKU'");
            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active)"
                    + " VALUES('SO-CTL-WH','销售契约验证仓',1)");
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
                ACTOR_ID, ACTOR_NAME, "销售部", Set.of(), Set.of("sales_order.manage"),
                "ALL", false, "COMPAT", null, null, Map.of()));
        // 数据范围过滤用 mock 置空：本测试只验证审批契约，范围过滤另有归属
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        var inventory = new ProductInventoryService(jdbc, users);
        var approvals = new ErpApprovalService(jdbc, actors, idem, changeLog);
        this.approvals = approvals;
        var outbounds = new SalesOutboundService(jdbc, inventory, users, scope, idem, changeLog, actors);
        // 本测试只走「建单 → 提交 → 审批」，不碰接单建任务单那条路，故把制造服务置桩
        service = new ErpSalesOrderService(jdbc, actors, scope, idem, changeLog, outbounds,
                new ObjectMapper(), mock(ManufacturingService.class), approvals);

        productId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code='SO-CTL-SKU'", Long.class);
        warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='SO-CTL-WH'", Long.class);
    }

    // ==================== 用例 1：提交前的校验不许松，提交后停在待审批 ====================

    @Test
    void submitGateRejectsUndecidedAndUnconfiguredLines() {
        runInRollbackTransaction(() -> {
            // ---- 1) 供货方式没定 → 不许提交（「填得对不对」的第一道） ----
            long undecided = createOrder("UNDECIDED", null, "so-ctl-u-create");
            var undecidedError = assertThrows(IllegalStateException.class,
                    () -> service.submit(undecided, "so-ctl-u-submit"),
                    "明细没定供货方式就不该提交审批");
            assertTrue(String.valueOf(undecidedError.getMessage()).contains("供货方式"),
                    "提示应点明供货方式：" + undecidedError.getMessage());
            assertEquals("DRAFT", status(undecided), "被挡下后必须仍是草稿");
            assertEquals("NONE", approvalStatus(undecided), "被挡下不得留下审批痕");
            assertEquals(0, count("SELECT COUNT(*) FROM erp_approvals"), "校验失败不得写审批流水");

            // ---- 2) 自产行没填产品配置 → 不许提交 ----
            long makeNoConfig = createOrder("MAKE", productId, "so-ctl-m-create");
            var makeError = assertThrows(IllegalStateException.class,
                    () -> service.submit(makeNoConfig, "so-ctl-m-submit"),
                    "自产明细没配产品配置就不该提交审批");
            assertTrue(String.valueOf(makeError.getMessage()).contains("产品配置"),
                    "提示应点明产品配置：" + makeError.getMessage());
            assertEquals("DRAFT", status(makeNoConfig));

            // ---- 3) 填全了 → 提交通过，停在待审批 ----
            long ok = createOrder("BUY", productId, "so-ctl-ok-create");
            service.submit(ok, "so-ctl-ok-submit");
            assertEquals("PENDING_APPROVAL", status(ok), "提交后必须停在待审批");
            assertEquals("PENDING", approvalStatus(ok));
            assertEquals(ACTOR_ID, jdbc.queryForObject(
                    "SELECT approval_submitted_by_employee_id FROM erp_sales_orders WHERE id=?",
                    Long.class, ok).longValue(), "提交人必须落库");
            assertNull(jdbc.queryForObject(
                    "SELECT approval_decided_by_employee_id FROM erp_sales_orders WHERE id=?", Object.class, ok),
                    "提交只该写提交人 —— 决定人必须留空（否则待审批单上会挂一个并不存在的审批人）");
            assertEquals("XSDD" + String.format("%08d", ok), jdbc.queryForObject(
                    "SELECT order_no FROM erp_sales_orders WHERE id=?", String.class, ok));

            // 待审批不得重复提交
            assertThrows(IllegalStateException.class, () -> service.submit(ok, "so-ctl-ok-submit-again"),
                    "待审批状态不能重复提交");
        });
    }

    // ==================== 用例 2：未审批 ⇒ 下游不可用 ====================

    @Test
    void approvalGateKeepsDownstreamBlockedUntilApproved() {
        runInRollbackTransaction(() -> {
            long orderId = createOrder("STOCK", productId, "so-ctl-gate-create");
            long itemId = firstItemId(orderId);

            // ---- 草稿：出库草稿必须被拒（审批是执行的前置关口） ----
            var draftBlocked = assertThrows(IllegalStateException.class,
                    () -> service.createOutboundDraft(orderId, itemId, warehouseId, BigDecimal.ONE, "so-ctl-ob0"),
                    "未审批的销售订单不得生成出库草稿");
            assertTrue(String.valueOf(draftBlocked.getMessage()).contains("已确认"),
                    "提示应点明需先确认：" + draftBlocked.getMessage());

            // ---- 待审批：一样不能出库 ----
            service.submit(orderId, "so-ctl-gate-submit");
            assertEquals("PENDING_APPROVAL", status(orderId));
            assertThrows(IllegalStateException.class,
                    () -> service.createOutboundDraft(orderId, itemId, warehouseId, BigDecimal.ONE, "so-ctl-ob1"),
                    "待审批状态一样不得生成出库草稿");

            // ---- 审批通过：状态关口放行（后续能否出库取决于库存/采购，但不再是「状态」拦的） ----
            service.approve(orderId, "同意", "so-ctl-gate-approve");
            assertEquals("CONFIRMED", status(orderId));
            assertEquals("APPROVED", approvalStatus(orderId));
            assertNotNull(jdbc.queryForObject(
                    "SELECT confirmed_at FROM erp_sales_orders WHERE id=?", Object.class, orderId),
                    "通过后必须落确认时间（DocType 注册表的 approveExtraSet）");
            assertEquals(ACTOR_ID, jdbc.queryForObject(
                    "SELECT confirmed_by_employee_id FROM erp_sales_orders WHERE id=?",
                    Long.class, orderId).longValue(), "确认人必须落库");

            String afterApprove = null;
            try {
                service.createOutboundDraft(orderId, itemId, warehouseId, BigDecimal.ONE, "so-ctl-ob2");
            } catch (Exception e) {
                afterApprove = String.valueOf(e.getMessage());
            }
            assertFalse(afterApprove != null && afterApprove.contains("只有已确认销售订单"),
                    "通过后不该再被状态拦下，实际：" + afterApprove);

            // ---- 待审批清单：通过后不再出现 ----
            assertTrue(approvals.pending().stream().noneMatch(r -> ((Number) r.get("docId")).longValue() == orderId),
                    "已通过的单不应留在审批待办里");
        });
    }

    // ==================== 用例 3：驳回退回草稿；重提必须清掉上一轮审批决定 ====================

    @Test
    void rejectReturnsToDraftAndResubmitClearsPreviousDecision() {
        runInRollbackTransaction(() -> {
            long orderId = createOrder("BUY", productId, "so-ctl-rj-create");
            service.submit(orderId, "so-ctl-rj-submit");
            assertEquals("PENDING_APPROVAL", status(orderId));

            // ---- 1) 驳回原因必填 ----
            var noReason = assertThrows(IllegalArgumentException.class,
                    () -> service.reject(orderId, "   ", "so-ctl-rj-bad"),
                    "驳回必须填写原因");
            assertTrue(String.valueOf(noReason.getMessage()).contains("原因"));
            assertEquals("PENDING_APPROVAL", status(orderId), "驳回失败不得改变状态");

            // ---- 2) 有原因 → 退回草稿 ----
            service.reject(orderId, "价格需再谈", "so-ctl-rj-reject");
            assertEquals("DRAFT", status(orderId), "驳回必须退回草稿（可改可再提）");
            assertEquals("REJECTED", approvalStatus(orderId));
            assertEquals(ACTOR_ID, jdbc.queryForObject(
                    "SELECT approval_decided_by_employee_id FROM erp_sales_orders WHERE id=?",
                    Long.class, orderId).longValue(), "驳回时记录决定人");

            // ---- 3) 重新提交：必须清掉上一轮的审批结论 ----
            service.submit(orderId, "so-ctl-rj-resubmit");
            assertEquals("PENDING_APPROVAL", status(orderId));
            assertEquals("PENDING", approvalStatus(orderId));
            assertNull(jdbc.queryForObject(
                    "SELECT approval_decided_by_employee_id FROM erp_sales_orders WHERE id=?", Object.class, orderId),
                    "重提后上一轮的审批人必须被清空（否则审批人栏显示的是上一轮的人）");
            assertNull(jdbc.queryForObject(
                    "SELECT approval_decided_at FROM erp_sales_orders WHERE id=?", Object.class, orderId),
                    "重提后上一轮的审批时间也必须被清空");

            // ---- 4) 通过 ----
            service.approve(orderId, "同意销售", "so-ctl-rj-approve");
            assertEquals("CONFIRMED", status(orderId));
            assertEquals("APPROVED", approvalStatus(orderId));

            // ---- 5) 审批流水 4 条，逐次留痕（驳回几次都看得到） ----
            var history = service.approvalHistory(orderId);
            assertEquals(4, history.size(), "应为 提交/驳回/重提/通过 四条");
            assertEquals(List.of("SUBMIT", "REJECT", "SUBMIT", "APPROVE"),
                    history.stream().map(r -> String.valueOf(r.get("action"))).toList());
            assertEquals("价格需再谈", history.get(1).get("comment"), "驳回原因必须落流水");
            assertEquals(ACTOR_NAME, history.get(1).get("actorName"), "操作人姓名快照");
            assertEquals("XSDD" + String.format("%08d", orderId), history.getFirst().get("docNo"),
                    "单号快照必须落库（流水对账靠它）");
        });
    }

    // ==================== 用例 4：草稿不能直接审批；空明细不能提交；同键重放幂等 ====================

    @Test
    void draftCannotBeApprovedDirectlyAndSubmitIsIdempotent() {
        runInRollbackTransaction(() -> {
            long orderId = createOrder("BUY", productId, "so-ctl-d-create");

            // ---- 1) 草稿直接审批 → 拒（等于废掉了「草稿直跳已确认」的绕过口子） ----
            var tooEarly = assertThrows(IllegalStateException.class,
                    () -> service.approve(orderId, "跳过提交", "so-ctl-d-approve"),
                    "草稿不能被审批 —— 否则审批形同虚设");
            assertTrue(String.valueOf(tooEarly.getMessage()).contains("待审批"),
                    "提示应点明不在待审批状态：" + tooEarly.getMessage());
            assertEquals("DRAFT", status(orderId));

            // ---- 2) 空明细不能提交（防御性：正常建单要求 1~200 行，这里直接抽掉明细） ----
            long emptyOrder = createOrder("BUY", productId, "so-ctl-e-create");
            jdbc.update("DELETE FROM erp_sales_order_items WHERE order_id=?", emptyOrder);
            var empty = assertThrows(IllegalStateException.class,
                    () -> service.submit(emptyOrder, "so-ctl-e-submit"),
                    "没有明细的订单不该能提交审批");
            assertTrue(String.valueOf(empty.getMessage()).contains("明细"), empty.getMessage());

            // ---- 3) 同键重放：复用首次结果，不产生第二条流水 ----
            service.submit(orderId, "so-ctl-i-submit");
            assertEquals(1, count("SELECT COUNT(*) FROM erp_approvals WHERE doc_type='SALES_ORDER'"));

            service.submit(orderId, "so-ctl-i-submit");
            assertEquals(1, count("SELECT COUNT(*) FROM erp_approvals WHERE doc_type='SALES_ORDER'"),
                    "同键重放不得重复写流水");

            // ---- 4) 已通过不能重复审批 ----
            service.approve(orderId, "同意", "so-ctl-i-approve");
            assertThrows(IllegalStateException.class, () -> service.approve(orderId, null, "so-ctl-i-approve-again"),
                    "已确认不能重复审批");
        });
    }

    // ==================== 辅助 ====================

    private long createOrder(String supplyMode, Long salesProductId, String key) {
        var line = new ErpSalesOrderService.Line(null, PRODUCT_NAME, "10*10", salesProductId, supplyMode,
                PRODUCT_UNIT, new BigDecimal("3"), new BigDecimal("120"), LocalDate.now().plusDays(7), null, null);
        return service.create(new ErpSalesOrderService.Request(null, CUSTOMER_NAME, LocalDate.now(),
                LocalDate.now().plusDays(7), "审批契约验证", List.of(line)), key);
    }

    private long firstItemId(long orderId) {
        return jdbc.queryForObject("SELECT id FROM erp_sales_order_items WHERE order_id=? ORDER BY line_no LIMIT 1",
                Long.class, orderId);
    }

    private String status(long orderId) {
        return jdbc.queryForObject("SELECT status FROM erp_sales_orders WHERE id=?", String.class, orderId);
    }

    private String approvalStatus(long orderId) {
        return jdbc.queryForObject("SELECT approval_status FROM erp_sales_orders WHERE id=?", String.class, orderId);
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
