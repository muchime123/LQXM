package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpBusinessException;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpErrorCode;
import com.liqiang.archive.erp.ErpIdempotencyService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 制造执行段「M0 契约 + 全链路落账」真库验证（报工 → 自动放行 → 交付收货 → 冲销）。
 *
 * <p><b>要证明的三件事</b>（对应决策登记 #12 无键不直通、B15 同连接同事务、DA7 记操作人）：
 * <ol>
 *   <li><b>幂等</b>：同键同载荷重放 → 复用首次结果、不重复落账；同键异载荷 → 报冲突而不是静默覆盖；</li>
 *   <li><b>审计</b>：每个写动作在 {@code row_change_log} 留行，操作人必填（employee_id / 姓名快照）；</li>
 *   <li><b>事务边界</b>：业务失败时幂等键与业务行<b>一起</b>回滚（同键可安全重试），
 *       不存在「键写进去了但业务没落」或「业务落了但键没进」。</li>
 * </ol>
 *
 * <p><b>隔离边界</b>：只操作专用测试 schema {@code mfg_contract_test}（建/删由本测试自管），
 * 表结构由 {@code CREATE TABLE … LIKE product_archive.*} 复制（真 DDL、真唯一约束，不含外键），
 * 工序/制程模板主数据从正式库复制一份。**不触碰 product_archive 与 erp 的任何业务行。**
 * 除 {@link #keyAndBusinessWriteShareOneTransaction()} 用独立连接验证真实提交/回滚语义外，
 * 其余用例都在单连接事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ManufacturingExecutionContractMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ManufacturingExecutionContractMysqlTest {

    private static final String SCHEMA = "mfg_contract_test";
    /** 3 道工序的短模板。V318 后全部工序 requires_inspection=0 → 报工完成即自动放行。 */
    private static final String TEMPLATE = "STANDARD_SEALER";
    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "李强";

    private static final List<String> TABLES = List.of(
            "manufacturing_orders", "manufacturing_operations", "manufacturing_process_templates",
            "manufacturing_process_template_operations", "manufacturing_order_operations",
            "manufacturing_work_reports", "manufacturing_inspections", "manufacturing_reworks",
            "manufacturing_completion_receipts", "manufacturing_order_outputs",
            "manufacturing_completion_receipt_items",
            "finished_goods_lots", "finished_goods_movements",
            "warehouses", "employees", "sales_products",
            "product_inventory_balances", "product_inventory_transactions");
    private static final List<String> SEEDED = List.of(
            "manufacturing_operations", "manufacturing_process_templates", "manufacturing_process_template_operations");

    private static String baseUrl;
    private static String dbUser;
    private static String dbPassword;
    private static Connection conn;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager txm;

    private ManufacturingExecutionService execution;
    private ManufacturingService manufacturing;

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
            // M0 契约表在 erp schema；测试 schema 内按同款 DDL 复制一份（含唯一键，冲突分支才可测）。
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");
            for (String t : SEEDED) st.execute("INSERT INTO " + SCHEMA + "." + t + " SELECT * FROM product_archive." + t);
            // 收货需要一个有效仓库（complete() 会校验 active=1）
            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active) VALUES('VERIFY-WH','契约验证仓',1)");
            // 建单必须绑 SKU（P3 ①）：产品身份只从 sales_products 取，不接受自由文本。
            // 主产品 CTL-SKU-1 + 配套模具 CTL-SKU-2，用于覆盖「一单多产出」（实测占 14.0%）。
            st.execute("INSERT INTO " + SCHEMA + ".sales_products(product_code,product_name,specification,unit) VALUES"
                    + "('CTL-SKU-1','契约验证产品','X1','台'),('CTL-SKU-2','契约验证模具','M1','套')");
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
                ACTOR_ID, ACTOR_NAME, "生产部", Set.of(), Set.of("manufacturing.report", "manufacturing.report.override"),
                "ALL", false, "COMPAT", null, null, Map.of()));
        // 数据范围过滤用 mock 置空：本测试只验证契约与落账，范围过滤另有归属。
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        execution = new ManufacturingExecutionService(jdbc, users, idem, changeLog, actors);
        manufacturing = new ManufacturingService(jdbc, new ProductInventoryService(jdbc, users), users, scope,
                execution, new ManufacturingTaskTemplateResolver(jdbc), idem, changeLog, actors);
    }

    // ---------- 用例 1：全链路（建单 → 报工 → 自动放行 → 收货 → 冲销）幂等且留痕 ----------

    @Test
    void fullLoopIsIdempotentAndAudited() {
        runInRollbackTransaction(() -> {
            long templateId = template(TEMPLATE);
            long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='VERIFY-WH'", Long.class);

            // 1) 建单：同键重放必须返回同一个工单，而不是再建一张
            var createRequest = new ManufacturingService.CreateOrder(null, null, "契约验证客户", "契约验证产品",
                    product("CTL-SKU-1"), "X1", null, new BigDecimal("10"), null, "契约验证", "SAMPLE", templateId, null, null, null);
            long orderId = manufacturing.create(createRequest, "ctl-loop-order-create");
            assertEquals(orderId, (long) manufacturing.create(createRequest, "ctl-loop-order-create"),
                    "同键重放必须复用首次结果，不得重复建单");
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_orders WHERE customer_name_snapshot='契约验证客户'"),
                    "重放不得产生第二张工单");
            String orderNo = jdbc.queryForObject("SELECT order_no FROM manufacturing_orders WHERE id=?", String.class, orderId);

            // 2) 工序随建单展开（同一请求键内），共 3 道
            List<Long> ops = jdbc.queryForList(
                    "SELECT id FROM manufacturing_order_operations WHERE manufacturing_order_id=? ORDER BY sequence_no",
                    Long.class, orderId);
            assertEquals(3, ops.size(), TEMPLATE + " 应为 3 道工序");

            // 3) 报工：同键重放不重复落账；同键换载荷报冲突（不能借旧键拿到旧结果）
            long reportId = execution.report(orderId, ops.get(0), report(10), "ctl-loop-report-01");
            assertEquals(reportId, (long) execution.report(orderId, ops.get(0), report(10), "ctl-loop-report-01"),
                    "同键重放必须返回首次报工单 id");
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_work_reports WHERE manufacturing_order_operation_id=" + ops.get(0)),
                    "同一次报工重放不得落第二行");

            var conflict = assertThrows(ErpBusinessException.class,
                    () -> execution.report(orderId, ops.get(0), report(5), "ctl-loop-report-01"),
                    "同一请求键提交不同内容必须报冲突");
            assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, conflict.errorCode());
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_work_reports WHERE manufacturing_order_operation_id=" + ops.get(0)),
                    "冲突请求不得落任何行");

            // 4) 收尾两道工序 → 无检验工序，自动放行至待入库（V318 闸门）
            execution.report(orderId, ops.get(1), report(10), "ctl-loop-report-02");
            execution.report(orderId, ops.get(2), report(10), "ctl-loop-report-03");
            Map<String, Object> released = order(orderId);
            assertEquals("PENDING_RECEIPT", released.get("status"), "工序全部报工且无检验工序时应直接进待入库");
            assertEquals("AUTO", released.get("inspection_source"));
            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_inspections"), "自动确认不得伪造检验事实");

            // 5) 交付收货：同键重放不得重复入库（否则会多出成品批次与库存流水）
            //    P3 起收货按产出清单逐项确认，不再手打成品名
            long outputId = jdbc.queryForObject(
                    "SELECT id FROM manufacturing_order_outputs WHERE manufacturing_order_id=? AND is_primary=1",
                    Long.class, orderId);
            var receiptRequest = new ManufacturingService.CompleteOrder(warehouseId, null,
                    List.of(new ManufacturingService.ReceiptLine(outputId, new BigDecimal("10"), new BigDecimal("100"), "契约验证")),
                    "契约验证");
            long receiptId = manufacturing.complete(orderId, receiptRequest, "ctl-loop-receipt-post");
            assertEquals(receiptId, (long) manufacturing.complete(orderId, receiptRequest, "ctl-loop-receipt-post"),
                    "同键重放必须复用首次入库单");
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_completion_receipts WHERE manufacturing_order_id=" + orderId));
            assertEquals(1, count("SELECT COUNT(*) FROM finished_goods_lots WHERE manufacturing_order_id=" + orderId));
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_completion_receipt_items WHERE receipt_id=" + receiptId),
                    "收货明细必须落一行（主产品）");
            assertEquals(1, count("SELECT COUNT(*) FROM finished_goods_lots WHERE manufacturing_order_id=" + orderId
                            + " AND manufacturing_order_output_id=" + outputId),
                    "批次必须挂回产出清单行 —— 否则「工单→清单→批次→出库」血缘断在这里");
            assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) jdbc.queryForMap(
                            "SELECT received_quantity FROM manufacturing_order_outputs WHERE id=?",
                            outputId).get("received_quantity")),
                    "产出清单必须累计已收数量（收货校验与差异展示都靠它）");
            assertEquals(1, count("SELECT COUNT(*) FROM finished_goods_movements WHERE movement_type='PRODUCTION_RECEIPT'"
                    + " AND source_type='COMPLETION_RECEIPT' AND source_id=" + receiptId));
            assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) order(orderId).get("completed_quantity")));
            assertEquals("COMPLETED", order(orderId).get("status"));

            // 6) 反入库：同键重放不得重复冲销
            manufacturing.reverseReceipt(receiptId, "契约验证冲销", "ctl-loop-receipt-reverse");
            manufacturing.reverseReceipt(receiptId, "契约验证冲销", "ctl-loop-receipt-reverse");
            assertEquals(1, count("SELECT COUNT(*) FROM finished_goods_movements WHERE movement_type='PRODUCTION_RECEIPT_REVERSAL'"
                    + " AND source_type='COMPLETION_RECEIPT_REVERSAL' AND source_id=" + receiptId),
                    "重放不得生成第二条反向流水");
            assertEquals("REVERSED", jdbc.queryForObject(
                    "SELECT status FROM manufacturing_completion_receipts WHERE id=?", String.class, receiptId));
            assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) order(orderId).get("completed_quantity")),
                    "反入库必须回退工单已入库数量");

            // 7) 幂等键：6 个动作各 1 行，操作人必填且落到发起人
            //    按 ctl-loop- 前缀计数：本类的另一用例会用独立连接真实提交键行，不能全表计数
            assertEquals(6, count("SELECT COUNT(*) FROM idem_request WHERE request_key LIKE 'ctl-loop-%'"),
                    "6 次唯一动作 = 6 个幂等键，重放不新增");
            assertEquals(0, count("SELECT COUNT(*) FROM idem_request WHERE request_key LIKE 'ctl-loop-%'"
                    + " AND (created_by IS NULL OR created_by<>" + ACTOR_ID + ")"), "幂等键必须记录发起人");
            List<String> actions = jdbc.queryForList(
                    "SELECT DISTINCT action FROM idem_request WHERE request_key LIKE 'ctl-loop-%' ORDER BY action", String.class);
            assertEquals(List.of(
                    "manufacturing.completionReceipt.post",
                    "manufacturing.completionReceipt.reverse",
                    "manufacturing.order.create",
                    "manufacturing.workReport.submit"), actions);

            // 8) 行级审计：动作齐全、带原因码与操作人姓名快照
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log WHERE operator_id IS NULL OR operator_id<>" + ACTOR_ID
                    + " OR operator_name<>'" + ACTOR_NAME + "'"), "审计行必须携带操作人");
            List<String> auditedTables = jdbc.queryForList(
                    "SELECT DISTINCT target_table FROM row_change_log ORDER BY target_table", String.class);
            assertTrue(auditedTables.contains("product_archive.manufacturing_orders"), "建单/收货/冲销都要审计工单行：" + auditedTables);
            assertTrue(auditedTables.contains("product_archive.manufacturing_work_reports"), "报工要审计：" + auditedTables);
            assertTrue(auditedTables.contains("product_archive.manufacturing_completion_receipts"), "入库单要审计：" + auditedTables);
            // 冲销一次要留两条行：被冲的入库单 + 被回退的工单，两条都要带结构化原因码与原文原因
            assertEquals(1, count("SELECT COUNT(*) FROM row_change_log WHERE reason_code='REVERSAL'"
                            + " AND change_reason='契约验证冲销'"
                            + " AND target_table='product_archive.manufacturing_completion_receipts'"),
                    "入库单冲销必须带结构化原因码 + 原文原因");
            assertEquals(1, count("SELECT COUNT(*) FROM row_change_log WHERE reason_code='REVERSAL'"
                            + " AND change_reason='契约验证冲销'"
                            + " AND target_table='product_archive.manufacturing_orders'"),
                    "工单回退数量也必须留痕并带原因");
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log WHERE request_key IS NULL"),
                    "审计行必须能关联回请求键，否则无法与幂等键对账");
            // changed_fields 必须是真实内容，不能是「缺 Java 8 时间模块」触发的降级摘要
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log"
                            + " WHERE JSON_EXTRACT(changed_fields,'$._serializationError') IS NOT NULL"),
                    "审计载荷不得静默降级为序列化失败摘要");
            // 工单表被写 5 次：建单 INSERT + 展开工序 UPDATE + 自动放行 UPDATE + 收货 UPDATE + 冲销 UPDATE
            // （方案 A 之后补上了「自动放行」这条 —— 它原先只写 audit_logs，不在这本账里）
            assertEquals(5, count("SELECT COUNT(*) FROM row_change_log WHERE target_table='product_archive.manufacturing_orders'"),
                    "工单的每一次状态/数量变更都必须留痕");
            assertNotNull(orderNo);
        });
    }

    // ---------- 用例 2：业务失败的请求不落半张单，且同键可安全重试 ----------

    @Test
    void failedRequestLeavesNothingAndKeyStaysRetryable() {
        runInRollbackTransaction(() -> {
            long templateId = template(TEMPLATE);
            long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='VERIFY-WH'", Long.class);
            var createRequest = new ManufacturingService.CreateOrder(null, null, "契约重试客户", "契约重试产品",
                    product("CTL-SKU-1"), null, null, new BigDecimal("10"), null, "契约验证", "SAMPLE", templateId, null, null, null);
            long orderId = manufacturing.create(createRequest, "ctl-retry-create");
            for (long opId : jdbc.queryForList("SELECT id FROM manufacturing_order_operations WHERE manufacturing_order_id=? ORDER BY sequence_no",
                    Long.class, orderId)) {
                execution.report(orderId, opId, report(10), "ctl-retry-report-" + opId);
            }

            // 第一次用「超量」入库 → 业务校验失败（P3：按产出清单项校验，数量超过该产出的计划）
            long retryOutput = jdbc.queryForObject(
                    "SELECT id FROM manufacturing_order_outputs WHERE manufacturing_order_id=? AND is_primary=1",
                    Long.class, orderId);
            var tooMany = new ManufacturingService.CompleteOrder(warehouseId, null,
                    List.of(new ManufacturingService.ReceiptLine(retryOutput, new BigDecimal("999"), BigDecimal.ZERO, null)), null);
            assertThrows(IllegalArgumentException.class,
                    () -> manufacturing.complete(orderId, tooMany, "ctl-retry-receipt"));

            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_completion_receipts WHERE manufacturing_order_id=" + orderId),
                    "校验失败不得留下任何入库单");
            assertEquals(0, count("SELECT COUNT(*) FROM idem_request WHERE request_key='ctl-retry-receipt'"),
                    "业务失败必须连带回滚幂等键——否则客户重试会被当成重放，永久拿不到结果");
            assertEquals(0, count("SELECT COUNT(*) FROM row_change_log WHERE target_table='product_archive.manufacturing_completion_receipts'"),
                    "业务失败不得留下审计孤儿行");

            // 同一个请求键重试（换内容）必须成功，而不是被判定为「同键异载荷冲突」
            var ok = new ManufacturingService.CompleteOrder(warehouseId, null,
                    List.of(new ManufacturingService.ReceiptLine(retryOutput, new BigDecimal("10"), BigDecimal.ZERO, null)), null);
            long receiptId = manufacturing.complete(orderId, ok, "ctl-retry-receipt");
            assertTrue(receiptId > 0, "回滚后的请求键必须可再次使用");
        });
    }

    // ---------- 用例 4：一单多产出（主产品 + 配套模具）逐项落批次，冲销各按自己数量回退 ----------

    @Test
    void multiOutputReceiptLandsOneLotPerLineAndReversesPerLine() {
        runInRollbackTransaction(() -> {
            long templateId = template(TEMPLATE);
            long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='VERIFY-WH'", Long.class);
            long primarySku = product("CTL-SKU-1");
            long moldSku = product("CTL-SKU-2");

            // 建单：主产品 10 台 + 配套模具 2 套（实测 14.0% 的工单产出多个 SKU）
            var createRequest = new ManufacturingService.CreateOrder(null, null, "多产出客户", "契约验证产品",
                    primarySku, "X1", null, new BigDecimal("10"), null, "契约验证", "SAMPLE", templateId, null,
                    List.of(new ManufacturingService.OutputLine(moldSku, new BigDecimal("2"), true, "配套模具")), null);
            long orderId = manufacturing.create(createRequest, "ctl-multi-create");

            assertEquals(2, count("SELECT COUNT(*) FROM manufacturing_order_outputs WHERE manufacturing_order_id=" + orderId),
                    "产出清单应为 2 行（主产品 + 模具）");
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_order_outputs WHERE manufacturing_order_id=" + orderId
                    + " AND is_primary=1"), "主产品行有且仅有一行（部分唯一索引兜底）");
            long primaryOutput = jdbc.queryForObject("SELECT id FROM manufacturing_order_outputs WHERE manufacturing_order_id=?"
                    + " AND is_primary=1", Long.class, orderId);
            long moldOutput = jdbc.queryForObject("SELECT id FROM manufacturing_order_outputs WHERE manufacturing_order_id=?"
                    + " AND is_primary=0", Long.class, orderId);

            for (long opId : jdbc.queryForList("SELECT id FROM manufacturing_order_operations WHERE manufacturing_order_id=?"
                    + " ORDER BY sequence_no", Long.class, orderId)) {
                execution.report(orderId, opId, report(10), "ctl-multi-report-" + opId);
            }

            // 收货：两项一次收完
            long receiptId = manufacturing.complete(orderId, new ManufacturingService.CompleteOrder(warehouseId, null,
                    List.of(new ManufacturingService.ReceiptLine(primaryOutput, new BigDecimal("10"), new BigDecimal("50"), null),
                            new ManufacturingService.ReceiptLine(moldOutput, new BigDecimal("2"), new BigDecimal("8"), null)),
                    "多产出收货"), "ctl-multi-receipt");

            assertEquals(2, count("SELECT COUNT(*) FROM finished_goods_lots WHERE manufacturing_order_id=" + orderId),
                    "每个产出各落一个批次");
            assertEquals(2, count("SELECT COUNT(*) FROM manufacturing_completion_receipt_items WHERE receipt_id=" + receiptId),
                    "收货明细按清单逐项落账");
            assertEquals(2, count("SELECT COUNT(*) FROM finished_goods_movements WHERE source_type='COMPLETION_RECEIPT'"
                    + " AND source_id=" + receiptId), "每个产出各一笔成品流水");
            assertEquals(2, count("SELECT COUNT(*) FROM product_inventory_transactions WHERE source_type='COMPLETION_RECEIPT'"
                    + " AND source_id=" + receiptId), "每个产出各一笔统一库存流水");
            // 工单数量**只按主产品**判定（配套件/模具收几件不影响"这张单做完没有"）
            assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) order(orderId).get("completed_quantity")),
                    "工单已入库数量只应含主产品，不含模具");
            assertEquals("COMPLETED", order(orderId).get("status"));
            assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) jdbc.queryForMap(
                            "SELECT received_quantity FROM manufacturing_order_outputs WHERE id=?", moldOutput)
                    .get("received_quantity")), "产出清单各自累计已收数量");

            // 反入库：逐批次按**自己那一份**回退（沿用头部汇总值会重复扣减）
            manufacturing.reverseReceipt(receiptId, "多产出冲销", "ctl-multi-reverse");
            assertEquals(2, count("SELECT COUNT(*) FROM finished_goods_movements WHERE source_type='COMPLETION_RECEIPT_REVERSAL'"
                    + " AND source_id=" + receiptId), "每个批次各一笔反向流水");
            assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) order(orderId).get("completed_quantity")),
                    "反入库只回退主产品数量");
            assertEquals(0, new BigDecimal("0").compareTo((BigDecimal) jdbc.queryForMap(
                            "SELECT received_quantity FROM manufacturing_order_outputs WHERE id=?", moldOutput)
                    .get("received_quantity")), "模具的已收数量也必须归零");
            assertEquals(0, count("SELECT COUNT(*) FROM product_inventory_balances WHERE warehouse_id=" + warehouseId
                            + " AND quantity<>0 AND canonical_product_id IN (" + primarySku + "," + moldSku + ")"),
                    "收货再冲销后两个产出的余额都应回到 0");
        });
    }

    // ---------- 用例 3：幂等键与业务写入同连接同事务（B15 直测，独立连接看真实提交/回滚） ----------

    @Test
    void keyAndBusinessWriteShareOneTransaction() throws Exception {
        // 必须用独立连接：外层测试事务是「参与式」的，标记 rollback-only 后行仍可见，
        // 观测不到真实的回滚语义。
        try (Connection own = DriverManager.getConnection(baseUrl, dbUser, dbPassword)) {
            var source = new SingleConnectionDataSource(own, true);
            var ownJdbc = new JdbcTemplate(source);
            var ownTxm = new DataSourceTransactionManager(source);
            ownJdbc.execute("USE " + SCHEMA);
            var idem = new ErpIdempotencyService(ownJdbc, ownTxm, "idem_request");

            assertThrows(IllegalStateException.class,
                    () -> idem.execute(new ErpActor(ACTOR_ID, ACTOR_NAME), "ctl-atomic-1", "ctl.atomic",
                            Map.of("k", "v"), () -> {
                                ownJdbc.update("""
                                        INSERT INTO manufacturing_orders(order_no,customer_name_snapshot,
                                          product_name_snapshot,planned_quantity,unit)
                                        VALUES('MO-ATOMIC-ROLLBACK','原子性验证客户','原子性验证产品',1,'台')
                                        """);
                                throw new IllegalStateException("业务体故意失败");
                            }),
                    "业务体异常必须向上抛出，不能吞掉");

            // 用一条全新的连接读：跳过旧连接的 REPEATABLE READ 快照
            try (Connection check = DriverManager.getConnection(baseUrl, dbUser, dbPassword)) {
                var checkJdbc = new JdbcTemplate(new SingleConnectionDataSource(check, true));
                checkJdbc.execute("USE " + SCHEMA);
                assertEquals(0, checkJdbc.queryForObject(
                        "SELECT COUNT(*) FROM manufacturing_orders WHERE order_no='MO-ATOMIC-ROLLBACK'", Integer.class),
                        "业务失败必须连带回滚业务写入");
                assertEquals(0, checkJdbc.queryForObject(
                        "SELECT COUNT(*) FROM idem_request WHERE request_key='ctl-atomic-1'", Integer.class),
                        "业务失败必须连带回滚幂等键（B15：回滚则键不存在，可安全重试）");
            }

            // 反向：键写入失败（同键并发抢先）时，业务写入也必须一并回滚
            var first = new ErpIdempotencyService(ownJdbc, ownTxm, "idem_request");
            first.execute(new ErpActor(ACTOR_ID, ACTOR_NAME), "ctl-atomic-2", "ctl.atomic.first",
                    Map.of("k", "v"), () -> "first-result");
            var conflicting = assertThrows(ErpBusinessException.class,
                    () -> first.execute(new ErpActor(ACTOR_ID, ACTOR_NAME), "ctl-atomic-2", "ctl.atomic.second",
                            Map.of("k", "v2"), () -> "second-result"));
            assertEquals(ErpErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT, conflicting.errorCode());
        }
    }

    // ---------- helpers ----------

    private void runInRollbackTransaction(Runnable body) {
        var status = txm.getTransaction(new DefaultTransactionDefinition());
        try {
            body.run();
        } finally {
            txm.rollback(status);
        }
    }

    private static ManufacturingExecutionService.WorkReportRequest report(int quantity) {
        return new ManufacturingExecutionService.WorkReportRequest(new BigDecimal(quantity), null, null, false, null);
    }

    private long template(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM manufacturing_process_templates WHERE template_code=?", Long.class, code);
        assertNotNull(id, "正式库缺少制程模板 " + code + "，本用例的主数据假定不成立");
        return id;
    }

    /**
     * 取测试产品 id。
     *
     * <p>P3（V322）起建单**必须绑 SKU** —— 产品身份只从 {@code sales_products} 取，
     * 不存在「传一个名字」的入参了，所以测试也得先有产品行。
     */
    private long product(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?", Long.class, code);
        assertNotNull(id, "测试产品不存在：" + code + "（prepareSchema 的种子已失效）");
        return id;
    }

    /** 工单原始行：{@code queryForMap} 的键是**列名原样**（snake_case），不是服务返回体的驼峰。 */
    private Map<String, Object> order(long orderId) {
        return jdbc.queryForMap("SELECT * FROM manufacturing_orders WHERE id=?", orderId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? -1 : value;
    }
}
