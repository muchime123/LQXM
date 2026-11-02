import java.sql.*;
/** 只读验证：连库执行 nodeAnalyticsOrders 两类下钻 SQL（简化 visible=JOIN milestones），确认可执行、返回结构合理、含 id。 */
public class _verify_node_orders {
    public static void main(String[] a) throws Exception {
        var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/product_archive"
                +"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        String asg=" LEFT JOIN (SELECT MIN(id) id,name FROM employees WHERE status='ACTIVE' AND name IN ('陈苏平','胡健') GROUP BY name) assigned "
                +"ON assigned.name=CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END ";
        String cols="SELECT p.id id,p.work_order_no orderNo,p.product_name productName,"
                +"COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"DATE(p.source_created_at) submittedDate,DATE(p.schedule_time) scheduleTime,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) waitDays,";

        System.out.println("== A) 节点下钻：封口机 + MOLD（含 stageStatus）==");
        String nodeSql=cols+"m.stage_status stageStatus "
                +"FROM production_orders p "+asg
                +"JOIN production_tracking_milestones m ON m.production_order_id=p.id AND m.stage_code='MOLD' "
                +"JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1 "
                +"AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END "
                +"WHERE p.production_line LIKE '%封口%' ORDER BY p.id DESC LIMIT 5";
        try(var st=c.createStatement();var r=st.executeQuery(nodeSql)){
            int n=0;while(r.next()){n++;System.out.println("  id="+r.getLong(1)+" "+r.getString(2)+" | 车间="+r.getString(4)+" | 等待="+r.getLong(7)+"天 | 节点="+r.getString(8));}
            System.out.println("  返回 "+n+" 行");
        }

        System.out.println("== B) 等待下钻：waitDays=0（按单去重）==");
        String wait0=cols+"NULL stageStatus "
                +"FROM production_orders p "+asg
                +"JOIN (SELECT DISTINCT production_order_id FROM production_tracking_milestones) m ON m.production_order_id=p.id "
                +"WHERE p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL "
                +"AND FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at)))=0 ORDER BY p.id DESC LIMIT 5";
        try(var st=c.createStatement();var r=st.executeQuery(wait0)){
            int n=0;while(r.next()){n++;System.out.println("  id="+r.getLong(1)+" "+r.getString(2)+" | 等待="+r.getLong(7)+"天");}
            System.out.println("  返回 "+n+" 行");
        }

        System.out.println("== C) 等待>3 天：waitDaysMin=4（概览卡）==");
        String waitMin="SELECT COUNT(*) cnt FROM (SELECT p.id "
                +"FROM production_orders p "+asg
                +"JOIN (SELECT DISTINCT production_order_id FROM production_tracking_milestones) m ON m.production_order_id=p.id "
                +"WHERE p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL "
                +"AND FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at)))>=4) x";
        try(var st=c.createStatement();var r=st.executeQuery(waitMin)){ r.next(); System.out.println("  >3天单数="+r.getLong(1)); }

        System.out.println("== D) 节点下钻去重：该节点一单是否仅一行（封口机 MOLD COUNT）==");
        String dup="SELECT COUNT(*) rowsCnt,COUNT(DISTINCT p.id) singleCnt FROM production_orders p "+asg
                +"JOIN production_tracking_milestones m ON m.production_order_id=p.id AND m.stage_code='MOLD' "
                +"JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1 "
                +"AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END "
                +"WHERE p.production_line LIKE '%封口%'";
        try(var st=c.createStatement();var r=st.executeQuery(dup)){ r.next(); System.out.println("  行数="+r.getLong(1)+" 单数="+r.getLong(2)); }
        c.close();
        System.out.println("== done ==");
    }
}
