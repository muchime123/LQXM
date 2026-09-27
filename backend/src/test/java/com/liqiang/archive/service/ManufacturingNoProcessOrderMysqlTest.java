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
 * 「无制程产线建单」（免报工工单）契约（2026-09-23 LQ 拍板放开）。
 *
 * <p><b>为什么要这条契约</b>：氚云《产品零部件制程》里，称重机车间与「无需报工」两条路线都标
 * 「是否有制程=**否**」；《生产任务单管理》全量 5,691 张里也有 180 张是显式选了「无需报工」的。
 * 也就是说「没有工序」是**真实业务形态**，不是配置事故。而 09-23 制程按氚云对齐后，
 * 称重机车间默认制程被置空，建单直接 400（「产品未关联制程，且车间未设置默认制程」），
 * 这条产线在 ERP 里彻底无法下单。
 *
 * <p><b>被钉死的口径</b>：
 * <ol>
 *   <li>无制程产线**可以建单**：不展开工序、{@code inspection_status=NOT_REQUIRED}、
 *       工单直接进入 {@code PENDING_RECEIPT}（没有工序就没有报工，收货是唯一推进动作）；</li>
 *   <li>产出清单**照常生成** —— 它是收货的唯一入口，少了它这张单永远收不了货；</li>
 *   <li>收货**不需要任何报工**即可完成，且与无 SKU 收货同一条出口：只登记事实，库存三表逐表为 0；</li>
 *   <li>**部分收货必须留在「待收货」列表里** —— 该列表只查 PENDING_RECEIPT / PARTIALLY_RECEIVED，
 *       若 0 工序工单部分收货被判成 IN_PRODUCTION，第二笔收货就再也找不到入口（09-23 一并修正）；</li>
 *   <li>有制程的产线**行为不变**（回归）：仍按模板展开工序、状态仍为 RELEASED。</li>
 * </ol>
 *
 * <p>隔离边界：只操作专用测试 schema {@code mfg_noprocess_test}（建/删由本测试自管），
 * 表结构由 {@code CREATE TABLE ... LIKE product_archive.*} 复制（真 DDL、真唯一约束），
 * 车间 / 任务单配置 / 工序 / 制程模板主数据从正式库**整表复制**（因此驱动本用例的
 * 「哪条产线没有默认制程」是真库事实，不是测试自造的假设）。每个用例在单连接事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ManufacturingNoProcessOrderMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ManufacturingNoProcessOrderMysqlTest {

    private static final String SCHEMA = "mfg_noprocess_test";
    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "李强";
    private static final String CATEGORY = "契约免报工品类";

    private static final List<String> TABLES = List.of(
            "manufacturing_orders", "manufacturing_order_outputs", "manufacturing_order_operations",
            "manufacturing_operations", "manufacturing_process_templates", "manufacturing_process_template_operations",
            "manufacturing_workshops", "manufacturing_task_config_items",
            "manufacturing_completion_receipts", "manufacturing_completion_receipt_items",
            "finished_goods_lots", "finished_goods_movements",
            "warehouses", "employees", "sales_products",
            "product_inventory_balances", "product_inventory_transactions");
    /** 制程解析依赖的三张主数据表必须与正式库一致，否则本用例的前提不成立。 */
    private static final List<String> SEEDED = List.of(
            "manufacturing_operations", "manufacturing_process_templates", "manufacturing_process_template_operations",
            "manufacturing_workshops", "manufacturing_task_config_items");

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
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");
            for (String t : SEEDED) st.execute("INSERT INTO " + SCHEMA + "." + t + " SELECT * FROM product_archive." + t);
            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active) VALUES('NOPROC-WH','免报工验证仓',1)");
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
        // 数据范围过滤用 mock 置空：本测试只验证无工序建单与收货口径，范围过滤另有归属。
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        execution = new ManufacturingExecutionService(jdbc, users, idem, changeLog, actors);
        manufacturing = new ManufacturingService(jdbc, new ProductInventoryService(jdbc, users), users, scope,
                execution, new ManufacturingTaskTemplateResolver(jdbc), idem, changeLog, actors,
                org.mockito.Mockito.mock(ErpApprovalService.class));
    }

    // ---------- 用例 1：无制程产线建单 —— 不展开工序，直接待收货 ----------

    @Test
    void createForWorkshopWithoutProcessSkipsOperationsAndWaitsForInspection() {
        runInRollbackTransaction(() -> {
            long wsId = workshopWithoutDefaultProcess();
            long orderId = createForWorkshop(wsId, "noproc-create-1", new BigDecimal("10"));

            Map<String, Object> ord = order(orderId);
            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=" + orderId),
                    "该产线无制程 → 不得展开任何工序");
            assertEquals("PENDING_INSPECTION", ord.get("status"),
                    "无工序免报工，但仍须人工完工检验");
            assertEquals("PENDING", ord.get("inspection_status"));
            assertNull(ord.get("process_template_id"), "无制程 → 制程列必须为空（不得借用别的车间的制程）");
            assertEquals(workshopName(wsId), ord.get("workshop_type"), "产线仍要落到工单上（跟单/看板按产线统计）");

            // 产出清单是收货的唯一入口，无制程也必须有
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_order_outputs WHERE manufacturing_order_id=" + orderId),
                    "产出清单必须照常生成，否则这张单永远收不了货");

            // 审计留痕：建单时对工单的一次 UPDATE 必须记上「无制程」（列名以 erp.row_change_log 为准：
            // 身份是 target_table / target_id，不是 entity_type / entity_id）
            assertEquals(1, count("SELECT COUNT(*) FROM row_change_log WHERE target_id=" + orderId
                            + " AND target_table='product_archive.manufacturing_orders' AND action='UPDATE'"
                            + " AND change_reason LIKE '%无制程%'"),
                    "无制程建单必须留下行级审计（M0 契约），且原因可追");
        });
    }

    // ---------- 用例 2：不需要任何报工即可收货，且不落库存账 ----------

    @Test
    void noProcessOrderCannotSkipInspectionForInventory() {
        runInRollbackTransaction(() -> {
            long wsId = workshopWithoutDefaultProcess();
            long orderId = createForWorkshop(wsId, "noproc-create-2", new BigDecimal("10"));
            long outputId = outputIdOfPrimary(orderId);

            assertThrows(IllegalStateException.class, () -> manufacturing.complete(orderId, new ManufacturingService.CompleteOrder(
                    warehouseId(), null,
                    List.of(new ManufacturingService.ReceiptLine(outputId, new BigDecimal("10"), new BigDecimal("100"), "免报工收货")),
                    "免报工收货"), "noproc-receipt-2"));
            assertEquals("PENDING_INSPECTION", order(orderId).get("status"));
            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_completion_receipts WHERE manufacturing_order_id=" + orderId));
            assertEquals(0, count("SELECT COUNT(*) FROM finished_goods_lots WHERE manufacturing_order_id=" + orderId));
            assertEquals(0, count("SELECT COUNT(*) FROM product_inventory_transactions"));
            assertEquals(0, count("SELECT COUNT(*) FROM product_inventory_balances"));
            assertEquals(0, count("SELECT COUNT(*) FROM finished_goods_movements"));
        });
    }

    // ---------- 用例 3：部分收货必须留在「待收货」列表（否则第二笔收货没有入口）----------

    @Test
    void noProcessOrderRejectsPartialReceiptBeforeInspection() {
        runInRollbackTransaction(() -> {
            long wsId = workshopWithoutDefaultProcess();
            long orderId = createForWorkshop(wsId, "noproc-create-3", new BigDecimal("10"));
            long outputId = outputIdOfPrimary(orderId);

            assertThrows(IllegalStateException.class, () -> manufacturing.complete(orderId, new ManufacturingService.CompleteOrder(
                    warehouseId(), null,
                    List.of(new ManufacturingService.ReceiptLine(outputId, new BigDecimal("4"), BigDecimal.ZERO, null)),
                    null), "noproc-receipt-3"));

            assertEquals("PENDING_INSPECTION", order(orderId).get("status"), "不能跳过检验或部分收货");
        });
    }

    // ---------- 用例 4：有制程的产线行为不变（回归）----------

    @Test
    void workshopWithDefaultProcessStillGeneratesOperations() {
        runInRollbackTransaction(() -> {
            long wsId = workshopWithDefaultProcess();
            int expectedOps = count("SELECT COUNT(*) FROM manufacturing_process_template_operations WHERE template_id="
                            + jdbc.queryForObject("SELECT default_process_template_id FROM manufacturing_workshops WHERE id=?", Long.class, wsId));
            assertTrue(expectedOps > 0, "该产线的默认制程必须有工序，否则本用例的前提不成立");

            long orderId = createForWorkshop(wsId, "noproc-create-4", new BigDecimal("10"));
            Map<String, Object> ord = order(orderId);
            assertEquals(expectedOps, count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=" + orderId),
                    "有制程的产线仍必须按模板展开全部工序");
            assertEquals("RELEASED", ord.get("status"), "有制程的工单仍从 RELEASED 起步");
            assertNotNull(ord.get("process_template_id"), "有制程的工单必须落制程 id");
        });
    }

    // ---------- 用例 5：既没选车间、又没给制程 —— 仍然拒绝 ----------

    @Test
    void createWithoutWorkshopAndWithoutProcessTemplateIsStillRejected() {
        runInRollbackTransaction(() -> {
            var request = new ManufacturingService.CreateOrder(null, null, "无制程客户", CATEGORY,
                    null, null, null, null, new BigDecimal("1"), null, null, "SAMPLE", null, null, null, null, null);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> manufacturing.create(request, "noproc-create-5"));
            // ⚠️ 只断言「文案同时点出制程与产线」这两件事，**不钉整句措辞**。
            // 09-23 下游交互改为「先选制程、再定产线」后，文案由「请选择生产车间以确定产品或车间默认制程」
            // 改成「请选择制程或生产车间」—— 钉死子串会让一句纯文案调整变成假失败（已实际发生一次）。
            String message = error.getMessage() == null ? "" : error.getMessage();
            assertTrue(message.contains("制程") && message.contains("车间"),
                    "无从判断这单走哪条线时必须拒绝，且要同时点出制程与产线两个可选项，实际=" + message);
        });
    }

    // ---------- 工具 ----------

    /** 真库里第一条「没有默认制程」的启用产线（当前=称重机车间，氚云标「是否有制程=否」）。 */
    private long workshopWithoutDefaultProcess() {
        Long id = jdbc.query("SELECT id FROM manufacturing_workshops WHERE enabled=1 AND default_process_template_id IS NULL ORDER BY id",
                (r, n) -> r.getLong(1)).stream().findFirst().orElse(null);
        assertNotNull(id, "正式库里已没有「无默认制程」的产线，本用例的前提不成立（若确认不再需要该形态，请删除本测试）");
        return id;
    }

    private long workshopWithDefaultProcess() {
        Long id = jdbc.query("SELECT id FROM manufacturing_workshops WHERE enabled=1 AND default_process_template_id IS NOT NULL ORDER BY id",
                (r, n) -> r.getLong(1)).stream().findFirst().orElse(null);
        assertNotNull(id, "正式库里没有配置默认制程的产线，本回归用例的前提不成立");
        return id;
    }

    /** 无制程产线上第一个可用的产品名称（制程解析要求「该车间唯一有效的产品配置」）。 */
    private String firstProductName(long workshopId) {
        String name = jdbc.query("SELECT config_name FROM manufacturing_task_config_items WHERE workshop_id=?"
                        + " AND config_type='PRODUCT_NAME' AND active=1 ORDER BY id",
                (r, n) -> r.getString(1), workshopId).stream().findFirst().orElse(null);
        assertNotNull(name, "该产线没有可用的产品名称配置，本用例的前提不成立");
        return name;
    }

    private String workshopName(long workshopId) {
        return jdbc.queryForObject("SELECT workshop_name FROM manufacturing_workshops WHERE id=?", String.class, workshopId);
    }

    /** 建单的 `processCategory` 必须是该产线**配置里真实存在**的产品名称 —— 制程解析要求匹配唯一一条。 */
    private long createForWorkshop(long workshopId, String requestKey, BigDecimal quantity) {
        var request = new ManufacturingService.CreateOrder(null, null, "免报工客户-" + requestKey,
                firstProductName(workshopId), null, null, null, null, quantity, null, "无工序建单验证", "SAMPLE",
                null, workshopId, null, null, null);
        long orderId = manufacturing.create(request, requestKey);
        // 决策 #35：建单只落草稿。本用例主体是「无工序单的执行链」，故这里把草稿推到「已放行」，
        // 口径与审批通过一致：有工序 → RELEASED，无工序 → PENDING_INSPECTION。
        // 审批状态机本身由 ManufacturingApprovalContractMysqlTest 单独验。
        // 前置断言 DRAFT 是故意的：建单不再落草稿时这里立刻红，不会静默失效。
        assertEquals("DRAFT", order(orderId).get("status"), "建单必须先落草稿（决策 #35）");
        boolean hasOperations = count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=" + orderId) > 0;
        jdbc.update("UPDATE manufacturing_orders SET status=? WHERE id=?", hasOperations ? "RELEASED" : "PENDING_INSPECTION", orderId);
        return orderId;
    }

    private long outputIdOfPrimary(long orderId) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM manufacturing_order_outputs WHERE manufacturing_order_id=? AND is_primary=1", Long.class, orderId);
        assertNotNull(id, "主产品产出行必须存在");
        return id;
    }

    private long warehouseId() {
        Long id = jdbc.queryForObject("SELECT id FROM warehouses WHERE warehouse_code='NOPROC-WH'", Long.class);
        assertNotNull(id, "测试仓库不存在（prepareSchema 的种子已失效）");
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

    private void runInRollbackTransaction(Runnable body) {
        var status = txm.getTransaction(new DefaultTransactionDefinition());
        try {
            body.run();
        } finally {
            txm.rollback(status);
        }
    }
}
