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
    /**
     * 只读并发执行器：给「同一次请求里几条互不依赖的查询」并行发出用（见 {@link #filterOptions()}）。
     * 用虚拟线程——这些任务几乎全程阻塞在 JDBC 上，虚拟线程在等待时让出平台线程，
     * 不会因并发数上升而吃光 Tomcat 的线程；JDBC 阻塞在虚拟线程上由 JDK21 的 pinning 处理，
     * 只持有极短时间，实测无影响。⚠️ 任务里**不得**依赖 ThreadLocal 形式的请求上下文
     * （身份已在调用线程解析成参数传进去），也别在任务里开事务。
     */
    private static final java.util.concurrent.ExecutorService READ_POOL =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
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
     * 「模具生产」跟进筛选的三档（顺序＝下拉顺序；10-21 跟单员需求）。
     *
     * <p>跟单员要回答的问题只有一个：<b>模具到底做完没有</b>。所以判据刻意<b>不看模具之前</b>
     * （画图/备料/钣金跟进与否都不影响），只看两件事：
     * <ol>
     *   <li><b>模具之后有没有动</b>（{@link #MOLD_LATER_TRACKED}）——后面任一节点已跟进，说明这张单
     *       已经越过模具这一关，三档都不筛出；</li>
     *   <li>模具节点自己的状态，分出未跟进 / 进行中 / 已完成三档（{@link #MOLD_NODE_STATE}）。</li>
     * </ol>
     * 「进行中」与「已完成」必须分开：混成一桶时，「已开工」和「已完工」在清单里长得一样，
     * 恰好把这个功能要回答的问题重新变得看不出来。
     *
     * <p>⚠️ 「未跟进」包含<b>完全没有该节点记录</b>的单（新单尚未开始跟单）——跟单员要能一并看到并催。
     */
    static final List<String> MOLD_STAGE_CODES=List.of("MOLD_NONE","MOLD_WORKING","MOLD_DONE");
    /** 模具节点三态：优先认已完成，其次进行中，其余（含无记录）都算未跟进。 */
    static final String MOLD_NODE_STATE="CASE WHEN COALESCE(ms.mold_done_count,0)>0 THEN 'MOLD_DONE' "
            +"WHEN COALESCE(ms.mold_working_count,0)>0 THEN 'MOLD_WORKING' ELSE 'MOLD_NONE' END";
    /** 模具之后是否已有节点跟进（按车间取不同的后置节点集合）；为真即「已越过模具」，不再筛出。 */
    static final String MOLD_LATER_TRACKED="(CASE WHEN p.production_line LIKE '%封口%' "
            +"THEN COALESCE(ms.later_sealer_count,0) ELSE COALESCE(ms.later_machine_count,0) END)>0";
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
            // 「模具生产」跟进三态与「模具之后是否已推进」——见 {@link #MOLD_NODE_STATE}。
            // 同为同一 GROUP BY 上的聚合，不改变行使数，故统计卡/筛选项口径不受影响。
            // ⚠️ 三态必须用条件求和判优先级，不能用 MAX(stage_status)：字典序是 DONE<IN_PROGRESS<PENDING，
            // MAX 会把「待开始」取成最大，正好把已完成判成未开工。
            +"SUM(stage_code='MOLD' AND stage_status='DONE') mold_done_count,"
            +"SUM(stage_code='MOLD' AND stage_status='IN_PROGRESS') mold_working_count,"
            // 模具之后的节点：封口机是氧化/组装/入库/木箱，机器是组装/调试/入库/木箱（钣金在模具之前）。
            // 只要其中任一节点 status<>'PENDING'，就说明这张单已经越过模具这一关。
            +"SUM(stage_code IN ('OXIDATION','ASSEMBLY','WAREHOUSE','WOODEN_CRATE') AND stage_status<>'PENDING') later_sealer_count,"
            +"SUM(stage_code IN ('ASSEMBLY','TESTING','WAREHOUSE','WOODEN_CRATE') AND stage_status<>'PENDING') later_machine_count,"
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
     * 入库进度对照日期优先采用跟单员登记的「入库」节点日期；尚未登记时采用采购首次入库日期。
     * 两个来源都没有时为 NULL。采购首次入库可能只有部分数量，不代表整单完工；
     * 生产交期分析另用按计划数量核实后的整单完工日，不复用此表达式。
     *
     * <p>⚠️ 依赖 {@link #INBOUND_JOIN} 的 {@code ib} 别名与 {@link #TRACKING_FROM} 的 {@code ms} 别名，
     * 只能用在同时挂了这两者的 SELECT 上（列表 / 详情）。末尾不带逗号。
     */
    /** 无车间独立预计日期时，采用有效的业务员要求日；填错的早于申请/创建日期不作为基准。 */
    static final String VALID_REQUIRED_DATE="CASE WHEN (p.application_date IS NULL "
            +"OR p.required_completion_date>=p.application_date) AND (p.source_created_at IS NULL "
            +"OR p.required_completion_date>=DATE(p.source_created_at)) THEN p.required_completion_date END";
    /**
     * 「已交付」的唯一判据 —— 全站（列表列 / 生产分析 / 周复盘 / 工作台默认视图）都只用这一处。
     *
     * <p><b>口径（LQ 2026-10-21 确认）：以入库为准。</b>
     * ① 首选跟单员在跟单里填写的入库里程碑（{@code WAREHOUSE} 节点的完成日期，ms.warehouse_in_date）——
     *    这是人确认过的「货真的进了库」，最可信；
     * ② 跟单员没登记入库，则回退采购入库（{@code ib.first_in}，即该工单号最早的采购入库日期）；
     * ③ 两者都没有 ＝ 没入库 ＝ 未交付。
     *
     * <p>⚠️ <b>不要换成 {@code p.actual_completion_date}</b>（报工完成日）。真库里九月起 569 单中
     * 报工 507 单、入库里程碑 472 单、采购入库 438 单，三者并不重合；其中 25 单「已报工但还没入库」——
     * 按报工算已交付的话，这 25 单会从工作台消失，而列表的「生产交付结果」列、生产分析、周复盘
     * 仍按入库判它们是「未交付 / 逾期」，同一个单在两个页面会给出相反的结论。
     * 报工 ≠ 入库：车间报完工，货可能还在产线上等入库。
     *
     * <p>依赖 {@code TRACKING_FROM} 的 {@code ms} 与 {@link #INBOUND_JOIN} 的 {@code ib}，
     * 调用点必须两者都挂（{@code filter()} 用 needsInbound 兜住）。
     */
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
     * 生产交付结果（10-19 LQ 定名：统一叫「交付」，不叫「入库记录交期」）：以车间给出的预计完成日期
     * （跟单员确认后，见 {@link #DELIVERY_BASELINE}）为基准，判「未交付 / 按期交付 / 逾期交付 X 天」；不代表整单生产完工。
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
            +"WHEN t.closed_at IS NOT NULL THEN NULL "
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
    /**
     * 跟单范围＝**九月起**（2026-09-01，见 {@link ProductionTrackingPolicy#SCOPE_START_DATE}）。
     *
     * <p><b>2026-10-21 修正：{@code OR} 改 {@code AND}。</b>原先写「申请日期<b>或</b>提交时间任一落在九月后」，
     * 于是「七、八月提交、但申请日期被填成了九月」的历史单会从侧门回到工作台——这类单在真库有几十条，
     * 表现为「突然冒出一张我根本没跟过的旧单」。跟单起点是一个**时间点**：任务单提交时间早于九月，
     * 就不在跟单范围内，不管它的申请日期被人填成了什么。
     *
     * <p>例外只有两条，都写在 {@link #visible} 里：已被进度表覆盖、或已有跟单记录的历史单——
     * 那是「九月前就真的在跟」的那批，不能因为起点调整而让它的跟单记录失联。
     */
    static final String SCOPE_DATE="(p.application_date>='"+ProductionTrackingPolicy.SCOPE_START_DATE
            +"' AND p.source_created_at>='"+ProductionTrackingPolicy.SCOPE_START_DATE+"')";
    /** 跟单状态只取本地节点记录，不复用氚云任务单状态：「有节点记录即跟单中」由 NEVER_TRACKED 表达。
     *  闭案优先级最高：跟单员主动确认结束后，无论节点是否齐全一律显示「已完成」，避免为了凑齐节点
     *  而补录虚假生产记录（与「停止」互斥：闭案后状态为 completed，停止后状态为 stopped）。 */
    static final String TRACKING_STATUS_CASE="CASE WHEN t.stopped_at IS NOT NULL THEN 'stopped' "
            +"WHEN t.closed_at IS NOT NULL THEN 'completed' "
            +"WHEN "+NEVER_TRACKED+" THEN 'pending' "
            +"WHEN "+TRACKING_COMPLETE+" THEN 'completed' ELSE 'in_progress' END";
    /** 提醒只表示待处理风险；任务单状态和跟单状态各有独立字段。 */
    static final String REMINDER_CASE="CASE WHEN t.stopped_at IS NOT NULL THEN 'none' "
            +"WHEN t.closed_at IS NOT NULL THEN 'none' "
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
    /** 源日期异常保留历史事实；汇总「待核实问题」只纳入当前源数据尚未核实的任务。 */
    static final String TIME_SOURCE="("+TIME_SOURCE_APPLICATION+" OR "+TIME_SOURCE_REQUIRED+")";
    static final String TIME_SOURCE_PENDING="("+TIME_SOURCE+" AND NOT EXISTS "
            +"(SELECT 1 FROM production_tracking_time_reviews n WHERE n.production_order_id=p.id "
            +"AND n.kind='SOURCE_DATE' AND n.id=(SELECT MAX(n2.id) FROM production_tracking_time_reviews n2 "
            +"WHERE n2.production_order_id=p.id AND n2.kind='SOURCE_DATE') "
            +"AND n.source_application_date<=>p.application_date "
            +"AND n.source_due_date<=>p.required_completion_date "
            +"AND n.source_created_at<=>p.source_created_at))";
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
    static final String TIME_PRODUCTION="("+String.join(" OR ",TIME_ACCEPTANCE,TIME_COMMITMENT,
            TIME_REPORTING,TIME_INBOUND_REPORTING_ATTENTION)+")";
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
    static final String TIME_ATTENTION="("+String.join(" OR ",TIME_SOURCE_PENDING,TIME_PRODUCTION,TIME_OVERDUE,TIME_REVIEW_DUE)+")";

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
            +"t.closed_at,t.closed_by_name,t.closed_note,"
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
    /**
     * 跟单结案确认：与「停止」互斥的两个本地处置。
     * {@code closed=true} ＝跟单员主动确认结束（无论节点是否齐全），写 closed_at/note/by_name；
     * {@code closed=false}＝撤销结案，恢复按节点进度派生状态。
     * 关闭态下 status 强制展示为 completed；停止态下 status 仍为 stopped；二者互斥。
     */
    public record ClosureRequest(long version, boolean closed, String note) {}
    public record TimeReviewRequest(String kind,String status,String reason,String contactName,
                                    LocalDate agreedDate,LocalDate nextFollowupDate) {}

    StringBuilder visible(List<Object> params) {
        // 跟单范围：九月起提交的任务自动进入工作台。
        // 另有三类**例外**必须保留可见，否则九月前就在跟的单会突然从列表里消失、跟单记录随之失联：
        //   ① 手工加入跟单（enrollments）② 已被跟单进度表覆盖（workbook_rows）③ 已经真的产生过跟单事实。
        // ③ 是 2026-10-21 补的：真库有 46 单（7 月 2 / 8 月 44）在九月前就在跟，它们既没走 enroll 入口、
        // 也不一定在进度表里，只因为「提交时间是八月」就会被新口径挡掉——而它们的节点和跟进历史是真实存在的。
        // 判据只认「真的跟过」（里程碑节点 / 跟进条目 / 版本被推进过），**不认空壳 state 行**：
        // 建了状态行但一个节点没填、一条跟进没写，属于「还没开始跟」，不该拿它当保留理由。
        StringBuilder where=new StringBuilder(" WHERE p.external_source='H3YUN' "
                +"AND ("+SCOPE_DATE+" "
                +"OR EXISTS (SELECT 1 FROM production_tracking_enrollments e WHERE e.production_order_id=p.id) "
                +"OR EXISTS (SELECT 1 FROM production_tracking_workbook_rows w WHERE w.production_order_id=p.id) "
                +"OR EXISTS (SELECT 1 FROM production_tracking_milestones m WHERE m.production_order_id=p.id) "
                +"OR EXISTS (SELECT 1 FROM production_tracking_entries en WHERE en.production_order_id=p.id) "
                +"OR EXISTS (SELECT 1 FROM production_tracking_state t2 WHERE t2.production_order_id=p.id AND t2.version>0))");
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
        // 在制按交付进度日期判断；本概览与列表的生产交付结果口径一致，不代表整单完工。
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
            DateRangeFilter expected,boolean urgent,String moldLayout,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,
                EMPTY_DATE_FILTER,page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,
            DateRangeFilter created,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,
                created,"",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,
            DateRangeFilter created,String optionalStage,int page,int size) {
        return list(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,created,
                optionalStage,"",page,size);
    }

    public Map<String,Object> list(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,
            DateRangeFilter created,String optionalStage,String moldStage,int page,int size) {
        if(page<1 || size<1 || size>100)throw new IllegalArgumentException("分页范围无效");
        var f=filter(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,created,
                optionalStage,moldStage);
        // 计数与明细必须挂同一份 JOIN：TIME_OVERDUE 谓词引用交付日（依赖 INBOUND_JOIN 的 ib）。
        // INBOUND_JOIN 是按工单号聚合的 1:1 派生表，挂上不改变行数，COUNT(*) 仍等于工单数。
        // ⚠️ 但「不改变行数」不等于「不花钱」：该派生表要全量聚合 6.5 万行采购明细（实测 +23ms）。
        // 故只在筛选真的引用 ib 时才挂（见 Filtered.needsInbound），默认打开时 COUNT 不挂。
        long total=Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*)"+TRACKING_FROM+(f.needsInbound()?INBOUND_JOIN:"")+f.where(),
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
    /**
     * 列表/导出的筛选取数与附加 JOIN（**唯一来源**）：{@code where} + 参数 + 这条查询需不需要
     * 挂 {@link #INBOUND_JOIN}。
     *
     * <p>⚠️ 为什么需要第三个字段（2026-10-20）：{@code COUNT(*)} 原先无条件挂 {@code INBOUND_JOIN}，
     * 而那个派生表要把 {@code purchase_receipt_items}(6.5 万行) 按工单号<strong>全量</strong>聚合成 5834 组，
     * 再对每行做一次主键回表——实测默认打开时 COUNT 从 49ms 涨到 72ms，纯属白付：
     * <b>只有两组谓词真的用到 {@code ib}</b>——「生产交付结果」（{@link #DELIVERY_OUTCOME}）与
     * 「倒计时」（{@link #DELIVERED_IN_DATE}）；{@code TRACKING_ACTION} 里的 {@code INBOUND_*} 也用它，
     * 但那两支都先要求 {@code ms.node_count=0}（未跟单），与交付结果的 {@code PENDING} 等价，同样不需要额外挂。
     * 故：{@code deliveryResult} 非空或 {@code countdown} 选了「已交付/未交付类」时才挂，其余不挂。
     *
     * <p>⚠️ 明细查询（{@link #LIST_COLUMNS} 含 {@code INBOUND_COLUMNS}）和导出<b>永远</b>要挂，
     * 与该标记无关——它只决定「光要个数字」的 COUNT 要不要白跑一趟。
     */
    private record Filtered(StringBuilder where,List<Object> params,boolean needsInbound) {}

    /**
     * 只给契约测试用的窄入口：按跟单状态跑一遍 {@link #filter}，把拼出来的 where 与 needsInbound 交出去。
     *
     * <p>【为什么要有它】工作台默认口径（`open` ＝ 未交付）是「不报错、只筛错」的典型：SQL 拼错一个条件，
     * 编译通过、接口 200、页面照样出数据，只是数字悄悄错了。这类判定必须在单测里钉死。
     * 而 {@code filter} 有 21 个入参、返回私有 record，直接暴露不合算，故只开这一条窄缝。
     * 返回值用 {@code Map} 而不是那个私有 record：私有嵌套类型的访问器在包外测试里不可见。
     */
    static Map<String,Object> filterForTest(ProductionTrackingService service,String trackingStatus) {
        Filtered filtered=service.filter(null,null,trackingStatus,"all",null,false,null,null,"all","",null,null,
                EMPTY_DATE_FILTER,EMPTY_DATE_FILTER,EMPTY_DATE_FILTER,false,null,EMPTY_DATE_FILTER,"","");
        return Map.of("where",filtered.where().toString(),"needsInbound",filtered.needsInbound());
    }

    private Filtered filter(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,DateRangeFilter created,
            String optionalStage,String moldStage) {
        // 引用 ib.first_in 的两组谓词：交付结果、倒计时（两侧都依赖「实际交付日」）。
        // ⚠️ 第三处：跟单状态 `open` ＝ 未交付，同样引用 DELIVERED_IN_DATE（含 ib.first_in），
        // 漏挂 INBOUND_JOIN 会直接 SQL 报「Unknown column 'ib.first_in'」——这不会在单测里暴露，
        // 因为单测只断言 where 字符串。（2026-10-21 改默认口径时新增）
        boolean needsInbound=(deliveryResult!=null&&!deliveryResult.isBlank())
                ||(countdown!=null&&!countdown.isBlank())
                ||"open".equals(trackingStatus);
        if(!Set.of("open","all","pending","in_progress","completed","closed","stopped").contains(trackingStatus))
            throw new IllegalArgumentException("跟单状态筛选无效");
        if(!Set.of("all","overdue","due","delayed","near","none","time_due","time_attention","time_source","time_production",
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
        // 「预计完成」筛选必须与列表同名列**显示同一个日期**（DELIVERY_BASELINE：延期至优先，
        // 回退车间源预计、有效业务员要求日）。原先用 DEADLINE（不含延期至）⇒ 车间改期后
        // 「列上写着 10-22、筛 10-12~10-18 却照样出现」，且与周复盘/交付结果口径打架
        // （2026-10-21 实测封口机车间同一周：列表 71 单 vs 周复盘 65 单，12 单全部由此造成）。
        // ⚠️ DEADLINE 仍用于延期判定（REMINDER_CASE）与报工延误，语义不同，勿一并替换。
        appendDateRangeFilter(where,params,DELIVERY_BASELINE,expected,"预计完成");
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
        // 两类「按跟单进度」的状态各自还要排除已停止/已结案的（停止态的状态值本身是 stopped，但
        // pending 的判据「一个节点都没有」对一条已停止的空单同样成立，不排除就会把停掉的单当成待跟单）。
        if("pending".equals(trackingStatus)||"in_progress".equals(trackingStatus))
            where.append(" AND t.stopped_at IS NULL AND t.closed_at IS NULL");
        if("closed".equals(trackingStatus))where.append(" AND t.closed_at IS NOT NULL");
        else if("open".equals(trackingStatus)){
            // 【2026-10-21 修正默认口径】`open` 原先翻成 `TRACKING_STATUS_CASE IN ('pending','in_progress')`，
            // 而 pending 的判据是「还没有任何跟单节点」——跟单真正开始于 2026-09 的 571 单里，508 单已经交付，
            // 交期一到它们就全成了「已超期」。于是一个「今天要做什么」的工作台，
            // 6043 条里 5119 条是已交付的历史单，点「已超期」出来 5935 条，等于没法用。
            // 工作台口径必须是**还没交付**：未停工、未结案、实际交付日为空。
            // 「已跟完但没交付」（节点齐了、货还在路上）仍留在工作台里——那正是要催发货的那批。
            where.append(" AND t.stopped_at IS NULL AND t.closed_at IS NULL AND ").append(DELIVERED_IN_DATE).append(" IS NULL");
        }
        else if(!"all".equals(trackingStatus))where.append(" AND ").append(TRACKING_STATUS_CASE)
                .append(" IN ('").append(trackingStatus).append("')");
        String timePredicate=switch(reminder){
            case "time_due"->TIME_REVIEW_DUE;
            case "time_attention"->TIME_ATTENTION;
            case "time_source"->TIME_SOURCE;
            case "time_production"->TIME_PRODUCTION;
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
            // 搜索框唯一入口（10-19 LQ：「搜索规格」并入任务号 / 产品 / 客户同一个框）：四个字段任一命中即算匹配。
            // 「规格」在氚云是三个字段（尺寸段 / 形状 / 材质），拼接方式与 LIST_COLUMNS 里的 specification_snapshot
            // 完全一致 —— 否则「搜出来的行」和「列里看到的规格」会对不上。
            // ⚠️ 占位符 4 个 ↔ params 4 个，改字段数必须同步改循环次数。
            where.append(" AND (p.work_order_no LIKE ? OR p.product_name LIKE ? OR p.customer_name LIKE ?"
                    +" OR CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) LIKE ?)");
            for(int i=0;i<4;i++)params.add("%"+keyword.trim()+"%");
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
        // 「模具生产」跟进筛选（三档可多选，取值固定枚举，白名单校验后内联）。
        // 两个条件缺一不可：① 模具之后没有任何节点跟进（否则单已越过模具这一关）；
        // ② 模具节点自身落在所选档位。刻意不看模具之前的节点——备料跟没跟与本功能无关。
        if(moldStage!=null&&!moldStage.isBlank()){
            var selected=new java.util.LinkedHashSet<>(List.of(moldStage.split(",",-1)));
            selected.removeIf(String::isBlank);
            if(!selected.isEmpty()){
                if(!MOLD_STAGE_CODES.containsAll(selected))
                    throw new IllegalArgumentException("模具跟进筛选无效");
                where.append(" AND NOT ").append(MOLD_LATER_TRACKED)
                        .append(" AND ").append(MOLD_NODE_STATE).append(" IN ('")
                        .append(String.join("','",selected)).append("')");
            }
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
        return new Filtered(where,params,needsInbound);
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
            DateRangeFilter expected,boolean urgent,String moldLayout) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,EMPTY_DATE_FILTER);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,DateRangeFilter created) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,created,"");
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,DateRangeFilter created,String ids) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,created,"",ids);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,DateRangeFilter created,
            String optionalStage,String ids) {
        return exportRows(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,created,
                optionalStage,"",ids);
    }

    public Map<String,Object> exportRows(String keyword,String workshop,String trackingStatus,String reminder,
            String sourceStatus,boolean mine,String assignee,String month,String action,String countdown,
            String salesperson,String deliveryResult,DateRangeFilter application,DateRangeFilter schedule,
            DateRangeFilter expected,boolean urgent,String moldLayout,DateRangeFilter created,
            String optionalStage,String moldStage,String ids) {
        var f=filter(keyword,workshop,trackingStatus,reminder,sourceStatus,mine,assignee,month,action,countdown,
                salesperson,deliveryResult,application,schedule,expected,urgent,moldLayout,created,
                optionalStage,moldStage);
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
        // total 与列表同口径（同一份 where + 按需挂 INBOUND_JOIN），截断时前端能报出「共多少、导了多少」。
        long total=Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*)"+TRACKING_FROM+(f.needsInbound()?INBOUND_JOIN:"")+where,
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
     *
     * <p>⚠️ 2026-10-20 重构（实测 450~830ms → 期望 ~100ms，返回值 906 字节不变）。
     * 原先这里**串行**跑 8 条 SQL，每条都挂一份完整的 {@link #TRACKING_FROM}。改法分两层：
     * <ol>
     *   <li><b>单遍扫描</b>：6 组取值（车间 / 模具排版 / 跟单员 / 任务单状态 / 业务员）用
     *       {@code GROUP_CONCAT(DISTINCT ...)} 在**同一次主表扫描**里全部取出——实测证明瓶颈是
     *       「主表被重复扫 N 遍」而不是 JOIN 派生表：去掉 t/ms 派生表几乎不提速（282→290ms），
     *       而改成单遍后同一批取值只需 40ms。用 {@code 0x1e} 作分隔符（不会出现在业务文本里），
     *       由 {@link #splitMulti} 拆开去重。</li>
     *   <li><b>并行</b>：月份计数（要 GROUP BY 和 count，无法并进上面那条）、待办计数（额外挂
     *       {@link #INBOUND_JOIN}）、按需工序计数（要 {@code ms} 派生表的聚合）三条形态各异，
     *       与主扫描**同时发出**，墙钟取最大值而非累加。</li>
     * </ol>
     * ⚠️ 口径一条没改：每项仍是同一套 FROM + 同一段可见性 where，下拉里的数字依旧等于点进去的条数。
     * ⚠️ {@code GROUP_CONCAT} 有 {@code group_concat_max_len} 上限（默认 1024）。
     * 这里只为「已经去过重的取值」拼接，全库维度多是几十项，故在会话级上调到 {@link #CONCAT_MAX_LEN}
     * 兜底；万一仍被截断（超长取自业务员/模具排版），{@link #splitMulti} 会把截断的尾巴丢弃而不是吐出半截值。
     */
    public Map<String,Object> filterOptions() {
        var params=new ArrayList<Object>();var where=visible(params);
        String settled=where.toString();
        String sep=String.valueOf((char)0x1e);
        // 主扫描：一次扫表拿到 5 组取值（月份带 count，单独走一条）。
        String scan="SELECT"
                +" GROUP_CONCAT(DISTINCT CASE WHEN p.production_line IS NOT NULL AND p.production_line<>''"
                +"   THEN p.production_line END ORDER BY p.production_line SEPARATOR 0x1e) k_workshop,"
                +" GROUP_CONCAT(DISTINCT CASE WHEN p.mold_layout IS NOT NULL AND TRIM(p.mold_layout)<>''"
                +"   THEN TRIM(p.mold_layout) END ORDER BY TRIM(p.mold_layout) SEPARATOR 0x1e) k_mold,"
                +" GROUP_CONCAT(DISTINCT CASE WHEN assigned.id IS NOT NULL"
                +"   THEN CONCAT(assigned.id,0x1f,assigned.name) END ORDER BY assigned.name SEPARATOR 0x1e) k_assignee,"
                +" GROUP_CONCAT(DISTINCT CASE WHEN p.status IS NOT NULL AND p.status<>''"
                +"   THEN p.status END ORDER BY p.status SEPARATOR 0x1e) k_sourceStatus,"
                +" GROUP_CONCAT(DISTINCT CASE WHEN p.salesperson_name IS NOT NULL AND p.salesperson_name<>''"
                +"   THEN p.salesperson_name END ORDER BY p.salesperson_name SEPARATOR 0x1e) k_salesperson"
                +TRACKING_FROM+settled;
        // 与主扫描并行：月份计数 / 待办计数（挂 INBOUND_JOIN）/ 按需工序计数（要 ms 聚合）
        java.util.concurrent.Future<List<Map<String,Object>>> monthFuture=READ_POOL.submit(()->jdbc.query(
                "SELECT DATE_FORMAT("+CREATED_MONTH_DATE+",'%Y-%m') month,COUNT(*) count"
                +TRACKING_FROM+settled+" AND "+CREATED_MONTH_DATE+" IS NOT NULL GROUP BY month ORDER BY month DESC",
                (r,n)->java.util.Collections.<String,Object>unmodifiableMap(
                        Map.<String,Object>of("month",(Object)r.getString("month"),"count",(Object)r.getLong("count"))),
                params.toArray()));
        java.util.concurrent.Future<List<Map<String,Object>>> actionFuture=READ_POOL.submit(()->jdbc.query(
                "SELECT "+TRACKING_ACTION+" action,COUNT(*) count"
                +TRACKING_FROM+INBOUND_JOIN+settled+" AND "+TRACKING_ACTION+" IS NOT NULL GROUP BY action",
                (r,n)->java.util.Collections.<String,Object>unmodifiableMap(
                        Map.<String,Object>of("action",(Object)r.getString("action"),"count",(Object)r.getLong("count"))),
                params.toArray()));
        java.util.concurrent.Future<List<Map<String,Object>>> optionalFuture=READ_POOL.submit(()->jdbc.queryForList(
                "SELECT COALESCE(SUM("
                +needsOptionalStage(ProductionTrackingPolicy.OXIDATION)+"),0) oxidation,COALESCE(SUM("
                +needsOptionalStage(ProductionTrackingPolicy.WOODEN_CRATE)+"),0) wooden_crate"
                +TRACKING_FROM+settled,params.toArray()));
        // GROUP_CONCAT 的会话上限：只在当前连接生效，不影响其它连接；取值已是去重后的短文本。
        jdbc.execute("SET SESSION group_concat_max_len="+CONCAT_MAX_LEN);
        Map<String,Object> scanRow=jdbc.queryForList(scan,params.toArray()).stream().findFirst().orElse(Map.of());
        var assignees=splitMulti(scanRow.get("k_assignee"),sep).stream()
                .map(packed->{
                    int cut=packed.indexOf('\u001f');
                    return cut<0?Map.<String,Object>of("id",(Object)0L,"name",packed)
                            :Map.<String,Object>of("id",(Object)Long.valueOf(packed.substring(0,cut)),
                                    "name",packed.substring(cut+1));
                })
                .sorted((a,b)->String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))))
                .toList();
        List<Map<String,Object>> months;
        List<Map<String,Object>> actionCounts;
        List<Map<String,Object>> optionalTotals;
        try{months=monthFuture.get();
        }catch(Exception e){throw new IllegalStateException("月份计数查询失败",e);}
        try{actionCounts=actionFuture.get();
        }catch(Exception e){throw new IllegalStateException("待办计数查询失败",e);}
        try{optionalTotals=optionalFuture.get();
        }catch(Exception e){throw new IllegalStateException("按需工序计数查询失败",e);}
        Map<String,Object> optionalRow=optionalTotals.isEmpty()?Map.of():optionalTotals.getFirst();
        // ⚠️ 不能用 getOrDefault(key,0L)：键存在但值为 null 时 getOrDefault 返回的是 null 而不是默认值，
        // 随后的 ((Number)null).longValue() 直接 NPE（2026-10-20 单测实测）。SQL 里虽有 COALESCE(SUM(...),0)，
        // 但那只能保证「没有匹配行」的场景；列名对不上、驱动返回 null 时仍会漏过来。故显式判空。
        var optionalStageCounts=OPTIONAL_STAGE_CODES.stream().map(code->{
            Object raw=optionalRow.get(ProductionTrackingPolicy.OXIDATION.equals(code)?"oxidation":"wooden_crate");
            long count=raw instanceof Number number?number.longValue():0L;
            return Map.<String,Object>of("code",code,"count",count);
        }).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("workshops",splitMulti(scanRow.get("k_workshop"),sep));
        result.put("moldLayouts",splitMulti(scanRow.get("k_mold"),sep));
        result.put("assignees",assignees);
        result.put("sourceStatuses",splitMulti(scanRow.get("k_sourceStatus"),sep));
        result.put("salespersons",splitMulti(scanRow.get("k_salesperson"),sep));
        result.put("months",months);
        result.put("actionCounts",actionCounts);
        result.put("optionalStageCounts",optionalStageCounts);
        return result;
    }
    /** GROUP_CONCAT 会话上限：取值均为已去重的短文本，20000 字符足够装下全库维度，且不会撑爆内存。 */
    private static final int CONCAT_MAX_LEN=20000;
    /**
     * 拆 {@code GROUP_CONCAT(... SEPARATOR 0x1e)} 的结果：拆段 → 去空 → 去重 → 保序。
     * SQL 侧已经 {@code DISTINCT} 且 {@code ORDER BY}，这里再兜一次是为了防御
     * 「结果被 group_concat_max_len 截断」——截断只会发生在最后一段，故末尾若疑似半截值直接丢弃。
     */
    static List<String> splitMulti(Object raw,String sep) {
        if(raw==null)return List.of();
        String text=String.valueOf(raw);
        var parts=new ArrayList<>(java.util.Arrays.asList(text.split(java.util.regex.Pattern.quote(sep),-1)));
        // 截断判据：MySQL 的 GROUP_CONCAT 超限时返回完整前缀（末尾正是分隔符），split 后最后一段为空。
        // 若最后一段为空，说明原串以分隔符结尾 —— 正常情况不会出现（DISTINCT+ORDER 不会补尾分隔符），
        // 而是被截断的信号：去掉这段空串即可，前面的值都是完整的。
        if(!parts.isEmpty()&&parts.getLast().isEmpty())parts.removeLast();
        return parts.stream().filter(s->s!=null&&!s.isEmpty()).distinct().toList();
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

    /**
     * 精确匹配车间与任务单产品名称，取该产品已发布的节点间隔覆盖车间基准；
     * 同名产品在同一车间出现多行配置时不猜，直接沿用车间基准。
     *
     * <p><b>车间口径</b>：车间名称一律从 {@code manufacturing_workshops} 反查（{@code workshop_id} 外键），
     * 不再用 {@code manufacturing_task_config_items.workshop_name} —— 后者是改名时用 UPDATE 手工级联的副本，
     * 漏一处就会静默错配到别的车间。产品名仍是文本匹配（历史任务里只有文本），改名的影响见页面提示。
     *
     * <p><b>不判 {@code active}（2026-10-21）</b>：任务单产品是氚云生产任务单产品下拉字典的镜像，
     * 产品能不能下单由氚云说了算。本地若保留一个「停用」开关，就会出现「氚云照常用、这边被停用 ⇒
     * 该产品所有在制单的参考工期静默退回车间基准」，且没有任何报错提示。实测全库 50 个产品全部启用，
     * 去掉判据后结果完全一致。
     */
    private Map<String,BigDecimal> benchmarksForOrder(Map<String,Object> order) {
        String workshop=(String)order.get("workshopType");
        boolean sealer=ProductionTrackingPolicy.isSealer(workshop);
        var values=new HashMap<>(nodeBenchmarks().getOrDefault(sealer?BENCHMARK_SEALER:BENCHMARK_MACHINE,Map.of()));
        String product=(String)order.get("productName");
        if(product==null||product.isBlank()||workshop==null)return values;
        var items=jdbc.query("SELECT c.id FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.config_type='PRODUCT_NAME' AND c.config_name=? AND w.workshop_name=?",
                (r,n)->r.getLong(1),product,workshop);
        if(items.size()!=1)return values;
        jdbc.query("SELECT stage_code,typical_days FROM production_tracking_product_benchmarks "
                +"WHERE task_config_id=? AND typical_days IS NOT NULL",
                r->{values.put(r.getString(1),r.getBigDecimal(2));},items.getFirst());
        return values;
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

    /** 产品级基准矩阵：每个产品名称配置行 × 已计算的节点，未算过候选的产品只有产品信息（stageCode 为 null）。
     *  车间名走 workshops 外键反查（同 benchmarksForOrder 口径）；workshop_id 为空的产品行不参与基准。
     *  不判 {@code active}：产品没有启用概念（见 {@link #benchmarksForOrder}），停用产品也要能看到并维护间隔。 */
    public List<Map<String,Object>> productBenchmarkMatrix() {
        return jdbc.queryForList("SELECT c.id AS taskConfigId,w.workshop_name AS workshopName,c.config_name AS configName,"
                +"b.stage_code AS stageCode,b.typical_days AS typicalDays,b.sample_count AS sampleCount,"
                +"b.p50_days AS p50Days,b.p75_days AS p75Days,b.excluded_count AS excludedCount,"
                +"b.planned_anchor_count AS plannedAnchorCount,b.application_anchor_count AS applicationAnchorCount,"
                +"b.application_p50_days AS applicationP50Days,b.application_p75_days AS applicationP75Days,"
                +"b.zero_day_count AS zeroDayCount,"
                +"b.published_source AS publishedSource,b.published_at AS publishedAt,"
                +"b.baseline_note AS baselineNote,b.computed_at AS computedAt "
                +"FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"LEFT JOIN production_tracking_product_benchmarks b "
                +"ON b.task_config_id=c.id WHERE c.config_type='PRODUCT_NAME' "
                +"ORDER BY w.workshop_name,c.config_name,b.stage_code");
    }

    public record ProductBenchmarkSave(long taskConfigId,String stageCode,BigDecimal typicalDays) {}

    /**
     * 一张历史任务在该产品下的样本原始记录：锚点（排产日优先：本地计划开工 → 氚云排产时间；都缺才退申请日）+ 各节点完成日期。
     * **候选计算与样本明细同源**，避免两处口径漂移。
     */
    private record OrderSample(long orderId,String orderNo,LocalDate submittedAt,LocalDate anchor,
                               boolean plannedAnchor,Map<String,LocalDate> stageDays) {}

    /**
     * 取该产品（车间 + 产品名精确匹配）的**跟单记录**样本，按任务单提交时间排序。
     * <p>⚠️ 样本基数＝有跟单记录（`production_tracking_state` 存在）的任务单，<b>不是</b>该产品的全部历史任务单。
     * 全量任务单里绝大多数从未进入跟单，把它们算进「命中任务」会出现「显示 1115 张、实际只有 103 条样本」，
     * 分位数没变、可信度却被判反（2026-10-21 实测：手动片膜 1115 张单 / 103 条跟单）。口径见工期文档。
     */
    private List<OrderSample> loadOrderSamples(String workshop,String product) {
        var anchors=new HashMap<Long,LocalDate>();
        var planned=new java.util.HashSet<Long>();
        var dates=new HashMap<Long,Map<String,LocalDate>>();
        var meta=new LinkedHashMap<Long,Object[]>();
        // 锚点优先级（2026-10-21 LQ 定：跟单开始时间一般从排产时间起算）：
        // 本地计划开工 t.planned_start_date → 氚云排产时间 p.schedule_time（99% 有值）→ 申请日兜底。
        // 前两者都算「排产锚点」（plannedAnchor=true，首段进正式候选）；只有申请日兜底不计入正式候选。
        jdbc.query("SELECT p.id,p.work_order_no,DATE(p.source_created_at),t.planned_start_date,p.schedule_time,p.application_date,"
                +"m.stage_code,m.completed_date "
                +"FROM production_orders p "
                +"JOIN production_tracking_state t ON t.production_order_id=p.id "
                +"LEFT JOIN production_tracking_milestones m ON m.production_order_id=p.id AND m.stage_status='DONE' "
                +"WHERE p.production_line=? AND p.product_name=? "
                +"AND (p.status IS NULL OR p.status NOT IN ('CANCELLED','CANCELED','已取消')) "
                +"ORDER BY p.source_created_at,p.id",
                r->{
                    long id=r.getLong(1);
                    java.sql.Date submitted=r.getDate(3),plan=r.getDate(4),sched=r.getDate(5),application=r.getDate(6),completed=r.getDate(8);
                    meta.putIfAbsent(id,new Object[]{r.getString(2),submitted==null?null:submitted.toLocalDate()});
                    if(plan!=null||sched!=null)planned.add(id);
                    LocalDate anchor=plan!=null?plan.toLocalDate()
                            :sched!=null?sched.toLocalDate()
                            :application==null?null:application.toLocalDate();
                    anchors.put(id,anchor);
                    if(r.getString(7)!=null&&completed!=null)
                        dates.computeIfAbsent(id,k->new HashMap<>()).put(r.getString(7),completed.toLocalDate());
                },workshop,product);
        var out=new ArrayList<OrderSample>(meta.size());
        for(var e:meta.entrySet()){
            Object[] m=e.getValue();
            out.add(new OrderSample(e.getKey(),(String)m[0],(LocalDate)m[1],anchors.get(e.getKey()),
                    planned.contains(e.getKey()),dates.getOrDefault(e.getKey(),Map.of())));
        }
        return out;
    }

    /**
     * 样本明细（只读，不落库）：把「候选是怎么算出来的」摊开给人工看——
     * 哪张单、用的哪个锚点、每段间隔多少天、哪段被排除及原因。发布前抽查的依据就在这里，
     * 否则产品行上只有一个「历史工单 N」，无法判断 P50 能不能信。
     */
    public Map<String,Object> productBenchmarkSamples(long taskConfigId) {
        var items=jdbc.queryForList("SELECT c.config_name,w.workshop_name FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.id=? AND c.config_type='PRODUCT_NAME'",taskConfigId);
        if(items.isEmpty())throw new IllegalArgumentException("产品不存在、不是产品名称配置，或没有所属车间");
        String product=(String)items.getFirst().get("config_name");
        String workshop=(String)items.getFirst().get("workshop_name");
        var codes=ProductionTrackingPolicy.stageCodes(workshop);
        var orders=loadOrderSamples(workshop,product);
        LocalDate today=LocalDate.now();
        var rows=new ArrayList<Map<String,Object>>();
        int planned=0,application=0,none=0;
        LocalDate from=null,to=null;
        for(OrderSample o:orders){
            if(o.anchor()==null)none++;
            else if(o.plannedAnchor())planned++;
            else application++;
            if(o.submittedAt()!=null){
                if(from==null||o.submittedAt().isBefore(from))from=o.submittedAt();
                if(to==null||o.submittedAt().isAfter(to))to=o.submittedAt();
            }
            var stages=new ArrayList<Map<String,Object>>();
            LocalDate previous=o.anchor();
            for(String code:codes){
                LocalDate day=o.stageDays().get(code);
                // 按需工序没登记就不算一段（与候选计算同源：不适用不加分母）
                if(ProductionTrackingPolicy.isOptionalStage(code)&&day==null)continue;
                var row=new LinkedHashMap<String,Object>();
                row.put("stageCode",code);
                if(day==null){
                    row.put("days",null);row.put("used",false);row.put("reason","未登记完成日期");
                    stages.add(row);previous=day;continue;
                }
                if(previous==null){
                    row.put("days",null);row.put("used",false);row.put("reason","缺锚点（无计划开工与申请日）");
                    stages.add(row);previous=day;continue;
                }
                long gap=ChronoUnit.DAYS.between(previous,day);
                row.put("days",gap);
                if(gap<0){row.put("used",false);row.put("reason","完成日期早于上一节点");}
                else if(gap>60){row.put("used",false);row.put("reason","间隔超过 60 天");}
                else if(day.isAfter(today)){row.put("used",false);row.put("reason","完成日期在未来");}
                else if(code.equals(codes.getFirst())&&!o.plannedAnchor()){
                    row.put("used",false);row.put("reason","首节点缺排产日，只计入申请日兜底");
                }else{
                    row.put("used",true);row.put("reason",null);
                    if(gap==0)row.put("zeroDay",true);
                }
                stages.add(row);
                previous=day;
            }
            var map=new LinkedHashMap<String,Object>();
            map.put("orderId",o.orderId());
            map.put("orderNo",o.orderNo());
            map.put("submittedAt",o.submittedAt());
            map.put("anchorKind",o.anchor()==null?"NONE":o.plannedAnchor()?"PLANNED":"APPLICATION");
            map.put("stages",stages);
            rows.add(map);
        }
        var summary=new LinkedHashMap<String,Object>();
        summary.put("matchedOrders",orders.size());
        summary.put("plannedAnchorCount",planned);
        summary.put("applicationAnchorCount",application);
        summary.put("noAnchorCount",none);
        summary.put("spanFrom",from);
        summary.put("spanTo",to);
        return Map.of("workshop",workshop,"product",product,"stageCodes",codes,"summary",summary,"orders",rows);
    }

    /**
     * 产品的历史生产任务明细（只读）：把配置页「历史任务单 / 跟单记录」两个数字摊开——
     * 这个产品做过哪些单、哪些建了跟单、跟到第几个必经节点。
     *
     * <p><b>口径</b>：与配置页那两个计数同源——{@code production_orders} 按「车间 + 产品名」精确匹配，
     * <b>不排除</b>草稿 / 已取消（它们是真实历史，剔掉会让数字与列表对不上，页面上单独标注即可）；
     * 「完成节点 x/y」的 y 只数必经节点，与参考间隔分母同源（按需工序没做不算缺）。
     *
     * <p><b>为什么这个方法在跟单域</b>：节点集合的唯一定义是 {@link ProductionTrackingPolicy}（包私有），
     * 复制一份判据到主数据 Controller 就是第二真相，迟早和跟单分母漂移。
     */
    public Map<String,Object> productOrderHistory(long taskConfigId,String status,int limit){
        var items=jdbc.queryForList("SELECT c.config_name,w.workshop_name FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.id=? AND c.config_type='PRODUCT_NAME'",taskConfigId);
        if(items.isEmpty())throw new IllegalArgumentException("产品不存在、不是产品名称配置，或没有所属车间");
        String product=(String)items.getFirst().get("config_name");
        String workshop=(String)items.getFirst().get("workshop_name");
        var required=ProductionTrackingPolicy.requiredStages(workshop);
        int size=Math.max(1,Math.min(limit,500));
        String stageIn=String.join(",",Collections.nCopies(required.size(),"?"));
        var sql=new StringBuilder("SELECT p.id,p.work_order_no,p.status,p.application_date,DATE(p.schedule_time) AS scheduled_date,")
                .append("p.required_completion_date,p.expected_completion_date,p.required_quantity,")
                .append("p.production_progress,p.warehousing_progress,p.source_created_at,")
                .append("(t.production_order_id IS NOT NULL) AS tracked,")
                .append("(SELECT COUNT(*) FROM production_tracking_milestones m WHERE m.production_order_id=p.id ")
                .append("AND m.stage_status='DONE' AND m.stage_code IN (").append(stageIn).append(")) AS done_stages,")
                .append("(SELECT MAX(m.completed_date) FROM production_tracking_milestones m ")
                .append("WHERE m.production_order_id=p.id AND m.stage_status='DONE') AS last_done_date ")
                .append("FROM production_orders p LEFT JOIN production_tracking_state t ON t.production_order_id=p.id ")
                .append("WHERE p.production_line=? AND p.product_name=? ");
        var args=new ArrayList<Object>(required);
        args.add(workshop);args.add(product);
        if(status!=null&&!status.isBlank()){sql.append("AND p.status=? ");args.add(status);}
        sql.append("ORDER BY p.source_created_at DESC,p.id DESC LIMIT ?");
        args.add(size);
        var rows=new ArrayList<Map<String,Object>>();
        jdbc.query(sql.toString(),r->{
            var row=new LinkedHashMap<String,Object>();
            row.put("orderId",r.getLong("id"));
            row.put("orderNo",r.getString("work_order_no"));
            row.put("status",r.getString("status"));
            java.sql.Date application=r.getDate("application_date");
            row.put("applicationDate",application==null?null:application.toLocalDate());
            row.put("scheduledDate",r.getObject("scheduled_date"));
            java.sql.Date requiredDate=r.getDate("required_completion_date");
            row.put("requiredDate",requiredDate==null?null:requiredDate.toLocalDate());
            java.sql.Date expectedDate=r.getDate("expected_completion_date");
            row.put("expectedDate",expectedDate==null?null:expectedDate.toLocalDate());
            row.put("quantity",r.getBigDecimal("required_quantity"));
            row.put("productionProgress",r.getString("production_progress"));
            row.put("warehousingProgress",r.getString("warehousing_progress"));
            row.put("createdAt",r.getObject("source_created_at"));
            row.put("tracked",r.getBoolean("tracked"));
            row.put("doneStages",r.getInt("done_stages"));
            java.sql.Date lastDone=r.getDate("last_done_date");
            row.put("lastDoneDate",lastDone==null?null:lastDone.toLocalDate());
            rows.add(row);
        },args.toArray());
        // summary 不受 status 过滤影响：左边筛状态、右边画像不能跟着变，否则「总量」会随筛选跳动。
        var summary=jdbc.queryForMap("SELECT COUNT(*) AS total,"
                +"SUM(CASE WHEN p.status IN ('已取消','CANCELLED','CANCELED') THEN 1 ELSE 0 END) AS cancelled,"
                +"SUM(CASE WHEN p.status='草稿' THEN 1 ELSE 0 END) AS draft,"
                +"SUM(EXISTS(SELECT 1 FROM production_tracking_state t WHERE t.production_order_id=p.id)) AS tracked,"
                +"MIN(p.source_created_at) AS firstAt,MAX(p.source_created_at) AS lastAt "
                +"FROM production_orders p WHERE p.production_line=? AND p.product_name=?",workshop,product);
        return Map.of("product",product,"workshop",workshop,"requiredStageCount",required.size(),
                "summary",summary,"orders",rows,"truncated",rows.size()>=size);
    }

    /** 从该产品自己的历史跟单完成日期计算候选间隔；只更新证据，不自动改动已发布参考值。 */
    @Transactional
    public List<Map<String,Object>> refreshProductBenchmarkCandidates(long taskConfigId) {
        var items=jdbc.queryForList("SELECT c.config_name,w.workshop_name FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.id=? AND c.config_type='PRODUCT_NAME'",taskConfigId);
        if(items.isEmpty())throw new IllegalArgumentException("产品不存在、不是产品名称配置，或没有所属车间");
        String product=(String)items.getFirst().get("config_name");
        String workshop=(String)items.getFirst().get("workshop_name");
        if(product==null||workshop==null)throw new IllegalArgumentException("产品缺少名称或车间，无法统计历史样本");
        var codes=ProductionTrackingPolicy.stageCodes(workshop);
        var orders=loadOrderSamples(workshop,product);
        var samples=new LinkedHashMap<String,List<Long>>();
        var applicationFallback=new HashMap<String,List<Long>>();
        var excluded=new HashMap<String,Integer>();
        var zeroDays=new HashMap<String,Integer>();
        for(String code:codes){samples.put(code,new ArrayList<>());applicationFallback.put(code,new ArrayList<>());}
        LocalDate today=LocalDate.now();
        for(OrderSample o:orders){
            LocalDate previous=o.anchor();
            for(String code:codes){
                LocalDate day=o.stageDays().get(code);
                if(ProductionTrackingPolicy.isOptionalStage(code)&&day==null)continue;
                if(day!=null){
                    if(previous!=null){
                        long gap=ChronoUnit.DAYS.between(previous,day);
                        if(gap<0||gap>60||day.isAfter(today))excluded.merge(code,1,Integer::sum);
                        else if(code.equals(codes.getFirst())&&!o.plannedAnchor())
                            applicationFallback.get(code).add(gap);
                        else {
                            samples.get(code).add(gap);
                            if(gap==0)zeroDays.merge(code,1,Integer::sum);
                        }
                    }else excluded.merge(code,1,Integer::sum);
                }
                previous=day;
            }
        }
        for(String code:codes){
            var values=samples.get(code);
            values.sort(Long::compareTo);
            var fallback=applicationFallback.get(code);
            fallback.sort(Long::compareTo);
            jdbc.update("INSERT INTO production_tracking_product_benchmarks(task_config_id,stage_code,typical_days,"
                    +"sample_count,p50_days,p75_days,excluded_count,planned_anchor_count,application_anchor_count,"
                    +"application_p50_days,application_p75_days,zero_day_count,baseline_note,computed_at) "
                    +"VALUES(?,?,NULL,?,?,?,?,?,?,?,?,?,'历史样本候选，待人工审核',CURDATE()) "
                    +"ON DUPLICATE KEY UPDATE sample_count=VALUES(sample_count),p50_days=VALUES(p50_days),"
                    +"p75_days=VALUES(p75_days),excluded_count=VALUES(excluded_count),"
                    +"planned_anchor_count=VALUES(planned_anchor_count),application_anchor_count=VALUES(application_anchor_count),"
                    +"application_p50_days=VALUES(application_p50_days),application_p75_days=VALUES(application_p75_days),"
                    +"                    zero_day_count=VALUES(zero_day_count),computed_at=CURDATE()",
                    taskConfigId,code,values.size(),percentile(values,0.5),percentile(values,0.75),excluded.getOrDefault(code,0),
                    code.equals(codes.getFirst())?values.size():0,fallback.size(),percentile(fallback,0.5),
                    percentile(fallback,0.75),zeroDays.getOrDefault(code,0));
        }
        return jdbc.queryForList("SELECT stage_code AS stageCode,sample_count AS sampleCount,p50_days AS p50Days,"
                +"p75_days AS p75Days,excluded_count AS excludedCount,typical_days AS typicalDays,"
                +"planned_anchor_count AS plannedAnchorCount,application_anchor_count AS applicationAnchorCount,"
                +"application_p50_days AS applicationP50Days,application_p75_days AS applicationP75Days,"
                +"zero_day_count AS zeroDayCount "
                +"FROM production_tracking_product_benchmarks WHERE task_config_id=? ORDER BY id",taskConfigId);
    }

    static BigDecimal percentile(List<Long> sorted,double p) {
        if(sorted.isEmpty())return null;
        double pos=(sorted.size()-1)*p;
        int lo=(int)Math.floor(pos),hi=(int)Math.ceil(pos);
        double value=sorted.get(lo)+(sorted.get(hi)-sorted.get(lo))*(pos-lo);
        return BigDecimal.valueOf(value).setScale(1,java.math.RoundingMode.HALF_UP);
    }

    @Transactional
    public void saveProductBenchmark(ProductBenchmarkSave row) {
        if(row==null||row.stageCode()==null)throw new IllegalArgumentException("请选择产品与节点");
        var items=jdbc.query("SELECT w.workshop_name FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.id=? AND c.config_type='PRODUCT_NAME'",(r,n)->r.getString(1),row.taskConfigId());
        if(items.isEmpty())throw new IllegalArgumentException("产品不存在、不是产品名称配置，或没有所属车间");
        if(!ProductionTrackingPolicy.stageCodes(items.getFirst()).contains(row.stageCode()))
            throw new IllegalArgumentException("节点不属于该产品车间");
        if(row.typicalDays()!=null&&(row.typicalDays().signum()<0||row.typicalDays().doubleValue()>60))
            throw new IllegalArgumentException("参考间隔须在 0~60 天之间");
        if(row.typicalDays()==null){
            // 撤销发布：清掉发布值与来源（published_source 回到 NULL＝未发布），候选统计保留，
            // 参考日期回落车间基准。与「从未发布」在统计上同形，但审计日志留下了撤销动作。
            jdbc.update("UPDATE production_tracking_product_benchmarks SET typical_days=NULL,published_source=NULL,"
                    +"published_at=NULL,baseline_note='已撤销发布，沿用车间基准' "
                    +"WHERE task_config_id=? AND stage_code=?",row.taskConfigId(),row.stageCode());
            audit.write(ErpChangeLogWriter.of(actors.current(),"product_archive.production_tracking_product_benchmarks",
                    row.taskConfigId(),"DELETE",ErpChangeLogWriter.changes("taskConfigId",row.taskConfigId(),
                            "stageCode",row.stageCode()),"FOLLOW_UP","撤销产品节点参考间隔",null,null));
            return;
        }
        jdbc.update("INSERT INTO production_tracking_product_benchmarks(task_config_id,stage_code,typical_days,"
                +"published_source,published_at,baseline_note,computed_at) "
                +"VALUES(?,?,?,'MANUAL',NOW(),'人工审核发布',CURDATE()) "
                +"ON DUPLICATE KEY UPDATE typical_days=VALUES(typical_days),published_source='MANUAL',"
                +"published_at=NOW(),baseline_note=VALUES(baseline_note),computed_at=CURDATE()",
                row.taskConfigId(),row.stageCode(),row.typicalDays());
        audit.write(ErpChangeLogWriter.of(actors.current(),"product_archive.production_tracking_product_benchmarks",
                row.taskConfigId(),"UPDATE",ErpChangeLogWriter.changes("taskConfigId",row.taskConfigId(),"stageCode",row.stageCode(),
                        "typicalDays",row.typicalDays()),"FOLLOW_UP","发布产品节点参考间隔",null,null));
    }

    /**
     * 批量发布的最低样本量：低于 8 段不自动采用 P50（2026-10-21 外部复查 P1，原值 3）。
     * 实测依据：3 段门槛下 38 个产品组合里 124 个节点对可发布，一条补录记录就能定参考值；
     * 提到 8 段覆盖 58 个节点对 / 15 个主力产品。**只约束批量填充**——管理员看过样本明细后
     * 的单行手工发布不受此限。
     */
    private static final int AUTO_PUBLISH_MIN_SAMPLES=8;

    /**
     * 从同车间另一产品复制已发布间隔：只补目标产品**尚未发布**的节点，已发布的人工值不被覆盖
     * （与 applyProductBenchmarkP50 同一原则）。同系列产品往往共用一套节奏，逐个维护太慢。
     */
    @Transactional
    public Map<String,Object> copyProductBenchmarks(long targetTaskConfigId,long sourceTaskConfigId) {
        if(targetTaskConfigId==sourceTaskConfigId)throw new IllegalArgumentException("不能从自身复制");
        var found=jdbc.queryForList("SELECT c.id AS id,w.workshop_name AS workshopName FROM manufacturing_task_config_items c "
                +"JOIN manufacturing_workshops w ON w.id=c.workshop_id "
                +"WHERE c.id IN (?,?) AND c.config_type='PRODUCT_NAME'",targetTaskConfigId,sourceTaskConfigId);
        if(found.size()!=2)throw new IllegalArgumentException("源产品与目标产品都必须是「产品名称」配置且已归属车间");
        var names=new HashMap<Long,String>();
        for(var r:found)names.put(((Number)r.get("id")).longValue(),(String)r.get("workshopName"));
        if(ProductionTrackingPolicy.isSealer(names.get(targetTaskConfigId))
                !=ProductionTrackingPolicy.isSealer(names.get(sourceTaskConfigId)))
            throw new IllegalArgumentException("两个产品不属于同一车间类别（封口机 / 机器车间），节点序列不同");
        var source=jdbc.queryForList("SELECT stage_code AS stageCode,typical_days AS typicalDays "
                +"FROM production_tracking_product_benchmarks "
                +"WHERE task_config_id=? AND typical_days IS NOT NULL",sourceTaskConfigId);
        var copied=new ArrayList<String>();
        for(var r:source){
            String code=(String)r.get("stageCode");
            BigDecimal days=(BigDecimal)r.get("typicalDays");
            int n=jdbc.update("UPDATE production_tracking_product_benchmarks SET typical_days=?,published_source='COPY',"
                    +"published_at=NOW(),baseline_note='从同车间产品复制',computed_at=CURDATE() "
                    +"WHERE task_config_id=? AND stage_code=? AND typical_days IS NULL",
                    days,targetTaskConfigId,code);
            if(n>0){copied.add(code);continue;}
            Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM production_tracking_product_benchmarks "
                    +"WHERE task_config_id=? AND stage_code=?",Integer.class,targetTaskConfigId,code);
            if(exists!=null&&exists==0){
                jdbc.update("INSERT INTO production_tracking_product_benchmarks(task_config_id,stage_code,typical_days,"
                        +"published_source,published_at,baseline_note,computed_at) "
                        +"VALUES(?,?,?,'COPY',NOW(),'从同车间产品复制',CURDATE())",targetTaskConfigId,code,days);
                copied.add(code);
            }
        }
        if(!copied.isEmpty())
            audit.write(ErpChangeLogWriter.of(actors.current(),"product_archive.production_tracking_product_benchmarks",
                    targetTaskConfigId,"UPDATE",ErpChangeLogWriter.changes("targetTaskConfigId",targetTaskConfigId,
                            "sourceTaskConfigId",sourceTaskConfigId,"stages",copied),
                    "FOLLOW_UP","从同车间产品复制节点参考间隔",null,null));
        return Map.of("copied",copied,"sourcePublished",source.size(),"skipped",source.size()-copied.size());
    }

    /**
     * 把候选 P50 批量写成发布值：只填「尚未发布 + 样本量达标 + 零天占比不过半」的节点，已经发布的节点不动。
     * 零天占比 ≥50% 的节点多为批量补录（封口机备料 73%、入库 90% 都是零天），自动发布等于宣布
     * 「上一节点完成当天必须完成本节点」，没有缓冲——这类节点人工看过样本明细再定（2026-10-21 外部复查 P1）。
     * 由界面确认后触发，返回受影响节点供提示。
     */
    @Transactional
    public List<Map<String,Object>> applyProductBenchmarkP50(long taskConfigId) {
        var rows=jdbc.queryForList("SELECT stage_code AS stageCode,p50_days AS p50Days,sample_count AS sampleCount "
                +"FROM production_tracking_product_benchmarks WHERE task_config_id=? AND typical_days IS NULL "
                +"AND p50_days IS NOT NULL AND sample_count>=? "
                +"AND COALESCE(zero_day_count,0)*2<sample_count ORDER BY id",taskConfigId,AUTO_PUBLISH_MIN_SAMPLES);
        for(var row:rows)
            jdbc.update("UPDATE production_tracking_product_benchmarks SET typical_days=?,"
                    +"published_source='P50',published_at=NOW(),"
                    +"baseline_note='按历史样本 P50 批量发布',computed_at=CURDATE() WHERE task_config_id=? AND stage_code=?",
                    row.get("p50Days"),taskConfigId,row.get("stageCode"));
        if(!rows.isEmpty())
            audit.write(ErpChangeLogWriter.of(actors.current(),"product_archive.production_tracking_product_benchmarks",
                    taskConfigId,"UPDATE",ErpChangeLogWriter.changes("taskConfigId",taskConfigId,"stages",
                            rows.stream().map(row->String.valueOf(row.get("stageCode"))).reduce((a,b)->a+","+b).orElse("")),
                    "FOLLOW_UP","按历史样本 P50 批量发布产品节点参考间隔",null,null));
        return rows;
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
        var benchmarks=benchmarksForOrder(source);
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
                +"t.closed_at,t.closed_by_name,t.closed_note,"
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

    /**
     * 修改某类时间核实的**最新一条**记录（写错原因、补漏细节）。
     *
     * <p>为什么改「最新一条」而不是新增一条：整条链路（列表 badge、详情页「已记录原因」、导出「逾期原因」列、
     * 分析看板的原文榜）读的都是 {@code MAX(id)} 那条（见 {@code attachTimeReviews}）。过去只能追加，用户想改正
     * 错字也只能再录一遍，结果是历史里堆两三条同义记录、原文榜被旧写法污染，还看不出哪条才算数。
     *
     * <p>与原记录的**快照列（source_*）一律不动**：它们代表「这条核实当时看到的工单日期」。若工单日期在之后变过，
     * 这条记录本就应该失效、重新进入待核实，不能让改原因把失效状态一并抹掉。
     *
     * <p>状态机与新增一致；改原因不算重新核实，所以不刷新 created_at/created_by，只把改动前后原文写进审计。
     */
    public long updateTimeReview(long id, long noteId, TimeReviewRequest request, String key) {
        order(id); // 校验可见范围（与新增同一判据）
        if(request==null||request.kind()==null)
            throw new IllegalArgumentException("时间核实类型无效");
        boolean commitment="COMMITMENT".equals(request.kind());
        if(request.status()==null
                ||!(commitment?Set.of("ON_TIME","NEGOTIATED"):Set.of("OPEN","RESOLVED")).contains(request.status()))
            throw new IllegalArgumentException("时间核实结果无效");
        String reason=clean(request.reason(),500,"核实说明");
        if(reason.isBlank())throw new IllegalArgumentException("请填写核实原因或处理结果");
        String contact=clean(request.contactName(),100,"核实对象");
        LocalDate agreed=request.agreedDate();
        // 与新增同口径：协商交期只在 COMMITMENT/NEGOTIATED 下有意义，其余类型必须为空。
        if(commitment&&"NEGOTIATED".equals(request.status())&&agreed==null)
            throw new IllegalArgumentException("协商交期不能为空");
        if(!commitment||!"NEGOTIATED".equals(request.status()))agreed=null;
        var actor=actors.current();
        LocalDate savedAgreed=agreed;
        return Long.parseLong(idem.execute(actor,key,"production.tracking.time-review.update",
                Map.of("id",id,"noteId",noteId,"request",request),()->{
            var rows=jdbc.query("SELECT id,production_order_id,kind,status,reason,contact_name,agreed_date,"
                    +"next_followup_date,created_by_name FROM production_tracking_time_reviews "
                    +"WHERE id=? AND production_order_id=? FOR UPDATE",
                    (r,n)->row(r),noteId,id);
            if(rows.isEmpty())throw new IllegalArgumentException("该核实记录不存在，请刷新后重试");
            var before=rows.get(0);
            if(!request.kind().equals(before.get("kind")))
                throw new IllegalArgumentException("核实类型不匹配，请刷新后重试");
            jdbc.update("UPDATE production_tracking_time_reviews SET status=?,reason=?,contact_name=?,"
                    +"agreed_date=?,next_followup_date=? WHERE id=?",
                    request.status(),reason,contact.isBlank()?null:contact,
                    savedAgreed,request.nextFollowupDate(),noteId);
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_time_reviews",noteId,
                    "UPDATE",ErpChangeLogWriter.changes(
                            "orderId",id,"kind",before.get("kind"),
                            "status",String.valueOf(before.get("status"))+" → "+request.status(),
                            "reason",String.valueOf(before.get("reason"))+" → "+reason),
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

    /**
     * 跟单结案确认（与 {@link #changeDisposition} 同款的本地处置写库链路）：
     * <ul>
     *   <li>状态优先级：{@code stopped > closed > 节点派生}；停止任务不能再结案，结案任务不能再停止；</li>
     *   <li>结案：要求提供说明；未登记交付的工单强制要求写明（避免「没记录交付就结案」的盲点）；</li>
     *   <li>撤销结案：恢复按节点进度派生（completed 可能回退为 in_progress / pending），要求写明撤销原因；</li>
     *   <li>同一条跟单历史时间线 + 统一审计日志，与「停止」共用同一种落地方式。</li>
     * </ul>
     */
    public long changeClosure(long id,ClosureRequest request,String key) {
        var current=order(id);
        if(request==null||request.version()<0)throw new IllegalArgumentException("缺少跟单版本");
        String note=clean(request.note(),500,request.closed()?"结案说明":"撤销结案原因");
        if(note.isBlank())throw new IllegalArgumentException(request.closed()?"请填写结案说明":"请填写撤销结案的原因");
        if(current.get("stoppedAt")!=null)throw new IllegalArgumentException("已停止跟单的任务不能结案，请先恢复跟单");
        if(request.closed() && current.get("deliveredDate")==null && note.trim().length()<6)
            throw new IllegalArgumentException("该任务尚未登记交付（入库），结案说明请写明实际情况与核实结果（不少于 6 字）");
        var actor=actors.current();
        return Long.parseLong(idem.execute(actor,key,"production.tracking.closure",
                Map.of("id",id,"request",request),()->{
            jdbc.update("INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin) VALUES(?,'MANUAL')",id);
            jdbc.update("INSERT INTO production_tracking_state(production_order_id) VALUES(?) "
                    +"ON DUPLICATE KEY UPDATE production_order_id=production_order_id",id);
            var before=jdbc.queryForMap("SELECT * FROM production_tracking_state WHERE production_order_id=? FOR UPDATE",id);
            long version=((Number)before.get("version")).longValue();
            if(version!=request.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"该任务已由其他人更新，请刷新后重试");
            boolean alreadyClosed=before.get("closed_at")!=null;
            if(alreadyClosed==request.closed())throw new IllegalArgumentException(alreadyClosed?"该任务已结案":"该任务未结案");
            if(request.closed())jdbc.update("UPDATE production_tracking_state SET closed_at=NOW(),closed_by_name=?,"
                    +"closed_note=?,version=?,last_followup_at=NOW() WHERE production_order_id=?",
                    actor.name(),note,version+1,id);
            else jdbc.update("UPDATE production_tracking_state SET closed_at=NULL,closed_by_name=NULL,"
                    +"closed_note=NULL,version=?,last_followup_at=NOW() WHERE production_order_id=?",
                    version+1,id);
            var after=jdbc.queryForMap("SELECT * FROM production_tracking_state WHERE production_order_id=?",id);
            var milestones=jdbc.queryForList("SELECT stage_code,stage_status,completed_date,note "
                    +"FROM production_tracking_milestones WHERE production_order_id=? ORDER BY stage_code",id);
            String content=(request.closed()?"跟单结案确认：":"撤销跟单结案：")+note;
            jdbc.update("INSERT INTO production_tracking_entries(production_order_id,version,content,before_json,after_json,"
                    +"created_by_employee_id,created_by_name) VALUES(?,?,?,?,?,?,?)",id,version+1,content,
                    write(Map.of("state",before,"milestones",milestones)),
                    write(Map.of("state",after,"milestones",milestones)),actor.employeeId(),actor.name());
            long entryId=Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class));
            audit.write(ErpChangeLogWriter.of(actor,"product_archive.production_tracking_entries",entryId,"INSERT",
                    ErpChangeLogWriter.changes("orderId",id,"version",version+1,"closed",request.closed()),
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
