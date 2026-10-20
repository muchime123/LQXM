package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only production due-date analysis; shares the tracking list's visibility, but uses an independent completion rule. */
@Service
@RequiredArgsConstructor
public class ProductionTrackingAnalyticsService {
    /** 分析专用：只认车间当前有效预计，绝不回退到业务员要求日。 */
    static final String EFFECTIVE_EXPECTED_DATE="COALESCE(t.forecast_finish_date,p.expected_completion_date)";
    /** 分析专用整单完工日：人工确认优先；未确认时须同产品累计入库数达到计划数。 */
    static final String COMPLETE_DATE="COALESCE(ms.warehouse_in_date,iq.full_in)";
    /**
     * 氚云任务名是短类目、采购品名带规格，不能按名称相等关联。仅当同任务编号下全部有效入库行
     * 品名和单位各自唯一、数量均为正数时，才按窗口累计推定整单完成；混合产品/单位留给跟单员核实。
     */
    static final String QUANTITY_JOIN=" LEFT JOIN (SELECT p2.id order_id,MIN(q.purchase_date) full_in "
            +"FROM production_orders p2 JOIN (SELECT i.production_order_no,r.purchase_date,"
            +"SUM(i.quantity) OVER (PARTITION BY i.production_order_no "
            +"ORDER BY r.purchase_date,r.id,i.id ROWS UNBOUNDED PRECEDING) running_quantity "
            +"FROM purchase_receipt_items i JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
            +"WHERE i.production_order_no IS NOT NULL AND i.production_order_no<>'' "
            +"AND r.purchase_date IS NOT NULL AND (r.data_status IS NULL OR r.data_status='已生效') "
            +") q ON q.production_order_no=p2.work_order_no "
            +"JOIN (SELECT i.production_order_no FROM purchase_receipt_items i "
            +"JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
            +"WHERE i.production_order_no IS NOT NULL AND i.production_order_no<>'' "
            +"AND r.purchase_date IS NOT NULL AND (r.data_status IS NULL OR r.data_status='已生效') "
            +"GROUP BY i.production_order_no HAVING COUNT(DISTINCT i.product_name)=1 "
            +"AND COUNT(DISTINCT i.unit)=1 AND COUNT(*)=COUNT(i.product_name) "
            +"AND COUNT(*)=COUNT(i.unit) AND COUNT(*)=COUNT(i.quantity) "
            +"AND MIN(i.quantity)>0) safe "
            +"ON safe.production_order_no=p2.work_order_no WHERE p2.required_quantity>0 "
            +"AND q.running_quantity>=p2.required_quantity GROUP BY p2.id) iq ON iq.order_id=p.id";
    private final JdbcTemplate jdbc;
    private final ProductionTrackingService tracking;

    public Map<String,Object> analyze(String fromDate, String toDate, String workshop,String dateBasis) {
        var result=new LinkedHashMap<>(summarize(loadRows(fromDate,toDate,workshop,dateBasis).stream()
                .map(AnalysisItem::row).toList(),LocalDate.now()));
        result.put("missingExpected",missingExpected(workshop));
        return result;
    }

    /** 无预计日期者不属于任一日期区间，单独给出同权限、同车间范围的核实数量。 */
    private long missingExpected(String workshop){
        var params=new ArrayList<Object>();
        var where=tracking.visible(params);
        where.append(" AND ").append(EFFECTIVE_EXPECTED_DATE).append(" IS NULL")
                .append(" AND (p.status IS NULL OR TRIM(p.status) NOT IN ('已取消','取消','已作废','作废','CANCELLED','CANCELED'))");
        if(workshop!=null&&!workshop.isBlank()){
            where.append(" AND COALESCE(NULLIF(p.production_line,''),'未分车间')=?");
            params.add(workshop.trim());
        }
        Long count=jdbc.queryForObject("SELECT COUNT(*)"+ProductionTrackingService.TRACKING_FROM+where,
                Long.class,params.toArray());
        return count==null?0:count;
    }

    public Map<String,Object> orders(String fromDate,String toDate,String workshop,String dateBasis,String month,
                                     String bucket,String reason,String reasonCategory,int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        if(!BUCKETS.contains(bucket))throw new IllegalArgumentException("分析指标无效");
        if(month!=null&&!month.isBlank()&&!month.matches("\\d{4}-(0[1-9]|1[0-2])|未填写"))
            throw new IllegalArgumentException("预计完成月份无效");
        // 类别下钻：类别是词典里的固定取值，白名单校验后才用于比较，避免把任意文本当作原因原文匹配。
        if(reasonCategory!=null&&!reasonCategory.isBlank()
                &&!ProductionTrackingReasonTaxonomy.keys().contains(reasonCategory))
            throw new IllegalArgumentException("原因类别无效");
        LocalDate today=LocalDate.now();
        var selected=loadRows(fromDate,toDate,workshop,dateBasis,"missingExpected".equals(bucket)).stream()
                .filter(item->month==null||month.isBlank()||month.equals(item.row().baseline()==null?"未填写":item.row().baseline().toString().substring(0,7)))
                .filter(item->matches(item.row(),bucket,reason,reasonCategory,today)).toList();
        long offset=(long)(page-1)*size;
        var result=new ArrayList<Map<String,Object>>();
        for(long i=offset;i<Math.min(offset+size,selected.size());i++){
            var item=selected.get((int)i);var row=item.row();
            var entry=new LinkedHashMap<String,Object>();
            entry.put("id",row.id());entry.put("orderNo",item.orderNo());entry.put("productName",item.productName());
            entry.put("workshop",row.workshop());entry.put("submittedDate",row.submitted());
            entry.put("baseline",row.baseline());entry.put("deliveredDate",row.delivered());
            entry.put("status",status(row));
            entry.put("overdueDays",row.delivered()!=null&&row.baseline()!=null&&row.delivered().isAfter(row.baseline())
                    ?ChronoUnit.DAYS.between(row.baseline(),row.delivered()):null);
            entry.put("unfinishedOverdueDays",row.delivered()==null&&row.baseline()!=null&&row.baseline().isBefore(today)
                    ?ChronoUnit.DAYS.between(row.baseline(),today):null);
            result.add(entry);
        }
        return Map.of("items",result,"total",selected.size(),"page",page,"size",size);
    }

    /** 包可见，供一致性测试穷尽遍历（新增 bucket 时测试自动覆盖，不必另抄一份名单）。 */
    static final java.util.Set<String> BUCKETS=java.util.Set.of(
            "total","dueTasks","cancelled","missingExpected","stopped","matured","openNotDue","openDueToday",
            "delivered","comparable","onTime","overdue","deliveredNoBaseline",
            "open","openOverdue","openDueSoon","openNoBaseline","requestedComparable","requestedOnTime",
            "extended","advanced","extensionReasonMissing","overdueReasonMissing","overdue1to3","overdue4to7",
            "overdue8plus","receiptOnly","manualOnly","bothSources","extensionReason","overdueReason","riskCategory",
            // 周复盘看板用：按「原因类别」下钻（六类原因见 ProductionTrackingReasonTaxonomy）
            "overdueReasonCategory","extensionReasonCategory");

    /** 不带原因类别的下钻走这里（卡片类 bucket 与一致性测试）。 */
    static boolean matches(OrderRow row,String bucket,String reason,LocalDate today){
        return matches(row,bucket,reason,null,today);
    }

    static boolean matches(OrderRow row,String bucket,String reason,String reasonCategory,LocalDate today){
        if("total".equals(bucket))return true;
        if("cancelled".equals(bucket))return row.cancelled();
        if("missingExpected".equals(bucket))return !row.cancelled()&&row.baseline()==null;
        if("stopped".equals(bucket))return row.stopped();
        if(row.cancelled())return false;
        boolean delivered=row.delivered()!=null,baseline=row.baseline()!=null;
        boolean extended=row.originalBaseline()!=null&&row.revisedBaseline()!=null
                &&row.revisedBaseline().isAfter(row.originalBaseline());
        boolean overdue=delivered&&baseline&&row.delivered().isAfter(row.baseline());
        long days=overdue?ChronoUnit.DAYS.between(row.baseline(),row.delivered()):0;
        return switch(bucket){
            case "dueTasks" -> true;
            // completed 已从名单移除：它与 delivered 完全同义，留着只会让人以为是两个指标
            case "matured" -> baseline&&!row.baseline().isAfter(today);
            case "openNotDue" -> !delivered&&baseline&&row.baseline().isAfter(today);
            case "openDueToday" -> !delivered&&baseline&&row.baseline().equals(today);
            case "delivered" -> delivered;
            case "comparable" -> delivered&&baseline;
            case "onTime" -> delivered&&baseline&&!overdue;
            case "overdue" -> overdue;
            case "deliveredNoBaseline" -> delivered&&!baseline;
            case "open" -> !delivered;
            case "openOverdue" -> !delivered&&baseline&&row.baseline().isBefore(today);
            case "openDueSoon" -> !delivered&&baseline&&!row.baseline().isBefore(today)&&!row.baseline().isAfter(today.plusDays(3));
            case "openNoBaseline" -> !delivered&&!baseline;
            case "requestedComparable" -> delivered&&row.requested()!=null;
            case "requestedOnTime" -> delivered&&row.requested()!=null&&!row.delivered().isAfter(row.requested());
            case "extended" -> extended;
            case "advanced" -> row.originalBaseline()!=null&&row.revisedBaseline()!=null
                    &&row.revisedBaseline().isBefore(row.originalBaseline());
            case "extensionReasonMissing" -> extended&&"未记录".equals(firstText(row.delayReason(),row.commitmentReason()));
            case "overdueReasonMissing" -> overdue&&"未记录".equals(firstText(row.overdueReason()));
            case "overdue1to3" -> overdue&&days<=3;
            case "overdue4to7" -> overdue&&days>=4&&days<=7;
            case "overdue8plus" -> overdue&&days>=8;
            case "receiptOnly" -> delivered&&row.receiptDate()!=null&&row.manualInDate()==null;
            case "manualOnly" -> delivered&&row.receiptDate()==null&&row.manualInDate()!=null;
            case "bothSources" -> delivered&&row.receiptDate()!=null&&row.manualInDate()!=null;
            case "extensionReason" -> extended&&firstText(row.delayReason(),row.commitmentReason()).equals(reason);
            case "overdueReason" -> overdue&&firstText(row.overdueReason()).equals(reason);
            case "riskCategory" -> extended&&firstText(row.riskCategory()).equals(reason);
            // 类别下钻：对原文先归类再比较，原文本身仍保留（见 ProductionTrackingReasonTaxonomy）
            case "extensionReasonCategory" -> extended&&ProductionTrackingReasonTaxonomy
                    .classify(firstText(row.delayReason(),row.commitmentReason())).equals(reasonCategory);
            case "overdueReasonCategory" -> overdue
                    &&ProductionTrackingReasonTaxonomy.classify(firstText(row.overdueReason())).equals(reasonCategory);
            default -> false;
        };
    }

    /** 分析状态互斥；停止本地跟单仍保留在生产履约集合中。 */
    static String status(OrderRow row){
        if(row.cancelled())return "CANCELLED";
        if(row.baseline()==null)return "MISSING_EXPECTED";
        if(row.delivered()==null)return "UNFINISHED";
        return row.delivered().isAfter(row.baseline())?"LATE":"ON_TIME";
    }

    /**
     * 周复盘专用：与 {@link #analyze} 同一批行、同一套口径，但两个原因榜**都不截断**。
     *
     * <p>【为什么单独一个入口】周复盘要把原因原文归并成类别（见 {@link ProductionTrackingReasonTaxonomy}），
     * 归并丢一条原文，类别之和就对不上总数，看板上就是「合计 10、分项加起来 7」。
     * 而抽屉展示用的是截断后的榜（延后原因截 12 条），那是给**人看**的，不能拿它当归并输入。
     */
    Map<String,Object> analyzeUncut(String fromDate,String toDate,String workshop){
        var rows=loadRows(fromDate,toDate,workshop,"baseline").stream().map(AnalysisItem::row).toList();
        var result=new LinkedHashMap<String,Object>(summarize(rows,LocalDate.now()));
        result.put("extensionReasons",rankAll(extensionReasonCounts(rows)));
        result.put("missingExpected",missingExpected(workshop));
        return result;
    }

    private List<AnalysisItem> loadRows(String fromDate,String toDate,String workshop,String dateBasis){
        return loadRows(fromDate,toDate,workshop,dateBasis,false);
    }

    private List<AnalysisItem> loadRows(String fromDate,String toDate,String workshop,String dateBasis,boolean missingExpected){
        LocalDate from=parseDate(fromDate), to=parseDate(toDate);
        if(from!=null && to!=null && from.isAfter(to)) throw new IllegalArgumentException("起始日期不能晚于截止日期");
        if(!"baseline".equals(dateBasis))throw new IllegalArgumentException("分析日期口径无效");
        var params=new ArrayList<Object>();
        var where=tracking.visible(params);
        if(missingExpected)where.append(" AND ").append(EFFECTIVE_EXPECTED_DATE).append(" IS NULL");
        else appendDateBasis(where,params,from,to,dateBasis);
        if(workshop!=null&&!workshop.isBlank()){
            where.append(" AND COALESCE(NULLIF(p.production_line,''),'未分车间')=?");
            params.add(workshop.trim());
        }
        // No customer, price, or outbound data crosses this endpoint. Latest review reasons remain source text.
        String sql="SELECT p.id,p.work_order_no,p.product_name,COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"DATE(p.source_created_at) submitted_date,"
                +ProductionTrackingService.VALID_REQUIRED_DATE+" requested_date,"
                +"p.expected_completion_date original_baseline,"
                +"t.forecast_finish_date revised_baseline,"
                +EFFECTIVE_EXPECTED_DATE+" baseline,"
                +COMPLETE_DATE+" delivered_date,"
                +"t.stopped_at stopped_at,CASE WHEN TRIM(p.status) IN ('已取消','取消','已作废','作废','CANCELLED','CANCELED') THEN 1 ELSE 0 END cancelled,"
                +"t.risk_category risk_category,t.delay_reason delay_reason,"
                +"CASE WHEN ov.source_expected_date<=>"+EFFECTIVE_EXPECTED_DATE
                +" AND ov.source_inbound_date<=>"+COMPLETE_DATE
                +" THEN ov.reason END overdue_reason,cm.reason commitment_reason,"
                +"ib.first_in receipt_date,ms.warehouse_in_date manual_in_date "
                +ProductionTrackingService.TRACKING_FROM+ProductionTrackingService.INBOUND_JOIN+QUANTITY_JOIN
                +" LEFT JOIN production_tracking_time_reviews ov ON ov.id=(SELECT MAX(n.id) FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id AND n.kind='OVERDUE')"
                +" LEFT JOIN production_tracking_time_reviews cm ON cm.id=(SELECT MAX(n.id) FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id AND n.kind='COMMITMENT')"
                +where+" ORDER BY p.id";
        return jdbc.query(sql,(rs,n)->new AnalysisItem(new OrderRow(rs.getLong("id"),rs.getString("workshop"),
                date(rs.getDate("submitted_date")),date(rs.getDate("requested_date")),
                date(rs.getDate("original_baseline")),date(rs.getDate("revised_baseline")),
                date(rs.getDate("baseline")),date(rs.getDate("delivered_date")),
                rs.getTimestamp("stopped_at")!=null,rs.getBoolean("cancelled"),rs.getString("risk_category"),
                rs.getString("delay_reason"),rs.getString("overdue_reason"),rs.getString("commitment_reason"),
                date(rs.getDate("receipt_date")),date(rs.getDate("manual_in_date"))),
                rs.getString("work_order_no"),rs.getString("product_name")),params.toArray());
    }

    static void appendDateBasis(StringBuilder where,List<Object> params,LocalDate from,LocalDate to,String dateBasis){
        if(!"baseline".equals(dateBasis))throw new IllegalArgumentException("分析日期口径无效");
        // 只按车间当前预计归入区间。实际完工日和业务员要求日均不影响归属。
        where.append(" AND ").append(EFFECTIVE_EXPECTED_DATE).append(" IS NOT NULL");
        if(from!=null){where.append(" AND ").append(EFFECTIVE_EXPECTED_DATE).append(">=?");params.add(from);}
        if(to!=null){where.append(" AND ").append(EFFECTIVE_EXPECTED_DATE).append("<=?");params.add(to);}
    }

    private static LocalDate date(java.sql.Date date){return date==null?null:date.toLocalDate();}
    private static LocalDate parseDate(String value){
        if(value==null||value.isBlank())return null;
        if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("预计完成日期格式应为 YYYY-MM-DD");
        try{return LocalDate.parse(value);}catch(DateTimeParseException e){throw new IllegalArgumentException("预计完成日期无效");}
    }

    static Map<String,Object> summarize(List<OrderRow> rows,LocalDate today){
        var totals=new Bucket();
        var workshops=new TreeMap<String,Bucket>();
        var months=new TreeMap<String,Bucket>();
        var riskReasons=new TreeMap<String,Integer>();
        for(var row:rows){
            totals.add(row,today);
            workshops.computeIfAbsent(row.workshop(),unused->new Bucket()).add(row,today);
            months.computeIfAbsent(row.baseline()==null?"未填写":row.baseline().toString().substring(0,7),unused->new Bucket()).add(row,today);
            if(row.cancelled())continue;
            if(isExtended(row))count(riskReasons,firstText(row.riskCategory()));
        }
        var byWorkshop=new ArrayList<Map<String,Object>>();
        workshops.forEach((name,bucket)->{var data=new LinkedHashMap<String,Object>(bucket.asMap());data.put("name",name);byWorkshop.add(data);});
        var byMonth=new ArrayList<Map<String,Object>>();
        months.forEach((name,bucket)->{var data=new LinkedHashMap<String,Object>(bucket.asMap());data.put("name",name);byMonth.add(data);});
        return Map.of("totals",totals.asMap(),"workshops",byWorkshop,"months",byMonth,
                "riskCategories",rank(riskReasons,20),
                // 延后原因榜截 12 条：这是给「人看」的榜；周复盘要拿它归并成类别，
                // 归并丢一条原文就会少一单，故那边另走 analyzeUncut 取全量（见该方法注释）。
                "extensionReasons",rank(extensionReasonCounts(rows),12),
                // ⚠️ 不截断：逾期原因目前是跟单员自由填写的原文，截断会让「分桶逾期数 ≠ 原因之和」，
                // 也看不到长尾写法——正是要拿全量原样本来归纳出后续更精确的原因选项，故全量返回。
                "overdueReasons",rankAll(overdueReasonCounts(rows)),"asOf",today.toString());
    }

    /**
     * 协商延后的唯一判据：跟单员确认后的车间预计晚于源任务的原车间预计。
     * 卡片（Bucket.extended）、原因榜、下钻三处共用这一份，避免各写一套。
     */
    private static boolean isExtended(OrderRow row){
        return row.baseline()!=null&&row.originalBaseline()!=null&&row.revisedBaseline()!=null
                &&row.revisedBaseline().isAfter(row.originalBaseline());
    }
    /** 延期原因原文榜的输入：原文优先取状态行的 delay_reason，为空时取 COMMITMENT 核实记录。包可见供测试对拍。 */
    static Map<String,Integer> extensionReasonCounts(List<OrderRow> rows){
        var counts=new TreeMap<String,Integer>();
        for(var row:rows){
            if(row.cancelled())continue;
            if(isExtended(row))count(counts,firstText(row.delayReason(),row.commitmentReason()));
        }
        return counts;
    }
    /** 逾期原因原文榜的输入：只认整单完工晚于基准、且快照仍与当前一致的那条 OVERDUE 核实记录。包可见供测试对拍。 */
    static Map<String,Integer> overdueReasonCounts(List<OrderRow> rows){
        var counts=new TreeMap<String,Integer>();
        for(var row:rows){
            if(row.cancelled())continue;
            if(row.delivered()!=null&&row.baseline()!=null&&row.delivered().isAfter(row.baseline()))
                count(counts,firstText(row.overdueReason()));
        }
        return counts;
    }

    private static void count(Map<String,Integer> counts,String reason){counts.merge(reason,1,Integer::sum);}
    private static String firstText(String... values){
        for(var value:values)if(value!=null&&!value.isBlank())return value.trim();
        return "未记录";
    }
    /** 全量返回，只排序不截断（自由文本原因榜用：要的就是完整原样本）。 */
    private static List<Map<String,Object>> rankAll(Map<String,Integer> counts){return rank(counts,Integer.MAX_VALUE);}
    private static List<Map<String,Object>> rank(Map<String,Integer> counts,int limit){
        return counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey)).limit(limit)
                .map(e->Map.<String,Object>of("name",e.getKey(),"count",e.getValue())).toList();
    }

    record OrderRow(long id,String workshop,LocalDate submitted,LocalDate requested,LocalDate originalBaseline,
                    LocalDate revisedBaseline,LocalDate baseline,LocalDate delivered,boolean stopped,boolean cancelled,
                    String riskCategory,String delayReason,String overdueReason,String commitmentReason,
                    LocalDate receiptDate,LocalDate manualInDate) {
        OrderRow(long id,String workshop,LocalDate submitted,LocalDate requested,LocalDate originalBaseline,
                 LocalDate revisedBaseline,LocalDate baseline,LocalDate delivered,boolean stopped,
                 String riskCategory,String delayReason,String overdueReason,String commitmentReason,
                 LocalDate receiptDate,LocalDate manualInDate){
            this(id,workshop,submitted,requested,originalBaseline,revisedBaseline,baseline,delivered,stopped,false,
                    riskCategory,delayReason,overdueReason,commitmentReason,receiptDate,manualInDate);
        }
    }
    private record AnalysisItem(OrderRow row,String orderNo,String productName) {}

    private static final class Bucket {
        int total,dueTasks,cancelled,stopped,matured,openNotDue,openDueToday,delivered,comparable,onTime,overdue,deliveredNoBaseline;
        int open,openOverdue,openDueSoon,openNoBaseline,requestedComparable,requestedOnTime;
        int extended,advanced,extensionReasonMissing,overdueReasonMissing,overdue1to3,overdue4to7,overdue8plus;
        int receiptOnly,manualOnly,bothSources;
        long overdueDays,openOverdueDays,extensionDays;
        void add(OrderRow row,LocalDate today){
            total++;
            if(row.stopped())stopped++;
            if(row.cancelled()){cancelled++;return;}
            dueTasks++;
            if(row.baseline()!=null&&!row.baseline().isAfter(today))matured++;
            if(row.originalBaseline()!=null && row.revisedBaseline()!=null){
                long delta=ChronoUnit.DAYS.between(row.originalBaseline(),row.revisedBaseline());
                if(delta>0){extended++;extensionDays+=delta;
                    if(firstText(row.delayReason(),row.commitmentReason()).equals("未记录"))extensionReasonMissing++;
                }else if(delta<0)advanced++;
            }
            if(row.delivered()==null){
                open++;
                if(row.baseline()==null)openNoBaseline++;
                else if(row.baseline().isBefore(today)){
                    openOverdue++;openOverdueDays+=ChronoUnit.DAYS.between(row.baseline(),today);
                }else if(row.baseline().equals(today)){openDueToday++;openDueSoon++;}
                else {openNotDue++;if(!row.baseline().isAfter(today.plusDays(3)))openDueSoon++;}
                return;
            }
            delivered++;
            if(row.receiptDate()!=null && row.manualInDate()!=null)bothSources++;
            else if(row.receiptDate()!=null)receiptOnly++;
            else if(row.manualInDate()!=null)manualOnly++;
            if(row.requested()!=null){requestedComparable++;if(!row.delivered().isAfter(row.requested()))requestedOnTime++;}
            if(row.baseline()==null){deliveredNoBaseline++;return;}
            comparable++;
            long late=ChronoUnit.DAYS.between(row.baseline(),row.delivered());
            if(late<=0){onTime++;return;}
            overdue++;overdueDays+=late;
            if(late<=3)overdue1to3++;else if(late<=7)overdue4to7++;else overdue8plus++;
            if(firstText(row.overdueReason()).equals("未记录"))overdueReasonMissing++;
        }
        Map<String,Object> asMap(){
            var m=new LinkedHashMap<String,Object>();
            m.put("total",total);m.put("dueTasks",dueTasks);m.put("cancelled",cancelled);m.put("stopped",stopped);m.put("matured",matured);
            m.put("openNotDue",openNotDue);m.put("openDueToday",openDueToday);m.put("delivered",delivered);
            m.put("completionRate",rate(delivered,dueTasks));m.put("onTimeAchievementRate",rate(onTime,dueTasks));
            m.put("completedOnTimeRate",rate(onTime,delivered));
            m.put("comparable",comparable);m.put("onTime",onTime);m.put("overdue",overdue);
            m.put("onTimeRate",rate(onTime,comparable));m.put("deliveredNoBaseline",deliveredNoBaseline);
            m.put("open",open);m.put("openOverdue",openOverdue);m.put("openDueSoon",openDueSoon);
            m.put("openNoBaseline",openNoBaseline);m.put("openOverdueDays",openOverdueDays);
            m.put("requestedComparable",requestedComparable);m.put("requestedOnTime",requestedOnTime);
            m.put("requestedOnTimeRate",rate(requestedOnTime,requestedComparable));
            m.put("extended",extended);m.put("advanced",advanced);m.put("extensionDays",extensionDays);
            m.put("extensionReasonMissing",extensionReasonMissing);m.put("overdueReasonMissing",overdueReasonMissing);
            m.put("overdueDays",overdueDays);m.put("averageOverdueDays",overdue==0?null:Math.round(overdueDays*10.0/overdue)/10.0);
            m.put("overdue1to3",overdue1to3);m.put("overdue4to7",overdue4to7);m.put("overdue8plus",overdue8plus);
            m.put("receiptOnly",receiptOnly);m.put("manualOnly",manualOnly);m.put("bothSources",bothSources);
            return m;
        }
        private static Double rate(long numerator,long denominator){return denominator==0?null:Math.round(numerator*1000.0/denominator)/10.0;}
    }
}
