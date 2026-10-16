package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only verification against the imported purchase receipts. Run with -Derp.foundation.mysql=true. */
class ProductionTrackingAnalyticsMysqlTest {
    @Test
    @EnabledIfSystemProperty(named="erp.foundation.mysql", matches="true")
    void quantityCompletionReachesThePlannedAmountOnItsFirstDate() throws Exception {
        var jdbc=jdbc();
        String from=" FROM production_orders p "+ProductionTrackingService.INBOUND_JOIN
                +ProductionTrackingAnalyticsService.QUANTITY_JOIN;
        var coverage=jdbc.queryForMap("SELECT COUNT(*) tasks,"
                +"COALESCE(SUM(ib.first_in IS NOT NULL),0) any_receipt,"
                +"COALESCE(SUM(iq.full_in IS NOT NULL),0) quantity_complete,"
                +"COALESCE(SUM(ib.first_in IS NOT NULL AND iq.full_in IS NULL),0) receipt_without_full_quantity "
                +from+" WHERE p.external_source='H3YUN' AND p.required_quantity>0");
        System.out.println("[交期分析实库] "+coverage);
        assertTrue(((Number)coverage.get("quantity_complete")).longValue()>0,
                "采购数量兜底不能长期为 0；请检查任务编号、品名/单位及入库状态的映射规则");

        var completed=jdbc.queryForList("SELECT p.work_order_no,p.required_quantity,iq.full_in "
                +from+" WHERE p.external_source='H3YUN' AND iq.full_in IS NOT NULL ORDER BY p.id LIMIT 50");
        for(var row:completed){
            var shape=jdbc.queryForMap("SELECT COUNT(DISTINCT i.product_name) names_,"
                    +"COUNT(DISTINCT i.unit) units_,MIN(i.quantity) min_qty,"
                    +"COUNT(*) rows_,COUNT(i.quantity) quantity_rows "
                    +"FROM purchase_receipt_items i JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
                    +"WHERE i.production_order_no=? AND r.purchase_date IS NOT NULL "
                    +"AND (r.data_status IS NULL OR r.data_status='已生效')",row.get("work_order_no"));
            assertTrue(((Number)shape.get("names_")).longValue()==1
                    && ((Number)shape.get("units_")).longValue()==1
                    && ((Number)shape.get("rows_")).longValue()==((Number)shape.get("quantity_rows")).longValue()
                    && ((BigDecimal)shape.get("min_qty")).signum()>0,
                    "自动推定完工不能混用不同品名、单位或无效数量："+row.get("work_order_no"));
            String sql="SELECT COALESCE(SUM(i.quantity),0) FROM purchase_receipt_items i "
                    +"JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
                    +"WHERE i.production_order_no=? "
                    +"AND r.purchase_date IS NOT NULL AND (r.data_status IS NULL OR r.data_status='已生效') "
                    +"AND r.purchase_date ";
            Object[] key={row.get("work_order_no"),row.get("full_in")};
            BigDecimal required=(BigDecimal)row.get("required_quantity");
            BigDecimal byDate=jdbc.queryForObject(sql+"<=?",BigDecimal.class,key);
            BigDecimal beforeDate=jdbc.queryForObject(sql+"<?",BigDecimal.class,key);
            assertTrue(byDate!=null&&byDate.compareTo(required)>=0,"完工日累计量不足："+row.get("work_order_no"));
            assertTrue(beforeDate!=null&&beforeDate.compareTo(required)<0,"完工日早于首次达到计划量："+row.get("work_order_no"));
        }
        assertTrue(((Number)coverage.get("tasks")).longValue()>=completed.size());
    }

    private static JdbcTemplate jdbc() throws Exception {
        Map<String,String> env=new HashMap<>();
        for(String line:Files.readAllLines(Path.of(".env"))){
            int i=line.indexOf('=');
            if(i>0)env.put(line.substring(0,i),line.substring(i+1).trim());
        }
        var source=new DriverManagerDataSource(env.getOrDefault("SPRING_DATASOURCE_URL","jdbc:mysql://localhost:3306/product_archive"),
                env.getOrDefault("DB_USERNAME","root"),env.getOrDefault("DB_PASSWORD",""));
        return new JdbcTemplate(source);
    }
}
