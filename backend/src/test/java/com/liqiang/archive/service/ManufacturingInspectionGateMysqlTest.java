package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
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
 * 制造工单检验闸门（V318）的真库验证：证明 {@code requires_inspection} 是唯一的政策开关。
 *
 * <p>背景：改造前 {@code recalculate()} 在全部必做工序完成后**无条件**置 {@code PENDING_INSPECTION}，
 * 而唯一出口 {@code inspect()} 不接受其他状态且强制要求检验员 + 合格数量
 * → 现场没有检验岗时工单永久停在待检验（活体证据 MO00000018 滞留 17 天），完工入库整段不通。
 *
 * <p>隔离边界：只操作专用测试 schema {@code mfg_gate_test}（建/删由本测试自管），
 * 表结构由 {@code CREATE TABLE ... LIKE product_archive.*} 复制（真 DDL、真唯一约束，不含外键），
 * 工序与制程模板主数据从正式库复制一份；**不触碰 product_archive 任何业务行**。
 * 每个用例在单连接事务内执行并回滚，零残留。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ManufacturingInspectionGateMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class ManufacturingInspectionGateMysqlTest {

    private static final String SCHEMA = "mfg_gate_test";
    /**
     * 闸门测试**自建**的 3 道工序制程（末道 {@link #INSPECT_OP} 为检验类工序，覆盖闸门两侧）。
     *
     * <p>⚠️ 不再借用正式库在用模板：09-23 氚云对齐（V332）把 STANDARD_SEALER 等旧模板整批停用，
     * 借用真实模板会让**整类测试随业务改主数据而假失败**（实测已发生一次）。本类验证的是**闸门逻辑**，
     * 与哪条真实产线在用哪条制程无关，因此在隔离 schema 里自建一套最稳。
     */
    private static final String TEMPLATE = "GATE_TEST_TPL";
    /**
     * 自建检验工序的 code。
     *
     * <p>⚠️ **不能复用正式库已有的 {@code DEBUG_INSPECTION}**：{@code SEEDED} 会把
     * {@code product_archive.manufacturing_operations} 整表复制进隔离 schema，其中已含 V224 种下的
     * {@code DEBUG_INSPECTION}，再 INSERT 同名会撞唯一键 {@code uk_manufacturing_operation_code}
     * （2026-09-23 实证 Duplicate entry）。故用本类专属前缀，与正式库主数据天然不碰撞。
     */
    private static final String INSPECT_OP = "GATE_INSPECT";

    private static final List<String> TABLES = List.of(
            "manufacturing_orders", "manufacturing_operations", "manufacturing_process_templates",
            "manufacturing_process_template_operations", "manufacturing_order_operations",
            "manufacturing_work_reports", "manufacturing_inspections", "manufacturing_reworks");
    private static final List<String> SEEDED = List.of(
            "manufacturing_operations", "manufacturing_process_templates", "manufacturing_process_template_operations");

    private static Connection conn;
    private static JdbcTemplate jdbc;

    private ManufacturingExecutionService execution;
    private DataSourceTransactionManager txm;

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
            // M0 契约表在 erp schema，测试 schema 里按同款 DDL 复制一份（含唯一键，幂等冲突分支才可测）。
            st.execute("CREATE TABLE " + SCHEMA + ".idem_request LIKE erp.idem_request");
            st.execute("CREATE TABLE " + SCHEMA + ".row_change_log LIKE erp.row_change_log");
            for (String t : SEEDED) st.execute("INSERT INTO " + SCHEMA + "." + t + " SELECT * FROM product_archive." + t);
            st.execute("USE " + SCHEMA);
            // 自建闸门测试制程（见 TEMPLATE 字段注释）：3 道工序，末道是检验类工序。
            // 全部 requires_inspection=0 起步；用例 2 自行把它翻成 1 来验证「有检验工序则维持闸门」。
            st.execute("INSERT INTO manufacturing_operations(operation_code,operation_name,operation_category,active,sort_order,requires_inspection)"
                    + " VALUES('GATE_CUT','闸门测试下料','FABRICATION',1,10,0),"
                    + "('GATE_ASSEMBLE','闸门测试装配','ASSEMBLY',1,20,0),"
                    + "('" + INSPECT_OP + "','闸门测试检验','INSPECTION',1,30,0)");
            st.execute("INSERT INTO manufacturing_process_templates(template_code,template_name,workshop_type,active,version)"
                    + " VALUES('" + TEMPLATE + "','闸门测试制程','闸门测试车间',1,1)");
            st.execute("INSERT INTO manufacturing_process_template_operations(template_id,operation_id,sequence_no,requirement_coefficient,required,is_final)"
                    + " SELECT t.id,o.id,CASE o.operation_code WHEN 'GATE_CUT' THEN 1 WHEN 'GATE_ASSEMBLE' THEN 2 ELSE 3 END,"
                    + " 1.000000,1,CASE WHEN o.operation_code='" + INSPECT_OP + "' THEN 1 ELSE 0 END"
                    + " FROM manufacturing_process_templates t"
                    + " JOIN manufacturing_operations o ON o.operation_code IN ('GATE_CUT','GATE_ASSEMBLE','" + INSPECT_OP + "')"
                    + " WHERE t.template_code='" + TEMPLATE + "'");
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
                23L, "李强", "生产部", Set.of(), Set.of("manufacturing.report", "manufacturing.inspect"),
                "ALL", false, "COMPAT", null, null, Map.of()));
        execution = new ManufacturingExecutionService(jdbc, users,
                new ErpIdempotencyService(jdbc, txm),
                new ErpChangeLogWriter(jdbc, new ObjectMapper()),
                new ErpActorResolver(users));
    }

    // ---------- 用例 1：无人工检验工序 → 报工完成即自动放行至待入库 ----------

    @Test
    void autoConfirmsWhenNoOperationRequiresInspection() {
        runInRollbackTransaction(() -> {
            long orderId = newOrder("MO-GATE-A", new BigDecimal("10"));
            List<Long> ops = generateOperations(orderId);
            assertEquals(3, ops.size(), TEMPLATE + " 应为 3 道工序");
            reportAll(orderId, ops, new BigDecimal("10"));

            Map<String, Object> order = orderRow(orderId);
            assertEquals("PENDING_INSPECTION", order.get("status"), "全部工序完成后仍须人工检验");
            assertEquals("PENDING", order.get("inspection_status"));
            assertNull(order.get("inspection_source"), "尚未检验，来源应为空");
            assertNull(order.get("inspection_at"), "自动确认 ≠ 检验合格：不得产生检验时间");
            assertNull(order.get("inspection_by_name"), "自动确认 ≠ 检验合格：不得产生检验人");
            assertNull(order.get("inspection_remark"));

            assertEquals(0, count("SELECT COUNT(*) FROM manufacturing_inspections"),
                    "未发生检验事实，不得写入检验记录表");
            assertFalse(hasAutoConfirmAudit(orderId), "不得自动放行：" + changeLogsOf(orderId));
        });
    }

    // ---------- 用例 2：有检验工序 → 维持待检验，人工检验后来源为 MANUAL ----------

    @Test
    void staysPendingInspectionWhenAnOperationRequiresInspection() {
        runInRollbackTransaction(() -> {
            jdbc.update("UPDATE manufacturing_operations SET requires_inspection=1 WHERE operation_code='" + INSPECT_OP + "'");
            long orderId = newOrder("MO-GATE-B", new BigDecimal("10"));
            List<Long> ops = generateOperations(orderId);
            reportAll(orderId, ops, new BigDecimal("10"));

            Map<String, Object> pending = orderRow(orderId);
            assertEquals("PENDING_INSPECTION", pending.get("status"), "存在检验工序时应维持既有闸门");
            assertEquals("PENDING", pending.get("inspection_status"));
            assertNull(pending.get("inspection_source"), "尚未检验，来源应为空");
            assertFalse(hasAutoConfirmAudit(orderId), "不该产生自动确认审计：" + changeLogsOf(orderId));

            execution.inspect(orderId, new ManufacturingExecutionService.InspectionRequest(
                    "PASSED", new BigDecimal("10"), BigDecimal.ZERO, null, "人工确认", null, null),
                    "gate-inspect-" + orderId);
            Map<String, Object> passed = orderRow(orderId);
            assertEquals("PENDING_RECEIPT", passed.get("status"));
            assertEquals("MANUAL", passed.get("inspection_source"), "人工检验必须标记为 MANUAL");
            assertNotNull(passed.get("inspection_at"));
            assertEquals(1, count("SELECT COUNT(*) FROM manufacturing_inspections"));
        });
    }

    // ---------- 用例 3：工序未全部完成时不得误放行 ----------

    @Test
    void doesNotReleaseWhileOperationsRemain() {
        runInRollbackTransaction(() -> {
            long orderId = newOrder("MO-GATE-C", new BigDecimal("10"));
            List<Long> ops = generateOperations(orderId);
            execution.report(orderId, ops.get(0),
                    new ManufacturingExecutionService.WorkReportRequest(new BigDecimal("10"), null, null, false, null),
                    "gate-report-" + orderId + "-" + ops.get(0));

            Map<String, Object> order = orderRow(orderId);
            assertEquals("IN_PRODUCTION", order.get("status"), "还有工序未完成时不能进入入库环节");
            assertNull(order.get("inspection_source"));
            assertFalse(hasAutoConfirmAudit(orderId), "不该产生自动确认审计：" + changeLogsOf(orderId));
        });
    }

    // ---------- 用例 4：开关可逆 —— 政策改回 0 后同一张工单恢复自动放行 ----------

    @Test
    void switchIsReversibleOnAlreadyGeneratedOrder() {
        runInRollbackTransaction(() -> {
            jdbc.update("UPDATE manufacturing_operations SET requires_inspection=1 WHERE operation_code='" + INSPECT_OP + "'");
            long orderId = newOrder("MO-GATE-D", new BigDecimal("10"));
            List<Long> ops = generateOperations(orderId);
            assertEquals("PENDING", orderRow(orderId).get("inspection_status"), "生成工序时按政策预判为待检验");

            // 政策改回「不设人工检验」——不需要重建工单，末道工序报工即自动放行
            jdbc.update("UPDATE manufacturing_operations SET requires_inspection=0 WHERE operation_code='" + INSPECT_OP + "'");
            reportAll(orderId, ops, new BigDecimal("10"));

            Map<String, Object> order = orderRow(orderId);
            assertEquals("PENDING_INSPECTION", order.get("status"));
            assertNull(order.get("inspection_source"));
        });
    }

    // ---------- 回填脚本（V318 的 UPDATE）语义验证：三个场景各自独立事务，只隔离一个变量 ----------
    // requires_inspection 是全局工序主数据，因此绝不能在同一个事务里既改开关又验证另一张单，
    // 否则「有检验工序」会顺带否决「无检验工序」的工单，得到假失败。

    private static final String BACKFILL = """
            UPDATE manufacturing_orders m
            SET m.status='PENDING_RECEIPT',m.inspection_status='NOT_REQUIRED',m.inspection_source='AUTO'
            WHERE m.status IN ('PENDING_INSPECTION','PENDING_REINSPECTION')
              AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations o
                                JOIN manufacturing_operations op ON op.id=o.operation_id
                               WHERE o.manufacturing_order_id=m.id AND o.required=1 AND op.requires_inspection=1)
              AND NOT EXISTS (SELECT 1 FROM manufacturing_order_operations o
                               WHERE o.manufacturing_order_id=m.id AND o.required=1 AND o.status<>'COMPLETED')
            """;

    @Test
    void backfillReleasesStuckOrderWithoutInspectionOperation() {
        runInRollbackTransaction(() -> {
            long orderId = stuckOrder("MO-GATE-F1", true);
            assertEquals(1, jdbc.update(BACKFILL), "工序全完成且无检验工序的滞留单应被回填");
            Map<String, Object> order = orderRow(orderId);
            assertEquals("PENDING_RECEIPT", order.get("status"));
            assertEquals("NOT_REQUIRED", order.get("inspection_status"));
            assertEquals("AUTO", order.get("inspection_source"));
            assertNull(order.get("inspection_at"), "回填不得伪造检验时间");
            assertNull(order.get("inspection_by_name"), "回填不得伪造检验人");
        });
    }

    @Test
    void backfillLeavesStuckOrderWithInspectionOperationUntouched() {
        runInRollbackTransaction(() -> {
            jdbc.update("UPDATE manufacturing_operations SET requires_inspection=1 WHERE operation_code='" + INSPECT_OP + "'");
            long orderId = stuckOrder("MO-GATE-F2", true);
            assertEquals(0, jdbc.update(BACKFILL), "存在检验工序的滞留单不得被自动放行");
            assertEquals("PENDING_INSPECTION", orderRow(orderId).get("status"));
        });
    }

    @Test
    void backfillLeavesUnfinishedOrderUntouched() {
        runInRollbackTransaction(() -> {
            long orderId = stuckOrder("MO-GATE-F3", false);
            assertEquals(0, jdbc.update(BACKFILL), "工序未完成的单不得被推进到入库环节");
            assertEquals("PENDING_INSPECTION", orderRow(orderId).get("status"));
        });
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

    private long newOrder(String orderNo, BigDecimal quantity) {
        jdbc.update("""
                INSERT INTO manufacturing_orders(order_no,customer_name_snapshot,product_name_snapshot,planned_quantity,unit)
                VALUES(?,?,?,?,'台')
                """, orderNo, "闸门测试客户", "闸门测试产品", quantity);
        return jdbc.queryForObject("SELECT id FROM manufacturing_orders WHERE order_no=?", Long.class, orderNo);
    }

    /** 造一张「改造前滞留态」工单：报工至 finishAll 决定是否全部完成，再人为回退成 PENDING_INSPECTION。 */
    private long stuckOrder(String orderNo, boolean finishAll) {
        long orderId = newOrder(orderNo, new BigDecimal("10"));
        List<Long> ops = generateOperations(orderId);
        if (finishAll) {
            reportAll(orderId, ops, new BigDecimal("10"));
        } else {
            execution.report(orderId, ops.get(0),
                    new ManufacturingExecutionService.WorkReportRequest(new BigDecimal("10"), null, null, false, null),
                    "gate-report-" + orderId + "-" + ops.get(0));
        }
        jdbc.update("UPDATE manufacturing_orders SET status='PENDING_INSPECTION',inspection_status='PENDING',inspection_source=NULL WHERE id=?", orderId);
        return orderId;
    }

    private List<Long> generateOperations(long orderId) {
        Long templateId = jdbc.queryForObject("SELECT id FROM manufacturing_process_templates WHERE template_code=?", Long.class, TEMPLATE);
        execution.generateOperations(orderId, templateId, "gate-gen-ops-" + orderId);
        return jdbc.queryForList("SELECT id FROM manufacturing_order_operations WHERE manufacturing_order_id=? ORDER BY sequence_no",
                Long.class, orderId);
    }

    private void reportAll(long orderId, List<Long> operations, BigDecimal quantity) {
        for (long operationId : operations) {
            execution.report(orderId, operationId,
                    new ManufacturingExecutionService.WorkReportRequest(quantity, null, null, false, null),
                    "gate-report-" + orderId + "-" + operationId);
        }
    }

    private Map<String, Object> orderRow(long orderId) {
        return jdbc.queryForMap("SELECT * FROM manufacturing_orders WHERE id=?", orderId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? -1 : value;
    }

    // ---------- 审计单通道（方案 A）辅助：不再断言 audit_logs，改查 row_change_log ----------

    /**
     * 某工单在 {@code row_change_log} 里的变更行。
     *
     * <p>审计身份取 {@code target_table}（schema 限定表名，物理唯一），不是自由字符串
     * {@code entity_type} —— 后者会让两个模块对同名概念用同一个词时**静默撞名**
     * （见 {@code docs/审计实体身份治理与撞名防治-20260921.md}）。
     * {@code changed_fields} 是 json 列，显式 CAST 成文本再断言，避免驱动按二进制返回。
     */
    private List<Map<String, Object>> changeLogsOf(long orderId) {
        return jdbc.queryForList(
                "SELECT action, CAST(changed_fields AS CHAR) AS changed_fields, change_reason"
                        + " FROM row_change_log"
                        + " WHERE target_table='product_archive.manufacturing_orders' AND target_id=?",
                orderId);
    }

    /**
     * 是否留下了「无检验工序自动确认放行」的审计（识别标志 = {@code inspectionSource=AUTO}）。
     *
     * <p>先压掉全部空白再比对：{@code changed_fields} 是 json 列，序列化时键值之间带空格
     * （{@code "inspectionSource": "AUTO"}），按紧凑写法 {@code contains} 会**假失败**
     * （2026-09-21 实证）。
     */
    private boolean hasAutoConfirmAudit(long orderId) {
        return changeLogsOf(orderId).stream().anyMatch(row ->
                String.valueOf(row.get("changed_fields")).replaceAll("\\s+", "")
                        .contains("\"inspectionSource\":\"AUTO\""));
    }
}
