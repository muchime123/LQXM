package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对现有 MySQL 做只读验收；不会创建模板、快照或修改跟单事实。 */
class ProductionTrackingProcessMysqlTest {
    @Test
    @EnabledIfSystemProperty(named="main.mysql",matches="true")
    void snapshotSchemaAndNodeAnalysisJoinAreConsistent() throws Exception {
        var jdbc=jdbc();
        Long empty=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshots s "
                +"LEFT JOIN production_tracking_process_snapshot_nodes n ON n.production_order_id=s.production_order_id "
                +"WHERE n.production_order_id IS NULL",Long.class);
        assertEquals(0L,empty);
        Long contradictory=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshot_nodes n "
                +"JOIN production_tracking_milestones m ON m.production_order_id=n.production_order_id "
                +"AND m.stage_code=n.stage_code WHERE n.applicability='NOT_APPLICABLE' "
                +"AND m.stage_status='DONE'",Long.class);
        assertEquals(0L,contradictory);
        var rows=jdbc.queryForList("SELECT p.id,pr.stage_code,COALESCE(m.stage_status,'PENDING') stage_status "
                +ProductionTrackingService.TRACKING_FROM
                +ProductionTrackingAnalyticsService.NODE_ROWS_JOIN+" LIMIT 1");
        assertFalse(rows.isEmpty(),"节点分析公共 JOIN 应在真实生产任务数据上返回记录");
    }

    @Test
    @EnabledIfSystemProperty(named="main.mysql",matches="true")
    void publishedTemplateCapturesNewOrderAndReplayKeepsSnapshot() throws Exception {
        var jdbc=jdbc();
        var candidates=jdbc.queryForList("SELECT c.id config_id,p.id order_id,p.production_line workshop "
                +"FROM production_orders p JOIN manufacturing_workshops w ON w.workshop_name=p.production_line "
                +"JOIN manufacturing_task_config_items c ON c.workshop_id=w.id "
                +"AND c.config_type='PRODUCT_NAME' AND c.config_name=p.product_name "
                +"LEFT JOIN production_tracking_process_snapshots s ON s.production_order_id=p.id "
                +"WHERE s.production_order_id IS NULL AND (SELECT COUNT(*) FROM manufacturing_task_config_items c2 "
                +"WHERE c2.workshop_id=w.id AND c2.config_type='PRODUCT_NAME' AND c2.config_name=p.product_name)=1 "
                +"LIMIT 1");
        assertFalse(candidates.isEmpty(),"需要一张产品配置唯一匹配且尚无快照的任务");
        var candidate=candidates.getFirst();
        long configId=((Number)candidate.get("config_id")).longValue();
        long orderId=((Number)candidate.get("order_id")).longValue();
        String kind=ProductionTrackingPolicy.isSealer((String)candidate.get("workshop"))?"SEALER":"MACHINE";
        var codes=jdbc.query("SELECT stage_code FROM production_tracking_stage_defs WHERE workshop_kind=? "
                +"AND enabled=1 ORDER BY sort_no LIMIT 2",(r,n)->r.getString(1),kind);
        assertEquals(2,codes.size());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status->{
            var service=new ProductionTrackingProcessService(jdbc);
            long templateId=service.createDraft(configId,new ProductionTrackingProcessService.Draft(
                    List.of(new ProductionTrackingProcessService.Node(codes.get(0),1,false),
                            new ProductionTrackingProcessService.Node(codes.get(1),2,false)),
                    List.of(new ProductionTrackingProcessService.Edge(codes.get(0),codes.get(1))),
                    "实库回滚验收"),"ROLLBACK_TEST");
            service.publish(templateId,"ROLLBACK_TEST");
            service.captureNewOrder(orderId);
            var first=service.snapshot(orderId);
            assertEquals("PUBLISHED",first.get("sourceKind"));
            @SuppressWarnings("unchecked") var nodes=(List<Map<String,Object>>)first.get("nodes");
            assertEquals(2,nodes.size());
            service.captureNewOrder(orderId);
            assertEquals(first,service.snapshot(orderId),"重复导入不得覆盖已捕获的快照");
            status.setRollbackOnly();
        });
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_snapshots "
                +"WHERE production_order_id=?",Integer.class,orderId));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_process_templates "
                +"WHERE product_config_id=? AND created_by='ROLLBACK_TEST'",Integer.class,configId));
    }

    private static JdbcTemplate jdbc() throws Exception {
        Map<String,String> env=new HashMap<>();
        for(String line:Files.readAllLines(Path.of(".env"))){
            int i=line.indexOf('=');
            if(i>0)env.put(line.substring(0,i),line.substring(i+1).trim());
        }
        var source=new DriverManagerDataSource(
                env.getOrDefault("SPRING_DATASOURCE_URL","jdbc:mysql://localhost:3306/product_archive"),
                env.getOrDefault("DB_USERNAME","root"),env.getOrDefault("DB_PASSWORD",""));
        return new JdbcTemplate(source);
    }
}
