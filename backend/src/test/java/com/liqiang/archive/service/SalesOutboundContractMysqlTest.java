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
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 销售出库单（P2）+ 库存主轴 canonical 口径（P1 / #28 Q1）的**真库契约测试**。
 *
 * <p>它守的是四件在纯单测里守不住、在 HTTP 端到端里又不够持久的事：
 * <ol>
 *   <li><b>扣的是 canonical 余额行</b>：用 {@code MFG-} 业务身份出库，必须扣到它所归一的标准 SKU 上——
 *       这是 Q1「保留 MFG- 编码、但不让它分裂库存」的落地判据；</li>
 *   <li><b>状态机与库存同生共死</b>：草稿不动库存；过账才扣；冲销回补；终态不可再过账；</li>
 *   <li><b>M0 契约</b>：{@code X-Request-Key} 重放复用首次结果、同键换内容报冲突、每次动作在
 *       {@code row_change_log} 留行且携带操作人与请求键；</li>
 *   <li><b>审计单通道（方案 A）</b>：只写 {@code row_change_log}，身份 = {@code target_table}
 *       （schema 限定表名，物理唯一）；不再写 {@code audit_logs} —— 后者以自由字符串 {@code entity_type}
 *       作身份，两个模块对同名概念用同一个词就会静默撞名。</li>
 * </ol>
 *
 * <p><b>隔离边界</b>：只操作专用测试 schema {@code so_contract_test}（建/删由本测试自管），
 * 表结构 {@code CREATE TABLE … LIKE product_archive.*} 复制真 DDL（含唯一约束，不含外键）。
 * **不触碰 product_archive 与 erp 的任何业务行**；用例在事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=SalesOutboundContractMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class SalesOutboundContractMysqlTest {

    private static final String SCHEMA = "so_contract_test";
    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "李强";

    /** 标准 SKU（氚云来源，external_id 非空）。 */
    private static final String H3_CODE = "H3-CTL-BOX";
    /** 订单专属产出身份（MFG-），归一 → 上面的标准 SKU。 */
    private static final String MFG_CODE = "MFG-CTL-0001";

    private static final List<String> TABLES = List.of(
            "sales_products", "warehouses", "product_inventory_balances", "product_inventory_transactions",
            "erp_sales_outbounds", "erp_sales_outbound_items");

    private static String baseUrl;
    private static String dbUser;
    private static String dbPassword;
    private static Connection conn;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager txm;
    private SalesOutboundService service;
    private long warehouseId;
    private long h3ProductId;
    private long mfgProductId;

    // ==================== 测试环境：专用 schema ====================

    @BeforeAll
    static void prepareSchema() throws Exception {
        var env = new HashMap<String, String>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int i = line.indexOf('=');
            if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        var uri = java.net.URI.create(env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive").substring(5));
        dbUser = env.getOrDefault("DB_USERNAME", "root");
        dbPassword = env.getOrDefault("DB_PASSWORD", "");
        baseUrl = "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort()) + "/";
        conn = DriverManager.getConnection(baseUrl, dbUser, dbPassword);
        try (Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " DEFAULT CHARSET utf8mb4");
            for (String t : TABLES) st.execute("CREATE TABLE " + SCHEMA + "." + t + " LIKE product_archive." + t);
            // M0 契约表在 erp schema；测试 schema 内按同款 DDL 复制（含唯一键，冲突分支才可测）
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");

            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active)"
                    + " VALUES('SO-CTL-WH','出库契约验证仓',1)");
            // 标准 SKU：先归自身
            st.execute("INSERT INTO " + SCHEMA + ".sales_products(product_code,product_name,specification,unit,source,status,external_id)"
                    + " VALUES('" + H3_CODE + "','契约验证盒','10*10','只','H3',1,888888)");
            st.execute("UPDATE " + SCHEMA + ".sales_products SET canonical_product_id=id WHERE product_code='" + H3_CODE + "'");
            // MFG- 身份：归一到标准 SKU（这正是 Q1 的归一口径）
            st.execute("INSERT INTO " + SCHEMA + ".sales_products(product_code,product_name,specification,unit,source,status)"
                    + " VALUES('" + MFG_CODE + "','契约验证盒','10*10','只','MANUFACTURING','PENDING_REVIEW')");
            st.execute("UPDATE " + SCHEMA + ".sales_products SET canonical_product_id="
                    + "(SELECT id FROM (SELECT id FROM " + SCHEMA + ".sales_products WHERE product_code='" + H3_CODE + "') x)"
                    + " WHERE product_code='" + MFG_CODE + "'");
            // 库存只挂在 canonical（标准 SKU）行上，MFG- 自己没有余额行
            st.execute("INSERT INTO " + SCHEMA + ".product_inventory_balances(warehouse_id,sales_product_id,canonical_product_id,unit,quantity)"
                    + " SELECT w.id,p.id,p.id,'只',100 FROM " + SCHEMA + ".warehouses w CROSS JOIN " + SCHEMA + ".sales_products p"
                    + " WHERE w.warehouse_code='SO-CTL-WH' AND p.product_code='" + H3_CODE + "'");
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
        // 数据范围过滤用 mock 置空：本测试只验证契约与落账，范围过滤另有归属
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        service = new SalesOutboundService(jdbc, new ProductInventoryService(jdbc, users), users, scope, idem,
                changeLog, actors);

        warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='SO-CTL-WH'", Long.class);
        h3ProductId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?", Long.class, H3_CODE);
        mfgProductId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?", Long.class, MFG_CODE);
    }

    // ==================== 用例 1：全链路（建单→改单→过账→冲销）扣 canonical 且留痕 ====================

    @Test
    void fullLoopDeductsCanonicalBalanceAndStaysAudited() {
        runInRollbackTransaction(() -> {
            // ---- 1) 建单：用 MFG- 业务身份出库，同键重放必须复用首次单据 ----
            var request = request(new BigDecimal("10"), new BigDecimal("12.5"));
            long id = service.create(request, "so-ctl-create");
            assertEquals(id, (long) service.create(request, "so-ctl-create"),
                    "同键重放必须复用首次结果，不得重复建单");
            assertEquals(1, count("SELECT COUNT(*) FROM erp_sales_outbounds"), "重放不得产生第二张出库单");
            assertEquals("DRAFT", status(id), "建单只能是草稿");
            assertEquals(0, canonicalQty().compareTo(new BigDecimal("100")), "草稿不得动库存");

            // 明细同时携带业务身份与库存聚合口径：这是「追溯是哪张单产出」与「库存算在哪个 SKU」的分工
            Map<String, Object> line = jdbc.queryForMap(
                    "SELECT sales_product_id,canonical_product_id,product_name_snapshot,quantity FROM erp_sales_outbound_items WHERE outbound_id=?", id);
            assertEquals(mfgProductId, ((Number) line.get("sales_product_id")).longValue(), "明细必须记业务身份 MFG-");
            assertEquals(h3ProductId, ((Number) line.get("canonical_product_id")).longValue(), "明细必须记归一口径");

            // ---- 2) 同键换内容 → 必须报冲突（不能借旧键拿到旧结果） ----
            var conflict = assertThrows(ErpBusinessException.class,
                    () -> service.create(request(new BigDecimal("11"), new BigDecimal("12.5")), "so-ctl-create"),
                    "同一请求键提交不同内容必须报冲突");
            assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, conflict.errorCode());
            assertEquals(1, count("SELECT COUNT(*) FROM erp_sales_outbounds"), "冲突请求不得落第二张单");

            // ---- 3) 改单：only DRAFT，且 payload 带 LocalDate（第二条会踩日期序列化的写路径） ----
            long updated = service.update(id, request(new BigDecimal("12"), new BigDecimal("10")), "so-ctl-update");
            assertEquals(id, updated);
            // DECIMAL 列读回来带标度（120.0000），必须按数值比不能用 equals
            assertEquals(0, jdbc.queryForObject("SELECT total_amount FROM erp_sales_outbounds WHERE id=?", BigDecimal.class, id)
                    .compareTo(new BigDecimal("120")), "改单必须重算金额（12 × 10）");
            assertEquals(1, count("SELECT COUNT(*) FROM erp_sales_outbound_items WHERE outbound_id=" + id),
                    "整体替换明细后仍应只有 1 行");

            // ---- 4) 过账：扣的是 canonical 行，而非 MFG- 自己的行 ----
            service.post(id, "so-ctl-post");
            assertEquals("POSTED", status(id));
            assertEquals(0, canonicalQty().compareTo(new BigDecimal("88")), "过账必须扣减 canonical 余额行（100-12）");
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_transactions"
                            + " WHERE source_type='ERP_SALES_OUTBOUND' AND source_id=" + id),
                    "过账必须写且只写一条出库流水");
            Map<String, Object> tx = jdbc.queryForMap("SELECT sales_product_id,canonical_product_id,quantity_change,balance_after"
                    + " FROM product_inventory_transactions WHERE source_type='ERP_SALES_OUTBOUND' AND source_id=?", id);
            assertEquals(mfgProductId, ((Number) tx.get("sales_product_id")).longValue(),
                    "流水保留业务身份，供追溯「这笔货是哪张单产出的」");
            assertEquals(h3ProductId, ((Number) tx.get("canonical_product_id")).longValue(),
                    "流水同时记归一口径，供按 SKU 汇总");
            assertEquals(0, ((BigDecimal) tx.get("quantity_change")).compareTo(new BigDecimal("-12")));

            // 重放过账不得重复扣减
            service.post(id, "so-ctl-post");
            assertEquals(0, canonicalQty().compareTo(new BigDecimal("88")), "同键重放过账不得重复扣减");

            // 已过账不能改单
            assertThrows(IllegalStateException.class,
                    () -> service.update(id, request(new BigDecimal("5"), BigDecimal.ONE), "so-ctl-update-after-post"));

            // ---- 5) 冲销：回补库存，且为终态 ----
            service.reverse(id, "契约验证冲销", "so-ctl-reverse");
            service.reverse(id, "契约验证冲销", "so-ctl-reverse");
            assertEquals("REVERSED", status(id));
            assertEquals("契约验证冲销", jdbc.queryForObject(
                    "SELECT reversal_reason FROM erp_sales_outbounds WHERE id=?", String.class, id));
            assertEquals(0, canonicalQty().compareTo(new BigDecimal("100")), "冲销必须把库存补回原值");
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_transactions"
                            + " WHERE source_type='ERP_SALES_OUTBOUND_REVERSAL' AND source_id=" + id),
                    "重放不得生成第二条反向流水");
            var afterReverse = assertThrows(IllegalStateException.class, () -> service.post(id, "so-ctl-post-again"),
                    "已冲销是终态，不能重新过账");
            assertTrue(String.valueOf(afterReverse.getMessage()).contains("新建一张"), "提示应引导新建一张：" + afterReverse.getMessage());

            // ---- 6) 幂等键：4 个唯一动作，操作人必填 ----
            assertEquals(4, count("SELECT COUNT(*) FROM idem_request WHERE request_key LIKE 'so-ctl-%'"),
                    "4 次唯一动作 = 4 个幂等键，重放不新增");
            assertEquals(0, count("SELECT COUNT(*) FROM idem_request WHERE request_key LIKE 'so-ctl-%'"
                    + " AND (created_by IS NULL OR created_by<>" + ACTOR_ID + ")"), "幂等键必须记录发起人");
            assertEquals(List.of(
                            "sales.outbound.create",
                            "sales.outbound.post",
                            "sales.outbound.reverse",
                            "sales.outbound.update"),
                    jdbc.queryForList("SELECT DISTINCT action FROM idem_request ORDER BY action", String.class));

            // ---- 7) 行级审计：4 次写动作各留一行，带操作人与请求键，且载荷未降级 ----
            assertEquals(4, count("SELECT COUNT(*) FROM row_change_log"
                    + " WHERE target_table='product_archive.erp_sales_outbounds'"), "每一次状态/内容变更都必须留痕");
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log WHERE operator_id IS NULL OR operator_id<>" + ACTOR_ID
                    + " OR operator_name<>'" + ACTOR_NAME + "'"), "审计行必须携带操作人");
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log WHERE request_key IS NULL"),
                    "审计行必须能关联回请求键，否则无法与幂等键对账");
            // 过账/冲销的 changed_fields 带 LocalDateTime，建单/改单带 LocalDate：
            // 一旦「缺 Java 8 时间模块」，审计内容会静默退化成一条无信息量的错误桩
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log"
                            + " WHERE JSON_EXTRACT(changed_fields,'$._serializationError') IS NOT NULL"),
                    "审计载荷不得静默降级为序列化失败摘要");

            // ---- 8) 审计单通道（方案 A）：只写 row_change_log，身份 = schema 限定表名 ----
            // 旧写法断言 audit.log("ErpSalesOutbound", …) 被调用 —— 那是「改名避让」时代的产物：
            // 靠加前缀躲开与氚云台账在 SalesOutbound 这个实体名下的撞名。正解是不写那个通道，
            // 身份改由 target_table 表达（表名物理唯一，撞不了）。
            // 见 docs/审计实体身份治理与撞名防治-20260921.md
            assertEquals(1, count("SELECT COUNT(DISTINCT target_table) FROM row_change_log"
                            + " WHERE target_table LIKE '%erp_sales_outbounds'"),
                    "出库单审计只能有一个身份（target_table = schema 限定表名）");
            assertTrue(count("SELECT COUNT(*) FROM row_change_log"
                            + " WHERE target_table='product_archive.erp_sales_outbounds'") >= 3,
                    "建单 / 改单 / 过账 / 冲销各应留下一条审计");
            assertTrue(count("SELECT COUNT(*) FROM row_change_log"
                            + " WHERE target_table='product_archive.erp_sales_outbounds'"
                            + " AND operator_id IS NOT NULL AND operator_id > 0") >= 3,
                    "每条 ERP 审计必须带操作人（决策 #14：不接受可空操作人）");
        });
    }

    // ==================== 用例 2：超量出库整体回滚，单据保持草稿且同键可安全重试 ====================

    @Test
    void overSellRollsBackWholeRequestAndKeyStaysRetryable() {
        runInRollbackTransaction(() -> {
            var tooMany = request(new BigDecimal("999"), new BigDecimal("12.5"));
            long id = service.create(tooMany, "so-ctl2-create");

            RuntimeException e = assertThrows(RuntimeException.class, () -> service.post(id, "so-ctl2-post"),
                    "库存不足必须拒绝过账");
            assertTrue(String.valueOf(e.getMessage()).contains("库存不足"),
                    "预检报错要讲清是哪个产品差多少：" + e.getMessage());

            assertEquals("DRAFT", status(id), "过账失败后单据必须仍是草稿（事务未落库）");
            assertEquals(0, canonicalQty().compareTo(new BigDecimal("100")), "被拒的过账不得扣减库存");
            assertEquals(0, count("SELECT COUNT(*) FROM product_inventory_transactions"), "被拒的过账不得留下流水");
            assertEquals(0, count("SELECT COUNT(*) FROM idem_request WHERE request_key='so-ctl2-post'"),
                    "失败动作的幂等键必须一并回滚，否则同键重试会被误判为重放");

            // 同键重试必须能真正重放（而不是被残留的键挡成「已处理」）
            service.update(id, request(new BigDecimal("20"), new BigDecimal("12.5")), "so-ctl2-update");
            service.post(id, "so-ctl2-post");
            assertEquals("POSTED", status(id), "改正数量后同键必须可以真正执行");
            assertEquals(0, canonicalQty().compareTo(new BigDecimal("80")), "应扣 20");
        });
    }

    // ==================== 辅助 ====================

    private SalesOutboundService.OutboundRequest request(BigDecimal quantity, BigDecimal unitPrice) {
        return new SalesOutboundService.OutboundRequest(null, "契约验证客户", warehouseId, "SO-CTL-REF",
                LocalDate.of(2026, 9, 21), "契约验证",
                List.of(new SalesOutboundService.OutboundLine(mfgProductId, quantity, unitPrice, null)));
    }

    /** canonical（标准 SKU）余额。 */
    private BigDecimal canonicalQty() {
        return jdbc.queryForObject("SELECT quantity FROM product_inventory_balances"
                + " WHERE warehouse_id=? AND canonical_product_id=? AND unit='只'", BigDecimal.class, warehouseId, h3ProductId);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM erp_sales_outbounds WHERE id=?", String.class, id);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? -1 : n;
    }

    private void runInRollbackTransaction(Runnable body) {
        var status = txm.getTransaction(new DefaultTransactionDefinition());
        try {
            body.run();
        } finally {
            txm.rollback(status);
        }
    }
}
