package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.DataScopeSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 车间执行看板（统计卡 + 卡片筛选）与列表的行级口径一致性。
 *
 * <p>钉死三件事，任何一件破了都说明「卡片数字」与「点进去看到的行」已经不是一套口径：
 * <ol>
 *   <li>卡片计数 = 同一数据范围、同一关键字下按 stage 分桶的计数，且各桶之和 = 总数；</li>
 *   <li>点卡片后的列表条数 = 卡片上的数字；</li>
 *   <li>数据范围收窄时卡片同步归零（否则卡片会变成越权信息的侧信道）；</li>
 * </ol>
 * 另覆盖：冲正掉的本地报工不计入进度、已入库的单不进「已超期」、非氚云来源不进本页。
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class H3ProductionExecutionBoardMysqlTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private String schema;
    private CurrentUserService users;
    private H3ProductionExecutionService service;

    @BeforeEach void setup() throws Exception {
        var env = new HashMap<String, String>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int i = line.indexOf('=');
            if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        var uri = java.net.URI.create(env.getOrDefault("SPRING_DATASOURCE_URL",
                "jdbc:mysql://localhost:3306/product_archive").substring(5));
        var connection = DriverManager.getConnection("jdbc:mysql://" + uri.getHost() + ":"
                        + (uri.getPort() < 0 ? 3306 : uri.getPort()) + "/",
                env.getOrDefault("DB_USERNAME", "root"), env.getOrDefault("DB_PASSWORD", ""));
        source = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(source);
        schema = "test_h3_board_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + schema + " DEFAULT CHARSET utf8mb4");
        jdbc.execute("USE " + schema);
        // 结构直接照真库复制（不手搓），避免测试表结构与迁移漂移。
        for (String table : List.of("production_orders", "production_order_components",
                "production_execution_reports", "production_execution_completions",
                "production_execution_reworks", "production_execution_component_states", "employees"))
            jdbc.execute("CREATE TABLE " + table + " LIKE product_archive." + table);
        jdbc.execute("CREATE TABLE idem_request LIKE erp.idem_request");
        jdbc.execute("CREATE TABLE row_change_log LIKE erp.row_change_log");
        jdbc.update("INSERT INTO employees(id,name,department) VALUES(23,'工人','车间'),(24,'别的业务员','销售部')");
        seedOrders();
        users = mock(CurrentUserService.class);
        setUser("ALL", 23L);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var idem = new ErpIdempotencyService(jdbc, new DataSourceTransactionManager(source));
        var audit = new ErpChangeLogWriter(jdbc, mapper);
        service = new H3ProductionExecutionService(jdbc, new DataScopeSupport(users), users,
                new ErpActorResolver(users), idem, audit);
    }

    /** 9 张氚云任务各覆盖一个阶段/超期组合，外加 1 张非氚云来源（必须被排除）。 */
    private void seedOrders() {
        jdbc.update("INSERT INTO production_orders(id,external_source,external_id,work_order_no,product_name,employee_id,"
                + "required_quantity,completed_quantity,warehoused_quantity,warehousing_completed,required_completion_date,status) VALUES"
                + "(1,'H3YUN','wa-source','GD0001','气调机',23,10,10,10,1,'2026-09-30','已完成'),"
                + "(2,'H3YUN','wa-local','GD0002','气调机',23,10,10,0,0,'2026-09-30','生产中'),"
                + "(3,'H3YUN','rw','GD0003','气调机',23,10,4,0,0,'2026-09-30','生产中'),"
                + "(4,'H3YUN','pending','GD0004','气调机',23,10,10,0,0,'2026-09-30','生产中'),"
                + "(5,'H3YUN','inprod','GD0005','气调机',23,10,4,0,0,'2026-09-30','生产中'),"
                + "(6,'H3YUN','notstarted','GD0006','气调机',23,10,0,0,0,'2026-09-30','待生产'),"
                + "(7,'H3YUN','notstarted2','GD0007','气调机',23,10,0,0,0,'2026-09-30','待生产'),"
                + "(8,'H3YUN','overdue-inprod','GD0008','气调机',23,10,4,0,0,'2026-01-05','生产中'),"
                + "(9,'H3YUN','overdue-warehoused','GD0009','气调机',23,10,10,10,1,'2026-01-05','已完成'),"
                + "(10,'LOCAL','local-1','GD0010','气调机',23,10,0,0,0,'2026-01-05','待生产')");
        jdbc.update("INSERT INTO production_order_components(production_order_id,external_id,sequence_no,component_name,"
                + "required_quantity,completed_quantity,completed,completed_at) VALUES"
                + "(3,'rw-c1',1,'装配',10,4,0,NULL),"
                + "(4,'p-c1',1,'备料',10,10,1,NOW()),(4,'p-c2',2,'装配',10,10,1,NOW()),"
                + "(5,'i-c1',1,'备料',10,10,1,NOW()),(5,'i-c2',2,'装配',10,0,0,NULL),"
                + "(6,'n-c1',1,'备料',10,0,0,NULL)");
        jdbc.update("INSERT INTO production_execution_completions(order_external_id,work_order_no_snapshot,warehouse_id,"
                + "status,inspected_by_employee_id,inspected_by_name) VALUES('wa-local','GD0002',1,'POSTED',23,'质检')");
        jdbc.update("INSERT INTO production_execution_reworks(order_external_id,work_order_no_snapshot,component_external_id,"
                + "component_name_snapshot,target_quantity,reported_quantity,status,failure_reason,"
                + "inspected_by_employee_id,inspected_by_name) "
                + "VALUES('rw','GD0003','rw-c1','装配',6,0,'REWORKING','尺寸超差',23,'质检')");
        jdbc.update("INSERT INTO production_execution_reports(order_external_id,component_external_id,work_order_no_snapshot,"
                + "component_name_snapshot,quantity,status,reported_by_employee_id,reported_by_name) VALUES"
                + "('overdue-inprod','i-c2','GD0008','装配',2,'VALID',23,'工人'),"
                + "('notstarted','n-c1','GD0006','备料',1,'REVERSED',23,'工人')");
    }

    private void setUser(String scope, long employeeId) {
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(employeeId, "工人", "车间", Set.of(),
                Set.of("manufacturing.report", "manufacturing.report.inspect"), scope, false, "TEST", null, null, Map.of()));
    }

    @AfterEach void cleanup() {
        if (source != null) try {
            if (schema != null && schema.matches("test_h3_board_[a-f0-9]{32}")) jdbc.execute("DROP DATABASE " + schema);
        } finally { source.destroy(); }
    }

    private long count(Object value) { return value == null ? 0L : ((Number) value).longValue(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String state) {
        return (List<Map<String, Object>>) service.list("", state, 1, 50).get("items");
    }

    @Test void summaryBucketsMatchStageDefinitions() {
        var summary = service.summary("");
        assertEquals(9L, count(summary.get("total")), "非氚云来源不应进入车间执行看板");
        assertEquals(2L, count(summary.get("notStarted")));
        assertEquals(2L, count(summary.get("inProduction")));
        assertEquals(1L, count(summary.get("pendingInspection")));
        assertEquals(1L, count(summary.get("rework")));
        assertEquals(3L, count(summary.get("warehoused")), "来源已入库 1 张 + 本地已入库 1 张 + 已入库且原交期已过 1 张");
        assertEquals(1L, count(summary.get("overdue")));
        long sum = count(summary.get("notStarted")) + count(summary.get("inProduction"))
                + count(summary.get("pendingInspection")) + count(summary.get("rework"))
                + count(summary.get("warehoused"));
        assertEquals(count(summary.get("total")), sum, "各阶段必须互斥且覆盖全部任务，否则卡片总数对不上");
    }

    @Test void cardCountEqualsFilteredListTotal() {
        for (String state : List.of("NOT_STARTED", "IN_PRODUCTION", "PENDING_INSPECTION", "REWORK", "WAREHOUSED", "OVERDUE")) {
            long card = count(service.summary("").get(switch (state) {
                case "NOT_STARTED" -> "notStarted";
                case "IN_PRODUCTION" -> "inProduction";
                case "PENDING_INSPECTION" -> "pendingInspection";
                case "REWORK" -> "rework";
                case "WAREHOUSED" -> "warehoused";
                default -> "overdue";
            }));
            long listed = ((Number) service.list("", state, 1, 50).get("total")).longValue();
            assertEquals(card, listed, state + " 卡片数字与点进去的条数必须一致");
        }
    }

    @Test void rowCarriesStageAndOverdueForListAndDetail() {
        var pending = items("PENDING_INSPECTION").getFirst();
        assertEquals("PENDING_INSPECTION", pending.get("stage"));
        assertEquals(0, ((Number) pending.get("overdue")).intValue());
        var overdue = items("OVERDUE").getFirst();
        assertEquals(8L, ((Number) overdue.get("id")).longValue());
        assertEquals(1, ((Number) overdue.get("overdue")).intValue());
        assertEquals("IN_PRODUCTION", overdue.get("stage"));
    }

    @Test void reversedReportDoesNotAdvanceStage() {
        assertEquals("NOT_STARTED", items("NOT_STARTED").stream()
                .filter(row -> ((Number) row.get("id")).longValue() == 6L).findFirst()
                .orElseThrow().get("stage"), "冲正掉的本地报工不能把任务算成生产中");
    }

    @Test void warehousedOrderIsNeverOverdue() {
        var row = items("WAREHOUSED").stream()
                .filter(item -> ((Number) item.get("id")).longValue() == 9L).findFirst().orElseThrow();
        assertEquals(0, ((Number) row.get("overdue")).intValue(), "原交期已过但已入库的单不应再算超期");
        assertEquals("SOURCE_WAREHOUSED", row.get("stage"));
    }

    @Test void keywordNarrowsSummaryAsWellAsList() {
        var summary = service.summary("GD0008");
        assertEquals(1L, count(summary.get("total")));
        assertEquals(1L, count(summary.get("overdue")));
        assertEquals(1L, ((Number) service.list("GD0008", "OVERDUE", 1, 50).get("total")).longValue());
        assertEquals(0L, ((Number) service.list("GD0008", "REWORK", 1, 50).get("total")).longValue());
    }

    @Test void unknownStateIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> service.list("", "NOT_A_STATE", 1, 50));
        assertTrue(error.getMessage().contains("未知的看板筛选状态"), "实际=" + error.getMessage());
    }

    @Test void dataScopeNarrowsCardsToo() {
        setUser("SELF", 24L);
        var summary = service.summary("");
        assertEquals(0L, count(summary.get("total")));
        assertEquals(0L, count(summary.get("inProduction")));
        assertEquals(0L, ((Number) service.list("", "OVERDUE", 1, 50).get("total")).longValue());
    }
}
