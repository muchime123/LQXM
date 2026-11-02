import java.sql.*;
/** 只读验证：连库执行 nodeAnalyticsView 三条核心 SQL（简化可见性=JOIN milestones 限定有节点），确认可执行、返回结构合理。 */
public class _verify_node_analytics {
    public static void main(String[] a) throws Exception {
        var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/product_archive"
                +"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        String asg=" LEFT JOIN (SELECT MIN(id) id,name FROM employees WHERE status='ACTIVE' AND name IN ('陈苏平','胡健') GROUP BY name) assigned "
                +"ON assigned.name=CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END ";
        String nodeSql="SELECT COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,d.stage_code stage,"
                +"COALESCE(d.stage_label,d.stage_code) stageName,COUNT(DISTINCT p.id) involved,"
                +"COUNT(DISTINCT CASE WHEN m.stage_status='DONE' THEN p.id END) done "
                +"FROM production_orders p "+asg
                +"JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                +"JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1 "
                +"AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END "
                +"GROUP BY workshop,d.stage_code,d.stage_label,d.sort_no ORDER BY workshop,d.sort_no";
        System.out.println("== nodeCompletion ==");
        try(var st=c.createStatement();var r=st.executeQuery(nodeSql)){
            while(r.next()) System.out.println("  "+r.getString(1)+" | "+r.getString(2)+"("+r.getString(3)+") | done="+r.getLong(5)+"/"+r.getLong(4));
        }
        String waitSql="SELECT t.days day,COUNT(*) cnt FROM (SELECT DISTINCT p.id,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) days "
                +"FROM production_orders p "+asg
                +"JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                +"WHERE p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL) t "
                +"GROUP BY t.days ORDER BY t.days";
        System.out.println("== scheduleWait ==");
        try(var st=c.createStatement();var r=st.executeQuery(waitSql)){
            long s=0;while(r.next()){s+=r.getLong(2);System.out.println("  "+r.getLong(1)+"天 → "+r.getLong(2));}
            System.out.println("  sum="+s);
        }
        String anomSql="SELECT p.work_order_no orderNo,COALESCE(NULLIF(p.product_name,''),'') productName,"
                +"COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"DATE(p.source_created_at) submitted,DATE(p.schedule_time) scheduled,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) waitDays "
                +"FROM production_orders p "+asg
                +"JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                +"WHERE p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL "
                +"AND FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at)))>3 "
                +"ORDER BY waitDays DESC LIMIT 50";
        System.out.println("== scheduleWaitAnomalies (count) ==");
        try(var st=c.createStatement();var r=st.executeQuery("SELECT COUNT(*) FROM ("+anomSql+") x")){ r.next(); System.out.println("  "+r.getLong(1)+" 行"); }
        c.close();
        System.out.println("== done ==");
    }
}
