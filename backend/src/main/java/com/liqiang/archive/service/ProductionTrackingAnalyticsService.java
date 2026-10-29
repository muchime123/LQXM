package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    /**
     * 节点分析的<b>参与范围</b>：未停止、未取消。
     *
     * <p>停产任务<b>不进入</b>覆盖任务数 / 节点完成率 / 排产等待分布与任何下钻；
     * 既不删除节点记录、也不改原单的停产状态 —— 恢复生产（撤销 stopped_at）后任务自动回到统计里。
     * 取消名单与生产分析 {@code cancelled} 判据同名，避免两处各写一份。
     */
    private static final String ACTIVE_SCOPE=" AND t.stopped_at IS NULL "
            +"AND (p.status IS NULL OR TRIM(p.status) NOT IN ('已取消','取消','已作废','作废','CANCELLED','CANCELED'))";

    /**
     * 整单是否<b>仍在产</b>（＝还没被跟单判定为完成），取 1 表示仍在产。
     *
     * <p>优先级与生产跟单列表完全一致（同一个 {@code TRACKING_STATUS_CASE} 的前两级）：
     * ① 停产 / 取消 —— 已由 {@link #ACTIVE_SCOPE} 排除；
     * ② 跟单员主动结案（{@code closed_at}，语义见 {@code ClosureRequest}：主动确认结束，与停止互斥）；
     * ③ 本单<b>最后适用节点</b>已完成 —— 直接复用 {@code TRACKING_COMPLETE}，它把「必选节点 + 本单适用的按需节点」
     *    （有记录的，或源单配置写明需要的）都算进分母，因此入库之后还要做木箱的单<b>不会被提前判成完工</b>。
     *
     * <p>刻意<b>不看</b> {@code p.actual_completion_date}：那是源单字段，这批工单常年不回填
     * （SCGD20260005428 七个节点全 DONE、入库也是 DONE，该列依然为空），
     * 用它判完工会把已完工的历史单算成「当前超期」并随日期一直累加。
     * 也刻意<b>不把「已登记入库」当作无条件完工信号</b>：那等于给木箱这类后置按需节点开一条绕过捷径。
     *
     * <p>「仍在产」是<b>跟单维度</b>的状态，不等于「车间还在加工」：源单已填整单完工日但节点没登记齐的单也会落在里面
     * （当前 26 单里有 24 单如此）。它们是<b>待跟单员核实</b>的对象，不是系统认定的生产逾期。
     */
    private static final String OPEN_TRACKING="CASE WHEN t.closed_at IS NOT NULL THEN 0 "
            +"WHEN "+ProductionTrackingService.TRACKING_COMPLETE+" THEN 0 ELSE 1 END";

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
                .append(" AND t.stopped_at IS NULL")
                .append(" AND (p.status IS NULL OR TRIM(p.status) NOT IN ('已取消','取消','已作废','作废','CANCELLED','CANCELED'))");
        if(workshop!=null&&!workshop.isBlank()){
            where.append(" AND COALESCE(NULLIF(p.production_line,''),'未分车间')=?");
            params.add(workshop.trim());
        }
        Long count=jdbc.queryForObject("SELECT COUNT(*)"+ProductionTrackingService.TRACKING_FROM+where,
                Long.class,params.toArray());
        return count==null?0:count;
    }

    /** 不带排序的下钻走这里：沿用既有顺序，其他所有指标的默认排序不受影响。 */
    public Map<String,Object> orders(String fromDate,String toDate,String workshop,String dateBasis,String month,
                                     String bucket,String reason,String reasonCategory,int page,int size){
        return orders(fromDate,toDate,workshop,dateBasis,month,bucket,reason,reasonCategory,page,size,null);
    }

    public Map<String,Object> orders(String fromDate,String toDate,String workshop,String dateBasis,String month,
                                     String bucket,String reason,String reasonCategory,int page,int size,String sort){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        if(!BUCKETS.contains(bucket))throw new IllegalArgumentException("分析指标无效");
        if(sort!=null&&!sort.isBlank()&&!SORTS.contains(sort))throw new IllegalArgumentException("排序方式无效");
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
        // 排序必须发生在切片**之前**：这里是先取全量再按 offset 取页，若只在前端重排已拿到的 20 条，
        // 得到的只是「最近创建的 20 张单」换个顺序，真正超期最久的老单根本不在这批数据里。
        var ordered="openUrgency".equals(sort)?selected.stream().sorted(openUrgency(today)).toList():selected;
        long offset=(long)(page-1)*size;
        var result=new ArrayList<Map<String,Object>>();
        for(long i=offset;i<Math.min(offset+size,ordered.size());i++){
            var item=ordered.get((int)i);var row=item.row();
            var entry=new LinkedHashMap<String,Object>();
            entry.put("id",row.id());entry.put("orderNo",item.orderNo());entry.put("productName",item.productName());
            // 下钻列表用：业务员 / 产品规格 / 模具出数。三者都只做展示，缺失时为 null（前端显示「—」）。
            entry.put("salesperson",row.salesperson());
            entry.put("specification",row.specification());
            entry.put("moldLayout",row.moldLayout());
            entry.put("workshop",row.workshop());entry.put("submittedDate",row.submitted());
            entry.put("baseline",row.baseline());entry.put("deliveredDate",row.delivered());
            entry.put("status",status(row));
            entry.put("overdueDays",row.delivered()!=null&&row.baseline()!=null&&row.delivered().isAfter(row.baseline())
                    ?ChronoUnit.DAYS.between(row.baseline(),row.delivered()):null);
            entry.put("unfinishedOverdueDays",!row.stopped()&&row.delivered()==null&&row.baseline()!=null&&row.baseline().isBefore(today)
                    ?ChronoUnit.DAYS.between(row.baseline(),today):null);
            result.add(entry);
        }
        return Map.of("items",result,"total",ordered.size(),"page",page,"size",size);
    }

    /** 可选排序白名单：只有显式传入才生效，其余下钻沿用既有顺序（见不带 sort 的重载）。 */
    private static final java.util.Set<String> SORTS=java.util.Set.of("openUrgency");

    /**
     * 周复盘「未完工清单」专用排序，仅在显式传入 {@code sort=openUrgency} 时使用。
     *
     * <p>【顺序】① 已超期的未完工优先 → ② 超期天数多的在前 → ③ 未到期按预计完成日期由近及远
     * → ④ 预计日期相同（或无预计日期）按工单 id 倒序。
     *
     * <p>【为什么单独开一个排序项而不是改默认顺序】这个接口同时承担生产分析等其他指标的下钻，
     * 改默认顺序会影响它们；周复盘第四节要的是「先处理哪几单」，故只给它单独启用。
     */
    private static java.util.Comparator<AnalysisItem> openUrgency(LocalDate today){
        java.util.Comparator<AnalysisItem> byBaseline=java.util.Comparator.comparing(
                item->item.row().baseline(),
                java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>naturalOrder()));
        return (a,b)->{
            Integer oa=openOverdueDays(a.row(),today),ob=openOverdueDays(b.row(),today);
            // 天数相同时必须继续往下走兜底规则，不能直接判 a 在前 —— 否则 compare(a,b) 与 compare(b,a)
            // 会同时返回 -1，违反比较器的反对称约定，列表变长后 TimSort 会直接抛异常。
            if(oa!=null&&ob!=null){ if(!oa.equals(ob))return Integer.compare(ob,oa); }
            else if(oa!=null)return -1;
            else if(ob!=null)return 1;
            int byDate=byBaseline.compare(a,b);
            return byDate!=0?byDate:Long.compare(b.row().id(),a.row().id());
        };
    }

    /** 未完工任务截至 today 已超期的天数；未超期（未到期 / 已完工 / 已停产 / 无预计日期）返回 null。 */
    private static Integer openOverdueDays(OrderRow row,LocalDate today){
        return !row.stopped()&&row.delivered()==null&&row.baseline()!=null&&row.baseline().isBefore(today)
                ?(int)ChronoUnit.DAYS.between(row.baseline(),today):null;
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
        if("missingExpected".equals(bucket))return !row.cancelled()&&!row.stopped()&&row.baseline()==null;
        if("stopped".equals(bucket))return row.stopped()&&!row.cancelled();
        if(row.cancelled()||row.stopped())return false;
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

    /** 停产任务单列，恢复生产后重新进入正常履约集合。 */
    static String status(OrderRow row){
        if(row.cancelled())return "CANCELLED";
        if(row.stopped())return "STOPPED";
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

    /**
     * 周期快照：把当前计算的分析结果写入快照表，供历史周报回看。
     *
     * <p>幂等：同一 (period_from,period_to,workshop) 已有快照则跳过（靠唯一键 + ON DUPLICATE KEY UPDATE id=id），
     * 保证「交期调整导致历史周期数字漂移」从部署起不再发生。
     *
     * <p>降级：快照表尚未创建（Flyway 未执行 V419）或序列化失败时，静默返回现算结果、不落库、不抛错，
     * 保证现有周复盘功能不受影响；表建好后自动启用快照。
     *
     * @return 本次生效的分析结果（无既有快照时为现算并固化；有既有快照时通常不会走到本方法）
     */
    Map<String,Object> snapshotForPeriod(String fromDate,String toDate,String workshop,Long employeeId){
        var result=analyzeUncut(fromDate,toDate,workshop);
        LocalDate from=parseDate(fromDate), to=parseDate(toDate);
        if(from==null||to==null)return result; // 缺周期不落快照
        try{
            String json=objectMapper.writeValueAsString(result);
            jdbc.update("INSERT INTO production_tracking_analytics_snapshots"
                    +"(period_from,period_to,workshop,snapshot_json,generated_by_employee_id) VALUES(?,?,?,?,?)"
                    +" ON DUPLICATE KEY UPDATE id=id",from,to,blankToNull(workshop),json,employeeId);
        }catch(Exception e){
            return result; // 降级：不影响现有周复盘
        }
        return result;
    }

    /**
     * 读取已固化的周期快照；无快照、或快照表未建/读取失败时返回 null（调用方据此回退现算）。
     */
    Map<String,Object> readSnapshot(String fromDate,String toDate,String workshop){
        LocalDate from=parseDate(fromDate), to=parseDate(toDate);
        if(from==null||to==null)return null;
        try{
            String json=jdbc.query("SELECT snapshot_json FROM production_tracking_analytics_snapshots"
                    +" WHERE period_from=? AND period_to=? AND workshop<=>? LIMIT 1",
                    rs->rs.next()?rs.getString(1):null,from,to,blankToNull(workshop));
            if(json==null)return null;
            @SuppressWarnings("unchecked")
            Map<String,Object> m=objectMapper.readValue(json,Map.class);
            return m;
        }catch(Exception e){
            return null; // 降级：表未建或读取失败视为无快照
        }
    }

    /**
     * 已固化的历史周期列表（含各周期 totals 摘要），供趋势看板使用。
     * <p>快照表未建（迁移未执行）或读取失败时返回空列表，前端显示「暂无历史快照」。
     */
    public List<Map<String,Object>> listSnapshots(String workshop){
        try{
            return jdbc.query("SELECT period_from,period_to,workshop,generated_at,snapshot_json "
                    +"FROM production_tracking_analytics_snapshots WHERE workshop<=>? "
                    +"ORDER BY period_to DESC,period_from DESC",
                    (rs,n)->{
                        var row=new LinkedHashMap<String,Object>();
                        row.put("periodFrom",rs.getDate("period_from").toLocalDate().toString());
                        row.put("periodTo",rs.getDate("period_to").toLocalDate().toString());
                        row.put("workshop",rs.getString("workshop"));
                        row.put("generatedAt",rs.getTimestamp("generated_at").toLocalDateTime().toString());
                        try{
                            @SuppressWarnings("unchecked")
                            Map<String,Object> snap=objectMapper.readValue(rs.getString("snapshot_json"),Map.class);
                            row.put("asOf",snap.get("asOf"));
                            row.put("totals",snap.get("totals"));
                        }catch(Exception e){ /* 单条快照解析失败不影响整体列表 */ }
                        return row;
                    }, blankToNull(workshop));
        }catch(Exception e){
            return List.of();
        }
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
                // 下钻列表要能一眼认出「谁的单、做什么规格」，故补业务员与产品规格/模具出数：
                // 规格取 CONCAT_WS(' / ',box_size,box_shape,box_material)、模具取 mold_layout，
                // 与生产跟单列表 LIST_COLUMNS 的 specification_snapshot / mold_layout **逐字同源**——
                // 分析页看到「120×80 / 双层」而跟单页看到别的写法，就是口径分叉，必须避免。
                +"p.salesperson_name salesperson,"
                +"CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) specification,"
                +"p.mold_layout mold_layout,"
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
                date(rs.getDate("receipt_date")),date(rs.getDate("manual_in_date")),
                // CONCAT_WS 遇到全空列会返回空串而不是 null，这里统一折成 null——
                // 下钻列表要把「没有规格」显示成「—」，而不是一个空白的格子。
                blankToNull(rs.getString("salesperson")),blankToNull(rs.getString("specification")),
                blankToNull(rs.getString("mold_layout"))),
                rs.getString("work_order_no"),rs.getString("product_name")),params.toArray());
    }

    /** 空串归一成 null：展示侧（前端）用 null 判「—」，避免空串与「未填写」两种假值并存。 */
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}

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
            if(row.cancelled()||row.stopped())continue;
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
            if(row.cancelled()||row.stopped())continue;
            if(isExtended(row))count(counts,firstText(row.delayReason(),row.commitmentReason()));
        }
        return counts;
    }
    /** 逾期原因原文榜的输入：只认整单完工晚于基准、且快照仍与当前一致的那条 OVERDUE 核实记录。包可见供测试对拍。 */
    static Map<String,Integer> overdueReasonCounts(List<OrderRow> rows){
        var counts=new TreeMap<String,Integer>();
        for(var row:rows){
            if(row.cancelled()||row.stopped())continue;
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

    /**
     * 分析用的一单一行。<b>后三个字段（业务员 / 产品规格 / 模具出数）只供下钻列表展示，不参与任何判据</b>——
     * 卡片数量、一致性校验都只看前 16 个字段，加列不会改变任何统计口径。
     * 放在末尾是为了让测试与既有构造调用不必改动（保留下面的兼容构造器）。
     */
    record OrderRow(long id,String workshop,LocalDate submitted,LocalDate requested,LocalDate originalBaseline,
                    LocalDate revisedBaseline,LocalDate baseline,LocalDate delivered,boolean stopped,boolean cancelled,
                    String riskCategory,String delayReason,String overdueReason,String commitmentReason,
                    LocalDate receiptDate,LocalDate manualInDate,
                    String salesperson,String specification,String moldLayout) {
        OrderRow(long id,String workshop,LocalDate submitted,LocalDate requested,LocalDate originalBaseline,
                 LocalDate revisedBaseline,LocalDate baseline,LocalDate delivered,boolean stopped,
                 String riskCategory,String delayReason,String overdueReason,String commitmentReason,
                 LocalDate receiptDate,LocalDate manualInDate){
            this(id,workshop,submitted,requested,originalBaseline,revisedBaseline,baseline,delivered,stopped,false,
                    riskCategory,delayReason,overdueReason,commitmentReason,receiptDate,manualInDate,null,null,null);
        }
        OrderRow(long id,String workshop,LocalDate submitted,LocalDate requested,LocalDate originalBaseline,
                 LocalDate revisedBaseline,LocalDate baseline,LocalDate delivered,boolean stopped,boolean cancelled,
                 String riskCategory,String delayReason,String overdueReason,String commitmentReason,
                 LocalDate receiptDate,LocalDate manualInDate){
            this(id,workshop,submitted,requested,originalBaseline,revisedBaseline,baseline,delivered,stopped,cancelled,
                    riskCategory,delayReason,overdueReason,commitmentReason,receiptDate,manualInDate,null,null,null);
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
            if(row.cancelled()){cancelled++;return;}
            if(row.stopped()){stopped++;return;}
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

    /**
     * 节点完成率分析视图：按车间（production_line）× 节点（动态取自制程表，含中文名）统计
     * 每个节点的涉及单数/完成单数/完成率，另附排产等待分布与异常单（提交→排产 >3 天）。
     * 口径与列表一致：只统计跟单覆盖的单（visible() 范围），并按 MANUFACTURING 数据范围收敛可见性；
     * 不依赖氚云报工/入库时间。节点序列由制程表判定，不写死。
     */
    public Map<String,Object> nodeAnalyticsView(){
        var params=new ArrayList<Object>();
        var where=tracking.visible(params);
        // 节点完成率：动态制程表 + 车间分组，按制程序号排序，不写死节点序列。
        var nodeSql="SELECT COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"d.stage_code stage,COALESCE(d.stage_label,d.stage_code) stageName,"
                +"COUNT(DISTINCT p.id) involved,"
                +"COUNT(DISTINCT CASE WHEN m.stage_status='DONE' THEN p.id END) done "
                // FROM 直接复用生产跟单的统一 FROM：它自带 t（跟单状态行：stopped_at/closed_at/forecast_finish_date）
                // 与 ms（按单聚合的节点计数派生表），TRACKING_COMPLETE 才能复用同一份判据；
                // 这两个派生表都对 p.id 一对一，不会让 COUNT(DISTINCT p.id) 变大。
                +ProductionTrackingService.TRACKING_FROM
                +" JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                +"JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1 "
                +"AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END "
                +where+ACTIVE_SCOPE
                +" GROUP BY workshop,d.stage_code,d.stage_label,d.sort_no ORDER BY workshop,d.sort_no";
        List<Map<String,Object>> nodes=new ArrayList<>();
        for(var row:jdbc.queryForList(nodeSql,params.toArray())){
            long involved=((Number)row.get("involved")).longValue();
            long done=((Number)row.get("done")).longValue();
            var m=new LinkedHashMap<String,Object>();
            m.put("workshop",row.get("workshop"));
            m.put("stage",row.get("stage"));
            m.put("stageName",row.get("stageName"));
            m.put("involved",involved);
            m.put("done",done);
            m.put("rate",involved==0?null:Math.round(done*1000.0/involved)/10.0);
            nodes.add(m);
        }
        // 排产等待分布（按单 DISTINCT，避免节点行放大）
        // 外层子查询别名用 w 而不是 t：t 在生产跟单的统一 FROM 里已经是**跟单状态行**的别名，
        // 同名会让 `t.stopped_at` 指到这个天数子查询上去，停产过滤直接失效（且 MySQL 报列不存在）。
        var waitSql="SELECT w.days day,COUNT(*) cnt FROM (SELECT DISTINCT p.id,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) days "
                +ProductionTrackingService.TRACKING_FROM+where+ACTIVE_SCOPE
                +" AND p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL) w "
                +"GROUP BY w.days ORDER BY w.days";
        List<Map<String,Object>> wait=new ArrayList<>();
        for(var row:jdbc.queryForList(waitSql,params.toArray())){
            var m=new LinkedHashMap<String,Object>();
            m.put("day",row.get("day"));
            m.put("count",row.get("cnt"));
            wait.add(m);
        }
        // 排产等待异常单（>3 天）
        var anomSql="SELECT p.work_order_no orderNo,COALESCE(NULLIF(p.product_name,''),'') productName,"
                +"COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"DATE(p.source_created_at) submitted,DATE(p.schedule_time) scheduled,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) waitDays "
                +ProductionTrackingService.TRACKING_FROM+where+ACTIVE_SCOPE
                +" AND p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL "
                +"AND FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at)))>3 "
                +"ORDER BY waitDays DESC LIMIT 50";
        List<Map<String,Object>> anomalies=jdbc.queryForList(anomSql,params.toArray());
        // 覆盖单数（跟单范围且确有节点）
        long covered=0L;
        try{
            Long cnt=jdbc.queryForObject("SELECT COUNT(DISTINCT p.id) "
                    +ProductionTrackingService.TRACKING_FROM
                    +" JOIN production_tracking_milestones m ON m.production_order_id=p.id "+where+ACTIVE_SCOPE,
                    Long.class,params.toArray());
            covered=cnt==null?0:cnt;
        }catch(Exception e){ /* 降级：覆盖单数统计失败不阻断视图 */ covered=0; }
        var result=new LinkedHashMap<String,Object>();
        result.put("meta",Map.of("covered",covered));
        result.put("nodeCompletion",nodes);
        result.put("scheduleWait",wait);
        result.put("scheduleWaitAnomalies",anomalies);
        return result;
    }

    /**
     * 节点分析下钻：给「点某节点」「点某等待天数」提供对应的工单清单（分页）。
     * 与生产分析/周复盘的下钻同一种交互：先看聚合，点了才展开明细，不默认平铺。
     * 两种模式：
     *  - 节点下钻：传入 workshop+stage（+status ALL/DONE/OPEN），列出该车间该节点涉及的单及其节点状态；
     *  - 等待下钻：waitDays 传精确天数（=），或 waitDaysMin 传下限（>=，用于「>3 天」这类概览卡），按单去重。
     * 范围与列表一致（visible()），不依赖氚云报工/入库时间。
     */
    public Map<String,Object> nodeAnalyticsOrders(String workshop,String stage,String status,Integer waitDays,Integer waitDaysMin,int page,int size){
        var base=new ArrayList<Object>();
        var where=tracking.visible(base);
        int pno=Math.max(1,page);
        int lim=Math.max(1,Math.min(size,100));
        boolean byNode=stage!=null&&!stage.isBlank();
        boolean byWait=waitDays!=null||waitDaysMin!=null;
        String cols="SELECT p.id id,p.work_order_no orderNo,p.product_name productName,"
                +"COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,"
                +"DATE(p.source_created_at) submittedDate,DATE(p.schedule_time) scheduleTime,"
                +"FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at))) waitDays,"
                +(byNode?"m.stage_status stageStatus":"NULL stageStatus")+","
                // 交期两列：预计日期用全站唯一的「当前有效预计」（车间 forecast 优先），不直接用源单的 expected_completion_date，
                // 否则同一个单在这个抽屉和生产分析里会按两个日期判断是否超期。
                +EFFECTIVE_EXPECTED_DATE+" expectedDate,"
                // 「当前超期」只对**仍在产**（未被跟单判定完成）且当前有效预计已过的单计算：
                // 已完成 / 已结案 / 已停产的单都是 NULL —— 停产单已被 ACTIVE_SCOPE 挡在结果外，这里不用重复判。
                // 早前这里用 p.actual_completion_date 判完工件，而该源单字段常年不回填（SCGD20260005428 节点全完成、入库也 DONE 仍为空），
                // 于是「入库都已登记的历史完工单」被当成未完工，超期天数还随日期一天天累加。
                +"CASE WHEN "+OPEN_TRACKING+"=1 AND "+EFFECTIVE_EXPECTED_DATE+" IS NOT NULL "
                +"AND "+EFFECTIVE_EXPECTED_DATE+"<CURDATE() "
                +"THEN DATEDIFF(CURDATE(),"+EFFECTIVE_EXPECTED_DATE+") END openOverdueDays,"
                // 基础列：业务员 / 产品规格 / 模具出数。三者只做下钻展示，缺失时为 null（前端显示「—」）。
                // 与生产分析 / 周复盘下钻逐字同源（见 loadRows SELECT 的 salesperson/specification/mold_layout），
                // 让三抽屉下钻表的「工单基本信息」列渲染一致，不重写第二套口径。
                +"p.salesperson_name salesperson,"
                +"CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) specification,"
                +"p.mold_layout moldLayout";
        String from,whereTail="";
        var p=new ArrayList<Object>(base);
        if(byWait){
            from=ProductionTrackingService.TRACKING_FROM
                    +" JOIN (SELECT DISTINCT production_order_id FROM production_tracking_milestones) m ON m.production_order_id=p.id "
                    +where+ACTIVE_SCOPE+" AND p.schedule_time IS NOT NULL AND p.source_created_at IS NOT NULL "
                    +"AND FLOOR(DATEDIFF(DATE(p.schedule_time),DATE(p.source_created_at)))"
                    +(waitDays!=null?"=? ":">=? ");
            p.add(waitDays!=null?waitDays:waitDaysMin);
        } else {
            from=ProductionTrackingService.TRACKING_FROM
                    +" JOIN production_tracking_milestones m ON m.production_order_id=p.id "
                    +"JOIN production_tracking_stage_defs d ON d.stage_code=m.stage_code AND d.enabled=1 "
                    +"AND d.workshop_kind=CASE WHEN p.production_line LIKE '%封口%' THEN 'SEALER' ELSE 'MACHINE' END "
                    +where+ACTIVE_SCOPE;
            // stage_code 过滤必须放在 visible() 之后，参数顺序才能与 SQL 中的 `?` 一致；
            // 若放在 JOIN ON 里，跟单员数据范围（SELF/DEPARTMENT）非空参数时 stage 会被错位绑定 → 节点下钻返回 0
            whereTail="AND m.stage_code=? ";p.add(stage);
            // 「未完成」按 <> 'DONE' 判定，不写成 = 'OPEN'：里程碑的实际状态是 DONE / PENDING / IN_PROGRESS
            // （V369 建表 NOT NULL DEFAULT 'PENDING'），库内从不存在 'OPEN' —— 旧写法让「未完成」筛选恒返回 0 条。
            // COALESCE 只是防御性兜底（该列 NOT NULL，正常情况下不会命中 NULL 分支）。
            if(status!=null&&!"ALL".equalsIgnoreCase(status)&&!status.isBlank()){
                if("DONE".equalsIgnoreCase(status)){whereTail+="AND m.stage_status=? ";p.add("DONE");}
                else{whereTail+="AND COALESCE(m.stage_status,'')<>? ";p.add("DONE");}
            }
        }
        if(workshop!=null&&!workshop.isBlank()&&!"未分车间".equals(workshop)){whereTail+="AND p.production_line=? ";p.add(workshop);}
        else if("未分车间".equals(workshop)){whereTail+="AND (p.production_line IS NULL OR p.production_line='') ";}
        Long t=jdbc.queryForObject("SELECT COUNT(*) "+from+whereTail,Long.class,p.toArray());
        long total=t==null?0:t;
        p.add(lim);p.add((pno-1)*lim);
        List<Map<String,Object>> items=jdbc.queryForList(cols+" "+from+whereTail+" ORDER BY p.id DESC LIMIT ? OFFSET ?",p.toArray());
        var r=new LinkedHashMap<String,Object>();
        r.put("items",items);r.put("total",total);r.put("page",pno);r.put("size",lim);
        return r;
    }
}
