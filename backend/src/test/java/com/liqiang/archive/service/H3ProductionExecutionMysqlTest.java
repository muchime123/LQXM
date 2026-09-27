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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证氚云任务直接展示、按制程标记计算进度，以及来源行级权限。 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
class H3ProductionExecutionMysqlTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private String schema;
    private CurrentUserService users;
    private H3ProductionExecutionService service;
    private H3ProductionCompletionService completion;
    private H3ProductionReworkService rework;

    @BeforeEach void setup() throws Exception {
        var env = new HashMap<String, String>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int i = line.indexOf('=');
            if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        var uri = java.net.URI.create(env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive").substring(5));
        var connection = DriverManager.getConnection("jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort()) + "/",
                env.getOrDefault("DB_USERNAME", "root"), env.getOrDefault("DB_PASSWORD", ""));
        source = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(source);
        schema = "test_h3_execution_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + schema + " DEFAULT CHARSET utf8mb4");
        jdbc.execute("USE " + schema);
        for (String table : new String[]{"production_orders", "production_order_components", "h3_production_reports", "employees",
                "warehouses", "sales_products", "product_inventory_balances", "product_inventory_transactions"})
            jdbc.execute("CREATE TABLE " + table + " LIKE product_archive." + table);
        jdbc.execute("CREATE TABLE idem_request LIKE erp.idem_request");
        jdbc.execute("CREATE TABLE row_change_log LIKE erp.row_change_log");
        for (String file : new String[]{"V335__h3_production_execution_reports.sql",
                "V336__h3_production_completion_inventory.sql","V338__h3_production_rework.sql"}) {
            String migration = Files.readString(Path.of("src/main/resources/db/migration/" + file));
            for (String statement : migration.replaceAll("(?m)^--.*$", "").split(";"))
                if (!statement.isBlank()) jdbc.execute(statement);
        }
        jdbc.update("INSERT INTO production_orders(id,external_source,external_id,work_order_no,product_name,employee_id,completed_quantity,required_quantity) "
                + "VALUES(1,'H3YUN','source-1','SCGD-test','气调机',23,10,10)");
        jdbc.update("INSERT INTO production_order_components(production_order_id,external_id,sequence_no,component_name,required_quantity,completed_quantity,completed,completed_at) "
                + "VALUES(1,'component-1',1,'备料',10,2,0,NULL),(1,'component-2',2,'装配',10,10,0,NOW())");
        jdbc.update("INSERT INTO h3_production_reports(external_id,source_no,work_order_no,component_name,report_qty) "
                + "VALUES('report-1','report-1','SCGD-test','装配',10)");
        jdbc.update("INSERT INTO warehouses(id,warehouse_code,warehouse_name) VALUES(7,'TEST','测试仓')");
        jdbc.update("INSERT INTO sales_products(id,external_id,product_code,product_name,unit,source,status,canonical_product_id) "
                + "VALUES(17,'h3-product-17','H3-17','测试气调机','台','H3YUN_PRODUCT_IMPORT','ACTIVE',17)");
        users = mock(CurrentUserService.class);
        setUser("ALL", 23L);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var idem = new ErpIdempotencyService(jdbc, new DataSourceTransactionManager(source));
        var audit = new ErpChangeLogWriter(jdbc, mapper);
        var actors = new ErpActorResolver(users);
        service = new H3ProductionExecutionService(jdbc, new DataScopeSupport(users), users,
                actors, idem, audit);
        completion = new H3ProductionCompletionService(jdbc, new DataScopeSupport(users), users,
                actors, idem, audit, new ProductInventoryService(jdbc, users), service);
        rework = new H3ProductionReworkService(jdbc,new DataScopeSupport(users),users,
                actors,idem,audit,service);
    }

    private void setUser(String scope, long employeeId) {
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(employeeId, "工人", "车间", Set.of(),
                Set.of("manufacturing.report", "manufacturing.report.override", "manufacturing.inspect"), scope, false, "TEST", null, null, Map.of()));
        when(users.can("manufacturing.report")).thenReturn(true);
        when(users.can("manufacturing.report.override")).thenReturn(true);
        when(users.can("manufacturing.inspect")).thenReturn(true);
    }

    @AfterEach void cleanup() {
        if (source != null) try {
            if (schema != null && schema.matches("test_h3_execution_[a-f0-9]{32}")) jdbc.execute("DROP DATABASE " + schema);
        } finally { source.destroy(); }
    }

    @Test void progressFollowsCompletedProcessFlagRatherThanCompletedQuantity() {
        var page = service.list("SCGD", 1, 20);
        var item = ((java.util.List<Map<String, Object>>) page.get("items")).getFirst();
        assertEquals(2L, ((Number) item.get("componentCount")).longValue());
        assertEquals(1L, ((Number) item.get("completedComponentCount")).longValue());
        var detail = service.detail(1);
        assertEquals(1L, detail.get("completedComponentCount"));
        assertEquals(1L, detail.get("reportCount"));
        assertEquals(1, ((java.util.List<?>) detail.get("reports")).size());
    }

    @Test void dataScopeProtectsListAndDetail() {
        setUser("SELF", 24L);
        assertEquals(0L, service.list("", 1, 20).get("total"));
        assertThrows(IllegalArgumentException.class, () -> service.detail(1));
        assertThrows(IllegalArgumentException.class, () -> service.report(1, "component-1",
                new H3ProductionExecutionService.ReportRequest(new BigDecimal("1"), ""), "scope-denied"));
    }

    @Test void localReportIsIdempotentAndReversalRestoresProcessProgress() {
        var request = new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"), "完成备料");
        long id = service.report(1, "component-1", request, "report-one");
        assertEquals(id, service.report(1, "component-1", request, "report-one"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_reports", Long.class));
        assertEquals(2L, service.detail(1).get("completedComponentCount"));
        service.reverse(1, id, new H3ProductionExecutionService.ReverseRequest("误报"), "reverse-one");
        service.reverse(1, id, new H3ProductionExecutionService.ReverseRequest("误报"), "reverse-one");
        assertEquals(1L, service.detail(1).get("completedComponentCount"));
        assertEquals(new BigDecimal("0.0000"), jdbc.queryForObject(
                "SELECT net_local_quantity FROM production_execution_component_states", BigDecimal.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM row_change_log", Long.class));
    }

    @Test void reimportCatchingUpDoesNotDoubleCountLocalReport() {
        service.report(1, "component-1", new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"), ""), "report-two");
        jdbc.update("DELETE FROM production_order_components WHERE production_order_id=1 AND external_id='component-1'");
        jdbc.update("INSERT INTO production_order_components(production_order_id,external_id,sequence_no,component_name,required_quantity,completed_quantity,completed) "
                + "VALUES(1,'component-1',1,'备料',10,10,0)");
        var detail = service.detail(1);
        var component = ((java.util.List<Map<String,Object>>) detail.get("components")).getFirst();
        assertEquals(new BigDecimal("10.0000"), component.get("effectiveQuantity"));
        assertEquals(2L, detail.get("completedComponentCount"));
    }

    @Test void qualityPassAndInventoryPostAreAtomicAndReversible() {
        var request = new H3ProductionCompletionService.CompleteRequest(7L,
                java.util.List.of(new H3ProductionCompletionService.Item(17L,new BigDecimal("10"),true)),
                "质检合格");
        assertThrows(IllegalStateException.class, () -> completion.complete(1,request,"too-early"));
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"finish-first");
        long id = completion.complete(1,request,"post-once");
        assertEquals(id,completion.complete(1,request,"post-once"));
        var history = completion.current(1);
        assertEquals(1,((java.util.List<?>)history.get("completions")).size());
        assertEquals(1,((java.util.List<?>)history.get("items")).size());
        var postedItem = ((java.util.List<Map<String,Object>>)history.get("items")).getFirst();
        assertEquals("PI00000001",postedItem.get("transactionNo"));
        assertEquals(new BigDecimal("10.000000"),jdbc.queryForObject(
                "SELECT quantity FROM product_inventory_balances WHERE warehouse_id=7",BigDecimal.class));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM product_inventory_transactions",Long.class));
        assertThrows(IllegalStateException.class, () -> service.report(1,"component-1",
                new H3ProductionExecutionService.ReportRequest(new BigDecimal("1"),""),"late-report"));
        completion.reverse(1,id,new H3ProductionCompletionService.ReverseRequest("检验记录有误"),"reverse-post");
        assertEquals(new BigDecimal("0.000000"),jdbc.queryForObject(
                "SELECT quantity FROM product_inventory_balances WHERE warehouse_id=7",BigDecimal.class));
        assertEquals(2L,jdbc.queryForObject("SELECT COUNT(*) FROM product_inventory_transactions",Long.class));
    }

    @Test void importedWarehousingBlocksDuplicateInventory() {
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"finish-for-source");
        jdbc.update("UPDATE production_orders SET warehoused_quantity=10 WHERE id=1");
        var request = new H3ProductionCompletionService.CompleteRequest(7L,
                java.util.List.of(new H3ProductionCompletionService.Item(17L,new BigDecimal("10"),true)),"");
        assertThrows(IllegalStateException.class, () -> completion.complete(1,request,"source-already-stocked"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM product_inventory_transactions",Long.class));
    }

    @Test void importedWarehousingAlsoBlocksNewLocalReports() {
        jdbc.update("UPDATE production_orders SET warehoused_quantity=1 WHERE id=1");
        assertThrows(IllegalStateException.class, () -> service.report(1,"component-1",
                new H3ProductionExecutionService.ReportRequest(new BigDecimal("1"),""),"stocked-report"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_reports",Long.class));

        jdbc.update("UPDATE production_orders SET warehoused_quantity=0,warehousing_completed=1 WHERE id=1");
        assertThrows(IllegalStateException.class, () -> service.report(1,"component-1",
                new H3ProductionExecutionService.ReportRequest(new BigDecimal("1"),""),"completed-report"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_reports",Long.class));
    }

    @Test void reverseCompletionFailsAtomicallyAfterInventoryWasConsumed() {
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"finish-before-sale");
        long id = completion.complete(1,new H3ProductionCompletionService.CompleteRequest(7L,
                java.util.List.of(new H3ProductionCompletionService.Item(17L,new BigDecimal("10"),true)),""),"post-before-sale");
        jdbc.update("UPDATE product_inventory_balances SET quantity=2 WHERE warehouse_id=7");
        assertThrows(IllegalStateException.class, () -> completion.reverse(1,id,
                new H3ProductionCompletionService.ReverseRequest("需纠错"),"reverse-consumed"));
        assertEquals("POSTED",jdbc.queryForObject("SELECT status FROM production_execution_completions WHERE id=?",
                String.class,id));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM product_inventory_transactions",Long.class));
    }

    @Test void failedInspectionRequiresReworkBeforeReinspectionAndInventory() {
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"base-finish");
        var item = new H3ProductionCompletionService.Item(17L,new BigDecimal("10"),true);
        var pass = new H3ProductionCompletionService.CompleteRequest(7L,java.util.List.of(item),"复检合格");
        long first = rework.fail(1,new H3ProductionReworkService.FailRequest("component-2",
                new BigDecimal("3"),"装配缺陷"),"fail-first");
        assertEquals(first,rework.fail(1,new H3ProductionReworkService.FailRequest("component-2",
                new BigDecimal("3"),"装配缺陷"),"fail-first"));
        assertEquals(1L,service.detail(1).get("completedComponentCount"));
        assertThrows(IllegalStateException.class,()->completion.complete(1,pass,"pass-too-soon"));

        long report = rework.report(1,first,new H3ProductionReworkService.ReportRequest(new BigDecimal("3"),"重新装配"),"rework-report");
        assertEquals(2L,service.detail(1).get("completedComponentCount"));
        assertEquals("PENDING_REINSPECTION",((Map<?,?>)rework.current(1).get("activeRework")).get("status"));
        rework.reverse(1,first,report,new H3ProductionReworkService.ReverseRequest("数量误报"),"rework-reverse");
        assertEquals(1L,service.detail(1).get("completedComponentCount"));
        assertThrows(IllegalStateException.class,()->completion.complete(1,pass,"still-reworking"));

        rework.report(1,first,new H3ProductionReworkService.ReportRequest(new BigDecimal("3"),"已返工"),"rework-again");
        long second = rework.fail(1,new H3ProductionReworkService.FailRequest("component-2",
                new BigDecimal("1"),"复检仍有缺陷"),"fail-again");
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM production_execution_reworks WHERE id=?",String.class,first));
        assertEquals(1L,service.detail(1).get("completedComponentCount"));
        rework.report(1,second,new H3ProductionReworkService.ReportRequest(new BigDecimal("1"),"修复"),"finish-second");
        long completionId=completion.complete(1,pass,"pass-after-rework");
        assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM production_execution_reworks WHERE id=?",String.class,second));
        assertThrows(IllegalStateException.class,()->rework.report(1,second,
                new H3ProductionReworkService.ReportRequest(new BigDecimal("1"),""),"late-rework"));
        completion.reverse(1,completionId,new H3ProductionCompletionService.ReverseRequest("质检复核"),"reverse-pass");
        assertEquals("PENDING_REINSPECTION",jdbc.queryForObject("SELECT status FROM production_execution_reworks WHERE id=?",String.class,second));
        assertEquals(2L,service.detail(1).get("completedComponentCount"));
    }

    @Test void sourceWarehousedAndDataScopeBlockInspectionRework() {
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"base-for-deny");
        var fail = new H3ProductionReworkService.FailRequest("component-2",new BigDecimal("1"),"缺陷");
        setUser("SELF",24L);
        assertThrows(IllegalArgumentException.class,()->rework.fail(1,fail,"wrong-scope"));
        setUser("ALL",23L);
        jdbc.update("UPDATE production_orders SET warehoused_quantity=10 WHERE id=1");
        assertThrows(IllegalStateException.class,()->rework.fail(1,fail,"stocked-fail"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM production_execution_reworks",Long.class));
    }

    @Test void reimportedSourceMustRetainTheSelectedComponentIdentity() {
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"base-before-reimport");
        long id=rework.fail(1,new H3ProductionReworkService.FailRequest("component-2",
                new BigDecimal("1"),"装配缺陷"),"fail-before-reimport");
        jdbc.update("DELETE FROM production_order_components WHERE production_order_id=1 AND external_id='component-2'");
        assertThrows(IllegalStateException.class,()->rework.report(1,id,
                new H3ProductionReworkService.ReportRequest(new BigDecimal("1"),""),"orphan-report"));
        jdbc.update("INSERT INTO production_order_components(production_order_id,external_id,sequence_no,"
                + "component_name,required_quantity,completed_quantity,completed,completed_at) "
                + "VALUES(1,'component-2',2,'装配',10,10,0,NOW())");
        rework.report(1,id,new H3ProductionReworkService.ReportRequest(new BigDecimal("1"),""),"valid-rework");
        jdbc.update("DELETE FROM production_order_components WHERE production_order_id=1 AND external_id='component-2'");
        var pass=new H3ProductionCompletionService.CompleteRequest(7L,
                java.util.List.of(new H3ProductionCompletionService.Item(17L,new BigDecimal("10"),true)),"");
        assertThrows(IllegalStateException.class,()->completion.complete(1,pass,"orphan-pass"));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM product_inventory_transactions",Long.class));
    }

    @Test void inspectionAndReworkUseDifferentRolePermissions() {
        service.report(1,"component-1",new H3ProductionExecutionService.ReportRequest(new BigDecimal("8"),""),"finish-for-roles");
        var fail=new H3ProductionReworkService.FailRequest("component-2",new BigDecimal("1"),"装配缺陷");
        when(users.can("manufacturing.inspect")).thenReturn(false);
        assertThrows(SecurityException.class,()->rework.fail(1,fail,"worker-cannot-inspect"));
        when(users.can("manufacturing.inspect")).thenReturn(true);
        long id=rework.fail(1,fail,"inspector-can-fail");
        when(users.can("manufacturing.report")).thenReturn(false);
        assertThrows(SecurityException.class,()->rework.report(1,id,
                new H3ProductionReworkService.ReportRequest(new BigDecimal("1"),""),"inspector-cannot-report"));
        when(users.can("manufacturing.report")).thenReturn(true);
        long reportId=rework.report(1,id,new H3ProductionReworkService.ReportRequest(new BigDecimal("1"),""),"worker-can-report");
        when(users.can("manufacturing.report.override")).thenReturn(false);
        assertThrows(SecurityException.class,()->rework.reverse(1,id,reportId,
                new H3ProductionReworkService.ReverseRequest("误报"),"worker-cannot-reverse"));
    }
}
