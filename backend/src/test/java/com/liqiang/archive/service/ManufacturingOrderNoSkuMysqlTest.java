package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
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
import static org.mockito.Mockito.*;

/**
 * 「无 SKU 建单 → 报工 → 交付收货 → 冲销」全链路契约（2026-09-22 LQ 拍板）。
 *
 * <p><b>被钉死的口径</b>：09-22 起下单界面不再提供「可售产品（SKU）」—— SKU 是**完工入库的库存身份键**
 * （{@code finished_goods_lots} / {@code product_inventory_transactions} /
 * {@code product_inventory_balances} 的必填列），不是业务员下单字段。因此：
 * <ol>
 *   <li>建单不传 SKU 时，工单与产出清单主行的 SKU / 规范化身份 / 产品编码三列必须**留空**，
 *       成品身份退化为 {@code product_name_snapshot}（制程品类）；</li>
 *   <li>单位不再有 SKU 来源 → 未显式给时回落默认 **「台」**，显式给则以传入为准；</li>
 *   <li>收货时该行**只登记收货事实**：写收货明细（SKU 列可空，由 V331 放松），
 *       **不建成品批次、不写成品流水、不动统一库存余额**；</li>
 *   <li>工单状态机不受影响：仍按主产品累计收货量判定 COMPLETED / PARTIALLY_RECEIVED；</li>
 *   <li>无批次的收货单**必须可冲销** —— 回退数量改从收货明细取（不能依赖批次行）。</li>
 * </ol>
 *
 * <p><b>为什么这组断言必要</b>：SKU 退出建单后，「收货不落库存账」是唯一的静默风险点 ——
 * 一旦有人把 SKU 判空写错（NPE 或落成 0 号产品），库存余额会悄悄多出一笔无身份流水，
 * 而页面上看不出任何异常。所以这里断言的是**库存三表逐表为 0**，不是"接口没报错"。
 *
 * <p>隔离边界：只操作专用测试 schema {@code mfg_nosku_test}（建/删由本测试自管），
 * 表结构由 {@code CREATE TABLE ... LIKE product_archive.*} 复制（真 DDL、真唯一约束），
 * 工序与制程模板主数据从正式库复制一份；**不触碰 product_archive 任何业务行**。
 * 每个用例在单连接事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ManufacturingOrderNoSkuMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ManufacturingOrderNoSkuMysqlTest {

    private static final String SCHEMA = "mfg_nosku_test";
    /**
     * 在用制程模板 code —— **不写死**：09-23 氚云对齐（V332）把 STANDARD_SEALER（普通封口机）等旧模板停用，
     * 写死会让整个测试类**整类假失败**（实测已发生一次，2026-09-23）。改为在 {@link #prepareSchema()}
     * 里取「启用中工序数最多」的模板；工序数断言一律从模板现算。V318 后全部工序 requires_inspection=0
     * → 报工完成即自动放行。
     */
    private static String TEMPLATE;
    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "李强";
    /** 制程品类（= 建单必填的 processCategory，也是成品身份的退化替身）。 */
    private static final String CATEGORY = "契约无SKU品类";
    private static final String DEFAULT_UNIT = "台";

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
        String base = "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort()) + "/";
        conn = DriverManager.getConnection(base, env.getOrDefault("DB_USERNAME", "root"), env.getOrDefault("DB_PASSWORD", ""));
        try (Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " DEFAULT CHARSET utf8mb4");
            for (String t : TABLES) st.execute("CREATE TABLE " + SCHEMA + "." + t + " LIKE product_archive." + t);
            // M0 契约表在 erp schema，测试 schema 内按同款 DDL 复制一份
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");
            for (String t : SEEDED) st.execute("INSERT INTO " + SCHEMA + "." + t + " SELECT * FROM product_archive." + t);
            // 收货需要有效仓库（complete() 校验 active=1）
            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active) VALUES('NOSKU-WH','无SKU验证仓',1)");
            st.execute("USE " + SCHEMA);
            // 选在用制程模板（见 TEMPLATE 字段注释）：USE 之后用非限定名，与种子同一连接。
            try (var rs = st.executeQuery("SELECT t.template_code FROM manufacturing_process_templates t"
                    + " JOIN manufacturing_process_template_operations x ON x.template_id=t.id"
                    + " WHERE t.active=1 GROUP BY t.id,t.template_code ORDER BY COUNT(x.id) DESC, t.id LIMIT 1")) {
                assertTrue(rs.next(), "正式库里没有「启用且带工序」的制程模板，本类测试的前提不成立");
                TEMPLATE = rs.getString(1);
            }
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
        // 数据范围过滤用 mock 置空：本测试只验证落账口径，范围过滤另有归属。
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        execution = new ManufacturingExecutionService(jdbc, users, idem, changeLog, actors);
        manufacturing = new ManufacturingService(jdbc, new ProductInventoryService(jdbc, users), users, scope,
                execution, new ManufacturingTaskTemplateResolver(jdbc), idem, changeLog, actors);
    }

    // ---------- 用例 1：不传 SKU 建单 —— 工单与产出清单主行都没有库存身份 ----------

    @Test
    void createWithoutSkuLeavesNoInventoryIdentity() {
        runInRollbackTransaction(() -> {
            long orderId = createWithoutSku("nosku-create-1", null);

            Map<String, Object> ord = order(orderId);
            assertNull(ord.get("sales_product_id"), "未传 SKU 时工单不得有 SKU");
            assertEquals(CATEGORY, ord.get("product_name_snapshot"), "成品身份退化为制程品类名");
            assertEquals(DEFAULT_UNIT, ord.get("unit"), "未显式给单位时应回落默认「台」");

            Map<String, Object> primary = primaryOutput(orderId);
            assertNull(primary.get("sales_product_id"), "产出清单主行不得有 SKU");
            assertNull(primary.get("canonical_product_id"), "产出清单主行不得有规范化产品 id（库存聚合口径）");
            assertNull(primary.get("product_code_snapshot"), "产出清单主行不得有产品编码");
            assertEquals(CATEGORY, primary.get("product_name_snapshot"), "成品身份用制程品类名");
            assertEquals(DEFAULT_UNIT, primary.get("unit"));
            assertTrue(flag(primary.get("is_primary")), "主产品行必须带 is_primary=1");

            assertEquals(templateOperationCount(), count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=" + orderId),
                    TEMPLATE + " 的工序数应与模板一致（去 SKU 不影响制程解析）");
        });
    }

    // ---------- 用例 2：单位优先取显式传入 ----------

    @Test
    void explicitUnitWinsOverDefault() {
        runInRollbackTransaction(() -> {
            long orderId = createWithoutSku("nosku-create-2", "套");
            assertEquals("套", order(orderId).get("unit"), "显式传入的单位必须生效");
            assertEquals("套", primaryOutput(orderId).get("unit"), "产出清单行与工单单位必须一致（同一份口径，禁两处取值）");
        });
    }

    // ---------- 用例 3：无 SKU 可以建单，但完工入库必须匹配库存产品 ----------

    @Test
    void qualityCompletionRequiresExplicitProductMatch() {
        runInRollbackTransaction(() -> {
            long orderId = createWithoutSku("nosku-create-3", null);
            reportAllOperations(orderId, new BigDecimal("10"));
            assertEquals("PENDING_INSPECTION", order(orderId).get("status"));
            long outputId = outputIdOfPrimary(orderId);
            var inspection = new ManufacturingExecutionService.InspectionRequest(
                    "PASSED", new BigDecimal("10"), BigDecimal.ZERO, null, null, null, null);
            var inventory = new ManufacturingService.CompleteOrder(warehouseId(), null,
                    List.of(new ManufacturingService.ReceiptLine(outputId, new BigDecimal("10"), BigDecimal.ZERO, null)), null);
            assertThrows(IllegalStateException.class, () -> manufacturing.inspectAndComplete(orderId,
                    new ManufacturingService.QualityCompletion(inspection, inventory, null), "nosku-quality-3"));
            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_inspections WHERE manufacturing_order_id=" + orderId));
            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_completion_receipts WHERE manufacturing_order_id=" + orderId));

            jdbc.update("INSERT INTO sales_products(product_code,product_name,specification,unit) VALUES('NOSKU-MATCH-3','契约产品','X1','台')");
            long skuId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code='NOSKU-MATCH-3'", Long.class);
            long receiptId = manufacturing.inspectAndComplete(orderId,
                    new ManufacturingService.QualityCompletion(inspection, inventory,
                            List.of(new ManufacturingService.OutputMatch(outputId, skuId))), "nosku-quality-3");
            assertTrue(receiptId > 0);
            assertEquals("COMPLETED", order(orderId).get("status"));
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_inspections WHERE manufacturing_order_id=" + orderId));
            assertEquals(1, count("SELECT COUNT(*) FROM finished_goods_lots WHERE manufacturing_order_id=" + orderId));
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_transactions WHERE source_type='COMPLETION_RECEIPT' AND source_id=" + receiptId));
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_balances WHERE sales_product_id=" + skuId));
        });
    }

    // ---------- 用例 4：已入库时冲销必须回退库存，而非只回退工单数量 ----------

    @Test
    void reversalRestoresInventoryAfterQualityCompletion() {
        runInRollbackTransaction(() -> {
            long orderId = createWithoutSku("nosku-create-4", null);
            reportAllOperations(orderId, new BigDecimal("10"));
            long outputId = outputIdOfPrimary(orderId);
            jdbc.update("INSERT INTO sales_products(product_code,product_name,specification,unit) VALUES('NOSKU-MATCH-4','契约产品','X1','台')");
            long skuId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code='NOSKU-MATCH-4'", Long.class);
            long receiptId = manufacturing.inspectAndComplete(orderId, new ManufacturingService.QualityCompletion(
                    new ManufacturingExecutionService.InspectionRequest("PASSED", new BigDecimal("10"), BigDecimal.ZERO, null, null, null, null),
                    new ManufacturingService.CompleteOrder(warehouseId(), null,
                            List.of(new ManufacturingService.ReceiptLine(outputId, new BigDecimal("10"), BigDecimal.ZERO, null)), null),
                    List.of(new ManufacturingService.OutputMatch(outputId, skuId))), "nosku-quality-4");
            manufacturing.reverseReceipt(receiptId, "检验入库冲销", "nosku-reverse-4");
            assertEquals("REVERSED", jdbc.queryForObject("SELECT status FROM manufacturing_completion_receipts WHERE id=?", String.class, receiptId));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) order(orderId).get("completed_quantity")));
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_transactions WHERE source_type='COMPLETION_RECEIPT_REVERSAL' AND source_id=" + receiptId));
        });
    }
    // ---------- 用例 5：传了 SKU 的旧路径不受影响（兼容性回归）----------

    @Test
    void explicitSkuStillLandsInventory() {
        runInRollbackTransaction(() -> {
            jdbc.update("INSERT INTO sales_products(product_code,product_name,specification,unit) VALUES('NOSKU-SKU-1','契约带SKU产品','X1','台')");
            long skuId = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code='NOSKU-SKU-1'", Long.class);
            var request = new ManufacturingService.CreateOrder(null, null, "带SKU客户", CATEGORY,
                    skuId, null, "X1", null, new BigDecimal("4"), null, "带SKU验证", "SAMPLE", template(TEMPLATE), null, null, null, null);
            long orderId = manufacturing.create(request, "nosku-create-5");
            assertEquals(skuId, ((Number) order(orderId).get("sales_product_id")).longValue(), "显式传 SKU 时仍应落到工单");
            reportAllOperations(orderId, new BigDecimal("4"));

            long outputId = outputIdOfPrimary(orderId);
            manufacturing.inspectAndComplete(orderId, new ManufacturingService.QualityCompletion(
                    new ManufacturingExecutionService.InspectionRequest("PASSED",new BigDecimal("4"),BigDecimal.ZERO,null,null,null,null),
                    new ManufacturingService.CompleteOrder(
                    warehouseId(), null,
                    List.of(new ManufacturingService.ReceiptLine(outputId, new BigDecimal("4"), new BigDecimal("100"), null)),
                    null),null), "nosku-receipt-5");

            // 有 SKU 就必须照旧落批次与库存流水 —— 「可选」不能变成「失效」
            assertEquals(1, count("SELECT COUNT(*) FROM finished_goods_lots WHERE manufacturing_order_id=" + orderId));
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_transactions WHERE source_no LIKE 'RC%'"));
            assertEquals(1, count("SELECT COUNT(*) FROM product_inventory_balances WHERE sales_product_id=" + skuId));
            assertEquals("COMPLETED", order(orderId).get("status"));
        });
    }

    // ---------- 工具 ----------

    private long createWithoutSku(String requestKey, String unit) {
        var request = new ManufacturingService.CreateOrder(null, null, "无SKU客户-" + requestKey, CATEGORY,
                null, unit, "X1", null, new BigDecimal("10"), null, "无SKU验证", "SAMPLE",
                template(TEMPLATE), null, null, null, null);
        return manufacturing.create(request, requestKey);
    }

    private void reportAllOperations(long orderId, BigDecimal quantity) {
        for (long opId : jdbc.queryForList(
                "SELECT id FROM manufacturing_order_operations WHERE manufacturing_order_id=? ORDER BY sequence_no",
                Long.class, orderId)) {
            execution.report(orderId, opId,
                    new ManufacturingExecutionService.WorkReportRequest(quantity, null, null, false, null),
                    "nosku-report-" + orderId + "-" + opId);
        }
    }

    private long outputIdOfPrimary(long orderId) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM manufacturing_order_outputs WHERE manufacturing_order_id=? AND is_primary=1", Long.class, orderId);
        assertNotNull(id, "主产品产出行必须存在");
        return id;
    }

    private Map<String, Object> primaryOutput(long orderId) {
        return jdbc.queryForMap(
                "SELECT * FROM manufacturing_order_outputs WHERE manufacturing_order_id=? AND is_primary=1", orderId);
    }

    private long warehouseId() {
        Long id = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='NOSKU-WH'", Long.class);
        assertNotNull(id, "测试仓库不存在（prepareSchema 的种子已失效）");
        return id;
    }

    private long template(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM manufacturing_process_templates WHERE template_code=?", Long.class, code);
        assertNotNull(id, "正式库缺少制程模板 " + code + "，本用例的主数据假定不成立");
        return id;
    }

    /** 该模板应有的工序数 —— 从模板现算，不写死数字（主数据按氚云调整时不会假失败）。 */
    private int templateOperationCount() {
        return count("SELECT COUNT(*) FROM manufacturing_process_template_operations WHERE template_id=" + template(TEMPLATE));
    }

    /** 工单原始行：{@code queryForMap} 的键是**列名原样**（snake_case），不是服务返回体的驼峰。 */
    private Map<String, Object> order(long orderId) {
        return jdbc.queryForMap("SELECT * FROM manufacturing_orders WHERE id=?", orderId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? -1 : value;
    }

    /** TINYINT(1) 布尔列：MySQL Connector/J 视参数可能读成 Boolean 或整数，必须两者兼容。 */
    private static boolean flag(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
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
