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

/** Read-only production delivery analysis, using exactly the tracking list's scope and delivery dates. */
@Service
@RequiredArgsConstructor
public class ProductionTrackingAnalyticsService {
    private final JdbcTemplate jdbc;
    private final ProductionTrackingService tracking;

    public Map<String,Object> analyze(String fromDate, String toDate, String workshop,String dateBasis) {
        return summarize(loadRows(fromDate,toDate,workshop,dateBasis).stream().map(AnalysisItem::row).toList(),LocalDate.now());
    }

    public Map<String,Object> orders(String fromDate,String toDate,String workshop,String dateBasis,String month,
                                     String bucket,String reason,int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        if(!BUCKETS.contains(bucket))throw new IllegalArgumentException("分析指标无效");
        if(month!=null&&!month.isBlank()&&!month.matches("\\d{4}-(0[1-9]|1[0-2])|未填写"))
            throw new IllegalArgumentException("提交月份无效");
        LocalDate today=LocalDate.now();
        var selected=loadRows(fromDate,toDate,workshop,dateBasis).stream()
                .filter(item->month==null||month.isBlank()||month.equals(item.row().submitted()==null?"未填写":item.row().submitted().toString().substring(0,7)))
                .filter(item->matches(item.row(),bucket,reason,today)).toList();
        long offset=(long)(page-1)*size;
        var result=new ArrayList<Map<String,Object>>();
        for(long i=offset;i<Math.min(offset+size,selected.size());i++){
            var item=selected.get((int)i);var row=item.row();
            var entry=new LinkedHashMap<String,Object>();
            entry.put("id",row.id());entry.put("orderNo",item.orderNo());entry.put("productName",item.productName());
            entry.put("workshop",row.workshop());entry.put("submittedDate",row.submitted());
            entry.put("baseline",row.baseline());entry.put("deliveredDate",row.delivered());
            entry.put("overdueDays",row.delivered()!=null&&row.baseline()!=null&&row.delivered().isAfter(row.baseline())
                    ?ChronoUnit.DAYS.between(row.baseline(),row.delivered()):null);
            result.add(entry);
        }
        return Map.of("items",result,"total",selected.size(),"page",page,"size",size);
    }

    private static final java.util.Set<String> BUCKETS=java.util.Set.of(
            "total","stopped","delivered","comparable","onTime","overdue","deliveredNoBaseline",
            "open","openOverdue","openDueSoon","openNoBaseline","requestedComparable","requestedOnTime",
            "extended","advanced","extensionReasonMissing","overdueReasonMissing","overdue1to3","overdue4to7",
            "overdue8plus","receiptOnly","manualOnly","bothSources","extensionReason","overdueReason","riskCategory");

    static boolean matches(OrderRow row,String bucket,String reason,LocalDate today){
        if("total".equals(bucket))return true;
        if("stopped".equals(bucket))return row.stopped();
        if(row.stopped())return false;
        boolean delivered=row.delivered()!=null,baseline=row.baseline()!=null;
        boolean extended=row.originalBaseline()!=null&&row.revisedBaseline()!=null
                &&row.revisedBaseline().isAfter(row.originalBaseline());
        boolean overdue=delivered&&baseline&&row.delivered().isAfter(row.baseline());
        long days=overdue?ChronoUnit.DAYS.between(row.baseline(),row.delivered()):0;
        return switch(bucket){
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
            default -> false;
        };
    }

    private List<AnalysisItem> loadRows(String fromDate,String toDate,String workshop,String dateBasis){
        LocalDate from=parseDate(fromDate), to=parseDate(toDate);
        if(from!=null && to!=null && from.isAfter(to)) throw new IllegalArgumentException("起始日期不能晚于截止日期");
        if(!java.util.Set.of("active","submitted","inbound").contains(dateBasis))throw new IllegalArgumentException("分析日期口径无效");
        var params=new ArrayList<Object>();
        var where=tracking.visible(params);
        appendDateBasis(where,params,from,to,dateBasis);
        if(workshop!=null&&!workshop.isBlank()){
            where.append(" AND COALESCE(NULLIF(p.production_line,''),'未分车间')=?");
            params.add(workshop.trim());
        }
        // No customer, price, or outbound data crosses this endpoint. Latest review reasons remain source text.
        String sql="SELECT p.id,p.work_order_no,p.product_name,COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"DATE(p.source_created_at) submitted_date,"
                +ProductionTrackingService.VALID_REQUIRED_DATE+" requested_date,"
                +"COALESCE(p.expected_completion_date,"+ProductionTrackingService.VALID_REQUIRED_DATE+") original_baseline,"
                +"t.forecast_finish_date revised_baseline,"
                +ProductionTrackingService.DELIVERY_BASELINE+" baseline,"
                +ProductionTrackingService.DELIVERED_IN_DATE+" delivered_date,"
                +"t.stopped_at stopped_at,t.risk_category risk_category,t.delay_reason delay_reason,"
                +"CASE WHEN ov.source_expected_date<=>"+ProductionTrackingService.DELIVERY_BASELINE
                +" AND ov.source_inbound_date<=>"+ProductionTrackingService.DELIVERED_IN_DATE
                +" THEN ov.reason END overdue_reason,cm.reason commitment_reason,"
                +"ib.first_in receipt_date,ms.warehouse_in_date manual_in_date "
                +ProductionTrackingService.TRACKING_FROM+ProductionTrackingService.INBOUND_JOIN
                +" LEFT JOIN production_tracking_time_reviews ov ON ov.id=(SELECT MAX(n.id) FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id AND n.kind='OVERDUE')"
                +" LEFT JOIN production_tracking_time_reviews cm ON cm.id=(SELECT MAX(n.id) FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id AND n.kind='COMMITMENT')"
                +where+" ORDER BY p.id";
        return jdbc.query(sql,(rs,n)->new AnalysisItem(new OrderRow(rs.getLong("id"),rs.getString("workshop"),
                date(rs.getDate("submitted_date")),date(rs.getDate("requested_date")),
                date(rs.getDate("original_baseline")),date(rs.getDate("revised_baseline")),
                date(rs.getDate("baseline")),date(rs.getDate("delivered_date")),
                rs.getTimestamp("stopped_at")!=null,rs.getString("risk_category"),
                rs.getString("delay_reason"),rs.getString("overdue_reason"),rs.getString("commitment_reason"),
                date(rs.getDate("receipt_date")),date(rs.getDate("manual_in_date"))),
                rs.getString("work_order_no"),rs.getString("product_name")),params.toArray());
    }

    static void appendDateBasis(StringBuilder where,List<Object> params,LocalDate from,LocalDate to,String dateBasis){
        switch(dateBasis){
            case "submitted" -> {
                if(from!=null){where.append(" AND p.source_created_at>=?");params.add(from);}
                if(to!=null){where.append(" AND p.source_created_at<?");params.add(to.plusDays(1));}
            }
            case "inbound" -> {
                where.append(" AND ").append(ProductionTrackingService.DELIVERED_IN_DATE).append(" IS NOT NULL");
                if(from!=null){where.append(" AND ").append(ProductionTrackingService.DELIVERED_IN_DATE).append(">=?");params.add(from);}
                if(to!=null){where.append(" AND ").append(ProductionTrackingService.DELIVERED_IN_DATE).append("<=?");params.add(to);}
            }
            case "active" -> {
                // A task belongs to the period if it had already started and was neither delivered nor stopped before it.
                if(to!=null){where.append(" AND COALESCE(DATE(p.source_created_at),p.application_date)<=?");params.add(to);}
                if(from!=null){
                    where.append(" AND (").append(ProductionTrackingService.DELIVERED_IN_DATE)
                            .append(" IS NULL OR ").append(ProductionTrackingService.DELIVERED_IN_DATE).append(">=?)");
                    params.add(from);
                    where.append(" AND (t.stopped_at IS NULL OR DATE(t.stopped_at)>=?)");params.add(from);
                }
            }
            default -> throw new IllegalArgumentException("分析日期口径无效");
        }
    }

    private static LocalDate date(java.sql.Date date){return date==null?null:date.toLocalDate();}
    private static LocalDate parseDate(String value){
        if(value==null||value.isBlank())return null;
        if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("提交日期格式应为 YYYY-MM-DD");
        try{return LocalDate.parse(value);}catch(DateTimeParseException e){throw new IllegalArgumentException("提交日期无效");}
    }

    static Map<String,Object> summarize(List<OrderRow> rows,LocalDate today){
        var totals=new Bucket();
        var workshops=new TreeMap<String,Bucket>();
        var months=new TreeMap<String,Bucket>();
        var riskReasons=new TreeMap<String,Integer>();
        var extensionReasons=new TreeMap<String,Integer>();
        var overdueReasons=new TreeMap<String,Integer>();
        for(var row:rows){
            totals.add(row,today);
            workshops.computeIfAbsent(row.workshop(),unused->new Bucket()).add(row,today);
            months.computeIfAbsent(row.submitted()==null?"未填写":row.submitted().toString().substring(0,7),unused->new Bucket()).add(row,today);
            if(row.stopped())continue;
            if(row.baseline()!=null && row.originalBaseline()!=null && row.revisedBaseline()!=null
                    && row.revisedBaseline().isAfter(row.originalBaseline())){
                count(extensionReasons,firstText(row.delayReason(),row.commitmentReason()));
                count(riskReasons,firstText(row.riskCategory()));
            }
            if(row.delivered()!=null && row.baseline()!=null && row.delivered().isAfter(row.baseline()))
                count(overdueReasons,firstText(row.overdueReason()));
        }
        var byWorkshop=new ArrayList<Map<String,Object>>();
        workshops.forEach((name,bucket)->{var data=new LinkedHashMap<String,Object>(bucket.asMap());data.put("name",name);byWorkshop.add(data);});
        var byMonth=new ArrayList<Map<String,Object>>();
        months.forEach((name,bucket)->{var data=new LinkedHashMap<String,Object>(bucket.asMap());data.put("name",name);byMonth.add(data);});
        return Map.of("totals",totals.asMap(),"workshops",byWorkshop,"months",byMonth,
                "riskCategories",rank(riskReasons,20),"extensionReasons",rank(extensionReasons,12),
                "overdueReasons",rank(overdueReasons,12),"asOf",today.toString());
    }

    private static void count(Map<String,Integer> counts,String reason){counts.merge(reason,1,Integer::sum);}
    private static String firstText(String... values){
        for(var value:values)if(value!=null&&!value.isBlank())return value.trim();
        return "未记录";
    }
    private static List<Map<String,Object>> rank(Map<String,Integer> counts,int limit){
        return counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey)).limit(limit)
                .map(e->Map.<String,Object>of("name",e.getKey(),"count",e.getValue())).toList();
    }

    record OrderRow(long id,String workshop,LocalDate submitted,LocalDate requested,LocalDate originalBaseline,
                    LocalDate revisedBaseline,LocalDate baseline,LocalDate delivered,boolean stopped,
                    String riskCategory,String delayReason,String overdueReason,String commitmentReason,
                    LocalDate receiptDate,LocalDate manualInDate) {}
    private record AnalysisItem(OrderRow row,String orderNo,String productName) {}

    private static final class Bucket {
        int total,stopped,delivered,comparable,onTime,overdue,deliveredNoBaseline;
        int open,openOverdue,openDueSoon,openNoBaseline,requestedComparable,requestedOnTime;
        int extended,advanced,extensionReasonMissing,overdueReasonMissing,overdue1to3,overdue4to7,overdue8plus;
        int receiptOnly,manualOnly,bothSources;
        long overdueDays,openOverdueDays,extensionDays;
        void add(OrderRow row,LocalDate today){
            total++;
            if(row.stopped()){stopped++;return;}
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
                }else if(!row.baseline().isAfter(today.plusDays(3)))openDueSoon++;
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
            m.put("total",total);m.put("stopped",stopped);m.put("delivered",delivered);
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
