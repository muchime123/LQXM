package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.liqiang.archive.erp.ErpActor;
import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.FeatureCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Local tracking decisions over imported H3 production orders; source facts stay untouched. */
@Service
public class ProductionTrackingService {
    private static final Set<String> RISKS=Set.of("NONE","MATERIAL","DESIGN","CAPACITY","CUSTOMER","OTHER");
    private static final Set<String> STAGE_STATUSES=Set.of("PENDING","IN_PROGRESS","DONE");
    private static final String STAGE_TOTAL_CASE=ProductionTrackingPolicy.stageTotalCase("p.production_line");
    private static final String CONFIGURED_OXIDATION="(p.production_line LIKE '%封口%' AND "
            +"(p.optional_configuration LIKE '%氧化%' OR p.optional_configuration LIKE '%特氟龙%') "
            +"AND p.optional_configuration NOT REGEXP '(不含|无需|不用|不需要|无)(氧化|特氟龙)')";
    private static final String CONFIGURED_WOODEN_CRATE="(p.optional_configuration LIKE '%木箱%' "
            +"AND p.optional_configuration NOT REGEXP '(不含|无需|不用|不需要|无)木箱')";
    /**
     * 「按需工序」筛选白名单（顺序＝下拉顺序）。跟单员要的典型问题：「哪些单要走氧化/喷特氟龙」——
     * 这两道工序不是每张单都有，靠可选配置或已登记的节点决定（见 {@link #needsOptionalStage}）。
     */
    static final List<String> OPTIONAL_STAGE_CODES=List.of(ProductionTrackingPolicy.OXIDATION,
            ProductionTrackingPolicy.WOODEN_CRATE);
    /**
     * 本单**是否需要**某道按需工序＝源单可选配置写明需要，<b>或</b>已有该节点的跟单记录。
     *
     * <p>与 {@link #attachStageProgress} 里 {@code applicable} 的判据同一口径（Java 侧唯一源＝
     * {@code ProductionTrackingPolicy.configuredOptionalStage}）：只看节点行会漏掉「还没开始跟单、
     * 但配置要求走氧化」的新单——那恰恰是跟单员想提前看到的；只看配置则会漏掉配置没写、
     * 跟单员实际已经登记过该节点的单。两者取并集。
     *
     * <p>⚠️ 依赖 {@link #TRACKING_FROM} 的 {@code ms} 派生表，只能用在挂了该 FROM 的语句上
     * （列表 / 导出 / filter-options 计数均已挂）。{@code CONFIGURED_OXIDATION} 自带「封口车间」条件
     * ——氧化只在封口机的节点序列里（{@code ProductionTrackingPolicy.SEALER_STAGES}）。
     */
    static String needsOptionalStage(String code) {
        if(ProductionTrackingPolicy.OXIDATION.equals(code))
            return "(COALESCE(ms.oxidation_count,0)>0 OR "+CONFIGURED_OXIDATION+")";
        if(ProductionTrackingPolicy.WOODEN_CRATE.equals(code))
            return "(COALESCE(ms.wooden_crate_count,0)>0 OR "+CONFIGURED_WOODEN_CRATE+")";
        throw new IllegalArgumentException("按需工序筛选无效");
    }
    /**
     * 节点分子/分母同源：必经节点 + 本单适用的「按需工序」（氧化/喷特氟龙）。
     * 不适用（源表填「无」或留空，没有里程碑行）时两者都不加 1 ⇒ 不会被误判成差一个节点；
     * 一旦该单真的登记了氧化，分母多 1、完成也就要求它做完。
     */
    static final String STAGE_DONE_CASE="CASE WHEN p.production_line LIKE '%封口%' "
            +"THEN COALESCE(ms.sealer_done_count,0) ELSE COALESCE(ms.machine_done_count,0) END"
            +"+COALESCE(ms.optional_done_count,0)";
    /**
     * 列表「x/y 已完成」的分母：与 TRACKING_COMPLETE 右端同源（必经节点 + 本单适用的按需工序）。
     * 少了它，走过氧化的单分子含 5 而分母只有 4 ⇒ 列表显示「5/4 已完成」。
     */
    static final String STAGE_TOTAL_WITH_OPTIONAL="("+STAGE_TOTAL_CASE+")+COALESCE(ms.optional_count,0)"
            +"+CASE WHEN "+CONFIGURED_OXIDATION+" AND COALESCE(ms.oxidation_count,0)=0 THEN 1 ELSE 0 END"
            +"+CASE WHEN "+CONFIGURED_WOODEN_CRATE+" AND COALESCE(ms.wooden_crate_count,0)=0 THEN 1 ELSE 0 END";
    static final String TRACKING_COMPLETE="("+STAGE_DONE_CASE+")="+STAGE_TOTAL_WITH_OPTIONAL;
    /** done 计数只数必经节点，按需工序单独统计，两者共用一个派生表。 */
    static final String TRACKING_FROM=" FROM production_orders p "
            +"LEFT JOIN production_tracking_state t ON t.production_order_id=p.id "
            +"LEFT JOIN (SELECT MIN(id) id,name FROM employees WHERE status='ACTIVE' "
            +"AND name IN ('陈苏平','胡健') GROUP BY name) assigned "
            +"ON assigned.name=CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END "
            +"LEFT JOIN (SELECT production_order_id,"
            +"SUM(stage_code IN ('MATERIAL','MOLD','ASSEMBLY','WAREHOUSE') AND stage_status='DONE') sealer_done_count,"
            +"SUM(stage_code IN ('DRAWING','MATERIAL','SHEET_METAL','MOLD','ASSEMBLY','TESTING','WAREHOUSE') AND stage_status='DONE') machine_done_count,"
            +"SUM(stage_code IN ('OXIDATION','WOODEN_CRATE')) optional_count,"
            +"SUM(stage_code IN ('OXIDATION','WOODEN_CRATE') AND stage_status='DONE') optional_done_count,"
            +"SUM(stage_code='OXIDATION') oxidation_count,"
            +"SUM(stage_code='WOODEN_CRATE') wooden_crate_count,"
            // 「跟单员登记的入库时间」＝在「入库」节点填的完成日期；节点未完成时为空。
            // 该列是同一 GROUP BY 上的聚合，不会改变行使数，故统计卡/筛选项口径不受影响。
            +"MAX(CASE WHEN stage_code='WAREHOUSE' AND stage_status='DONE' THEN completed_date END) warehouse_in_date,"
            // 「跟单是否已经开始」的真值＝该工单有没有任何跟单节点记录（见 NEVER_TRACKED）。
            // 同为同一 GROUP BY 上的聚合，不改变行使数。
            +"COUNT(*) node_count "
            +"FROM production_tracking_milestones GROUP BY production_order_id) ms "
            +"ON ms.production_order_id=p.id";
    /**
     * 采购入库关联：氚云生产任务单自身没有「入库时间」列，真正的入库时间落在采购入库明细的
     * 「选择生产任务单」上（首段编号 = {@code production_orders.work_order_no}）。
     *
     * <p>一个工单可能分多批入库，故取「首次入库日期 / 末次入库日期 / 入库单数」三个值，
     * 业务上的「入库时间」= 首次入库日期。
     *
     * <p>⚠️ 只允许挂在列表与详情的 SELECT 列表上，<b>不得并入 {@link #TRACKING_FROM}</b>——
     * 统计卡、筛选项与 COUNT(*) 都复用那份 FROM，多挂一个派生表会让跟单口径跟着采购数据变。
     * 派生表本身在 FROM 里、不是相关子查询，故不受「派生表禁进相关子查询」的性能约束。
     */
    static final String INBOUND_JOIN=" LEFT JOIN (SELECT i.production_order_no pon,"
            +"MIN(r.purchase_date) first_in,MAX(r.purchase_date) last_in,COUNT(DISTINCT r.id) receipt_cnt "
            +"FROM purchase_receipt_items i JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
            +"WHERE i.production_order_no IS NOT NULL AND i.production_order_no<>'' "
            +"GROUP BY i.production_order_no) ib ON ib.pon=p.work_order_no";
    /**
     * 采购入库派生表在 SELECT 列表中的取值。<b>末尾不带逗号</b>——两处调用点均已写成
     * {@code ...+" attention,"+INBOUND_COLUMNS+FROM}，若此处再带尾逗号会拼出
     * {@code report_to_inbound_days, FROM} 导致 bad SQL grammar。
     */
    static final String INBOUND_COLUMNS="ib.first_in inbound_date,ib.last_in inbound_last_date,"
            +"COALESCE(ib.receipt_cnt,0) inbound_count,"
            +"CASE WHEN ib.first_in IS NOT NULL AND p.actual_completion_date IS NOT NULL "
            +"THEN DATEDIFF(ib.first_in,p.actual_completion_date) END report_to_inbound_days";
    /**
     * 实际交付日期优先采用跟单员登记的「入库」节点日期；尚未登记时采用采购首次入库日期。
     * 两个来源都没有时为 NULL。分析筛选、列表交付结果与详情共用此口径。
     *
     * <p>⚠️ 依赖 {@link #INBOUND_JOIN} 的 {@code ib} 别名与 {@link #TRACKING_FROM} 的 {@code ms} 别名，
     * 只能用在同时挂了这两者的 SELECT 上（列表 / 详情）。末尾不带逗号。
     */
    /** 无车间独立预计日期时，采用有效的业务员要求日；填错的早于申请/创建日期不作为基准。 */
    static final String VALID_REQUIRED_DATE="CASE WHEN (p.application_date IS NULL "
            +"OR p.required_completion_date>=p.application_date) AND (p.source_created_at IS NULL "
            +"OR p.required_completion_date>=DATE(p.source_created_at)) THEN p.required_completion_date END";
    static final String DELIVERED_IN_DATE="COALESCE(ms.warehouse_in_date,ib.first_in)";
    /**
     * 交付基准＝跟单员确认后的车间交期；车间未另填日期时，采用有效的业务员要求日期。
     *
     * <p>车间申请延期、跟单员与车间/业务员协商出新日期后，新日期只落在
     * {@code production_tracking_state.forecast_finish_date}（进度表「预计生产完成」列导入，
     * 与源值相同时不落），氚云工单原值 {@code p.expected_completion_date} 不会回写。
     * 因此基准必须先取「跟单员确认后的车间预计完成日」，否则已登记延期的单会被系统性判成逾期
     * （封口机实测：按源值判会误判 38 单）。
     */
    static final String DELIVERY_BASELINE="COALESCE(t.forecast_finish_date,p.expected_completion_date,"+VALID_REQUIRED_DATE+")";
    /**
     * 生产交付结果：以车间给出的预计完成日期（跟单员确认后，见 {@link #DELIVERY_BASELINE}）为基准，
     * 判「未完工 / 按期完工 / 逾期完工 X 天」。
     * <p>⚠️ 用词：「逾期」= 实际交付晚于基准这一<b>既成事实</b>；「延期」专指车间预计完成实现不了时，
     * 跟单员与车间/业务员协商另订新完成日期的<b>主动动作</b>（见 {@code forecast_finish_date}）——两者不可混用。
     * <ul>
     *   <li>{@code PENDING}：两个入库来源都没有 ⇒ 还没交付；</li>
     *   <li>{@code NO_BASELINE}：已交付，且延期日、车间预计日及有效的业务员要求日均为空 ⇒ 无从比较；</li>
     *   <li>{@code ON_TIME}：实际交付日期 ≤ 交付基准；</li>
     *   <li>{@code OVERDUE}：晚于交付基准，天数见 {@link #DELIVERY_OVERDUE_DAYS}。</li>
     * </ul>
     */
    static final String DELIVERY_OUTCOME="CASE WHEN "+DELIVERED_IN_DATE+" IS NULL THEN 'PENDING' "
            +"WHEN "+DELIVERY_BASELINE+" IS NULL THEN 'NO_BASELINE' "
            +"WHEN "+DELIVERED_IN_DATE+"<="+DELIVERY_BASELINE+" THEN 'ON_TIME' ELSE 'OVERDUE' END";
    /** 逾期天数＝实际交付日期 − 交付基准；未逾期或无从比较时为 NULL。 */
    static final String DELIVERY_OVERDUE_DAYS="CASE WHEN "+DELIVERED_IN_DATE+" IS NOT NULL "
            +"AND "+DELIVERY_BASELINE+" IS NOT NULL AND "+DELIVERED_IN_DATE+">"+DELIVERY_BASELINE+" "
            +"THEN DATEDIFF("+DELIVERED_IN_DATE+","+DELIVERY_BASELINE+") END";
    /** 列表/详情 SELECT 里的交付结果四列。末尾不带逗号——调用点写 {@code +","+DELIVERY_COLUMNS+from}。 */
    static final String DELIVERY_COLUMNS=DELIVERED_IN_DATE+" delivered_date,"
            +DELIVERY_BASELINE+" delivery_baseline,"
            +DELIVERY_OUTCOME+" delivery_outcome,"+DELIVERY_OVERDUE_DAYS+" delivery_overdue_days";
    /**
     * 「尚未开始跟单」＝该工单<b>一个跟单节点记录都没有</b>（含 PENDING/DOING 等未完成节点也算记录）。
     *
     * <p>⚠️ 不能用 {@code production_tracking_state.version=0} 代替：进度表导入时，若该行所有节点都没填
     * 完成日期，只落状态行与 1 条表格快照、<b>不落任何节点</b>（{@code version} 被写成 1）。
     * 真库实测有 17 单属此类，会被误判成「跟单中」（用户口径：节点有记录才是跟单中）。
     * 反向不成立风险为零：真库实测「version=0 却有节点记录」的单为 0。
     *
     * <p>依赖 {@link #TRACKING_FROM} 的 {@code ms} 派生表，只能用在挂了该 FROM 的语句上。
     */
    static final String NEVER_TRACKED="COALESCE(ms.node_count,0)=0";
    /** 「长期未更新」阈值（天）。跟单写入路径每次都会同时刷新 last_followup_at，故 version>0 时它必有值。 */
    static final int STALE_FOLLOWUP_DAYS=7;
    /**
     * 跟单待办提醒（派生，不落库）：把「业务事实已经走到前面、跟单记录还没跟上」的单挑出来，
     * 直接告诉跟单员这单现在该干什么、为什么现在就轮到他。
     *
     * <p>一单只取<b>第一条命中</b>的代码——同一单常常同时命中多条（真库实测：69 单未跟单里有 28 单已完工、
     * 27 单已入库、56 单已排产，三者不互斥），同时挂三个标签只会淹没重点。排序＝业务阶段越靠后越严重：
     * <ul>
     *   <li>{@code FINISHED_PENDING} 已完工待跟单：氚云已报工完工，<b>一个节点都没登记</b>（最严重）；</li>
     *   <li>{@code INBOUND_PENDING} 已入库待跟单：采购已入库，一个节点都没登记；</li>
     *   <li>{@code SCHEDULED_PENDING} 已排产待跟单：车间已排产，一个节点都没登记；</li>
     *   <li>{@code INBOUND_UNLOGGED} 已入库待登记：已在跟单，但采购已入库而「入库」节点还没登记完成日期；</li>
     *   <li>{@code STALE_FOLLOWUP} 超 {@value #STALE_FOLLOWUP_DAYS} 天未更新：已登记过节点、还没完成，且长期没动过。</li>
     * </ul>
     *
     * <p>⚠️ 依赖 {@link #TRACKING_FROM} 的 {@code t}/{@code ms} 与 {@link #INBOUND_JOIN} 的 {@code ib}，
     * 只能用在同时挂了这两者的 SELECT 上（列表 / 详情 / 下拉计数）；末尾不带逗号。
     */
    static final String TRACKING_ACTION="CASE WHEN t.stopped_at IS NOT NULL THEN NULL "
            +"WHEN "+NEVER_TRACKED+" AND p.actual_completion_date IS NOT NULL THEN 'FINISHED_PENDING' "
            +"WHEN "+NEVER_TRACKED+" AND ib.first_in IS NOT NULL THEN 'INBOUND_PENDING' "
            +"WHEN "+NEVER_TRACKED+" AND p.schedule_time IS NOT NULL THEN 'SCHEDULED_PENDING' "
            +"WHEN ib.first_in IS NOT NULL AND ms.warehouse_in_date IS NULL THEN 'INBOUND_UNLOGGED' "
            +"WHEN NOT "+NEVER_TRACKED+" AND NOT "+TRACKING_COMPLETE+" AND t.last_followup_at<DATE_SUB(CURDATE(),INTERVAL "+STALE_FOLLOWUP_DAYS+" DAY) THEN 'STALE_FOLLOWUP' "
            +"ELSE NULL END";
    /** 提醒筛选白名单（顺序＝下拉顺序，与前端一一对应）；{@code all}＝不筛，{@code any}＝只要有提醒。 */
    static final List<String> TRACKING_ACTION_CODES=List.of("FINISHED_PENDING","INBOUND_PENDING",
            "SCHEDULED_PENDING","INBOUND_UNLOGGED","STALE_FOLLOWUP");
    /** 把提醒筛选值翻成 SQL：{@code all→null}（不加条件）、{@code any→IS NOT NULL}、其余按代码等值。 */
    static String trackingActionPredicate(String action) {
        if("any".equals(action))return TRACKING_ACTION+" IS NOT NULL";
        return TRACKING_ACTION+"=?";
    }
    static final String HAS_FOLLOWUP_SQL="SELECT t.version,"
            +"(SELECT COUNT(*) FROM production_tracking_entries e WHERE e.production_order_id=t.production_order_id) entries_count "
            +"FROM production_tracking_state t WHERE t.production_order_id=?";
    /** 进度表导入只同步三样：延期至（≠车间预计完成才登记，见 importWorkbookPayload）、延期原因、节点完成日期（milestones 单独写）。
     *  计划开工/发货状态/交付结果/跟单备注不再从表格写入（2026-10-10 LQ 定）；节点参考锚点因此回退申请日期（attachNodeReferences 已有兜底）。 */
    static final String IMPORT_STATE_SQL="INSERT INTO production_tracking_state(production_order_id,forecast_finish_date,"
            +"delay_reason,assignee_employee_id,assignee_name,version,last_followup_at) "
            +"VALUES(?,?,?,?,?,1,NOW()) ON DUPLICATE KEY UPDATE "
            +"forecast_finish_date=COALESCE(forecast_finish_date,VALUES(forecast_finish_date)),"
            +"delay_reason=COALESCE(delay_reason,VALUES(delay_reason)),"
            +"assignee_employee_id=COALESCE(assignee_employee_id,VALUES(assignee_employee_id)),"
            +"assignee_name=COALESCE(assignee_name,VALUES(assignee_name)),"
            +"version=IF(version=0,1,version),last_followup_at=COALESCE(last_followup_at,NOW())";
    /** 车间评估后的生产完成基准；能满足业务员要求时沿用订单要求日期。 */
    static final String DEADLINE="COALESCE(p.expected_completion_date,"+VALID_REQUIRED_DATE+")";
    /** 工具栏「创建月份」筛选与下拉计数按**任务单提交时间**（业务员提交生产任务单给车间审批，氚云 source_created_at）分月；不再用带兜底的申请日期。 */
    static final String CREATED_MONTH_DATE="DATE(p.source_created_at)";
    /** 跟单归属是生产工单的固有规则，未建立跟单状态行也已分配；状态行只记录跟进过程。 */
    static final String ASSIGNEE_NAME="CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END";
    /** 九月起正式跟单：申请日期或任务单提交时间任一落在范围内即可；重导不会改变提交时间。 */
    static final String SCOPE_DATE="(p.application_date>='"+ProductionTrackingPolicy.SCOPE_START_DATE
            +"' OR p.source_created_at>='"+ProductionTrackingPolicy.SCOPE_START_DATE+"')";
    /** 跟单状态只取本地节点记录，不复用氚云任务单状态：「有节点记录即跟单中」由 NEVER_TRACKED 表达。 */
    static final String TRACKING_STATUS_CASE="CASE WHEN t.stopped_at IS NOT NULL THEN 'stopped' "
            +"WHEN "+NEVER_TRACKED+" THEN 'pending' "
            +"WHEN "+TRACKING_COMPLETE+" THEN 'completed' ELSE 'in_progress' END";
    /** 提醒只表示待处理风险；任务单状态和跟单状态各有独立字段。 */
    static final String REMINDER_CASE="CASE WHEN t.stopped_at IS NOT NULL THEN 'none' "
            +"WHEN NOT "+NEVER_TRACKED+" AND "+TRACKING_COMPLETE+" THEN 'none' "
            +"WHEN "+DEADLINE+" IS NOT NULL AND "+DEADLINE+"<CURDATE() THEN 'overdue' "
            +"WHEN t.next_followup_date IS NOT NULL AND t.next_followup_date<=CURDATE() THEN 'due' "
            +"WHEN t.forecast_finish_date IS NOT NULL AND "+DEADLINE+" IS NOT NULL AND t.forecast_finish_date>"+DEADLINE+" THEN 'delayed' "
            +"WHEN "+DEADLINE+" IS NOT NULL AND "+DEADLINE+"<=DATE_ADD(CURDATE(),INTERVAL 3 DAY) THEN 'near' "
            +"ELSE 'none' END";
    static final String TIME_REVIEW_DUE="EXISTS (SELECT 1 FROM production_tracking_time_reviews n "
            +"WHERE n.production_order_id=p.id AND n.next_followup_date<=CURDATE() "
            +"AND (n.status='OPEN' OR (n.kind='COMMITMENT' "
            +"AND NOT (n.agreed_date <=> "+DEADLINE+"))) "
            +"AND n.id=(SELECT MAX(n2.id) FROM production_tracking_time_reviews n2 "
            +"WHERE n2.production_order_id=n.production_order_id AND n2.kind=n.kind))";
    /** 与列表「时间核实」列的分类同口径，具体筛选与汇总筛选复用这些条件。 */
    static final String TIME_SOURCE_APPLICATION="(p.application_date IS NOT NULL AND p.source_created_at IS NOT NULL "
            +"AND p.application_date<DATE(p.source_created_at))";
    static final String TIME_SOURCE_REQUIRED="(p.required_completion_date IS NOT NULL AND "
            +"((p.application_date IS NOT NULL AND p.required_completion_date<p.application_date) "
            +"OR (p.source_created_at IS NOT NULL AND p.required_completion_date<DATE(p.source_created_at))))";
    static final String TIME_ACCEPTANCE="(((p.schedule_time IS NOT NULL AND p.application_date IS NOT NULL "
            +"AND DATE(p.schedule_time)<p.application_date) "
            +"OR (p.schedule_time IS NOT NULL AND "+VALID_REQUIRED_DATE+" IS NOT NULL "
            +"AND DATE(p.schedule_time)>"+VALID_REQUIRED_DATE+") "
            +"OR (p.schedule_time IS NULL AND "+VALID_REQUIRED_DATE+"<CURDATE())) "
            +"AND "+unreviewedTimeIssue("ACCEPTANCE","AND n.source_schedule_time<=>p.schedule_time")+")";
    static final String TIME_COMMITMENT="(("+VALID_REQUIRED_DATE+" IS NOT NULL OR p.required_completion_date IS NULL) "
            +"AND (((p.expected_completion_date IS NOT NULL AND "+VALID_REQUIRED_DATE+" IS NOT NULL "
            +"AND p.expected_completion_date>"+VALID_REQUIRED_DATE+") "
            +"AND "+unreviewedTimeIssue("COMMITMENT","AND n.status='NEGOTIATED' "
                    +"AND n.agreed_date<=>p.expected_completion_date")+") "
            +"OR EXISTS (SELECT 1 FROM production_tracking_time_reviews n "
            +"WHERE n.production_order_id=p.id AND n.kind='COMMITMENT' "
            +"AND n.id=(SELECT MAX(n2.id) FROM production_tracking_time_reviews n2 "
            +"WHERE n2.production_order_id=p.id AND n2.kind='COMMITMENT') "
            +"AND (NOT (n.source_due_date<=>p.required_completion_date) "
            +"OR NOT (n.source_expected_date<=>p.expected_completion_date) "
            +"OR (n.agreed_date IS NOT NULL AND NOT (n.agreed_date<=>"+DEADLINE+"))))))";
    static final String TIME_INBOUND_REPORTING="EXISTS (SELECT 1 FROM purchase_receipt_items i "
            +"JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
            +"WHERE i.production_order_no=p.work_order_no "
            +"AND r.purchase_date IS NOT NULL "
            +"AND (p.actual_completion_date IS NULL OR r.purchase_date<p.actual_completion_date))";
    static final String TIME_REPORTING_UNREVIEWED=unreviewedTimeIssue("REPORTING",
            "AND n.source_report_date<=>p.actual_completion_date "
            +"AND n.source_inbound_date<=> (SELECT MIN(r.purchase_date) FROM purchase_receipt_items i "
            +"JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
            +"WHERE i.production_order_no=p.work_order_no)");
    static final String TIME_REPORTING="(NOT "+TIME_INBOUND_REPORTING+" AND "
            +"((p.actual_completion_date IS NOT NULL AND p.expected_completion_date IS NOT NULL "
            +"AND p.actual_completion_date>p.expected_completion_date) "
            +"OR (p.actual_completion_date IS NULL AND p.expected_completion_date<CURDATE())) "
            +"AND "+TIME_REPORTING_UNREVIEWED+")";
    static final String TIME_INBOUND_REPORTING_ATTENTION="("+TIME_INBOUND_REPORTING+" AND "
            +TIME_REPORTING_UNREVIEWED+")";
    /**
     * 逾期待核实：实际交付日期晚于交付基准（见 {@link #DELIVERY_BASELINE}），且最后一条 OVERDUE 核实记录的
     * 「基准 / 交付日」快照与当前不一致（含从未记录）。快照复用 source_expected_date（此处存<b>交付基准</b>）
     * 与 source_inbound_date（此处存<b>实际交付日</b>）——与 REPORTING 复用同两列时的语义不同，各自按 kind 解释。
     *
     * <p>⚠️ 依赖 {@link #INBOUND_JOIN} 的 ib 与 {@link #TRACKING_FROM} 的 ms/t，调用点必须同时挂这两者；
     * 列表的 COUNT 查询复用同一份 where，故该处也必须补挂 {@link #INBOUND_JOIN}（1:1 派生表，不改变行数）。
     */
    static final String TIME_OVERDUE="("+DELIVERED_IN_DATE+" IS NOT NULL AND "+DELIVERY_BASELINE+" IS NOT NULL "
            +"AND "+DELIVERED_IN_DATE+">"+DELIVERY_BASELINE+" AND NOT EXISTS "
            +"(SELECT 1 FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id AND n.kind='OVERDUE' "
            +"AND n.id=(SELECT MAX(n2.id) FROM production_tracking_time_reviews n2 "
            +"WHERE n2.production_order_id=p.id AND n2.kind='OVERDUE') "
            +"AND n.source_expected_date<=>"+DELIVERY_BASELINE+" "
            +"AND n.source_inbound_date<=>"+DELIVERED_IN_DATE+"))";
    static final String TIME_ATTENTION="("+String.join(" OR ",TIME_SOURCE_APPLICATION,TIME_SOURCE_REQUIRED,
            TIME_ACCEPTANCE,TIME_COMMITMENT,TIME_REPORTING,TIME_INBOUND_REPORTING_ATTENTION,TIME_OVERDUE,TIME_REVIEW_DUE)+")";

    /**
     * 列表与导出的**取数列唯一来源**：必须声明在上面所有片段常量之后
     * （Java 静态字段按文本顺序初始化，前向引用会拿到 null）。
     * 导出若另写一份 SELECT，两边迟早不同口径 —— 故强制共用本常量。
     */
    static final String LIST_COLUMNS="SELECT p.id,p.work_order_no order_no,p.status source_status,"
            +"p.product_name product_name_snapshot,p.production_requirement source_order_remark,"
            +"p.optional_configuration source_optional_configuration,"
            +"DATE_FORMAT("+CREATED_MONTH_DATE+",'%Y-%m') tracking_month,"
            +"CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) specification_snapshot,"
            +"p.mold_layout mold_layout,"
            +"p.required_quantity planned_quantity,'台' unit,p.production_line workshop_type,"
            +"p.customer_name customer_name_snapshot,p.salesperson_name salesperson_name_snapshot,"
            +"p.required_completion_date due_date,p.required_completion_date order_required_date,"
            +"p.expected_completion_date expected_finish_date,"
            +"p.application_date application_date,p.source_created_at source_created_at,"
            +"p.actual_completion_date actual_finish_date,p.schedule_time schedule_time,"
            +"p.warehousing_completed,p.production_completed,"
            +"CASE WHEN p.actual_completion_date IS NOT NULL AND "+DEADLINE+" IS NOT NULL "
            +"THEN DATEDIFF(p.actual_completion_date,"+DEADLINE+") END finish_delay_days,"
            +"p.external_source,"
            +"t.planned_start_date,t.forecast_finish_date,t.risk_category,t.delay_reason,t.next_followup_date,t.remark,"
            +"t.stopped_at,t.stopped_reason,t.stopped_by_name,"
            +"assigned.id assignee_employee_id,"+ASSIGNEE_NAME+" assignee_name,t.last_followup_at,"
            +"COALESCE(t.version,0) version,"
            +STAGE_DONE_CASE+" stage_done_count,"
            +STAGE_TOTAL_WITH_OPTIONAL+" stage_total,"
            +TRACKING_STATUS_CASE+" tracking_status,"
            +REMINDER_CASE+" reminder,"
            +TRACKING_ACTION+" tracking_action,"
            +INBOUND_COLUMNS+","+DELIVERY_COLUMNS;

    /** 列表与导出的固定排序（唯一来源）：最新申请在前，同申请时间按 id 兜底，保证分页与导出顺序一致。 */
    static final String LIST_ORDER=" ORDER BY p.source_created_at DESC,p.id DESC";
    /** 与前端加急标签同口径：备注含“加急”，但排除明确的否定表达。 */
    static final String URGENT_REMARK="REGEXP_REPLACE(COALESCE(p.production_requirement,''),'[[:space:]　]','') LIKE '%加急%' "
            +"AND REGEXP_REPLACE(COALESCE(p.production_requirement,''),'[[:space:]　]','') "
            +"NOT REGEXP '(不|无须|无需|不用|不需要|取消|非)加急'";

    /** 单次导出上限：超出即截断并在响应里标记，避免一次拉爆内存与浏览器。 */
    static final int EXPORT_MAX_ROWS=5000;

    private static String unreviewedTimeIssue(String kind,String extra) {
        return "NOT EXISTS (SELECT 1 FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id "
                +"AND n.kind='"+kind+"' AND n.id=(SELECT MAX(n2.id) FROM production_tracking_time_reviews n2 "
                +"WHERE n2.production_order_id=p.id AND n2.kind='"+kind+"') "
                +"AND n.source_due_date<=>p.required_completion_date "
                +"AND n.source_expected_date<=>p.expected_completion_date "+extra+")";
    }
    private final JdbcTemplate jdbc;
    private final ErpActorResolver actors;
    private final ErpIdempotencyService idem;
    private final ErpChangeLogWriter audit;
    private final ObjectMapper json;
    private final CurrentUserService users;

    public ProductionTrackingService(JdbcTemplate jdbc, ErpActorResolver actors,
            @Qualifier("archiveIdempotencyService") ErpIdempotencyService idem,
            @Qualifier("archiveAuditWriter") ErpChangeLogWriter audit, ObjectMapper json,
            CurrentUserService users) {
        this.jdbc=jdbc; this.actors=actors; this.idem=idem; this.audit=audit; this.json=json;
        this.users=users;
    }

    public record State(LocalDate plannedStartDate, LocalDate forecastFinishDate, String riskCategory,
                        String delayReason, LocalDate nextFollowupDate, Long assigneeEmployeeId,
                        String deliveryResult, String shipmentStatus, String remark) {}
    public record Milestone(String code, String status, LocalDate completedDate, String note) {}
    public record EntryRequest(long version, String content, State state, List<Milestone> milestones) {}
    public record DispositionRequest(long version, boolean stopped, String reason) {}
    public record TimeReviewRequest(String kind,String status,String reason,String contactName,
                                    LocalDate agreedDate,LocalDate nextFollowupDate) {}

    StringBuilder visible(List<Object> params) {
        // 九月起的任务自动进入工作台；更早但已有跟单或进度表覆盖的任务保留可见。
        StringBuilder where=new StringBuilder(" WHERE p.external_source='H3YUN' "
                +"AND ("+SCOPE_DATE+" "
                +"OR EXISTS (SELECT 1 FROM production_tracking_enrollments e WHERE e.production_order_id=p.id) "
                +"OR EXISTS (SELECT 1 FROM production_tracking_workbook_rows w WHERE w.production_order_id=p.id))");
        appendVisibility(where,params);
        return where;
    }

    /**
     * 跟单可见性（读与写同一判据）：MANUFACTURING 功能数据范围=ALL（跟单主管/管理员）看全部；
     * 其余（跟单员）只看按工单车间分给本人的任务，包括尚未产生跟单状态行的新导入工单。
     */
    private void appendVisibility(StringBuilder where,List<Object> params) {
        if("ALL".equals(users.scopeOf(FeatureCode.MANUFACTURING)))return;
        Long me=users.current().employeeId();
        if(me==null){where.append(" AND 1=0");return;}
        where.append(" AND assigned.id=?");
        params.add(me);
    }

    Optional<Map<String,Object>> defaultAssignee(long orderId) {
        String workshop=jdbc.query("SELECT production_line FROM production_orders WHERE id=?",
                (r,n)->r.getString(1),orderId).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("氚云生产任务不存在"));
        String name=ProductionTrackingPolicy.defaultAssigneeName(workshop);
        return jdbc.query("SELECT id,name FROM employees WHERE name=? AND status='ACTIVE' ORDER BY id LIMIT 1",
                (r,n)->row(r),name).stream().findFirst();
    }

    public void requireTrackingSupervisor() {
        if(!"ALL".equals(users.scopeOf(FeatureCode.MANUFACTURING)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅生产跟单主管可导入或核对进度表");
    }

    public List<Map<String,Object>> candidates(String keyword,Long workbookRowId) {
        String term=keyword==null?"":keyword.trim();
        if(term.length()<2) return List.of();
        if(workbookRowId!=null){
            requireTrackingSupervisor();
            Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_workbook_rows "
                    +"WHERE id=? AND match_status='PENDING'",Integer.class,workbookRowId);
            if(pending==null||pending==0)throw new IllegalArgumentException("待核对表格行不存在");
        }
        String dateScope=workbookRowId==null?"AND "+SCOPE_DATE+" ":"";
        String sql="SELECT p.id,p.work_order_no order_no,p.product_name,p.production_line workshop_type,"
                        +"p.application_date planned_start_date,"
                        +"p.required_completion_date due_date,p.expected_completion_date expected_finish_date,"
                        +"p.required_quantity planned_quantity "
                        +"FROM production_orders p "
                        +"LEFT JOIN production_tracking_state t ON t.production_order_id=p.id "
                        // 「还没有跟单记录」与跟单状态同源（NEVER_TRACKED 依赖 ms 派生表）；
                        // 复用 version=0 会漏掉「进度表导入但一个节点都没填」的工单（真库 17 单）。
                        +"LEFT JOIN (SELECT production_order_id,COUNT(*) node_count FROM production_tracking_milestones "
                        +"GROUP BY production_order_id) ms ON ms.production_order_id=p.id "
                        +"WHERE p.external_source='H3YUN' "
                        +dateScope
                        +(workbookRowId==null?"AND "+NEVER_TRACKED+" "
                            +"AND NOT EXISTS (SELECT 1 FROM production_tracking_entries e WHERE e.production_order_id=p.id) ":"")
                        +"AND (p.work_order_no LIKE ? OR p.product_name LIKE ? OR p.customer_name LIKE ?) "
                        +"ORDER BY p.source_created_at DESC,p.id DESC LIMIT 50";
        String like="%"+term+"%";
        return jdbc.query(sql,(r,n)->row(r),like,like,like);
    }

    public long enroll(long id,String key) {
        var actor=actors.current();
        if(!"ALL".equals(users.scopeOf(FeatureCode.MANUFACTURING))){
            var assigned=defaultAssignee(id).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "该车间未配置跟单员，请联系跟单主管"));
            if(!Objects.equals(((Number)assigned.get("id")).longValue(),actor.employeeId()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能加入本人负责车间的任务");
        }
        return Long.parseLong(idem.execute(actor,key,"production.tracking.enroll",Map.of("id",id),()->{
            Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM production_orders p WHERE p.id=? "
                    +"AND p.external_source='H3YUN' AND ("+SCOPE_DATE+" "
                    +"OR EXISTS (SELECT 1 FROM production_tracking_workbook_rows w WHERE w.production_order_id=p.id))",
                    Integer.class,id);
            if(exists==null||exists==0)throw new IllegalArgumentException("仅支持跟单 "
                    +ProductionTrackingPolicy.SCOPE_START_DATE+" 起或生产跟单进度表已覆盖的任务");
            int changed=jdbc.update("INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin) "
                    +"VALUES(?,'MANUAL')",id);
            if(changed>0){
                var assignee=defaultAssignee(id).orElse(null);
                jdbc.update("INSERT INTO production_tracking_state(production_order_id,assignee_employee_id,assignee_name) "
                        +"VALUES(?,?,?) ON DUPLICATE KEY UPDATE "
                        +"assignee_employee_id=COALESCE(assignee_employee_id,VALUES(assignee_employee_id)),"
                        +"assignee_name=COALESCE(assignee_name,VALUES(assignee_name))",
                        id,assignee==null?null:assignee.get("id"),assignee==null?null:assignee.get("name"));
                audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_enrollments",id,
                        "INSERT",ErpChangeLogWriter.changes("productionOrderId",id),"FOLLOW_UP","加入生产跟单",key,null));
            }
            return String.valueOf(id);
        }));
    }

    public List<Map<String,Object>> workbookRows(boolean history) {
        requireTrackingSupervisor();
        return jdbc.query("SELECT id,sheet_name,sheet_row,source_order_no,product_name,"
                +"required_completion_date,match_note,match_status,source_hash,created_at,"
                +"superseded_at,superseded_by_hash,reviewed_at,reviewed_by_employee_id,production_order_id,"
                +"JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.sourceFile')) source_file,"
                +"JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.quantity')) source_quantity,"
                +"JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.plannedStartDate')) planned_start_date,"
                // 该车间之后又导入过更新的版本、却没能覆盖到本行（例如新版本删了这一行）⇒ 标为「已被新版本取代」，
                // 提醒主管不必再逐行处理旧版本残留，避免对着过期行做无效核对。
                +"(CASE WHEN EXISTS (SELECT 1 FROM production_tracking_workbook_rows n "
                +"WHERE n.sheet_name=r.sheet_name AND n.source_hash<>r.source_hash AND n.created_at>r.created_at) "
                +"THEN 1 ELSE 0 END) stale "
                +"FROM production_tracking_workbook_rows r "
                +(history?"WHERE match_status NOT IN ('PENDING','REVIEW') ":"WHERE match_status IN ('PENDING','REVIEW') ")
                +"ORDER BY created_at DESC,id DESC LIMIT 1000",(r,n)->row(r));
    }

    /**
     * 「表格待处理」按钮上的数字专用轻量计数：不再为了显示一个数就把上千行待处理明细整包拉给前端。
     */
    public Map<String,Object> workbookSummary() {
        requireTrackingSupervisor();
        var counts=new HashMap<String,Integer>();
        for(var row:jdbc.queryForList("SELECT match_status s,COUNT(*) c FROM production_tracking_workbook_rows GROUP BY match_status"))
            counts.put(String.valueOf(row.get("s")),((Number)row.get("c")).intValue());
        int pending=counts.getOrDefault("PENDING",0),review=counts.getOrDefault("REVIEW",0);
        var out=new LinkedHashMap<String,Object>();
        out.put("pending",pending);
        out.put("review",review);
        out.put("actionable",pending+review);
        out.put("archived",counts.getOrDefault("ARCHIVED",0));
        out.put("skipped",counts.getOrDefault("SKIPPED",0));
        out.put("imported",counts.getOrDefault("IMPORTED",0));
        out.put("updated",counts.getOrDefault("UPDATED",0));
        out.put("total",counts.values().stream().mapToInt(Integer::intValue).sum());
        return out;
    }

    /** 生效中的归档规则（按表格产品名）：命中后导入时直接归为已归档，不再进入待处理。 */
    public List<Map<String,Object>> workbookArchiveRules() {
        requireTrackingSupervisor();
        return jdbc.query("SELECT id,product_key,product_label,reason,created_by_name,created_at "
                +"FROM production_tracking_workbook_archive_rules WHERE revoked_at IS NULL "
                +"ORDER BY created_at DESC,id DESC",(r,n)->row(r));
    }

    /**
     * 归档一行「本来就不对应氚云工单」的表格行（模具/配件/片膜等），并按它的产品名建立归档规则，
     * 使后续导入的同名行自动归为已归档。规则只影响**匹配不上**的行，能唯一匹配到工单的行不受影响。
     */
    public long archiveWorkbookRow(long rowId,String reason,String key) {
        requireTrackingSupervisor();
        String note=requireArchiveReason(reason);
        var actor=actors.current();
        return Long.parseLong(idem.execute(actor,key,"production.tracking.workbook.archive",
                Map.of("rowId",rowId),()->{
            var source=workbookRowForUpdate(rowId);
            requirePending(source);
            archiveRowAndRule(source,rowId,note,actor,key);
            return String.valueOf(rowId);
        }));
    }

    /** 批量处理待处理行：跳过 / 归档（各自按产品名建规则）/ 恢复为待处理。 */
    public Map<String,Object> batchResolveWorkbookRows(List<Long> rowIds,String action,String key) {
        requireTrackingSupervisor();
        if(rowIds==null||rowIds.isEmpty())throw new IllegalArgumentException("请先选择需要处理的表格行");
        if(rowIds.size()>200)throw new IllegalArgumentException("一次最多处理 200 行");
        if(!"skip".equals(action)&&!"archive".equals(action)&&!"restore".equals(action))
            throw new IllegalArgumentException("不支持的批量操作");
        String note="archive".equals(action)?requireArchiveReason(null):null;
        var actor=actors.current();
        String result=idem.execute(actor,key,"production.tracking.workbook.batch",
                Map.of("action",action,"rows",rowIds),()->{
            int changed=0;
            for(long rowId:rowIds){
                var source=workbookRowForUpdate(rowId);
                if(source==null)continue;
                String status=String.valueOf(source.get("matchStatus"));
                if("restore".equals(action)){
                    // 只把行放回待处理；归档规则是否撤销由主管在「归档规则」里单独决定。
                    if("PENDING".equals(status)||"REVIEW".equals(status))continue;
                    jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='PENDING',"
                            +"match_note='人工恢复为待处理',reviewed_at=NULL,reviewed_by_employee_id=NULL WHERE id=?",rowId);
                    changed++;
                    continue;
                }
                if(!"PENDING".equals(status))continue;
                if("skip".equals(action)){
                    jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='SKIPPED',"
                            +"match_note='人工标记跳过' WHERE id=?",rowId);
                    changed++;
                }else{
                    archiveRowAndRule(source,rowId,note,actor,key);
                    changed++;
                }
            }
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_workbook_rows",0,"UPDATE",
                    ErpChangeLogWriter.changes("action",action,"rows",rowIds,"changed",changed),
                    "FOLLOW_UP","批量处理表格待处理行："+action,key,null));
            return String.valueOf(changed);
        });
        return Map.of("changed",Integer.parseInt(result));
    }

    /** 撤销归档规则；restoreRows=true 时把由它归档的行一并放回待处理。 */
    public Map<String,Object> revokeWorkbookArchiveRule(long ruleId,boolean restoreRows,String key) {
        requireTrackingSupervisor();
        var actor=actors.current();
        String result=idem.execute(actor,key,"production.tracking.workbook.archive.revoke",
                Map.of("ruleId",ruleId,"restoreRows",restoreRows),()->{
            var rule=jdbc.queryForList("SELECT product_key,product_label FROM production_tracking_workbook_archive_rules "
                    +"WHERE id=? AND revoked_at IS NULL",ruleId).stream().findFirst()
                    .orElseThrow(()->new IllegalArgumentException("归档规则不存在或已撤销"));
            jdbc.update("UPDATE production_tracking_workbook_archive_rules SET revoked_at=NOW(),revoked_by_employee_id=? "
                    +"WHERE id=?",actor.employeeId(),ruleId);
            int restored=0;
            if(restoreRows){
                // 用与匹配同源的归一化键在 Java 侧比对，避免在 SQL 里再写一份归一化逻辑（两套实现必失控）。
                String productKey=String.valueOf(rule.get("product_key"));
                for(var row:jdbc.queryForList("SELECT id,product_name FROM production_tracking_workbook_rows "
                        +"WHERE match_status='ARCHIVED'")){
                    if(!productKey.equals(ProductionTrackingPolicy.normalizeKey(text(row.get("product_name")))))continue;
                    restored+=jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='PENDING',"
                            +"match_note='归档规则撤销，恢复为待处理',reviewed_at=NULL,reviewed_by_employee_id=NULL "
                            +"WHERE id=?",row.get("id"));
                }
            }
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_workbook_archive_rules",ruleId,"UPDATE",
                    ErpChangeLogWriter.changes("revoked",true,"restoredRows",restored),"FOLLOW_UP",
                    "撤销归档规则："+rule.get("product_label"),key,null));
            return restored+":"+rule.get("product_key");
        });
        String[] parts=result.split(":",2);
        return Map.of("restored",Integer.parseInt(parts[0]),"productKey",parts.length>1?parts[1]:"");
    }

    /**
     * 「关联工单」窗口打开时的智能候选：按车间/产品/交期/订单号/数量对氚云工单打分排序，
     * 直接给出最可能的工单并标出哪些字段对上、哪些冲突，不用主管先想关键词再搜。
     */
    public List<Map<String,Object>> workbookCandidates(long rowId) {
        requireTrackingSupervisor();
        var source=jdbc.queryForList("SELECT sheet_name,source_order_no,product_name,required_completion_date,"
                +"JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.quantity')) source_quantity "
                +"FROM production_tracking_workbook_rows WHERE id=?",rowId).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("表格行不存在"));
        String workshop=text(source.get("sheet_name"));
        String product=text(source.get("product_name"));
        String due=text(source.get("required_completion_date"));
        String orderNo=text(source.get("source_order_no"));
        String quantity=text(source.get("source_quantity"));
        String prefix=product==null||product.length()<4?product:product.substring(0,4);
        String suffix=orderNo!=null&&orderNo.length()>4?orderNo.substring(orderNo.length()-4):orderNo;
        var candidates=jdbc.query("SELECT p.id,p.work_order_no order_no,p.product_name,p.production_line workshop_type,"
                        +"p.application_date planned_start_date,p.required_completion_date due_date,"
                        +"p.expected_completion_date expected_finish_date,p.required_quantity planned_quantity "
                        +"FROM production_orders p WHERE p.external_source='H3YUN' AND ("
                        +"(? IS NOT NULL AND p.product_name LIKE ?) OR (? IS NOT NULL AND p.work_order_no LIKE ?) "
                        +"OR (? IS NOT NULL AND p.production_line=?)) "
                        +"ORDER BY CASE WHEN p.production_line=? THEN 0 ELSE 1 END,"
                        +"ABS(DATEDIFF(COALESCE(p.required_completion_date,CURDATE()),COALESCE(?,CURDATE()))),"
                        +"p.source_created_at DESC,p.id DESC LIMIT 400",
                (r,n)->row(r),
                prefix,prefix==null?null:"%"+prefix+"%",suffix,suffix==null?null:"%"+suffix+"%",
                workshop,workshop,workshop,due);
        var scored=new ArrayList<Map<String,Object>>();
        for(var candidate:candidates){
            var scoredRow=new LinkedHashMap<String,Object>(candidate);
            var matched=new ArrayList<String>();
            var conflicts=new ArrayList<String>();
            int score=0;
            boolean productEquals=equalsKey(product,String.valueOf(candidate.get("productName")));
            if(productEquals){score+=25;matched.add("产品名称");}else conflicts.add("产品名称");
            if(equalsKey(workshop,String.valueOf(candidate.get("workshopType")))){score+=20;matched.add("车间");}
            else conflicts.add("车间");
            Object dueDate=candidate.get("dueDate");
            if(due!=null&&dueDate!=null){
                if(due.equals(String.valueOf(dueDate))){score+=25;matched.add("交期");}else conflicts.add("交期");
            }
            String workOrderNo=text(candidate.get("orderNo"));
            if(orderNo!=null&&workOrderNo!=null){
                if(workOrderNo.endsWith(orderNo)||orderNo.endsWith(workOrderNo)){score+=30;matched.add("订单号");}
                else conflicts.add("订单号");
            }
            if(quantity!=null&&candidate.get("plannedQuantity")!=null
                    && new BigDecimal(quantity).compareTo(new BigDecimal(String.valueOf(candidate.get("plannedQuantity"))))==0){
                score+=10;matched.add("数量");
            }
            if(!productEquals&&containsKey(product,String.valueOf(candidate.get("productName")))){score+=15;matched.add("产品名称相近");}
            scoredRow.put("score",score);
            scoredRow.put("matchedFields",matched);
            scoredRow.put("conflictFields",conflicts);
            scored.add(scoredRow);
        }
        scored.sort((a,b)->Integer.compare((Integer)b.get("score"),(Integer)a.get("score")));
        return scored.size()>20?new ArrayList<>(scored.subList(0,20)):scored;
    }

    private static boolean equalsKey(String a,String b) {
        return a!=null&&ProductionTrackingPolicy.normalizeKey(a).equals(ProductionTrackingPolicy.normalizeKey(b));
    }
    private static boolean containsKey(String a,String b) {
        if(a==null||b==null)return false;
        String ka=ProductionTrackingPolicy.normalizeKey(a),kb=ProductionTrackingPolicy.normalizeKey(b);
        return !ka.isEmpty()&&!kb.isEmpty()&&(ka.contains(kb)||kb.contains(ka));
    }
    private static String text(Object value) {return value==null?null:String.valueOf(value);}
    private static String requireArchiveReason(String reason) {
        String note=clean(reason==null?"":reason,200,"归档原因");
        if(note.isEmpty())throw new IllegalArgumentException("请填写归档原因");
        return note;
    }
    private Map<String,Object> workbookRowForUpdate(long rowId) {
        return jdbc.query("SELECT * FROM production_tracking_workbook_rows WHERE id=? FOR UPDATE",(r,n)->row(r),rowId)
                .stream().findFirst().orElse(null);
    }
    private void requirePending(Map<String,Object> source) {
        if(!"PENDING".equals(source.get("matchStatus")))throw new IllegalArgumentException("该表格行已处理");
    }
    /** 置为已归档，并按产品名补一条归档规则（无产品名或规则已存在则只归档本行）。 */
    private void archiveRowAndRule(Map<String,Object> source,long rowId,String note,ErpActor actor,String key) {
        String label=text(source.get("productName"));
        jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='ARCHIVED',match_note=?,"
                +"reviewed_at=NOW(),reviewed_by_employee_id=? WHERE id=?",("已归档："+note),actor.employeeId(),rowId);
        String productKey=label==null?null:ProductionTrackingPolicy.normalizeKey(label);
        if(productKey!=null&&!productKey.isEmpty()){
            Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_workbook_archive_rules "
                    +"WHERE product_key=? AND revoked_at IS NULL",Integer.class,productKey);
            if(exists==null||exists==0)
                jdbc.update("INSERT INTO production_tracking_workbook_archive_rules(product_key,product_label,reason,"
                        +"created_by_employee_id,created_by_name) VALUES(?,?,?,?,?)",
                        productKey,label,note,actor.employeeId(),actor.name());
        }
        audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_workbook_rows",rowId,"UPDATE",
                ErpChangeLogWriter.changes("matchStatus","ARCHIVED","reason",note),"FOLLOW_UP",
                "归档表格行："+(label==null?"":label),key,null));
    }

    public long resolveWorkbookRow(long rowId,Long productionOrderId,boolean skip,String key) {
        requireTrackingSupervisor();
        var actor=actors.current();
        if(!skip && productionOrderId==null)throw new IllegalArgumentException("请选择对应的氚云生产任务");
        return Long.parseLong(idem.execute(actor,key,"production.tracking.workbook.resolve",
                Map.of("rowId",rowId,"productionOrderId",productionOrderId==null?0:productionOrderId,"skip",skip),()->{
            var source=jdbc.query("SELECT * FROM production_tracking_workbook_rows WHERE id=? FOR UPDATE",
                    (r,n)->row(r),rowId).stream().findFirst()
                    .orElseThrow(()->new IllegalArgumentException("表格行不存在"));
            if(!"PENDING".equals(source.get("matchStatus")))throw new IllegalArgumentException("该表格行已处理");
            if(skip){
                jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='SKIPPED',"
                        +"match_note='人工标记跳过' WHERE id=?",rowId);
                return String.valueOf(rowId);
            }
            long orderId=productionOrderId;
            Integer target=jdbc.queryForObject("SELECT COUNT(*) FROM production_orders WHERE id=? AND external_source='H3YUN'",
                    Integer.class,orderId);
            if(target==null||target==0)throw new IllegalArgumentException("氚云生产任务不存在");
            Integer other=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_workbook_rows "
                    +"WHERE production_order_id=? AND source_hash=? AND id<>? AND match_status IN ('IMPORTED','REVIEW','REVIEWED')",
                    Integer.class,orderId,source.get("sourceHash"),rowId);
            if(other!=null&&other>0)throw new IllegalArgumentException("同一版本已有其他表格行关联该任务，请核对重复行");
            jdbc.update("INSERT INTO production_tracking_enrollments(production_order_id,origin) VALUES(?,'WORKBOOK') "
                    +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",orderId);
            jdbc.update("INSERT INTO production_tracking_state(production_order_id) VALUES(?) "
                    +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",orderId);
            var followup=jdbc.query(HAS_FOLLOWUP_SQL+" FOR UPDATE",(r,n)->row(r),orderId).stream().findFirst()
                    .orElseThrow(()->new IllegalStateException("跟单状态初始化失败"));
            long version=((Number)followup.get("version")).longValue();
            long entries=((Number)followup.get("entriesCount")).longValue();
            if(version>0||entries>0){
                jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='REVIEW',production_order_id=?,"
                        +"match_note='主管已关联工单，请逐项审核表格差异' WHERE id=?",orderId,rowId);
                audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_workbook_rows",rowId,
                        "UPDATE",ErpChangeLogWriter.changes("productionOrderId",orderId,"matchStatus","REVIEW"),
                        "FOLLOW_UP","关联已有跟单，待审核表格差异",key,null));
                return String.valueOf(rowId);
            }
            importWorkbookPayload(orderId,String.valueOf(source.get("payloadJson")),rowId);
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_workbook_rows",rowId,
                    "UPDATE",ErpChangeLogWriter.changes("productionOrderId",orderId),"FOLLOW_UP","核对并导入表格记录",key,null));
            return String.valueOf(rowId);
        }));
    }

    void importWorkbookPayload(long orderId,String payload,long rowId) {
        JsonNode data;
        try {data=json.readTree(payload);}catch(Exception e){throw new IllegalArgumentException("表格行数据无效",e);}
        var existing=jdbc.queryForList("SELECT version,stopped_at FROM production_tracking_state WHERE production_order_id=? FOR UPDATE",orderId);
        if(!existing.isEmpty()&&existing.get(0).get("stopped_at")!=null)
            throw new IllegalArgumentException("该任务已停止跟单，请先恢复跟单再导入进度");
        if(!existing.isEmpty()&&((Number)existing.get(0).get("version")).longValue()>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该工单已有跟单记录，请重新预览并逐项审核差异");
        jdbc.update("INSERT INTO production_tracking_enrollments(production_order_id,origin) VALUES(?,'WORKBOOK') "
                +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",orderId);
        var assignee=defaultAssignee(orderId).orElse(null);
        var importedForecast=dateNode(data,"forecastFinishDate");
        var sourceDeadline=jdbc.queryForObject("SELECT COALESCE(expected_completion_date,CASE WHEN (application_date IS NULL "
                +"OR required_completion_date>=application_date) AND (source_created_at IS NULL "
                +"OR required_completion_date>=DATE(source_created_at)) THEN required_completion_date END) "
                +"FROM production_orders WHERE id=?",LocalDate.class,orderId);
        if(Objects.equals(importedForecast,sourceDeadline))importedForecast=null;
        jdbc.update(IMPORT_STATE_SQL,orderId,importedForecast,textNode(data,"delayReason"),
                assignee==null?null:assignee.get("id"),assignee==null?null:assignee.get("name"));
        for(JsonNode stage:data.path("milestones")) {
            if(stage.path("completedDate").isNull()||stage.path("completedDate").asText().isBlank())continue;
            jdbc.update("INSERT IGNORE INTO production_tracking_milestones(production_order_id,stage_code,stage_status,completed_date,note) "
                    +"VALUES(?,?,'DONE',?,NULL)",orderId,stage.path("code").asText(),
                    LocalDate.parse(stage.path("completedDate").asText()));
        }
        jdbc.update("INSERT IGNORE INTO production_tracking_entries(production_order_id,version,content,before_json,after_json,"
                +"created_by_employee_id,created_by_name) VALUES(?,1,?,'{}',?,0,'表格导入')",
                orderId,"导入车间进度表「"+data.path("sheet").asText()+"」第 "
                        +data.path("rowNumber").asInt()+" 行快照",payload);
        jdbc.update("UPDATE production_tracking_workbook_rows SET match_status='IMPORTED',production_order_id=?,"
                +"match_note='人工核对导入',imported_at=NOW() WHERE id=?",orderId,rowId);
    }

    private static LocalDate dateNode(JsonNode root,String field) {
        String value=textNode(root,field);return value==null?null:LocalDate.parse(value);
    }
    private static String textNode(JsonNode root,String field) {
        JsonNode node=root.path(field);return node.isNull()||node.isMissingNode()||node.asText().isBlank()?null:node.asText();
    }

    public Map<String,Object> summary() {
        var params=new ArrayList<Object>();var where=visible(params);
        where.append(" AND t.stopped_at IS NULL");
        // 待跟单＝范围内所有「一个跟单节点都没登记」的任务，与同名列表筛选共用 TRACKING_STATUS_CASE。
        return jdbc.queryForMap("SELECT COALESCE(SUM("+TRACKING_STATUS_CASE+"='pending'),0) untracked,"
                +"COALESCE(SUM("+REMINDER_CASE+"='due'),0) due,"
                +"COALESCE(SUM("+REMINDER_CASE+"='overdue'),0) overdue,"
                +"COALESCE(SUM("+REMINDER_CASE+"='delayed'),0) `delayed`,"
                +"COALESCE(SUM("+TIME_REVIEW_DUE+"),0) timeReviewDue "
                +TRACKING_FROM+where,params.toArray());
    }

    public Map<String,Object> dashboard() {
        var params=new ArrayList<Object>();
        var where=visible(params);
        where.append(" AND t.stopped_at IS NULL");
        // 在制按实际交付日期判断；已交付口径与列表的生产交付结果完全一致。
        var delivered=DELIVERED_IN_DATE;
        var baseline=DELIVERY_BASELINE;
        var sql="SELECT COALESCE(NULLIF(p.production_line,''),'未分车间') workshop,COUNT(*) total,"
                +"SUM("+delivered+" IS NULL) active,"
                +"COALESCE(SUM("+delivered+" IS NULL AND "+baseline+"<CURDATE()),0) overdueOpen,"
                +"COALESCE(SUM("+delivered+" IS NULL AND "+baseline+">=CURDATE() AND "+baseline+"<=DATE_ADD(CURDATE(),INTERVAL 3 DAY)),0) dueSoon,"
                +"SUM("+delivered+" IS NULL AND "+baseline+" IS NULL) noBaseline,"
                +"SUM("+DELIVERY_OUTCOME+"='ON_TIME') onTime,"
                +"SUM("+DELIVERY_OUTCOME+"='OVERDUE') deliveredOverdue,"
                +"SUM("+DELIVERY_OUTCOME+"='NO_BASELINE') deliveredNoBaseline,"
                +"SUM(ib.first_in IS NOT NULL AND (p.actual_completion_date IS NULL OR ib.first_in<p.actual_completion_date)) inboundBeforeReport "
                +TRACKING_FROM+INBOUND_JOIN+where+" GROUP BY COALESCE(NULLIF(p.production_line,''),'未分车间') ORDER BY active DESC,workshop";
        var rows=jdbc.queryForList(sql,params.toArray());
        var totals=new LinkedHashMap<String,Long>();
        for(var key:List.of("total","active","overdueOpen","dueSoon","noBaseline","onTime","deliveredOverdue","deliveredNoBaseline","inboundBeforeReport"))totals.put(key,0L);
        // ⚠️ 必须容忍 NULL：`SUM(cond1 AND cond2)` 在两个条件里任何一个对某行求值为 NULL 时，
        // 若该车间全部行都是 NULL，SUM 整体返回 NULL（例：某车间只有一单，且交付日期与交付基准都为空
        // ⇒ `NULL<CURDATE()` 得 NULL ⇒ overdueOpen/dueSoon 为 NULL）。直接 .longValue() 会 NPE 打挂看板。
        // 语义上空集计数即 0，故把非 Number 一律当 0。
        for(var row:rows)for(var key:totals.keySet()){
            var value=row.get(key);
            totals.put(key,totals.get(key)+(value instanceof Number number?number.longValue():0L));
        }
        return Map.of("totals",totals,"workshops",rows,"visibility",users.scopeOf(FeatureCode.MANUFACTURING));
    }

    public Map<String,Object> list(String keyword,String workshop,String filter,boolean mine,String assignee,int page,int size) {
        String tracking=switch(filter){case "active"->"open";case "complete"->"completed";case "untracked"->"pending";default->"all";};
        String reminder=Set.of("overdue","due","delayed","near").contains(filter)?filter:"all";
        if(!Set.of("active","all","complete","overdue","due","delayed","near","untracked").contains(filter))
            throw new IllegalArgumentException("跟单筛选无效");
        return list(keyword,workshop,tracking,reminder,"",mine,assignee,page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,"",page,size);
    }

    /** 兼容旧调用点（不含「跟单待办」筛选）：等价于 action=all。 */
    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,"all",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,"",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,"","",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,EMPTY_DATE_FILTER,EMPTY_DATE_FILTER,EMPTY_DATE_FILTER,page,size);
    }

    public record DateRangeFilter(String from,String to,boolean missing) {}
    private static final DateRangeFilter EMPTY_DATE_FILTER=new DateRangeFilter("","",false);

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,false,page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,"",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,"",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,
                EMPTY_DATE_FILTER,page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,
            DateRangeFilter created,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,
                created,"",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,
            DateRangeFilter created,String optionalStage,int page,int size) {
        if(page<1 || size<1 || size>100)throw new IllegalArgumentException("分页范围无效");
        var f=filter(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,created,
                optionalStage);
        // 计数与明细必须挂同一份 JOIN：TIME_OVERDUE 谓词引用交付日（依赖 INBOUND_JOIN 的 ib）。
        // INBOUND_JOIN 是按工单号聚合的 1:1 派生表，挂上不改变行数，COUNT(*) 仍等于工单数。
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+TRACKING_FROM+INBOUND_JOIN+f.where(),
                Long.class,f.params().toArray()));
        var pageParams=new ArrayList<>(f.params());
        pageParams.add(size);pageParams.add((long)(page-1)*size);
        var items=jdbc.query(LIST_COLUMNS+TRACKING_FROM+INBOUND_JOIN+f.where()+LIST_ORDER+" LIMIT ? OFFSET ?",
                (r,n)->row(r),pageParams.toArray());
        attachStageProgress(items);
        attachTimeReviews(items);
        return Map.of("items",items,"total",total,"page",page,"size",size,
                "visibility",users.scopeOf(FeatureCode.MANUFACTURING));
    }

    /**
     * 列表与导出**共用的筛选构造（唯一来源）**：入参校验 + 可见性 + 各筛选轴拼接。
     * 两边若各写一份，导出出来的行迟早和列表对不上 —— 所以导出只能从这里取条件。
     */
    private record Filtered(StringBuilder where,List<Object> params) {}

    private Filtered filter(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,DateRangeFilter created,
            String optionalStage) {
        if(!Set.of("open","all","pending","in_progress","completed","stopped").contains(trackingStatus))
            throw new IllegalArgumentException("跟单状态筛选无效");
        if(!Set.of("all","overdue","due","delayed","near","none","time_due","time_attention",
                "time_application","time_required","time_acceptance","time_commitment",
                "time_reporting","time_inbound_reporting","time_overdue").contains(reminder))
            throw new IllegalArgumentException("提醒筛选无效");
        if(!"all".equals(action)&&!"any".equals(action)&&!TRACKING_ACTION_CODES.contains(action))
            throw new IllegalArgumentException("跟单待办筛选无效");
        var params=new ArrayList<Object>();var where=visible(params);
        if(urgent)where.append(" AND ").append(URGENT_REMARK);
        appendDateRangeFilter(where,params,CREATED_MONTH_DATE,created,"生产任务单提交时间");
        appendDateRangeFilter(where,params,"p.application_date",application,"申请时间");
        appendDateRangeFilter(where,params,"DATE(p.schedule_time)",schedule,"排产时间");
        appendDateRangeFilter(where,params,DEADLINE,expected,"预计完成");
        if(countdown!=null&&!countdown.isBlank()){
            var selected=new java.util.LinkedHashSet<>(List.of(countdown.split(",",-1)));
            if(!Set.of("overdue","today","soon","later","undated","delivered").containsAll(selected))
                throw new IllegalArgumentException("生产倒计时筛选无效");
            String baseline="COALESCE(t.forecast_finish_date,p.expected_completion_date,"+VALID_REQUIRED_DATE+")";
            String pending="(t.stopped_at IS NULL AND "+DELIVERED_IN_DATE+" IS NULL AND ";
            var clauses=new ArrayList<String>();
            for(String value:selected)clauses.add(switch(value){
                case "delivered" -> "(t.stopped_at IS NULL AND "+DELIVERED_IN_DATE+" IS NOT NULL)";
                case "undated" -> pending+baseline+" IS NULL)";
                case "overdue" -> pending+baseline+"<CURDATE())";
                case "today" -> pending+baseline+"=CURDATE())";
                case "soon" -> pending+baseline+">CURDATE() AND "+baseline+"<=DATE_ADD(CURDATE(),INTERVAL 3 DAY))";
                case "later" -> pending+baseline+">DATE_ADD(CURDATE(),INTERVAL 3 DAY))";
                default -> throw new IllegalArgumentException("生产倒计时筛选无效");
            });
            where.append(" AND (").append(String.join(" OR ",clauses)).append(")");
        }
        // 「业务员」列筛选：取值来自数据本身（p.salesperson_name），故必须参数化传入，不做字符串拼接。
        // 空串会被 split 出来，但不是可选中的值，直接剔除；全空则视为不筛。
        if(salesperson!=null&&!salesperson.isBlank()){
            var selected=new java.util.LinkedHashSet<>(List.of(salesperson.split(",",-1)));
            selected.removeIf(String::isBlank);
            if(!selected.isEmpty()){
                where.append(" AND p.salesperson_name IN (")
                        .append("?,".repeat(selected.size()-1)).append("?)");
                params.addAll(selected);
            }
        }
        // 「生产交付结果」列筛选：取值是固定的四态枚举（见 {@link #DELIVERY_OUTCOME}），白名单校验后内联。
        // ⚠️ DELIVERY_OUTCOME 依赖 {@link #INBOUND_JOIN} 的 ib；列表/导出的 COUNT 与明细查询均已挂该 JOIN。
        if(deliveryResult!=null&&!deliveryResult.isBlank()){
            var selected=new java.util.LinkedHashSet<>(List.of(deliveryResult.split(",",-1)));
            if(!Set.of("PENDING","NO_BASELINE","ON_TIME","OVERDUE").containsAll(selected))
                throw new IllegalArgumentException("生产交付结果筛选无效");
            where.append(" AND t.stopped_at IS NULL AND (").append(DELIVERY_OUTCOME).append(") IN ('")
                    .append(String.join("','",selected)).append("')");
        }
        // 跟单待办与「时间核实」是两条独立的轴，各自一段条件，可叠加。
        if(!"all".equals(action)){
            where.append(" AND ").append(trackingActionPredicate(action));
            if(!"any".equals(action))params.add(action);
        }
        if(!"all".equals(trackingStatus))where.append(" AND ").append(TRACKING_STATUS_CASE)
                .append(" IN (").append("open".equals(trackingStatus)?"'pending','in_progress'":"'"+trackingStatus+"'").append(")");
        String timePredicate=switch(reminder){
            case "time_due"->TIME_REVIEW_DUE;
            case "time_attention"->TIME_ATTENTION;
            case "time_application"->TIME_SOURCE_APPLICATION;
            case "time_required"->TIME_SOURCE_REQUIRED;
            case "time_acceptance"->TIME_ACCEPTANCE;
            case "time_commitment"->TIME_COMMITMENT;
            case "time_reporting"->TIME_REPORTING;
            case "time_inbound_reporting"->TIME_INBOUND_REPORTING_ATTENTION;
            case "time_overdue"->TIME_OVERDUE;
            default->null;
        };
        if(timePredicate!=null)where.append(" AND t.stopped_at IS NULL AND ").append(timePredicate);
        else if(!"all".equals(reminder)){where.append(" AND ").append(REMINDER_CASE).append("=?");params.add(reminder);}
        if(sourceStatus!=null&&!sourceStatus.isBlank()){where.append(" AND p.status=?");params.add(sourceStatus.trim());}
        if(month!=null&&!month.isBlank()) {
            if(!month.matches("\\d{4}-(0[1-9]|1[0-2])"))throw new IllegalArgumentException("创建月份无效");
            var start=java.time.YearMonth.parse(month).atDay(1);
            where.append(" AND ").append(CREATED_MONTH_DATE).append(">=? AND ")
                    .append(CREATED_MONTH_DATE).append("<?");
            params.add(start);params.add(start.plusMonths(1));
        }
        if(keyword!=null&&!keyword.isBlank()) {
            where.append(" AND (p.work_order_no LIKE ? OR p.product_name LIKE ? OR p.customer_name LIKE ?)");
            for(int i=0;i<3;i++)params.add("%"+keyword.trim()+"%");
        }
        if(specification!=null&&!specification.isBlank()){
            where.append(" AND CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) LIKE ?");
            params.add("%"+specification.trim()+"%");
        }
        if(moldLayout!=null&&!moldLayout.isBlank()){
            where.append(" AND TRIM(p.mold_layout)=?");
            params.add(moldLayout.trim());
        }
        if(workshop!=null&&!workshop.isBlank()){where.append(" AND p.production_line LIKE ?");params.add("%"+workshop.trim()+"%");}
        // 「按需工序」筛选（氧化/喷特氟龙、木箱）：取值是固定的工序码，白名单校验后内联；
        // 谓词依赖 TRACKING_FROM 的 ms 派生表 —— 列表/导出的 COUNT 与明细都挂了同一份 FROM。
        if(optionalStage!=null&&!optionalStage.isBlank()){
            String code=optionalStage.trim();
            if(!OPTIONAL_STAGE_CODES.contains(code))throw new IllegalArgumentException("按需工序筛选无效");
            where.append(" AND ").append(needsOptionalStage(code));
        }
        if(mine){where.append(" AND assigned.id=?");params.add(actors.current().employeeId());}
        if(assignee!=null&&!assignee.isBlank()){
            String value=assignee.trim();
            long employeeId;
            try{employeeId=Long.parseLong(value);}catch(NumberFormatException e){
                throw new IllegalArgumentException("跟单员筛选无效");
            }
            where.append(" AND assigned.id=?");params.add(employeeId);
        }
        return new Filtered(where,params);
    }

    private static void appendDateRangeFilter(StringBuilder where,List<Object> params,String expression,
            DateRangeFilter filter,String label) {
        if(filter==null)return;
        var from=parseFilterDate(filter.from(),label);
        var to=parseFilterDate(filter.to(),label);
        if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException(label+"起始日期不能晚于截止日期");
        var range=new ArrayList<String>();
        if(from!=null){range.add(expression+">=?");params.add(from);}
        if(to!=null){range.add(expression+"<=?");params.add(to);}
        if(range.isEmpty()){
            if(filter.missing())where.append(" AND ").append(expression).append(" IS NULL");
        }else{
            where.append(" AND (").append(String.join(" AND ",range));
            if(filter.missing())where.append(" OR ").append(expression).append(" IS NULL");
            where.append(")");
        }
    }

    private static java.time.LocalDate parseFilterDate(String value,String label) {
        if(value==null||value.isBlank())return null;
        if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException(label+"日期无效");
        try{return java.time.LocalDate.parse(value);}catch(java.time.format.DateTimeParseException ex){
            throw new IllegalArgumentException(label+"日期无效",ex);
        }
    }

    /**
     * 导出用：与列表**完全同一套**筛选 / 可见性 / 取数列 / 排序，只是不分页。
     * 上限 {@link #EXPORT_MAX_ROWS}，超出即截断并在响应里标记 truncated（前端会明确提示）。
     */
    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,"");
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,"","");
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,EMPTY_DATE_FILTER,EMPTY_DATE_FILTER,EMPTY_DATE_FILTER);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,false);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,"");
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,"");
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,EMPTY_DATE_FILTER);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,DateRangeFilter created) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,created,"");
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,DateRangeFilter created,String ids) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,created,"",ids);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String specification,String moldLayout,DateRangeFilter created,
            String optionalStage,String ids) {
        var f=filter(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,specification,moldLayout,created,
                optionalStage);
        String where=f.where().toString();
        var params=new ArrayList<>(f.params());
        if(ids!=null&&!ids.isBlank()){
            var selected=new LinkedHashSet<Long>();
            for(String part:ids.split(",",-1)){
                if(!part.matches("[1-9][0-9]*"))throw new IllegalArgumentException("导出工单编号无效");
                try { selected.add(Long.parseLong(part)); }
                catch(NumberFormatException ex){throw new IllegalArgumentException("导出工单编号无效");}
            }
            if(selected.isEmpty()||selected.size()>100)throw new IllegalArgumentException("单次最多选择 100 条工单导出");
            where+=" AND p.id IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")";
            params.addAll(selected);
        }
        // total 与列表同口径（同一份 where + 同一份 INBOUND_JOIN），截断时前端能报出「共多少、导了多少」。
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*)"+TRACKING_FROM+INBOUND_JOIN+where,
                Long.class,params.toArray()));
        var limitParams=new ArrayList<>(params);
        limitParams.add(EXPORT_MAX_ROWS+1);
        var rows=jdbc.query(LIST_COLUMNS+TRACKING_FROM+INBOUND_JOIN+where+LIST_ORDER+" LIMIT ?",
                (r,n)->row(r),limitParams.toArray());
        boolean truncated=rows.size()>EXPORT_MAX_ROWS;
        if(truncated)rows=new ArrayList<>(rows.subList(0,EXPORT_MAX_ROWS));
        attachStageProgress(rows);
        attachTimeReviews(rows);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",rows);
        result.put("total",total);
        result.put("exported",rows.size());
        result.put("truncated",truncated);
        result.put("maxRows",EXPORT_MAX_ROWS);
        result.put("visibility",users.scopeOf(FeatureCode.MANUFACTURING));
        return result;
    }

    /**
     * 筛选下拉选项唯一源（车间 + 跟单员），与列表共用同一可见性口径，
     * 保证下拉里出现的项一定能在列表里筛到 —— 避免各写一套过滤条件导致选项与结果错位。
     */
    public Map<String,Object> filterOptions() {
        var params=new ArrayList<Object>();var where=visible(params);
        var workshops=jdbc.queryForList("SELECT DISTINCT p.production_line"+TRACKING_FROM+where
                +" AND p.production_line IS NOT NULL AND p.production_line<>'' ORDER BY p.production_line",
                String.class,params.toArray());
        var moldLayouts=jdbc.queryForList("SELECT DISTINCT TRIM(p.mold_layout)"+TRACKING_FROM+where
                +" AND p.mold_layout IS NOT NULL AND TRIM(p.mold_layout)<>'' ORDER BY 1",
                String.class,params.toArray());
        var assignees=jdbc.query("SELECT DISTINCT assigned.id,assigned.name"+TRACKING_FROM+where
                +" AND assigned.id IS NOT NULL ORDER BY assigned.name",(r,n)->row(r),params.toArray());
        var sourceStatuses=jdbc.queryForList("SELECT DISTINCT p.status"+TRACKING_FROM+where
                +" AND p.status IS NOT NULL AND p.status<>'' ORDER BY p.status",String.class,params.toArray());
        // 「业务员」列头多选筛选用：与上面几项同一可见性口径，保证下拉出现的人一定能筛到。
        var salespersons=jdbc.queryForList("SELECT DISTINCT p.salesperson_name"+TRACKING_FROM+where
                +" AND p.salesperson_name IS NOT NULL AND p.salesperson_name<>'' ORDER BY p.salesperson_name",
                String.class,params.toArray());
        var months=jdbc.query("SELECT DATE_FORMAT("+CREATED_MONTH_DATE+",'%Y-%m') month,COUNT(*) count"
                +TRACKING_FROM+where+" AND "+CREATED_MONTH_DATE+" IS NOT NULL GROUP BY month ORDER BY month DESC",
                (r,n)->Map.of("month",r.getString("month"),"count",r.getLong("count")),params.toArray());
        // 各类跟单待办的条数：与列表同一个 FROM + 同一段 where，故下拉里的数字必然等于点进去的条数。
        // ⚠️ 这里不能像上面几项那样再追加业务条件（如 production_line IS NOT NULL），否则数字会偏小。
        var actionCounts=jdbc.query("SELECT "+TRACKING_ACTION+" action,COUNT(*) count"
                +TRACKING_FROM+INBOUND_JOIN+where+" AND "+TRACKING_ACTION+" IS NOT NULL GROUP BY action",
                (r,n)->Map.of("action",r.getString("action"),"count",r.getLong("count")),params.toArray());
        // 「按需工序」条数（氧化/喷特氟龙、木箱）：同一份 FROM + 同一段可见性 where ⇒ 下拉里的数字＝点进去的条数。
        // ⚠️ SUM 的输入是布尔表达式；可见范围内一行都没有时整列返回 NULL（不是 0）⇒ 必须 COALESCE 兜底，
        // 否则会打成「下拉里的工序计数为 null」这种看不出来的错。
        var optionalTotals=jdbc.queryForList("SELECT COALESCE(SUM("
                +needsOptionalStage(ProductionTrackingPolicy.OXIDATION)+"),0) oxidation,COALESCE(SUM("
                +needsOptionalStage(ProductionTrackingPolicy.WOODEN_CRATE)+"),0) wooden_crate"
                +TRACKING_FROM+where,params.toArray()).stream().findFirst().orElse(Map.of());
        var optionalStageCounts=OPTIONAL_STAGE_CODES.stream().map(code->Map.<String,Object>of("code",code,
                "count",((Number)optionalTotals.getOrDefault(
                        ProductionTrackingPolicy.OXIDATION.equals(code)?"oxidation":"wooden_crate",0L)).longValue()))
                .toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("workshops",workshops);
        result.put("moldLayouts",moldLayouts);
        result.put("assignees",assignees);
        result.put("sourceStatuses",sourceStatuses);
        result.put("salespersons",salespersons);
        result.put("months",months);
        result.put("actionCounts",actionCounts);
        result.put("optionalStageCounts",optionalStageCounts);
        return result;
    }

    /**
     * 本单需要完成的节点数＝必经节点 + 本单适用的「按需工序」（只有 optional 且 applicable 的才算）。
     * 必须与列表 SQL 的 STAGE_TOTAL_WITH_OPTIONAL 口径一致，否则详情进度条会和列表数字打架。
     */
    static int stageTotal(String workshop,List<Map<String,Object>> milestones) {
        return ProductionTrackingPolicy.requiredStages(workshop).size()
                +(int)milestones.stream().filter(m->Boolean.TRUE.equals(m.get("optional"))
                        &&Boolean.TRUE.equals(m.get("applicable"))).count();
    }

    /** 单个节点的参考完成推算结果：typicalDays=该工序一般耗时（基准表中位数）；referenceDate=参考完成日期；referenceOverdueDays=超参考未完成的天数。 */
    record NodeReference(BigDecimal typicalDays,LocalDate referenceDate,Integer referenceOverdueDays) {}

    /**
     * 链式推算各节点参考完成日期（负责人 2026-10-10 定：给跟单员一个「一般情况下这道工序何时该完成」的参考）。
     * <p>锚点＝计划开工（回退申请日）；每往前推一个节点：上游已实际完成用实际完成日接力，未完成用其参考值接力；
     * 按需工序本单不适用则不占链（封口机氧化列绝大多数「无」，与基准中位数的计算口径一致）。
     * 无基准（如机器车间的画图/钣金/调试暂无跟单数据）或无锚点时参考值为空，不强算。</p>
     */
    static Map<String,NodeReference> computeNodeReferences(List<String> codes,LocalDate anchor,
            Map<String,BigDecimal> benchmarks,Map<String,Boolean> applicable,
            Map<String,String> statuses,Map<String,LocalDate> completedDates,LocalDate today) {
        var out=new LinkedHashMap<String,NodeReference>();
        LocalDate cursor=anchor;
        for(String code:codes){
            if(ProductionTrackingPolicy.isOptionalStage(code)&&!Boolean.TRUE.equals(applicable.get(code))){
                out.put(code,new NodeReference(benchmarks.get(code),null,null));continue;
            }
            BigDecimal typical=benchmarks.get(code);
            LocalDate reference=typical==null||cursor==null?null:cursor.plusDays((long)Math.round(typical.doubleValue()));
            String status=statuses.getOrDefault(code,"PENDING");
            LocalDate completed=completedDates.get(code);
            if("DONE".equals(status)&&completed!=null)cursor=completed;
            else if(reference!=null)cursor=reference;
            Integer overdue=reference!=null&&!"DONE".equals(status)&&today.isAfter(reference)
                    ?(int)ChronoUnit.DAYS.between(reference,today):null;
            out.put(code,new NodeReference(typical,reference,overdue));
        }
        return out;
    }

    /** 节点参考基准：workshop_kind → stage_code → 一般耗时天数。表很小，不做缓存。 */
    private Map<String,Map<String,BigDecimal>> nodeBenchmarks() {
        var out=new HashMap<String,Map<String,BigDecimal>>();
        jdbc.query("SELECT workshop_kind,stage_code,typical_days FROM production_tracking_node_benchmarks",r->{
            out.computeIfAbsent(r.getString(1),k->new HashMap<String,BigDecimal>())
                    .put(r.getString(2),r.getBigDecimal(3));
        });
        return out;
    }

    private static final String BENCHMARK_SEALER="SEALER";
    private static final String BENCHMARK_MACHINE="MACHINE";

    /** 保存入参：typicalDays 传 null 表示不设基准（删除该节点基准）。 */
    public record BenchmarkSave(String workshopKind,String stageCode,BigDecimal typicalDays) {}

    /**
     * 基准配置矩阵：两个车间类别 × 各自全部节点（含表里还没配的，如机器车间 7 个节点）。
     * 不缓存——配置页低频访问，缓存只会让刚保存的值读不回来。
     */
    public Map<String,Object> nodeBenchmarkMatrix() {
        var saved=new HashMap<String,Map<String,Object>>();
        jdbc.query("SELECT workshop_kind,stage_code,typical_days,sample_count,baseline_note,computed_at "
                +"FROM production_tracking_node_benchmarks",r->{
            var hit=new LinkedHashMap<String,Object>();
            hit.put("typicalDays",r.getBigDecimal(3));
            hit.put("sampleCount",r.getInt(4));
            hit.put("baselineNote",r.getString(5));
            var computed=r.getDate(6);
            hit.put("computedAt",computed==null?null:computed.toLocalDate());
            saved.put(r.getString(1)+"/"+r.getString(2),hit);
        });
        var workshops=new ArrayList<Map<String,Object>>();
        workshops.add(benchmarkWorkshop(BENCHMARK_SEALER,"封口机",saved));
        workshops.add(benchmarkWorkshop(BENCHMARK_MACHINE,"机器车间",saved));
        return Map.of("workshops",workshops);
    }

    /** 单车间矩阵：按 Policy 的节点顺序枚举，首段锚点是「计划开工」，其余取上一节点。 */
    static Map<String,Object> benchmarkWorkshop(String kind,String label,
            Map<String,Map<String,Object>> saved) {
        boolean sealer=BENCHMARK_SEALER.equals(kind);
        var stages=new ArrayList<Map<String,Object>>();
        String prev=null;
        for(String code:ProductionTrackingPolicy.stageCodes(sealer?"封口机":"全自动气调机")){
            var hit=saved.get(kind+"/"+code);
            String prevLabel=prev==null?"计划开工":ProductionTrackingPolicy.stageLabel(prev,sealer);
            var row=new LinkedHashMap<String,Object>();
            row.put("stageCode",code);
            row.put("stageLabel",ProductionTrackingPolicy.stageLabel(code,sealer));
            row.put("intervalLabel",prevLabel+" → "+ProductionTrackingPolicy.stageLabel(code,sealer));
            row.put("optionalStage",ProductionTrackingPolicy.isOptionalStage(code));
            row.put("typicalDays",hit==null?null:hit.get("typicalDays"));
            row.put("sampleCount",hit==null?null:hit.get("sampleCount"));
            row.put("baselineNote",hit==null?null:hit.get("baselineNote"));
            row.put("computedAt",hit==null?null:hit.get("computedAt"));
            stages.add(row);
            prev=code;
        }
        var out=new LinkedHashMap<String,Object>();
        out.put("workshopKind",kind);
        out.put("workshopLabel",label);
        out.put("stages",stages);
        return out;
    }

    /**
     * 保存节点参考基准（权限由 Controller 的 manufacturing.baseline.manage 把关）。
     * <p>逐行 upsert、逐行审计——「谁把备料→模具从 3 天改成 5 天」必须可追。
     * <p>⚠️ 参考天数是派生值（详情的 referenceDate / referenceOverdueDays）的输入，改完
     * <b>全部在制单的参考完成日期当场重算</b>，不落库也不保留旧判定——这是设计使然：
     * 留着旧判定会让「口径已换、结论还是老的」更难解释。因此改基准必进审计日志。
     */
    @Transactional
    public Map<String,Object> saveNodeBenchmarks(List<BenchmarkSave> rows) {
        if(rows==null||rows.isEmpty())throw new IllegalArgumentException("没有要保存的基准");
        var actor=actors.current();
        int changed=0;
        for(var row:rows){
            if(row==null||row.stageCode()==null)throw new IllegalArgumentException("基准行缺少节点");
            boolean sealer;
            if(BENCHMARK_SEALER.equals(row.workshopKind()))sealer=true;
            else if(BENCHMARK_MACHINE.equals(row.workshopKind()))sealer=false;
            else throw new IllegalArgumentException("车间类别无效");
            if(!ProductionTrackingPolicy.stageCodes(sealer?"封口机":"全自动气调机").contains(row.stageCode()))
                throw new IllegalArgumentException("该节点不属于此车间");
            BigDecimal days=row.typicalDays();
            if(days==null){
                Long existing=benchmarkId(row.workshopKind(),row.stageCode());
                if(existing==null)continue;
                jdbc.update("DELETE FROM production_tracking_node_benchmarks WHERE id=?",existing);
                audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_node_benchmarks",existing,
                        "DELETE",ErpChangeLogWriter.changes("workshopKind",row.workshopKind(),"stageCode",row.stageCode()),
                        "FOLLOW_UP","删除节点参考基准",null,null));
                changed++;
                continue;
            }
            if(days.signum()<0||days.doubleValue()>60)
                throw new IllegalArgumentException("参考天数须在 0~60 天之间");
            jdbc.update("INSERT INTO production_tracking_node_benchmarks(workshop_kind,stage_code,typical_days,"
                    +"sample_count,baseline_note,computed_at) VALUES(?,?,?,0,'管理员手工配置',CURDATE()) "
                    +"ON DUPLICATE KEY UPDATE typical_days=VALUES(typical_days),sample_count=0,"
                    +"baseline_note='管理员手工配置',computed_at=CURDATE()",
                    row.workshopKind(),row.stageCode(),days);
            Long saved=benchmarkId(row.workshopKind(),row.stageCode());
            if(saved!=null)audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_node_benchmarks",
                    saved,"UPDATE",ErpChangeLogWriter.changes("workshopKind",row.workshopKind(),"stageCode",
                            row.stageCode(),"typicalDays",days),"FOLLOW_UP","配置节点参考基准天数",null,null));
            changed++;
        }
        return Map.of("changed",changed);
    }

    private Long benchmarkId(String kind,String stage) {
        return jdbc.query("SELECT id FROM production_tracking_node_benchmarks WHERE workshop_kind=? AND stage_code=?",
                (r,n)->r.getLong(1),kind,stage).stream().findFirst().orElse(null);
    }

    /**
     * 列表默认可见列（全站，仅管理员可改）的可配置列清单。
     * <p>⚠️ 必须与前端 {@code LIST_COLUMN_DEFS} 的 key **同集合同顺序**——前端契约测试
     * （{@code trackingColumnContract.spec.ts}）直接读本常量比对，漂移会当场红。
     * <p>顺序即列表展示顺序，前端只按 key 勾选/隐藏，不另存顺序。
     */
    static final List<String> LIST_COLUMN_KEYS=List.of(
            "salesperson","workshop","sourceStatus","trackingStatus","trackingAction","sourceCreatedAt","applicationDate","scheduleTime","dueDate","requestedLeadDays",
            "expectedFinish","countdown","completion","deliveryResult","stages","timeSignals","salesTrace","assignee");

    /** 表里没有该列的配置时回落到什么：＝改造前前端写死的那 6 个隐藏列（保证升级后界面不变）。 */
    static final Set<String> LIST_COLUMN_FALLBACK_HIDDEN=Set.of(
            "workshop","trackingStatus","trackingAction","dueDate","salesTrace","assignee");

    /** 保存入参：visible 为 null 视为非法（「不设」在列配置里没有意义）。 */
    public record ColumnDefaultSave(String columnKey,Boolean visible) {}

    /**
     * 默认列配置矩阵：按白名单枚举**全部**列（含从未配置过的），左连接配置表。
     * <p>反例：直接 {@code SELECT * FROM 配置表} ⇒ 未配置的列一个都不返回，管理员看不见也配不上。
     * <p>不缓存：配置页低频访问，缓存只会让刚保存的值读不回来。
     */
    public Map<String,Object> columnDefaultMatrix() {
        var saved=new LinkedHashMap<String,Boolean>();
        jdbc.query("SELECT column_key,visible FROM production_tracking_column_defaults",r->{
            saved.put(r.getString(1),r.getBoolean(2));
        });
        var columns=new ArrayList<Map<String,Object>>();
        for(String key:LIST_COLUMN_KEYS){
            var row=new LinkedHashMap<String,Object>();
            row.put("columnKey",key);
            row.put("configured",saved.containsKey(key));
            row.put("visible",saved.getOrDefault(key,!LIST_COLUMN_FALLBACK_HIDDEN.contains(key)));
            columns.add(row);
        }
        var out=new LinkedHashMap<String,Object>();
        out.put("columns",columns);
        out.put("fallbackHidden",new ArrayList<>(LIST_COLUMN_FALLBACK_HIDDEN));
        return out;
    }

    /**
     * 保存全站默认列（权限由 Controller 的 manufacturing.listcolumns.manage 把关）。
     * <p>语义＝**全量覆盖**：请求里给的列写显式配置，白名单里没给的列**删掉显式配置、回落代码默认**
     * （所以「全部显示」＝15 行 visible=1，与「未配置」可区分，不会出现「管理员选了全显示、下次打开又变隐藏」）。
     * <p>逐行审计：只记真正发生变化的行，「谁把销售去向从默认隐藏改成显示」必须可追。
     * <p>⚠️ 本配置就是「新员工打开列表看到哪些列」的输入：管理员改完，**没自己勾过的员工当场换一套列**，
     * 自己勾过的员工不受影响（本机勾选优先）。这是设计使然，故必须写审计。
     */
    @Transactional
    public Map<String,Object> saveColumnDefaults(List<ColumnDefaultSave> rows) {
        if(rows==null||rows.isEmpty())throw new IllegalArgumentException("没有要保存的列配置");
        var pending=new LinkedHashMap<String,Boolean>();
        for(var row:rows){
            if(row==null||row.columnKey()==null||row.columnKey().isBlank())
                throw new IllegalArgumentException("列配置缺少列标识");
            if(!LIST_COLUMN_KEYS.contains(row.columnKey()))
                throw new IllegalArgumentException("列标识无效："+row.columnKey());
            if(pending.containsKey(row.columnKey()))
                throw new IllegalArgumentException("列标识重复："+row.columnKey());
            if(row.visible()==null)throw new IllegalArgumentException("列显示状态缺失："+row.columnKey());
            pending.put(row.columnKey(),row.visible());
        }
        var actor=actors.current();
        var current=new LinkedHashMap<String,Boolean>();
        jdbc.query("SELECT column_key,visible FROM production_tracking_column_defaults",r->{
            current.put(r.getString(1),r.getBoolean(2));
        });
        int changed=0;
        for(String key:LIST_COLUMN_KEYS){
            Boolean want=pending.get(key);
            if(want==null){
                if(!current.containsKey(key))continue;
                Long id=columnDefaultId(key);
                if(id==null)continue;
                jdbc.update("DELETE FROM production_tracking_column_defaults WHERE id=?",id);
                audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_column_defaults",id,
                        "DELETE",ErpChangeLogWriter.changes("columnKey",key,"visible",current.get(key)),
                        "FOLLOW_UP","移除列表默认列配置（回落代码默认）",null,null));
                changed++;
                continue;
            }
            Boolean before=current.get(key);
            if(want.equals(before))continue;
            // ⚠️ 列清单/值清单改动后必数「占位符个数 ↔ 传参个数」：3 个 ? 对应 3 个参数。
            // ⚠️ updated_by 传的是员工 id，不是 ErpActor 本身——传对象 JDBC 不认，运行期才 500。
            jdbc.update("INSERT INTO production_tracking_column_defaults(column_key,visible,updated_by) VALUES(?,?,?) "
                    +"ON DUPLICATE KEY UPDATE visible=VALUES(visible),updated_by=VALUES(updated_by)",
                    key,want?1:0,actor==null?null:actor.employeeId());
            Long id=columnDefaultId(key);
            if(id!=null)audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_column_defaults",id,
                    before==null?"INSERT":"UPDATE",ErpChangeLogWriter.changes("columnKey",key,
                            "visible",want,"before",before),"FOLLOW_UP","配置列表默认显示列",null,null));
            changed++;
        }
        return Map.of("changed",changed);
    }

    private Long columnDefaultId(String key) {
        return jdbc.query("SELECT id FROM production_tracking_column_defaults WHERE column_key=?",
                (r,n)->r.getLong(1),key).stream().findFirst().orElse(null);
    }

    /** 详情里程碑附加参考完成三字段（不改库，纯派生）。 */
    private void attachNodeReferences(Map<String,Object> source,List<Map<String,Object>> milestones,List<String> codes) {
        boolean sealer=ProductionTrackingPolicy.isSealer((String)source.get("workshopType"));
        var benchmarks=nodeBenchmarks().getOrDefault(sealer?BENCHMARK_SEALER:BENCHMARK_MACHINE,Map.of());
        var applicable=new HashMap<String,Boolean>();var statuses=new HashMap<String,String>();var dates=new HashMap<String,LocalDate>();
        for(var item:milestones){
            String code=String.valueOf(item.get("code"));
            applicable.put(code,Boolean.TRUE.equals(item.get("applicable")));
            statuses.put(code,String.valueOf(item.get("status")));
            if(item.get("completedDate") instanceof LocalDate d)dates.put(code,d);
        }
        LocalDate anchor=source.get("plannedStartDate") instanceof LocalDate d?d
                :source.get("applicationDate") instanceof LocalDate a?a:null;
        var refs=computeNodeReferences(codes,anchor,benchmarks,applicable,statuses,dates,LocalDate.now());
        for(var item:milestones){
            var ref=refs.get(String.valueOf(item.get("code")));
            if(ref==null)continue;
            item.put("typicalDays",ref.typicalDays());
            item.put("referenceDate",ref.referenceDate());
            item.put("referenceOverdueDays",ref.referenceOverdueDays());
        }
    }

    private void attachStageProgress(List<Map<String,Object>> items) {
        if(items.isEmpty())return;
        var ids=items.stream().map(item->((Number)item.get("id")).longValue()).toList();
        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        // completedDate：节点 DONE 时的完成日期。列表圆点不用它，但导出要按节点展开成「各阶段完成日期」列。
        // note：10-15 LQ 要「导出后能分析」——节点备注（跟单员在报进度时手写的说明）必须随工单导出，
        // 否则线下只能看到日期、看不到为什么。列表与导出共用这一次查询。
        var saved=jdbc.query("SELECT production_order_id,stage_code,stage_status,completed_date,note FROM production_tracking_milestones "
                +"WHERE production_order_id IN ("+marks+")",(r,n)->row(r),ids.toArray());
        var byOrder=new HashMap<Long,Map<String,Map<String,Object>>>();
        for(var milestone:saved){
            byOrder.computeIfAbsent(((Number)milestone.get("productionOrderId")).longValue(),id->new HashMap<>())
                    .put(String.valueOf(milestone.get("stageCode")),milestone);
        }
        for(var item:items){
            String workshop=(String)item.get("workshopType");
            boolean sealer=ProductionTrackingPolicy.isSealer(workshop);
            var milestones=byOrder.getOrDefault(((Number)item.get("id")).longValue(),Map.of());
            var stages=new ArrayList<Map<String,Object>>();
            for(String code:ProductionTrackingPolicy.stageCodes(workshop)){
                var stage=new LinkedHashMap<String,Object>();
                var milestone=milestones.get(code);
                boolean optional=ProductionTrackingPolicy.isOptionalStage(code);
                stage.put("code",code);
                stage.put("label",ProductionTrackingPolicy.stageLabel(code,sealer));
                stage.put("status",milestone!=null?milestone.get("stageStatus"):"PENDING");
                stage.put("completedDate",milestone!=null?milestone.get("completedDate"):null);
                stage.put("note",milestone!=null?milestone.get("note"):null);
                // 按需工序：没有里程碑行＝本单不走这道工序，前端不该把它显示成「未完成」。
                stage.put("optional",optional);
                stage.put("applicable",!optional||milestone!=null||ProductionTrackingPolicy.configuredOptionalStage(code,
                        (String)item.get("sourceOptionalConfiguration")));
                stages.add(stage);
            }
            item.put("stageProgress",stages);
        }
    }

    Map<String,Object> order(long id) {
        var params=new ArrayList<Object>();var where=visible(params);where.append(" AND p.id=?");params.add(id);
        return jdbc.query("SELECT p.id,p.work_order_no order_no,p.external_source,p.external_id,p.status source_status,"
                +"p.product_name product_name_snapshot,"
                +"p.production_requirement source_order_remark,p.optional_configuration source_optional_configuration,"
                +"CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) specification_snapshot,"
                +"p.mold_layout mold_layout,"
                +"p.required_quantity planned_quantity,'台' unit,p.production_line workshop_type,"
                +"p.customer_name customer_name_snapshot,p.salesperson_name salesperson_name_snapshot,"
                +"p.required_completion_date due_date,p.required_completion_date order_required_date,"
                +"p.expected_completion_date expected_finish_date,"
                +"p.application_date application_date,p.source_created_at source_created_at,"
                +"p.actual_completion_date actual_finish_date,p.schedule_time schedule_time,"
                +"p.warehousing_completed,p.production_completed,"
                +"CASE WHEN p.actual_completion_date IS NOT NULL AND "+DEADLINE+" IS NOT NULL "
                +"THEN DATEDIFF(p.actual_completion_date,"+DEADLINE+") END finish_delay_days,"
                +"p.completed_quantity,"
                +"t.planned_start_date,t.forecast_finish_date,t.risk_category,t.delay_reason,t.next_followup_date,"
                +"t.stopped_at,t.stopped_reason,t.stopped_by_name,"
                +"t.delivery_result,t.shipment_status,t.remark,"
                +"assigned.id assignee_employee_id,"+ASSIGNEE_NAME+" assignee_name,t.last_followup_at,COALESCE(t.version,0) version,"
                +TRACKING_STATUS_CASE+" tracking_status,"+REMINDER_CASE+" reminder,"
                +TRACKING_ACTION+" tracking_action,"
                +INBOUND_COLUMNS+","+DELIVERY_COLUMNS+TRACKING_FROM+INBOUND_JOIN+where,
                (r,n)->row(r),params.toArray()).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("生产任务不存在或无权查看"));
    }

    /** 先校验当前工单的跟单可见范围，再按同一工单编号列出关联的氚云采购入库单。 */
    public String visibleOrderNo(long id) { return (String)order(id).get("orderNo"); }

    /**
     * 导回（导出件改完再导回）用：任务号 → **可见**工单 id。
     *
     * <p>判据复用 {@link #visible(List)} —— 与列表/详情/导出完全同一套（含「九月起自动可见 +
     * 已跟单/进度表覆盖的保留可见」与按车间默认跟单员的数据范围），不另写一份可见性。
     * 于是跟单员导回自己的表格时，只会写到自己负责车间的任务，其余行在预检里显示「不在可见范围」。
     */
    Map<String,Long> visibleOrderIdsByNos(List<String> orderNos) {
        var result=new HashMap<String,Long>();
        if(orderNos==null||orderNos.isEmpty())return result;
        var unique=orderNos.stream().filter(no->no!=null&&!no.isBlank()).distinct().toList();
        int batch=500; // IN 占位符分批，避免上万行导出件拼出超长 SQL
        for(int from=0;from<unique.size();from+=batch){
            var slice=unique.subList(from,Math.min(unique.size(),from+batch));
            var params=new ArrayList<Object>();
            var where=visible(params);
            where.append(" AND p.work_order_no IN (").append(String.join(",",java.util.Collections.nCopies(slice.size(),"?"))).append(")");
            params.addAll(slice);
            // ⚠️ lambda 必须是「块体且无 return」：写成表达式或被解析成 ResultSetExtractor
            // （整个 ResultSet 只回调一次且不会自动 next()）⇒ 运行期 "Illegal operation on empty result set"。
            jdbc.query("SELECT p.work_order_no no,p.id id"+TRACKING_FROM+where,
                    r->{result.put(r.getString("no"),r.getLong("id"));},params.toArray());
        }
        return result;
    }

    public List<Map<String,Object>> purchaseReceipts(long id) {
        var source=order(id);
        return jdbc.query("SELECT DISTINCT r.id,r.source_no,r.purchase_date,r.warehouse_name "
                +"FROM purchase_receipt_items i JOIN purchase_receipts r ON r.id=i.purchase_receipt_id "
                +"WHERE i.production_order_no=? AND r.external_source='H3YUN' "
                +"ORDER BY r.purchase_date,r.id",
                (r,n)->row(r),source.get("orderNo"));
    }

    public Map<String,Object> detail(long id) {
        var result=new LinkedHashMap<String,Object>();
        var source=order(id);result.put("order",source);
        var codes=stageCodes((String)source.get("workshopType"));
        var saved=jdbc.query("SELECT stage_code,stage_status,completed_date,note FROM production_tracking_milestones WHERE production_order_id=?",
                (r,n)->row(r),id);
        var milestones=new ArrayList<Map<String,Object>>();
        boolean sealer=ProductionTrackingPolicy.isSealer((String)source.get("workshopType"));
        for(String code:codes){
            var item=new LinkedHashMap<String,Object>();
            boolean optional=ProductionTrackingPolicy.isOptionalStage(code);
            Optional<Map<String,Object>> savedRow=saved.stream().filter(s->code.equals(s.get("stageCode"))).findFirst();
            item.put("code",code);item.put("label",ProductionTrackingPolicy.stageLabel(code,sealer));
            item.put("optional",optional);
            // 按需工序没有里程碑行 ⇒ 本单不适用；有行则按行上的状态走。
            item.put("applicable",!optional||savedRow.isPresent()||ProductionTrackingPolicy.configuredOptionalStage(code,
                    (String)source.get("sourceOptionalConfiguration")));
            item.put("status","PENDING");item.put("completedDate",null);item.put("note",null);
            savedRow.ifPresent(s->{
                item.put("status",s.get("stageStatus"));item.put("completedDate",s.get("completedDate"));item.put("note",s.get("note"));
            });
            milestones.add(item);
        }
        attachNodeReferences(source,milestones,codes);
        result.put("milestones",milestones);
        result.put("stageTotal",stageTotal((String)source.get("workshopType"),milestones));
        result.put("operations",jdbc.query("SELECT c.id,c.sequence_no,c.component_name operation_name_snapshot,"
                +"CASE WHEN c.completed=1 OR c.completed_at IS NOT NULL "
                +"OR (COALESCE(c.required_quantity,0)>0 AND COALESCE(c.completed_quantity,0)>=c.required_quantity) "
                +"THEN 'COMPLETED' ELSE 'PENDING' END status,"
                +"c.completed_quantity reported_quantity,c.required_quantity,c.completed_at "
                +"FROM production_order_components c WHERE c.production_order_id=? ORDER BY c.sequence_no,c.id",
                (r,n)->row(r),id));
        result.put("entries",jdbc.query("SELECT id,version,content,before_json,after_json,created_by_name,created_at "
                +"FROM production_tracking_entries WHERE production_order_id=? ORDER BY id DESC",
                (r,n)->row(r),id));
        var timeReviews=jdbc.query("SELECT id,production_order_id,kind,status,reason,contact_name,agreed_date,"
                +"next_followup_date,source_due_date,source_application_date,source_created_at,source_expected_date,source_schedule_time,"
                +"source_report_date,source_inbound_date,created_by_name,created_at "
                +"FROM production_tracking_time_reviews WHERE production_order_id=? ORDER BY id DESC",
                (r,n)->row(r),id);
        result.put("timeReviewHistory",timeReviews);
        var currentTimeReviews=jdbc.query("SELECT id,production_order_id,kind,status,reason,contact_name,agreed_date,"
                +"next_followup_date,source_due_date,source_application_date,source_created_at,source_expected_date,source_schedule_time,"
                +"source_report_date,source_inbound_date,created_by_name,created_at "
                +"FROM production_tracking_time_reviews WHERE id IN "
                +"(SELECT MAX(id) FROM production_tracking_time_reviews WHERE production_order_id=? GROUP BY kind) "
                +"ORDER BY id DESC",(r,n)->row(r),id);
        source.put("timeReviews",currentTimeReviews);
        result.put("timeReviews",currentTimeReviews);
        return result;
    }

    private void attachTimeReviews(List<Map<String,Object>> items) {
        if(items.isEmpty())return;
        var ids=items.stream().map(item->item.get("id")).toList();
        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        var notes=jdbc.query("SELECT n.production_order_id,n.kind,n.status,n.reason,n.agreed_date,n.next_followup_date,"
                +"n.source_due_date,n.source_application_date,n.source_created_at,n.source_expected_date,n.source_schedule_time,n.source_report_date,n.source_inbound_date "
                +"FROM production_tracking_time_reviews n JOIN "
                +"(SELECT production_order_id,kind,MAX(id) id FROM production_tracking_time_reviews "
                +"WHERE production_order_id IN ("+marks+") GROUP BY production_order_id,kind) last ON last.id=n.id",
                (r,n)->row(r),ids.toArray());
        for(var item:items)item.put("timeReviews",notes.stream()
                .filter(note->Objects.equals(note.get("productionOrderId"),item.get("id"))).toList());
    }

    public long addTimeReview(long id,TimeReviewRequest request,String key) {
        var source=order(id);
        if(source.get("stoppedAt")!=null)throw new IllegalArgumentException("该任务已停止跟单，请先恢复跟单");
        if(request==null||request.kind()==null
                ||!Set.of("SOURCE_DATE","ACCEPTANCE","COMMITMENT","REPORTING","WAREHOUSING","OVERDUE").contains(request.kind()))
            throw new IllegalArgumentException("时间核实类型无效");
        boolean commitment="COMMITMENT".equals(request.kind());
        if(request.status()==null
                ||!(commitment?Set.of("ON_TIME","NEGOTIATED"):Set.of("OPEN","RESOLVED")).contains(request.status()))
            throw new IllegalArgumentException("时间核实结果无效");
        String reason=clean(request.reason(),500,"核实说明");
        if(reason.isBlank())throw new IllegalArgumentException("请填写核实原因或处理结果");
        String contact=clean(request.contactName(),100,"核实对象");
        LocalDate agreed=request.agreedDate();
        if(commitment){
            if("ON_TIME".equals(request.status())){
                agreed=(LocalDate)source.get("dueDate");
                if(agreed==null)throw new IllegalArgumentException("业务员要求日期为空，无法确认按期完成");
            } else if(agreed==null)throw new IllegalArgumentException("协商交期不能为空");
        } else if(agreed!=null)throw new IllegalArgumentException("此核实类型不能填写协商交期");
        var actor=actors.current();
        LocalDate savedAgreed=agreed;
        // OVERDUE 的快照语义与其他类型不同：source_expected_date 存「交付基准」、source_inbound_date 存「实际交付日」，
        // 这样基准或交付日任一变化都会让上次核实失效、重新进入待核实（与 TIME_OVERDUE 的比对方一致）。
        boolean overdue="OVERDUE".equals(request.kind());
        return Long.parseLong(idem.execute(actor,key,"production.tracking.time-review",
                Map.of("id",id,"request",request),()->{
            jdbc.update("INSERT INTO production_tracking_time_reviews(production_order_id,kind,status,reason,"
                    +"contact_name,agreed_date,next_followup_date,source_due_date,source_application_date,source_created_at,source_expected_date,"
                    +"source_schedule_time,source_report_date,source_inbound_date,created_by_employee_id,created_by_name) "
                    +"VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,request.kind(),request.status(),reason,contact.isBlank()?null:contact,
                    savedAgreed,request.nextFollowupDate(),source.get("dueDate"),source.get("applicationDate"),source.get("sourceCreatedAt"),
                    overdue?source.get("deliveryBaseline"):source.get("expectedFinishDate"),
                    source.get("scheduleTime"),source.get("actualFinishDate"),
                    overdue?source.get("deliveredDate"):source.get("inboundDate"),
                    actor.employeeId(),actor.name());
            long noteId=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_time_reviews",noteId,
                    "INSERT",ErpChangeLogWriter.changes("orderId",id,"kind",request.kind(),"status",request.status()),
                    "FOLLOW_UP",reason,key,null));
            return String.valueOf(noteId);
        }));
    }

    /** 本地处置：只停止/恢复跟单，不修改氚云任务单；每次变更写入同一条跟单历史时间线。 */
    public long changeDisposition(long id,DispositionRequest request,String key) {
        var current=order(id); // 即使幂等重放，也先按当前用户校验可见范围。
        if(request==null||request.version()<0)throw new IllegalArgumentException("缺少跟单版本");
        String reason=clean(request.reason(),500,request.stopped()?"停止原因":"恢复原因");
        if(reason.isBlank())throw new IllegalArgumentException(request.stopped()?"请填写不再生产的原因":"请填写恢复跟单的原因");
        if(request.stopped() && ("completed".equals(current.get("trackingStatus"))
                || current.get("deliveredDate")!=null))
            throw new IllegalArgumentException("已有完整节点或入库记录，不能标记为不再生产；请先核实实际情况");
        var actor=actors.current();
        return Long.parseLong(idem.execute(actor,key,"production.tracking.disposition",
                Map.of("id",id,"request",request),()->{
            jdbc.update("INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin) VALUES(?,'MANUAL')",id);
            jdbc.update("INSERT INTO production_tracking_state(production_order_id) VALUES(?) "
                    +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",id);
            var before=jdbc.queryForMap("SELECT * FROM production_tracking_state WHERE production_order_id=? FOR UPDATE",id);
            long version=((Number)before.get("version")).longValue();
            if(version!=request.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"该任务已由其他人更新，请刷新后重试");
            boolean alreadyStopped=before.get("stopped_at")!=null;
            if(alreadyStopped==request.stopped())throw new IllegalArgumentException(alreadyStopped?"该任务已停止跟单":"该任务未停止跟单");
            if(request.stopped())jdbc.update("UPDATE production_tracking_state SET stopped_at=NOW(),stopped_reason=?,"
                    +"stopped_by_name=?,version=?,last_followup_at=NOW() WHERE production_order_id=?",
                    reason,actor.name(),version+1,id);
            else jdbc.update("UPDATE production_tracking_state SET stopped_at=NULL,stopped_reason=NULL,"
                    +"stopped_by_name=NULL,version=?,last_followup_at=NOW() WHERE production_order_id=?",version+1,id);
            var after=jdbc.queryForMap("SELECT * FROM production_tracking_state WHERE production_order_id=?",id);
            var milestones=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                    +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",id);
            String content=(request.stopped()?"停止生产跟单：":"恢复生产跟单：")+reason;
            jdbc.update("INSERT INTO production_tracking_entries(production_order_id,version,content,before_json,after_json,"
                    +"created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,?,?)",id,version+1,content,
                    write(Map.of("state",before,"milestones",milestones)),
                    write(Map.of("state",after,"milestones",milestones)),actor.employeeId(),actor.name());
            long entryId=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_entries",entryId,"INSERT",
                    ErpChangeLogWriter.changes("orderId",id,"version",version+1,"stopped",request.stopped()),
                    "FOLLOW_UP",content,key,null));
            return String.valueOf(version+1);
        }));
    }

    public long add(long id,EntryRequest request,String key) {
        var current=order(id); // Authorization also applies to idempotent replays.
        if(current.get("stoppedAt")!=null)throw new IllegalArgumentException("该任务已停止跟单，请先恢复跟单");
        if(request==null||request.version()<0)throw new IllegalArgumentException("缺少跟单版本");
        var s=request.state();
        String provided=clean(request.content(),1000,"跟进内容");
        boolean hasMilestones=request.milestones()!=null&&!request.milestones().isEmpty();
        if(s==null&&provided.isEmpty()&&!hasMilestones)throw new IllegalArgumentException("没有需要保存的变更");
        String reason=s==null?null:clean(s.delayReason(),300,"延期原因");
        String deliveryResult=s==null?null:clean(s.deliveryResult(),100,"交付结果");
        String shipmentStatus=s==null?null:clean(s.shipmentStatus(),100,"发货状态");
        String remark=s==null?null:clean(s.remark(),1000,"备注");
        if(s!=null&&s.riskCategory()!=null&&!RISKS.contains(s.riskCategory()))
            throw new IllegalArgumentException("风险分类无效");
        var allowedStages=stageCodes((String)current.get("workshopType"));
        var milestones=request.milestones()==null?List.<Milestone>of():request.milestones();
        if(milestones.size()>allowedStages.size())throw new IllegalArgumentException("跟单节点数量无效");
        var seen=new java.util.HashSet<String>();
        for(var milestone:milestones){
            if(milestone==null||!allowedStages.contains(milestone.code())||!seen.add(milestone.code())
                    ||!STAGE_STATUSES.contains(milestone.status()))throw new IllegalArgumentException("跟单节点无效");
            if("DONE".equals(milestone.status()) && milestone.completedDate()==null)
                throw new IllegalArgumentException(ProductionTrackingPolicy.stageLabel(milestone.code(),
                        ProductionTrackingPolicy.isSealer((String)current.get("workshopType")))+"已完成时请填写完成日期");
            clean(milestone.note(),500,"节点备注");
        }
        validateNodeReasons(current,milestones,allowedStages);
        if(s!=null&&s.forecastFinishDate()!=null
                &&Objects.equals(s.forecastFinishDate(),current.get("expectedFinishDate")!=null
                        ?current.get("expectedFinishDate"):current.get("dueDate")))
            throw new IllegalArgumentException("延期至与车间预计完成相同，无需登记；请清空延期至");
        var assignee=defaultAssignee(id).orElse(null);
        var actor=actors.current();
        return Long.parseLong(idem.execute(actor,key,"production.tracking.add",Map.of("id",id,"request",request),()->{
            jdbc.update("INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin) VALUES(?,'MANUAL')",id);
            jdbc.update("INSERT INTO production_tracking_state(production_order_id) VALUES(?) "
                    +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",id);
            var before=jdbc.queryForMap("SELECT * FROM production_tracking_state WHERE production_order_id=? FOR UPDATE",id);
            if(before.get("stopped_at")!=null)throw new IllegalArgumentException("该任务已停止跟单，请先恢复跟单");
            var beforeMilestones=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                    +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",id);
            long version=((Number)before.get("version")).longValue();
            if(version!=request.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"该任务已由其他人更新，请刷新后重试");
            if(s!=null){
                // 延期至日期允许早于、等于或晚于跟单截止日；其余状态字段用 COALESCE 保护。
                jdbc.update("UPDATE production_tracking_state SET planned_start_date=COALESCE(?,planned_start_date),"
                        +"forecast_finish_date=?,risk_category=COALESCE(?,risk_category),"
                        +"delay_reason=COALESCE(?,delay_reason),next_followup_date=COALESCE(?,next_followup_date),"
                        +"assignee_employee_id=COALESCE(?,assignee_employee_id),"
                        +"assignee_name=COALESCE(?,assignee_name),"
                        +"delivery_result=COALESCE(?,delivery_result),shipment_status=COALESCE(?,shipment_status),"
                        +"remark=COALESCE(?,remark),version=?,last_followup_at=NOW() "
                        +"WHERE production_order_id=?",
                        s.plannedStartDate(),s.forecastFinishDate(),s.riskCategory(),reason,s.nextFollowupDate(),
                        assignee==null?null:assignee.get("id"),assignee==null?null:assignee.get("name"),
                        deliveryResult,shipmentStatus,remark,version+1,id);
            }else{
                // 只推进节点的跟进不携带状态字段，不能把已有状态清空。
                jdbc.update("UPDATE production_tracking_state SET version=?,last_followup_at=NOW() "
                        +"WHERE production_order_id=?",version+1,id);
            }
            for(var milestone:milestones){
                jdbc.update("INSERT INTO production_tracking_milestones(production_order_id,stage_code,stage_status,completed_date,note) "
                                +"VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE stage_status=VALUES(stage_status),"
                                +"completed_date=VALUES(completed_date),note=VALUES(note)",
                        id,milestone.code(),milestone.status(),milestone.completedDate(),clean(milestone.note(),500,"节点备注"));
            }
            var after=jdbc.queryForMap("SELECT * FROM production_tracking_state WHERE production_order_id=?",id);
            var milestoneSnapshot=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                    +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",id);
            // 跟进内容不再要求手填：按节点或延期至日期的变更自动生成。
            String entryContent=provided;
            if(entryContent.isEmpty()){
                entryContent=describeStageChanges(
                        ProductionTrackingPolicy.isSealer((String)current.get("workshopType")),
                        request.milestones()==null?List.<Milestone>of():request.milestones(),beforeMilestones);
                if(entryContent.isEmpty())entryContent=describeStateChange(before,s);
            }
            if(entryContent.isEmpty())throw new IllegalArgumentException("没有需要保存的变更");
            jdbc.update("INSERT INTO production_tracking_entries(production_order_id,version,content,before_json,after_json,"
                    +"created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,?,?)",id,version+1,entryContent,
                    write(Map.of("state",before,"milestones",beforeMilestones)),write(Map.of("state",after,"milestones",milestoneSnapshot)),
                    actor.employeeId(),actor.name());
            long entryId=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_entries",entryId,"INSERT",
                    ErpChangeLogWriter.changes("orderId",id,"version",version+1,"content",entryContent),
                    "FOLLOW_UP",entryContent,key,null));
            return String.valueOf(version+1);
        }));
    }

    /**
     * 超参考完成日期仍未完成的节点必须写节点备注说明原因（负责人 2026-10-10 定）。
     * 校验范围＝本次请求提交的节点（前端每次都提交全部可编辑节点）；链式参考值按「库内已存 + 本次请求」合并后的状态推算，
     * 与详情展示口径一致。无基准（机器车间暂未播种）或无锚点（无计划开工且无申请日期）时不校验。
     */
    private void validateNodeReasons(Map<String,Object> order,List<Milestone> milestones,List<String> allowedStages) {
        if(milestones.isEmpty())return;
        boolean sealer=ProductionTrackingPolicy.isSealer((String)order.get("workshopType"));
        var benchmarks=nodeBenchmarks().getOrDefault(sealer?BENCHMARK_SEALER:BENCHMARK_MACHINE,Map.of());
        if(benchmarks.isEmpty())return;
        var applicable=new HashMap<String,Boolean>();var statuses=new HashMap<String,String>();var dates=new HashMap<String,LocalDate>();
        jdbc.query("SELECT stage_code,stage_status,completed_date FROM production_tracking_milestones WHERE production_order_id=?",
                r->{
                    String code=r.getString(1);
                    applicable.put(code,true);
                    statuses.put(code,r.getString(2));
                    java.sql.Date d=r.getDate(3);
                    if(d!=null)dates.put(code,d.toLocalDate());
                },((Number)order.get("id")).longValue());
        for(String code:allowedStages)if(ProductionTrackingPolicy.configuredOptionalStage(code,
                (String)order.get("sourceOptionalConfiguration")))applicable.put(code,true);
        var requested=new java.util.HashSet<String>();
        for(var m:milestones){
            requested.add(m.code());
            statuses.put(m.code(),m.status());
            dates.put(m.code(),m.completedDate());
        }
        LocalDate anchor=order.get("plannedStartDate") instanceof LocalDate d?d
                :order.get("applicationDate") instanceof LocalDate a?a:null;
        var refs=computeNodeReferences(allowedStages,anchor,benchmarks,applicable,statuses,dates,LocalDate.now());
        for(var m:milestones){
            var ref=refs.get(m.code());
            if(ref==null||ref.referenceOverdueDays()==null)continue;
            if(m.note()!=null&&!m.note().isBlank())continue;
            throw new IllegalArgumentException("节点「"+ProductionTrackingPolicy.stageLabel(m.code(),sealer)
                    +"」已超参考完成日期 "+ref.referenceOverdueDays()+" 天仍未完成，请在节点备注中说明原因后再保存");
        }
    }

    /** 跟进只改节点时自动生成跟进内容；状态、完成日期或备注发生变化的节点才会出现在描述里。 */
    static String describeStageChanges(boolean sealer,List<Milestone> milestones,List<Map<String,Object>> before) {        var prev=new HashMap<String,Map<String,Object>>();
        for(var row:before)prev.put(String.valueOf(row.get("stage_code")),row);
        var changed=new ArrayList<String>();
        for(var m:milestones){
            var old=prev.get(m.code());
            String oldStatus=old==null?null:str(old.get("stage_status"));
            String oldDate=old==null||old.get("completed_date")==null?null:str(old.get("completed_date"));
            String newDate=m.completedDate()==null?null:m.completedDate().toString();
            String oldNote=old==null||old.get("note")==null?null:str(old.get("note"));
            String newNote=m.note()==null?null:m.note().trim();
            String label=ProductionTrackingPolicy.stageLabel(m.code(),sealer);
            if(!Objects.equals(oldStatus,m.status())||!Objects.equals(oldDate,newDate)) {
                String text=switch(m.status()){
                    case "DONE"->"已完成"+(newDate==null?"":" "+newDate);
                    case "IN_PROGRESS"->"进行中";
                    default->"未开始";
                };
                changed.add(label+"→"+text);
            } else if(!Objects.equals(oldNote,newNote)) {
                changed.add(label+"（备注更新）");
            }
        }
        return changed.isEmpty()?"":"更新节点："+String.join("；",changed);
    }
    private static String str(Object value){return value==null?null:String.valueOf(value);}

    /** 跟进只改了延期至日期或延期原因时的自动描述。 */
    static String describeStateChange(Map<String,Object> before,State s) {
        if(before==null||s==null)return "";
        var parts=new ArrayList<String>();
        String oldForecast=before.get("forecast_finish_date")==null?null:str(before.get("forecast_finish_date"));
        String newForecast=s.forecastFinishDate()==null?null:s.forecastFinishDate().toString();
        if(!Objects.equals(oldForecast,newForecast)){
            if(newForecast==null)parts.add("延期至→清空");
            else parts.add("延期至→"+newForecast);
        }
        String oldReason=before.get("delay_reason")==null?null:str(before.get("delay_reason"));
        String newReason=s.delayReason()==null?"":s.delayReason().trim();
        // delay_reason 走 COALESCE 更新：可覆盖、不可清空，因此只在填写了新原因时描述差异。
        if(!newReason.isEmpty()&&!Objects.equals(oldReason,newReason))
            parts.add("延期原因→"+newReason);
        return parts.isEmpty()?"":"更新跟进信息："+String.join("；",parts);
    }

    private String write(Object data){try{return json.writeValueAsString(data);}catch(Exception e){throw new IllegalStateException("跟单历史序列化失败",e);}}
    private static List<String> stageCodes(String workshop){return ProductionTrackingPolicy.stageCodes(workshop);}
    private static String clean(String value,int max,String label){String s=value==null?"":value.trim();if(s.length()>max)throw new IllegalArgumentException(label+"最多"+max+"字");return s;}
    private static Map<String,Object> row(ResultSet r)throws SQLException {
        var out=new LinkedHashMap<String,Object>();var md=r.getMetaData();
        for(int i=1;i<=md.getColumnCount();i++){
            StringBuilder key=new StringBuilder();boolean up=false;
            for(char c:md.getColumnLabel(i).toLowerCase().toCharArray()) {if(c=='_')up=true;else{key.append(up?Character.toUpperCase(c):c);up=false;}}
            Object value=r.getObject(i);
            if(value instanceof java.sql.Date d)value=d.toLocalDate();
            if(value instanceof java.sql.Timestamp t)value=t.toLocalDateTime();
            out.put(key.toString(),value);
        }return out;
    }
}
