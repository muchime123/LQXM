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
 * 生产任务单**审批关口**契约（决策 #35）。
 *
 * <p><b>要证明的四件事</b>：
 * <ol>
 *   <li><b>建单不再直通车间</b>：建单只落 {@code DRAFT}，此时执行看板查不到、排产被拒、
 *       排产看板不计入；审批通过后才出现在车间；</li>
 *   <li><b>审完落哪个状态由单据自己决定</b>：有工序 → {@code RELEASED}（可报工）；
 *       0 道工序 → {@code PENDING_INSPECTION}（没有报工可做，只能直接进检验）。
 *       若一律落 {@code RELEASED}，无工序单会卡死（老坑）；</li>
 *   <li><b>状态机守卫</b>：草稿不能直接审批、驳回必须给原因；</li>
 *   <li><b>驳回重提要清空上一轮结论</b>：重提后 {@code approval_decided_*} 必须为 NULL，
 *       否则提交人被显示成审批人（销售订单侧实际发生过这个缺陷，这里同样钉死）。</li>
 * </ol>
 *
 * <p><b>隔离边界</b>：只操作专用 schema {@code mfg_approval_test}（建/删由本测试自管），
 * 表结构由 {@code CREATE TABLE … LIKE product_archive.*} 复制（真 DDL、真唯一约束）；
 * 制程/工序/产线/产品配置主数据从正式库复制一份。**不触碰 product_archive 与 erp 的任何业务行**。
 * 全部用例在单连接事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ManufacturingApprovalContractMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ManufacturingApprovalContractMysqlTest {

    private static final String SCHEMA = "mfg_approval_test";
    /** 不写死模板 code：业务按氚云调整主数据（V332 就停用过一批）会让写死的测试整类假失败。 */
    private static String TEMPLATE;
    /** 无默认制程的启用产线（氚云「是否有制程=否」）——用来覆盖「0 工序单审完直接进待检验」。 */
    private static long NO_PROCESS_WORKSHOP;

    private static final long ACTOR_ID = 23L;
    private static final String ACTOR_NAME = "李强";

    private static final List<String> TABLES = List.of(
            "manufacturing_orders", "manufacturing_operations", "manufacturing_process_templates",
            "manufacturing_process_template_operations", "manufacturing_order_operations",
            "manufacturing_work_reports", "manufacturing_inspections", "manufacturing_reworks",
            "manufacturing_completion_receipts", "manufacturing_order_outputs",
            "manufacturing_completion_receipt_items", "manufacturing_issues",
            "manufacturing_workshops", "manufacturing_task_config_items",
            "warehouses", "employees", "sales_products",
            // 审批流水建在 product_archive（V346：与三张单据表同库，跨 schema 会让同事务写入静默失效）
            "erp_approvals");
    private static final List<String> SEEDED = List.of(
            "manufacturing_operations", "manufacturing_process_templates",
            "manufacturing_process_template_operations", "manufacturing_workshops",
            "manufacturing_task_config_items");

    private static String baseUrl;
    private static String dbUser;
    private static String dbPassword;
    private static Connection conn;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager txm;

    private ManufacturingExecutionService execution;
    private ManufacturingService manufacturing;
    private ErpApprovalService approvals;

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
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");
            for (String t : SEEDED) st.execute("INSERT INTO " + SCHEMA + "." + t + " SELECT * FROM product_archive." + t);
            st.execute("INSERT INTO " + SCHEMA + ".warehouses(warehouse_code,warehouse_name,active)"
                    + " VALUES('APR-WH','审批验证仓',1)");
            st.execute("INSERT INTO " + SCHEMA + ".sales_products(product_code,product_name,specification,unit)"
                    + " VALUES('APR-SKU-1','审批验证产品','X1','台')");
            st.execute("USE " + SCHEMA);
            try (var rs = st.executeQuery("SELECT t.template_code FROM manufacturing_process_templates t"
                    + " JOIN manufacturing_process_template_operations x ON x.template_id=t.id"
                    + " WHERE t.active=1 GROUP BY t.id,t.template_code ORDER BY COUNT(x.id) DESC, t.id LIMIT 1")) {
                assertTrue(rs.next(), "正式库里没有「启用且带工序」的制程模板，本类测试的前提不成立");
                TEMPLATE = rs.getString(1);
            }
            try (var rs = st.executeQuery("SELECT id FROM manufacturing_workshops"
                    + " WHERE enabled=1 AND default_process_template_id IS NULL ORDER BY id LIMIT 1")) {
                assertTrue(rs.next(), "正式库里没有「无默认制程」的启用产线，本用例前提不成立（若确认不再需要该形态，请删掉本类）");
                NO_PROCESS_WORKSHOP = rs.getLong(1);
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
                ACTOR_ID, ACTOR_NAME, "生产部", Set.of(),
                Set.of("manufacturing.manage", "manufacturing.approve", "manufacturing.schedule"),
                "ALL", false, "COMPAT", null, null, Map.of()));
        // 行级范围过滤置空：本类只验审批契约，范围过滤另有归属（且 #35 明确审批不做行级过滤）。
        var scope = mock(DataScopeSupport.class);
        var idem = new ErpIdempotencyService(jdbc, txm, "idem_request");
        var changeLog = new ErpChangeLogWriter(jdbc, new ObjectMapper(), "row_change_log");
        var actors = new ErpActorResolver(users);
        approvals = new ErpApprovalService(jdbc, actors, idem, changeLog);
        execution = new ManufacturingExecutionService(jdbc, users, idem, changeLog, actors);
        manufacturing = new ManufacturingService(jdbc, new ProductInventoryService(jdbc, users), users, scope,
                execution, new ManufacturingTaskTemplateResolver(jdbc), idem, changeLog, actors, approvals);
    }

    // ---------- 用例 1：建单落草稿 → 车间看不见；审批通过后才出现 ----------

    @Test
    void draftStaysInvisibleUntilApproved() {
        runInRollbackTransaction(() -> {
            long orderId = createWithProcess("apr-invisible-1");
            String orderNo = String.valueOf(order(orderId).get("order_no"));

            // 1) 建单即草稿：不得直接落 RELEASED
            assertEquals("DRAFT", order(orderId).get("status"), "建单必须先落草稿（决策 #35）");
            assertEquals("NONE", order(orderId).get("approval_status"), "从未提交过时审批态应为 NONE");
            int operations = count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=" + orderId);
            assertTrue(operations > 0, "工序仍应随建单展开——审批人要看到工序清单才审得下去");

            // 2) 车间入口看不见：执行看板（报工入口）按 status 过滤，DRAFT 必须查不到
            assertEquals(0, ((Number) execution.executionBoard(orderNo, "", "", 1, 20).get("total")).intValue(),
                    "草稿工单不得出现在车间执行看板（否则车间能在审批前报工）");
            // 3) 排产/分派被拒
            assertThrows(IllegalStateException.class, () -> execution.schedule(orderId, scheduleRequest()),
                    "草稿工单不得排产");
            // 4) 排产看板不计入「待排产」
            assertEquals(0, ((Number) manufacturing.scheduleSummary().get("unscheduled")).intValue(),
                    "草稿不得计入排产看板的待排产数");

            // 5) 提交审批 → 仍是车间不可见
            manufacturing.submit(orderId, "apr-invisible-submit");
            assertEquals("PENDING_APPROVAL", order(orderId).get("status"));
            assertEquals("PENDING", order(orderId).get("approval_status"));
            assertEquals(ACTOR_ID, ((Number) order(orderId).get("approval_submitted_by_employee_id")).longValue());
            assertNull(order(orderId).get("approval_decided_by_employee_id"), "提交不得写审批决定人");
            assertEquals(0, ((Number) execution.executionBoard(orderNo, "", "", 1, 20).get("total")).intValue(),
                    "待审批工单同样不得出现在车间看板");
            assertThrows(IllegalStateException.class, () -> execution.schedule(orderId, scheduleRequest()),
                    "待审批工单不得排产");

            // 6) 审批通过 → 有工序落 RELEASED，车间这才看得见
            manufacturing.approve(orderId, "工序与交期确认可接", "apr-invisible-approve");
            assertEquals("RELEASED", order(orderId).get("status"), "有工序的单审完应可报工（RELEASED）");
            assertEquals("APPROVED", order(orderId).get("approval_status"));
            assertEquals(ACTOR_ID, ((Number) order(orderId).get("approval_decided_by_employee_id")).longValue());
            // 看板只列「可做」的工序（首道 READY，后续 PENDING 要等前道完工），故与可做工序数对拍而不是总工序数
            int readyOperations = count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id="
                    + orderId + " AND status IN ('READY','IN_PROGRESS','BLOCKED')");
            assertTrue(readyOperations > 0, "审批通过后至少要有一道可做的工序");
            assertEquals(readyOperations, ((Number) execution.executionBoard(orderNo, "", "", 1, 20).get("total")).intValue(),
                    "审批通过后待做的工序应出现在车间看板");
            assertEquals(1, ((Number) manufacturing.scheduleSummary().get("unscheduled")).intValue(),
                    "审批通过后才计入待排产");
            // 排产闸门已放开
            execution.schedule(orderId, scheduleRequest());
            assertEquals("NORMAL", order(orderId).get("priority"));
        });
    }

    // ---------- 用例 2：0 工序单审完落「待检验」，不是 RELEASED ----------

    @Test
    void noProcessOrderLandsInPendingInspectionAfterApproval() {
        runInRollbackTransaction(() -> {
            long orderId = createWithoutProcess("apr-noproc-1");
            assertEquals("DRAFT", order(orderId).get("status"), "无工序单同样先进草稿");
            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_order_operations WHERE manufacturing_order_id=" + orderId),
                    "无制程产线不应展开工序");

            manufacturing.submit(orderId, "apr-noproc-submit");
            manufacturing.approve(orderId, null, "apr-noproc-approve");

            // ⚠️ 这条是本用例的核心：若也落 RELEASED，这张单没有工序可报、永远推不动。
            assertEquals("PENDING_INSPECTION", order(orderId).get("status"),
                    "0 工序的单审完必须直接进待检验（否则卡在 RELEASED 无人能推）");
            assertEquals("APPROVED", order(orderId).get("approval_status"));
        });
    }

    // ---------- 用例 3：状态机守卫 + 驳回重提清空上一轮结论 ----------

    @Test
    void guardsHoldAndResubmitClearsPreviousDecision() {
        runInRollbackTransaction(() -> {
            long orderId = createWithProcess("apr-guard-1");

            // 草稿不能直接审批（不能跳过提交）
            assertThrows(IllegalStateException.class,
                    () -> manufacturing.approve(orderId, null, "apr-guard-approve"),
                    "草稿不能直接审批");
            assertThrows(IllegalStateException.class,
                    () -> manufacturing.reject(orderId, "越权", "apr-guard-reject-early"),
                    "草稿不能驳回");

            manufacturing.submit(orderId, "apr-guard-submit-1");
            assertEquals("PENDING_APPROVAL", order(orderId).get("status"));

            // 驳回必须给原因
            assertThrows(IllegalArgumentException.class,
                    () -> manufacturing.reject(orderId, "   ", "apr-guard-reject-blank"),
                    "空白驳回原因必须被拒");

            manufacturing.reject(orderId, "排产能力不足，请与车间确认后重提", "apr-guard-reject-1");
            assertEquals("DRAFT", order(orderId).get("status"), "驳回退回草稿");
            assertEquals("REJECTED", order(orderId).get("approval_status"));
            assertEquals(ACTOR_ID, ((Number) order(orderId).get("approval_decided_by_employee_id")).longValue());

            // 重提：必须清空上一轮的审批结论，只留本次提交人
            manufacturing.submit(orderId, "apr-guard-submit-2");
            assertEquals("PENDING_APPROVAL", order(orderId).get("status"));
            assertEquals("PENDING", order(orderId).get("approval_status"));
            assertEquals(ACTOR_ID, ((Number) order(orderId).get("approval_submitted_by_employee_id")).longValue());
            assertNull(order(orderId).get("approval_decided_by_employee_id"),
                    "重提必须清空上一轮审批决定人（否则提交人会被显示成审批人）");
            assertNull(order(orderId).get("approval_decided_at"), "重提必须清空上一轮审批决定时间");

            // 流水 append-only：驳回几次全都留痕
            List<Map<String, Object>> history = manufacturing.approvalHistory(orderId);
            assertEquals(List.of("SUBMIT", "REJECT", "SUBMIT"),
                    history.stream().map(row -> String.valueOf(row.get("action"))).toList(),
                    "驳回重提应在流水里留下 SUBMIT/REJECT/SUBMIT 三条");
            assertEquals("DRAFT", history.get(0).get("fromStatus"));
            assertEquals("PENDING_APPROVAL", history.get(0).get("toStatus"));
            assertEquals("PENDING_APPROVAL", history.get(1).get("fromStatus"));
            assertEquals("DRAFT", history.get(1).get("toStatus"));
        });
    }

    // ---------- 用例 4：提交闸门（工序缺失 / 产出清单缺失）与同键幂等 ----------

    @Test
    void submitGateAndIdempotency() {
        runInRollbackTransaction(() -> {
            // 4.1 选了制程却没工序 → 提交被拒（否则审完也是一张推不动的单）
            long broken = createWithProcess("apr-gate-1");
            jdbc.update("DELETE FROM manufacturing_order_operations WHERE manufacturing_order_id=?", broken);
            var error = assertThrows(IllegalStateException.class,
                    () -> manufacturing.submit(broken, "apr-gate-submit-1"));
            assertTrue(String.valueOf(error.getMessage()).contains("工序"),
                    "应提示缺工序，实际=" + error.getMessage());
            assertEquals("DRAFT", order(broken).get("status"), "提交失败不得改变状态");
            assertEquals(0, count("SELECT COUNT(*) FROM erp_approvals WHERE doc_id=" + broken),
                    "提交失败不得留下审批流水");
            assertEquals(0, count("SELECT COUNT(*) FROM idem_request WHERE request_key='apr-gate-submit-1'"),
                    "业务失败必须连带回滚幂等键，否则重试会被当成重放");

            // 4.2 没有产出清单 → 提交被拒（收货时无从填写）
            long noOutput = createWithProcess("apr-gate-2");
            jdbc.update("DELETE FROM manufacturing_order_outputs WHERE manufacturing_order_id=?", noOutput);
            assertThrows(IllegalStateException.class,
                    () -> manufacturing.submit(noOutput, "apr-gate-submit-2"),
                    "没有产出清单不能提交");

            // 4.3 同键重放：只落一条 SUBMIT，不重复推进
            long orderId = createWithProcess("apr-gate-3");
            manufacturing.submit(orderId, "apr-gate-submit-3");
            manufacturing.submit(orderId, "apr-gate-submit-3");
            assertEquals(1, count("SELECT COUNT(*) FROM erp_approvals WHERE doc_id=" + orderId + " AND action='SUBMIT'"),
                    "同键重放不得落第二条提交流水");

            // 4.4 已通过的单不能重复审批
            manufacturing.approve(orderId, null, "apr-gate-approve-3");
            assertThrows(IllegalStateException.class,
                    () -> manufacturing.approve(orderId, null, "apr-gate-approve-4"),
                    "已通过的单不能重复审批");
        });
    }

    // ---------- 工具 ----------

    private long createWithProcess(String requestKey) {
        var request = new ManufacturingService.CreateOrder(null, null, "审批验证客户-" + requestKey, "审批验证产品",
                product("APR-SKU-1"), null, null, null, new BigDecimal("10"), null, "审批验证", "SAMPLE",
                template(TEMPLATE), null, null, null, null);
        return manufacturing.create(request, requestKey);
    }

    private long createWithoutProcess(String requestKey) {
        var request = new ManufacturingService.CreateOrder(null, null, "审批验证客户-" + requestKey,
                firstProductName(NO_PROCESS_WORKSHOP), null, null, null, null, new BigDecimal("10"), null,
                "审批验证", "SAMPLE", null, NO_PROCESS_WORKSHOP, null, null, null);
        return manufacturing.create(request, requestKey);
    }

    private ManufacturingExecutionService.ScheduleRequest scheduleRequest() {
        // 排产入参：优先级 + 计划起止 + 班组 + 责任人。这里只关心「审批没过就不许排」。
        return new ManufacturingExecutionService.ScheduleRequest("NORMAL", null, null, "契约班组", null);
    }

    private long template(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM manufacturing_process_templates WHERE template_code=?", Long.class, code);
        assertNotNull(id, "正式库缺少制程模板 " + code + "，本用例的主数据假定不成立");
        return id;
    }

    private long product(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?", Long.class, code);
        assertNotNull(id, "测试产品不存在：" + code + "（prepareSchema 的种子已失效）");
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
        } catch (RuntimeException | Error e) {
            txm.rollback(status);
            throw e;
        }
        txm.rollback(status);
    }
}
