package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.*;
import com.liqiang.archive.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 随机专用 schema，运行新迁移的真实 DDL，不改生产数据。 */
@EnabledIfSystemProperty(named="erp.foundation.mysql",matches="true")
class ProductionFollowUpMysqlTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private String schema;
    private ProductionFollowUpService service;
    private CurrentUserService users;
    private ErpChangeLogWriter audit;
    private final LocalDate today=LocalDate.now();

    @BeforeEach void setup()throws Exception {
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(Path.of(".env"))) {int i=line.indexOf('=');if(i>0)env.put(line.substring(0,i),line.substring(i+1).trim());}
        var uri=java.net.URI.create(env.getOrDefault("SPRING_DATASOURCE_URL","jdbc:mysql://localhost:3306/product_archive").substring(5));
        var conn=DriverManager.getConnection("jdbc:mysql://"+uri.getHost()+":"+(uri.getPort()<0?3306:uri.getPort())+"/",
                env.getOrDefault("DB_USERNAME","root"),env.getOrDefault("DB_PASSWORD",""));
        source=new SingleConnectionDataSource(conn,true);jdbc=new JdbcTemplate(source);
        schema="test_followup_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+schema+" DEFAULT CHARSET utf8mb4");jdbc.execute("USE "+schema);
        // 跟单迁移完整执行（包括权限配置），依赖表只复制结构，绝不复制业务数据。
        for(String table:List.of("production_orders","production_order_components","employees","system_permissions","system_roles","system_role_feature_grants","system_feature_actions"))
            jdbc.execute("CREATE TABLE "+table+" LIKE product_archive."+table);
        jdbc.execute("CREATE TABLE idem_request LIKE erp.idem_request");
        jdbc.execute("CREATE TABLE row_change_log LIKE erp.row_change_log");
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V329__production_followup_workspace.sql"));
        for(String statement:sql.replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())jdbc.execute(statement);
        jdbc.update("INSERT INTO production_orders(id,external_source,external_id,work_order_no,product_name,production_line,required_completion_date,employee_id) VALUES(1,'H3YUN','source-1','SCGD-test-1','测试气调机','全自动气调机车间',?,23)",today.minusDays(2));
        users=mock(CurrentUserService.class);setUser("ALL",23L);
        var mapper=new ObjectMapper().findAndRegisterModules();
        audit=spy(new ErpChangeLogWriter(jdbc,mapper));
        service=new ProductionFollowUpService(jdbc,new DataScopeSupport(users),new ErpActorResolver(users),
                new ErpIdempotencyService(jdbc,new DataSourceTransactionManager(source)),audit,mapper);
    }
    private void setUser(String scope,long employeeId) {
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(employeeId,"测试跟单员","办公室",Set.of(),Set.of("manufacturing.followup"),scope,false,"TEST",null,null,Map.of()));
    }
    @AfterEach void cleanup() {
        if(source!=null)try{if(schema!=null && schema.matches("test_followup_[a-f0-9]{32}"))jdbc.execute("DROP DATABASE "+schema);}finally{source.destroy();}
    }
    private ProductionFollowUpService.SaveRequest request(long version,LocalDate expected,Map<String,LocalDate> stages) {
        return new ProductionFollowUpService.SaveRequest(version,new ProductionFollowUpService.State(null,expected,"等待配件",null,null,null,"",stages),"现场确认进度");
    }
    @Test void savesWithoutManufacturingAndReplaysWithoutDuplicateHistory() {
        var request=request(0,today.plusDays(2),Map.of("备料",today));
        assertEquals(1,service.save(1,request,"retry"));assertEquals(1,service.save(1,request,"retry"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM production_followup_logs",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM row_change_log",Integer.class));
        assertEquals(1L,service.list("","","active",true,1,30).get("total"));
        assertThrows(IllegalStateException.class,()->service.save(1,request,"another-editor"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM production_followup_logs",Integer.class));
    }
    @Test void reimportKeepsLocalStateEvenIfSourceRowIdChanges() {
        service.save(1,request(0,today.plusDays(2),Map.of("备料",today)),"first");
        jdbc.update("UPDATE production_orders SET id=2,work_order_no='SCGD-renamed',required_completion_date=?,expected_completion_date=?,actual_completion_date=? WHERE id=1",today.plusDays(10),today.plusDays(10),today);
        var detail=service.detail(2);var order=(Map<?,?>)detail.get("order");
        var state=(ProductionFollowUpService.State)order.get("state");
        assertEquals(today.plusDays(2),state.expectedFinishDate());assertNull(state.completedDate());
        assertEquals(today.minusDays(2),order.get("requiredCompletionDate"));
        assertEquals(today,state.stages().get("备料"));assertEquals(1L,detail.get("logCount"));
    }
    @Test void clearIsExplicitAndAuditFailureRollsBackEverything() {
        service.save(1,request(0,today.plusDays(2),Map.of("备料",today)),"first");
        var clear=request(1,null,Map.of());
        doThrow(new IllegalStateException("audit unavailable")).when(audit).write(any());
        assertThrows(IllegalStateException.class,()->service.save(1,clear,"clear"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM production_followup_logs",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM idem_request",Integer.class));
        doCallRealMethod().when(audit).write(any());
        assertEquals(2,service.save(1,clear,"clear"));
        var order=(Map<?,?>)service.detail(1).get("order");
        var state=(ProductionFollowUpService.State)order.get("state");assertNull(state.expectedFinishDate());assertTrue(state.stages().isEmpty());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM production_followup_logs",Integer.class));
    }
    @Test void scopeAppliesToListDetailWriteAndReplay() {
        var request=request(0,null,Map.of());service.save(1,request,"first");
        setUser("SELF",24L);
        assertEquals(0L,service.list("","","all",false,1,30).get("total"));
        assertThrows(IllegalArgumentException.class,()->service.detail(1));
        assertThrows(IllegalArgumentException.class,()->service.save(1,request,"first"));
        setUser("SELF",23L);assertEquals(1L,service.list("","","all",false,1,30).get("total"));
    }

    @Test void sourceCompletedFlagWithoutDateNeverAppearsAsActiveEvenAfterLocalNotes() {
        jdbc.update("UPDATE production_orders SET production_completed=1,completed_quantity=1,production_progress='100.00%' WHERE id=1");
        assertEquals(0L,service.list("","","active",false,1,30).get("total"));
        assertEquals(0L,service.list("","","all",true,1,30).get("total"));
        assertEquals(1L,service.list("","","completed",false,1,30).get("total"));
        service.save(1,request(0,null,Map.of()),"source-done-notes");
        assertEquals(0L,service.list("","","active",false,1,30).get("total"));
        var order=(Map<?,?>)service.detail(1).get("order");
        assertEquals("100.00%",order.get("productionProgress"));
        assertNull(((ProductionFollowUpService.State)order.get("state")).completedDate());
    }

    @Test void defaultOrderMatchesSourceLedgerAndDetailsKeepRawComponentProgress() {
        jdbc.update("INSERT INTO production_orders(id,external_source,external_id,work_order_no,product_name,production_line,required_completion_date,employee_id) VALUES(2,'H3YUN','source-2','SCGD-test-2','新导入工单','封口机车间',?,23)",today.plusDays(10));
        jdbc.update("INSERT INTO production_order_components(production_order_id,sequence_no,component_name,required_quantity,completed_quantity,completion_ratio,completed) VALUES(2,1,'模具加工',1,2,'200.00%',1)");
        var items=(List<?>)service.list("","",null,false,1,30).get("items");
        assertEquals(2L,((Number)((Map<?,?>)items.get(0)).get("id")).longValue());
        var source=(List<?>)service.detail(2).get("sourceComponents");
        assertEquals("200.00%",((Map<?,?>)source.get(0)).get("completionRatio"));
        assertEquals("模具加工",((Map<?,?>)source.get(0)).get("componentName"));
    }
}
