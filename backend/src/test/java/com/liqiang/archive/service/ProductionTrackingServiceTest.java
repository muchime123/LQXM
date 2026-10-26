package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.infra.Actor;
import com.liqiang.archive.infra.ActorResolver;
import com.liqiang.archive.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductionTrackingServiceTest {
    @Test
    void productDurationCandidatePercentilesDoNotAssumeAllOrdersAreValid() {
        assertEquals(new BigDecimal("2.5"),ProductionTrackingService.percentile(List.of(1L,2L,3L,10L),0.5));
        assertEquals(new BigDecimal("4.8"),ProductionTrackingService.percentile(List.of(1L,2L,3L,10L),0.75));
        assertNull(ProductionTrackingService.percentile(List.of(),0.5));
    }
    @Test
    void exportSourceIncludesTrackingRemarkAndProductionOrderFields() {
        String columns=ProductionTrackingService.LIST_COLUMNS;
        assertTrue(columns.contains("t.remark"),"导出可选的跟单备注必须随列表和导出查询返回");
        assertTrue(columns.contains("source_created_at"));
        assertTrue(columns.contains("source_order_remark"));
        assertTrue(columns.contains("source_optional_configuration"));
    }

    @Test
    void septemberOrdersAndPreviouslyTrackedOrdersAreVisible() {
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),null,null,new ObjectMapper(),allScope());
        var params=new ArrayList<>();

        String where=service.visible(params).toString();

        assertTrue(where.contains("p.external_source='H3YUN'"));
        // 跟单起点是一个时间点：提交时间必须落在九月后（申请日期早/晚都不改变这一点）。
        assertEquals(ProductionTrackingService.SCOPE_DATE,
                "(p.application_date>='2026-09-01' AND p.source_created_at>='2026-09-01')",
                "跟单范围必须是 AND 而不是 OR——OR 会让「八月提交、申请日期被填成九月」的历史单从侧门回来");
        assertTrue(where.contains("production_tracking_enrollments"),"手工加入跟单的历史单应保留可见："+where);
        assertTrue(where.contains("production_tracking_workbook_rows"),"表格覆盖的历史任务应保留可见："+where);
        // 真的跟过的历史单（节点 / 跟进条目 / 版本被推进）一个都不能丢，否则跟单记录会凭空失联。
        assertTrue(where.contains("FROM production_tracking_milestones"),"已登记节点的历史跟单应保留可见："+where);
        assertTrue(where.contains("FROM production_tracking_entries"),"已有跟进条目的历史跟单应保留可见："+where);
        assertTrue(where.contains("t2.version>0"),"版本被推进过的历史跟单应保留可见："+where);
        assertEquals(List.of(),params);
    }

    /**
     * 默认工作台口径＝**还没交付**。§ 旧实现把 `open` 翻成「跟单状态 ∈ pending/in_progress」，
     * 结果 9 月起已交付的 508 单只要交期一过就全变成「已超期」，工作台 63 条被 5119 条历史单淹没。
     */
    /**
     * 「已交付」的判据只允许一处定义，且必须是**入库优先、采购入库兜底**（LQ 2026-10-21 确认）。
     *
     * <p>这条测试防的是「有人图省事把它换成报工完成日」：真库九月起 569 单里，
     * 报工 507 / 入库里程碑 472 / 采购入库 438，其中 25 单已报工但未入库。
     * 换成 actual_completion_date 后这 25 单会从工作台消失，而列表、生产分析、周复盘
     * 仍按入库判它们未交付 —— 同一张单在两个页面给出相反结论。
     */
    @Test
    void deliveredMeansInboundFirstThenPurchaseReceipt() {
        assertEquals("COALESCE(ms.warehouse_in_date,ib.first_in)",ProductionTrackingService.DELIVERED_IN_DATE,
                "已交付＝跟单员填的入库里程碑优先、采购入库兜底；两者皆无才算未交付");
        assertFalse(ProductionTrackingService.DELIVERED_IN_DATE.contains("actual_completion_date"),
                "报工完成 ≠ 入库：车间报完工后货可能还在产线上等入库");
    }

    @Test
    void openTrackingStatusMeansNotYetDelivered() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var filtered=ProductionTrackingService.filterForTest(service,"open");
        String openWhere=(String)filtered.get("where");

        assertTrue(openWhere.contains("t.stopped_at IS NULL AND t.closed_at IS NULL AND "
                        +ProductionTrackingService.DELIVERED_IN_DATE+" IS NULL"),
                "`open` 必须落成「未停工、未结案、未交付」："+openWhere);
        assertEquals(Boolean.TRUE,filtered.get("needsInbound"),
                "`open` 引用了 DELIVERED_IN_DATE（含 ib.first_in），必须挂 INBOUND_JOIN，否则真库直接报 Unknown column");

        var all=ProductionTrackingService.filterForTest(service,"all");
        String allWhere=(String)all.get("where");
        assertFalse(allWhere.contains("t.closed_at IS NULL AND "+ProductionTrackingService.DELIVERED_IN_DATE),
                "`all` 不能带上工作台口径："+allWhere);
        assertEquals(Boolean.FALSE,all.get("needsInbound"),"`all` 不引用 ib，不该白挂 INBOUND_JOIN");
    }

    @Test
    void workbookCanTakeOverAnEmptyEnrollmentShellButNotRealFollowups() {
        assertTrue(ProductionTrackingService.HAS_FOLLOWUP_SQL.contains("t.version"));
        assertTrue(ProductionTrackingService.HAS_FOLLOWUP_SQL.contains("production_tracking_entries"),
                "是否已有跟单必须同时看版本和跟进历史，不能只看 state 行是否存在");
        assertTrue(ProductionTrackingService.IMPORT_STATE_SQL.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(ProductionTrackingService.IMPORT_STATE_SQL.contains(
                "forecast_finish_date=COALESCE(forecast_finish_date,VALUES(forecast_finish_date))"));
        assertTrue(ProductionTrackingService.IMPORT_STATE_SQL.contains(
                "assignee_employee_id=COALESCE(assignee_employee_id,VALUES(assignee_employee_id))"));
        assertTrue(ProductionTrackingService.IMPORT_STATE_SQL.contains("version=IF(version=0,1,version)"));
        // 【同步范围 2026-10-10】表格只同步 延期至/延期原因/节点完成日期；这四列必须从导入 SQL 里消失。
        for(String banned:List.of("planned_start_date","delivery_result","shipment_status","remark"))
            assertFalse(ProductionTrackingService.IMPORT_STATE_SQL.contains(banned),
                    "进度表不应再写入 "+banned+"："+ProductionTrackingService.IMPORT_STATE_SQL);
    }

    @Test
    void workbookExistingMeansRealFollowupNotMereEnrollment() {
        String sql=ProductionTrackingWorkbookImportService.TRACKED_ORDERS_SQL;

        assertTrue(sql.contains("production_tracking_state WHERE version>0"));
        assertTrue(sql.contains("UNION SELECT production_order_id FROM production_tracking_entries"));
        assertFalse(sql.contains("production_tracking_enrollments"),
                "空壳 enrollment 不应阻止 Excel 记录接管该任务："+sql);
    }

    @Test
    void missingDefaultAssigneeNoLongerBreaksEnrollmentOrImport() {
        var jdbc=mock(JdbcTemplate.class);
        doReturn(List.of("封口机")).when(jdbc).query(contains("SELECT production_line"),any(RowMapper.class),eq(7L));
        doReturn(List.of()).when(jdbc).query(contains("FROM employees"),any(RowMapper.class),eq("陈苏平"));
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        assertTrue(service.defaultAssignee(7L).isEmpty(),
                "默认跟单员改名或停用时必须软降级为未分配，不能抛出 500");
    }

    @Test
    void candidatesSearchCustomerAndStayInTrackingScope() {
        var jdbc=mock(JdbcTemplate.class);
        doReturn(List.of()).when(jdbc).query(anyString(),any(RowMapper.class),any(),any(),any());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.candidates("客户",null);

        verify(jdbc).query(argThat((String sql)->sql.contains("p.customer_name LIKE ?")
                        &&sql.contains("p.application_date>='2026-09-01'")
                        &&sql.contains("p.source_created_at>='2026-09-01'")
                        &&sql.contains(ProductionTrackingService.NEVER_TRACKED)
                        &&sql.contains("FROM production_tracking_milestones")
                        &&sql.contains("NOT EXISTS (SELECT 1 FROM production_tracking_entries")
                        &&!sql.contains("production_tracking_enrollments")),
                any(RowMapper.class),eq("%客户%"),eq("%客户%"),eq("%客户%"));
    }

    @Test
    void listAndDetailShareSeparateTrackingAndReminderRules() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var sqls=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sqls.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"active",false,null,1,30);
        try { service.detail(7L); } catch (IllegalArgumentException expected) { /* 详情在无数据时抛错，SQL 仍需核对 */ }

        var captured=sqls.getAllValues();
        assertFalse(captured.isEmpty());
        assertTrue(captured.stream().allMatch(sql->sql.contains("tracking_status")&&sql.contains("reminder")
                        &&sql.contains("source_status")&&!sql.contains(" attention,")),
                "列表与详情必须分别返回任务单状态、跟单状态和提醒："+captured);
    }

    @Test
    void deadlinePrefersWorkshopExpectedDateWithoutSourceCompletionOverride() {
        String attention=ProductionTrackingService.REMINDER_CASE;

        assertTrue(attention.contains(ProductionTrackingService.DEADLINE)
                && ProductionTrackingService.DEADLINE.contains("p.required_completion_date>=p.application_date"),
                "跟单截止日优先取车间预计，业务员要求早于申请日时不得回落到错误日期："+attention);
        assertFalse(attention.contains("warehousing_completed")||attention.contains("actual_completion_date")
                ||attention.contains("production_completed"),"源工单完工入库不得覆盖跟单状态："+attention);
        assertTrue(ProductionTrackingService.TRACKING_STATUS_CASE.contains(ProductionTrackingService.TRACKING_COMPLETE),
                "跟单完成必须由本地节点计数决定");
        // 列表 SQL 的排序与延期天数也要跟随 deadline 口径，由 list SQL 拼装检查。
        assertTrue(ProductionTrackingService.REMINDER_CASE.contains(ProductionTrackingService.DEADLINE),
                "提醒 CASE 必须引用统一 DEADLINE 表达式");
    }

    @Test
    void completionDependsOnlyOnApplicableTrackingMilestones() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"active",false,null,1,30);
        service.list(null,null,"complete",false,null,1,30);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc,atLeast(2)).queryForObject(sql.capture(),eq(Long.class),any(Object[].class));
        var counts=sql.getAllValues();
        // 【2026-10-21 口径变更】「active」原先翻成跟单状态 IN ('pending','in_progress')，
        // 现已改为**未交付**（未停工/未结案/无交付日）。旧断言等于把「已交付但没过跟单节点」的单
        // 也算进工作台，而九月那批 571 单里 508 单已交付 —— 工作台 63 条被 5119 条历史单淹没。
        assertTrue(counts.get(0).contains("t.closed_at IS NULL AND "+ProductionTrackingService.DELIVERED_IN_DATE+" IS NULL"),
                "进行中列表＝还没交付的任务："+counts.get(0));
        assertTrue(counts.get(1).contains("IN ('completed')"),
                "已完成筛选只依据跟单状态");
        assertTrue(counts.stream().allMatch(value->value.contains("stage_status='DONE'")
                &&value.contains("sealer_done_count") && value.contains("machine_done_count")));
        assertTrue(ProductionTrackingService.TRACKING_STATUS_CASE.contains("THEN 'completed'"));
        assertFalse(ProductionTrackingService.TRACKING_STATUS_CASE.contains("p.status"));
        assertFalse(ProductionTrackingService.REMINDER_CASE.contains("p.status"));
    }

    @Test
    void sourceStatusTrackingStatusAndReminderAreIndependent() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"pending","overdue","已生效",false,null,1,30);
        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),any(Object[].class));
        String sql=countSql.getValue();
        assertTrue(sql.contains("IN ('pending')") && sql.contains(ProductionTrackingService.REMINDER_CASE+"=?"));
        assertTrue(sql.contains("p.status=?"));
        assertFalse(ProductionTrackingService.TRACKING_STATUS_CASE.contains("p.status"));
        assertFalse(ProductionTrackingService.REMINDER_CASE.contains("p.status"));
    }

    @Test
    void timeAttentionFilterUsesTheSamePredicateForCountAndPage() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"all","time_attention","",false,"","2026-09",1,30);

        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),any(Object[].class));
        var pageSql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(pageSql.capture(),any(RowMapper.class),any(Object[].class));
        assertTrue(countSql.getValue().contains(ProductionTrackingService.TIME_ATTENTION));
        assertTrue(pageSql.getValue().contains(ProductionTrackingService.TIME_ATTENTION));
        assertTrue(countSql.getValue().contains(ProductionTrackingService.CREATED_MONTH_DATE));
        assertTrue(ProductionTrackingService.TIME_ATTENTION.contains("p.application_date<DATE(p.source_created_at)"));
        assertTrue(ProductionTrackingService.TIME_ATTENTION.contains("n.kind='SOURCE_DATE'"),
                "待核实汇总应排除源日期快照已核实且未变化的任务");
        assertTrue(ProductionTrackingService.TIME_ATTENTION.contains("n.source_created_at<=>p.source_created_at"));
        assertTrue(ProductionTrackingService.TIME_ATTENTION.contains(ProductionTrackingService.TIME_REVIEW_DUE));
    }

    @Test
    void everyTimeCategoryHasItsOwnServerSidePredicate() {
        var predicates=Map.of(
                "time_source",ProductionTrackingService.TIME_SOURCE,
                "time_production",ProductionTrackingService.TIME_PRODUCTION,
                "time_application",ProductionTrackingService.TIME_SOURCE_APPLICATION,
                "time_required",ProductionTrackingService.TIME_SOURCE_REQUIRED,
                "time_acceptance",ProductionTrackingService.TIME_ACCEPTANCE,
                "time_commitment",ProductionTrackingService.TIME_COMMITMENT,
                "time_reporting",ProductionTrackingService.TIME_REPORTING,
                "time_inbound_reporting",ProductionTrackingService.TIME_INBOUND_REPORTING_ATTENTION,
                "time_overdue",ProductionTrackingService.TIME_OVERDUE,
                "time_due",ProductionTrackingService.TIME_REVIEW_DUE);
        for(var entry:predicates.entrySet()) {
            var jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
            when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
            var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());
            service.list(null,null,"all",entry.getKey(),"",false,"",1,30);
            var sql=org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jdbc).queryForObject(sql.capture(),eq(Long.class),any(Object[].class));
            assertTrue(sql.getValue().contains(entry.getValue()),entry.getKey());
        }
    }

    /** 统计卡与同名筛选项必须共用同一段条件 —— 卡片数字必须等于点进去的条数。 */
    @Test
    void summaryCardsAndListFiltersShareOnePredicate() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(),any(Object[].class))).thenReturn(new java.util.LinkedHashMap<String,Object>());
        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.summary();
        var summarySql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForMap(summarySql.capture(),any(Object[].class));
        String cards=summarySql.getValue();
        for(String key:List.of("untracked","due","overdue","delayed"))
            assertTrue(cards.contains(key),"统计卡必须含 "+key+"："+cards);
        assertFalse(cards.contains("warehousing_completed")||cards.contains("actual_completion_date"),
                "源工单完工不得掩盖未完成跟单的超期："+cards);

        service.list(null,null,"overdue",false,null,1,30);
        var filterSql=countSql.getAllValues();
        assertEquals(1,filterSql.size());
        assertTrue(filterSql.get(0).contains(ProductionTrackingService.DEADLINE));
        assertFalse(filterSql.get(0).contains("actual_completion_date"));
        assertThrows(IllegalArgumentException.class,()->service.list(null,null,"nope",false,null,1,30),
                "白名单外的筛选项必须报错，不能静默返回全量");
    }

    @Test
    void workshopStagePolicyIsTheSingleJavaSource() {
        assertEquals(8,ProductionTrackingPolicy.stageCodes("全自动气调机").size());
        assertEquals(6,ProductionTrackingPolicy.stageCodes("封口机").size());
        assertEquals(List.of("MATERIAL","MOLD","OXIDATION","ASSEMBLY","WAREHOUSE","WOODEN_CRATE"),
                ProductionTrackingPolicy.stageCodes("封口机"));
        assertEquals(4,ProductionTrackingPolicy.requiredStages("封口机").size());
        assertEquals(7,ProductionTrackingPolicy.requiredStages("全自动气调机").size());
        assertTrue(ProductionTrackingPolicy.isOptionalStage("OXIDATION"));
        assertTrue(ProductionTrackingPolicy.isOptionalStage("WOODEN_CRATE"));
        assertFalse(ProductionTrackingPolicy.isOptionalStage("ASSEMBLY"));
        assertEquals("模具",ProductionTrackingPolicy.stageLabel("MOLD",false));
        assertEquals("模具生产",ProductionTrackingPolicy.stageLabel("MOLD",true));
        assertEquals("氧化/喷特氟龙",ProductionTrackingPolicy.stageLabel("OXIDATION",true));
        assertEquals("木箱",ProductionTrackingPolicy.stageLabel("WOODEN_CRATE",true));
        assertTrue(ProductionTrackingPolicy.configuredOptionalStage("WOODEN_CRATE","出口木箱"));
        assertFalse(ProductionTrackingPolicy.configuredOptionalStage("WOODEN_CRATE","不含木箱"));
        assertTrue(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","喷特氟龙"));
        assertFalse(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","无需氧化"));
        // 完成判据的分母＝必经节点数，按需工序不计入，否则封口机会永远差一个节点完成不了。
        assertEquals("CASE WHEN p.production_line LIKE '%封口%' THEN 4 ELSE 7 END",
                ProductionTrackingPolicy.stageTotalCase("p.production_line"));
        assertEquals("陈苏平",ProductionTrackingPolicy.defaultAssigneeName("封口机"));
        assertEquals("胡健",ProductionTrackingPolicy.defaultAssigneeName("半自动气调机"));
    }

    /** 进度表阶段列的表头写法必须能反查回节点码，否则模板一改列序就会静默读错列。 */
    @Test
    void workbookStageHeadersMapBackToStageCodes() {
        assertEquals("MATERIAL",ProductionTrackingPolicy.stageCodeOfLabel("备料"));
        assertEquals("MOLD",ProductionTrackingPolicy.stageCodeOfLabel("模具生产"));
        assertEquals("MOLD",ProductionTrackingPolicy.stageCodeOfLabel("模具"));
        assertEquals("OXIDATION",ProductionTrackingPolicy.stageCodeOfLabel("氧化/喷特氟龙"));
        assertEquals("WOODEN_CRATE",ProductionTrackingPolicy.stageCodeOfLabel("木箱"));
        assertEquals("ASSEMBLY",ProductionTrackingPolicy.stageCodeOfLabel("组装"));
        assertEquals("WAREHOUSE",ProductionTrackingPolicy.stageCodeOfLabel("入库"));
        assertEquals("SHEET_METAL",ProductionTrackingPolicy.stageCodeOfLabel("钣金"));
        assertEquals("TESTING",ProductionTrackingPolicy.stageCodeOfLabel("调试"));
        assertEquals("DRAWING",ProductionTrackingPolicy.stageCodeOfLabel("画图"));
        assertNull(ProductionTrackingPolicy.stageCodeOfLabel("生产进度"));
        assertNull(ProductionTrackingPolicy.stageCodeOfLabel(null));
    }

    /** 详情进度分母＝必经节点 + 本单适用的按需工序；必经节点不能被当成「按需」重复计入。 */
    @Test
    void stageTotalCountsRequiredPlusApplicableOptionalOnly() {
        assertEquals(5,ProductionTrackingService.stageTotal("封口机车间",sealerMilestones(true)),
                "走了氧化的封口机单：4 个必经 + 1 个按需");
        assertEquals(4,ProductionTrackingService.stageTotal("封口机车间",sealerMilestones(false)),
                "没走氧化的封口机单不能被算成 5 个节点");
        var withCrate=new ArrayList<>(sealerMilestones(false));
        withCrate.stream().filter(row->"WOODEN_CRATE".equals(row.get("code")))
                .forEach(row->row.put("applicable",true));
        assertEquals(5,ProductionTrackingService.stageTotal("封口机车间",withCrate),
                "配置木箱时才把木箱计入节点总数");
        assertEquals(7,ProductionTrackingService.stageTotal("全自动气调机",List.of()),
                "气调机没有按需工序，分母恒为 7");
    }

    private static List<Map<String,Object>> sealerMilestones(boolean oxidationApplicable) {
        var rows=new ArrayList<Map<String,Object>>();
        for(String code:ProductionTrackingPolicy.stageCodes("封口机车间")){
            var row=new java.util.LinkedHashMap<String,Object>();
            boolean optional=ProductionTrackingPolicy.isOptionalStage(code);
            row.put("code",code);row.put("optional",optional);
            row.put("applicable",!optional||("OXIDATION".equals(code)&&oxidationApplicable));
            rows.add(row);
        }
        return rows;
    }

    /** 按需工序（氧化/喷特氟龙）只有在「本单适用」时才进分子分母，不适用时不能被算成差一个节点。 */
    @Test
    void optionalStageCountsOnlyWhenThisOrderEnrolledIt() {
        assertTrue(ProductionTrackingService.TRACKING_FROM.contains("optional_count")
                        &&ProductionTrackingService.TRACKING_FROM.contains("optional_done_count"),
                "按需工序的「是否适用」与「是否完成」必须共用一个派生表："+ProductionTrackingService.TRACKING_FROM);
        assertTrue(ProductionTrackingService.STAGE_DONE_CASE.contains("optional_done_count"),
                "分子要含本单确实做完的按需工序："+ProductionTrackingService.STAGE_DONE_CASE);
        assertTrue(ProductionTrackingService.TRACKING_COMPLETE.contains("optional_count"),
                "分母要含本单适用的按需工序："+ProductionTrackingService.TRACKING_COMPLETE);
        assertTrue(ProductionTrackingService.TRACKING_FROM.contains(
                        "stage_code IN ('MATERIAL','MOLD','ASSEMBLY','WAREHOUSE') AND stage_status='DONE'"),
                "封口机的必经节点计数不能把氧化算进去："+ProductionTrackingService.TRACKING_FROM);
    }

    /** 跟单状态＝「有没有跟单节点记录」：节点有记录＝跟单中，一个都没有＝待跟单。 */
    @Test
    void trackingStatusCountsMilestoneRowsNotStateVersion() {
        assertTrue(ProductionTrackingService.TRACKING_FROM.contains("COUNT(*) node_count"),
                "「有没有跟单节点」必须从里程碑派生表数出来："+ProductionTrackingService.TRACKING_FROM);
        assertEquals("COALESCE(ms.node_count,0)=0",ProductionTrackingService.NEVER_TRACKED);
        assertFalse(ProductionTrackingService.NEVER_TRACKED.contains("version"),
                "不能用 state.version 代替节点记录：进度表导入「整行没填完成日期」时 version 已是 1 却零节点："
                        +ProductionTrackingService.NEVER_TRACKED);
        assertTrue(ProductionTrackingService.TRACKING_STATUS_CASE.startsWith("CASE WHEN t.stopped_at IS NOT NULL THEN 'stopped'"),
                "已停止的本地跟单必须优先于节点记录判定："+ProductionTrackingService.TRACKING_STATUS_CASE);
        assertTrue(ProductionTrackingService.TRACKING_STATUS_CASE.contains("WHEN "+ProductionTrackingService.NEVER_TRACKED+" THEN 'pending'"),
                "非停止任务继续用节点记录判「待跟单」："+ProductionTrackingService.TRACKING_STATUS_CASE);
        assertTrue(ProductionTrackingService.REMINDER_CASE.contains(ProductionTrackingService.NEVER_TRACKED),
                "提醒的「已完成即无提醒」分支也要用同一判据："+ProductionTrackingService.REMINDER_CASE);
    }

    @Test
    void followupWithoutContentAutoDescribesStageChanges() {
        var before=List.<Map<String,Object>>of(
                row("MATERIAL","PENDING",null,null),
                row("MOLD","IN_PROGRESS",null,null));
        var after=List.of(
                new ProductionTrackingService.Milestone("MATERIAL","DONE",java.time.LocalDate.parse("2026-10-08"),null,null,null),
                new ProductionTrackingService.Milestone("MOLD","IN_PROGRESS",null,"客户确认后继续",null,null));

        String text=ProductionTrackingService.describeStageChanges(false,after,before);

        assertTrue(text.startsWith("更新节点："), "无内容的跟进必须自动生成节点描述："+text);
        assertTrue(text.contains("备料→已完成 2026-10-08"),"状态+日期变化要写进描述："+text);
        assertTrue(text.contains("模具（备注更新）"),"仅备注变化也要可追溯："+text);
        assertEquals("",ProductionTrackingService.describeStageChanges(false,List.of(),before),
                "没有节点变化时返回空，由调用方拒绝保存");
    }

    @Test
    void supervisorScopeSeesAllFollowupsWithoutExtraFilter() {
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),null,null,new ObjectMapper(),allScope());
        var params=new ArrayList<>();

        String where=service.visible(params).toString();

        assertFalse(where.contains("assignee_employee_id"),
                "跟单主管（MANUFACTURING 范围=ALL）必须看全部跟单，不得加归属过滤："+where);
        assertEquals(List.of(),params,"已纳入的全部跟单不需要日期参数");
    }

    @Test
    void trackerScopeSeesOnlyWorkshopsAssignedToThem() {
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),null,null,new ObjectMapper(),tracker(160L));
        var params=new ArrayList<>();

        String where=service.visible(params).toString();

        assertTrue(where.contains("assigned.id=?"),
                "跟单员只看按车间规则归给自己的任务，包括还没有跟单状态行的工单："+where);
        assertFalse(where.contains("t.assignee_employee_id IS NULL"),"不应再开放未分配任务："+where);
        assertEquals(List.of(160L),params,"跟单员视角只需本人员工 ID");
    }

    @Test
    void assignmentIsDerivedFromWorkshopEvenWithoutTrackingState() {
        assertTrue(ProductionTrackingService.TRACKING_FROM.contains("assigned.name=CASE WHEN p.production_line LIKE '%封口%'"));
        assertTrue(ProductionTrackingService.TRACKING_FROM.contains("'陈苏平' ELSE '胡健'"));
        assertTrue(ProductionTrackingService.ASSIGNEE_NAME.contains("'陈苏平' ELSE '胡健'"));
    }

    @Test
    void listUsesWorkshopAssignmentBeforeAnyFollowupExists() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"all","all","",false,"",1,30);

        verify(jdbc).query(argThat((String sql)->sql.contains("LEFT JOIN production_tracking_state t")
                        &&sql.contains("assigned.id assignee_employee_id")
                        &&sql.contains("CASE WHEN p.production_line LIKE '%封口%' THEN '陈苏平' ELSE '胡健' END assignee_name")
                        &&!sql.contains("t.assignee_employee_id,t.assignee_name")),
                any(RowMapper.class),any(Object[].class));
    }

    /** 跟单主管 / 管理员：MANUFACTURING 数据范围 = ALL，看全部跟单。 */
    /**
     * 基准配置矩阵必须列出 Policy 的全部节点（含尚未配置的），否则管理员在界面上根本看不到
     * 机器车间那 7 个待配节点。段标签首段锚点是「计划开工」，其余取上一节点。
     */
    @Test
    void benchmarkMatrixListsEveryPolicyStageIncludingUnconfigured() {
        var saved=new java.util.HashMap<String,Map<String,Object>>();
        saved.put("SEALER/MOLD",Map.of("typicalDays",new BigDecimal("3.0"),"sampleCount",381,
                "baselineNote","中位数：备料完成→模具生产完成","computedAt",java.time.LocalDate.of(2026,10,10)));

        var sealer=ProductionTrackingService.benchmarkWorkshop("SEALER","封口机",saved);
        @SuppressWarnings("unchecked")
        var sealerStages=(List<Map<String,Object>>)sealer.get("stages");
        assertEquals(ProductionTrackingPolicy.SEALER_STAGES.size(),sealerStages.size(),
                "含氧化按需工序在内的全部节点都要列出："+sealerStages);
        assertEquals("计划开工 → 备料",sealerStages.get(0).get("intervalLabel"));
        assertEquals("备料 → 模具生产",sealerStages.get(1).get("intervalLabel"));
        assertEquals(new BigDecimal("3.0"),sealerStages.get(1).get("typicalDays"),"表里有值的节点要带出天数");
        assertNull(sealerStages.get(0).get("typicalDays"),"表里没有的节点显示为未配置");
        assertEquals(Boolean.TRUE,sealerStages.get(2).get("optionalStage"),"氧化是按需工序");

        var machine=ProductionTrackingService.benchmarkWorkshop("MACHINE","机器车间",saved);
        @SuppressWarnings("unchecked")
        var machineStages=(List<Map<String,Object>>)machine.get("stages");
        assertEquals(ProductionTrackingPolicy.MACHINE_STAGES.size(),machineStages.size(),
                "机器车间 7 个节点即使一条基准都没配也必须全部列出："+machineStages);
        assertTrue(machineStages.stream().allMatch(stage->stage.get("typicalDays")==null));
    }

    /** 保存校验：车间类别、节点归属、天数范围必须在写库前拦下，否则脏配置会污染所有在制单的参考日期。 */
    @Test
    void savingBenchmarksRejectsInvalidWorkshopStageAndRange() {
        var actors=mock(ActorResolver.class);
        when(actors.current()).thenReturn(new Actor(1L,"测试管理员"));
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),actors,null,new ObjectMapper(),allScope());

        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveNodeBenchmarks(List.of()))
                .getMessage().contains("没有要保存"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveNodeBenchmarks(
                List.of(new ProductionTrackingService.BenchmarkSave("SEALER","DRAWING",new BigDecimal("1.0")))))
                .getMessage().contains("不属于"),"封口机没有画图节点");
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveNodeBenchmarks(
                List.of(new ProductionTrackingService.BenchmarkSave("OTHER","MATERIAL",new BigDecimal("1.0")))))
                .getMessage().contains("车间类别"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveNodeBenchmarks(
                List.of(new ProductionTrackingService.BenchmarkSave("SEALER","MATERIAL",new BigDecimal("61")))))
                .getMessage().contains("0~60"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveNodeBenchmarks(
                List.of(new ProductionTrackingService.BenchmarkSave("MACHINE","TESTING",new BigDecimal("-1")))))
                .getMessage().contains("0~60"));
    }

    /**
     * 默认列矩阵必须列出**全部**列（含从未配置过的），否则管理员在界面上看不到、也配不上。
     * 未配置的列回落「代码兜底」：改造前那群隐藏列照样隐藏、其余可见。
     */
    @Test
    void columnDefaultMatrixListsEveryColumnAndFallsBackWhenUnconfigured() {
        var saved=Map.of("workshop",false,"salesTrace",true);
        var jdbc=mock(JdbcTemplate.class);
        doAnswer(inv->{
            org.springframework.jdbc.core.RowCallbackHandler handler=inv.getArgument(1);
            for(var entry:saved.entrySet()){
                java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(entry.getKey());
                when(rs.getBoolean(2)).thenReturn(entry.getValue());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.RowCallbackHandler.class));

        var service=new ProductionTrackingService(jdbc,mock(ActorResolver.class),null,new ObjectMapper(),allScope());
        var matrix=service.columnDefaultMatrix();

        @SuppressWarnings("unchecked")
        var columns=(List<Map<String,Object>>)matrix.get("columns");
        assertEquals(ProductionTrackingService.LIST_COLUMN_KEYS.size(),columns.size(),"全部列都要列出："+columns);
        var byKey=new java.util.HashMap<String,Map<String,Object>>();
        columns.forEach(column->byKey.put((String)column.get("columnKey"),column));

        assertEquals(Boolean.FALSE,byKey.get("workshop").get("visible"),"配过且为隐藏：按配置");
        assertEquals(Boolean.TRUE,byKey.get("workshop").get("configured"));
        assertEquals(Boolean.TRUE,byKey.get("salesTrace").get("visible"),"配过且为显示：盖过代码兜底的隐藏");
        assertEquals(Boolean.TRUE,byKey.get("salesTrace").get("configured"));
        assertEquals(Boolean.FALSE,byKey.get("trackingStatus").get("visible"),"没配过：回落代码兜底（该列隐藏）");
        assertEquals(Boolean.FALSE,byKey.get("trackingStatus").get("configured"));
        // ⚠️ 必须是白名单里真实存在的列 key。曾误写列表里没有的 quantity，
        // HashMap.get 返回 null ⇒ 下一行 NPE；编译期与类型检查都看不出来，只有跑到这条断言才暴露。
        assertEquals(Boolean.TRUE,byKey.get("salesperson").get("visible"),"没配过且不在兜底隐藏名单：可见");
        assertEquals(Boolean.FALSE,byKey.get("salesperson").get("configured"));
    }

    /** 保存校验：列标识非法 / 重复 / 缺显示状态必须在写库前拦下。 */
    @Test
    void savingColumnDefaultsRejectsInvalidDuplicateAndMissingVisibility() {
        var actors=mock(ActorResolver.class);
        when(actors.current()).thenReturn(new Actor(1L,"测试管理员"));
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),actors,null,new ObjectMapper(),allScope());

        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveColumnDefaults(List.of()))
                .getMessage().contains("没有要保存"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveColumnDefaults(
                List.of(new ProductionTrackingService.ColumnDefaultSave("notAColumn",true))))
                .getMessage().contains("列标识无效"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveColumnDefaults(
                List.of(new ProductionTrackingService.ColumnDefaultSave("workshop",true),
                        new ProductionTrackingService.ColumnDefaultSave("workshop",false))))
                .getMessage().contains("重复"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->service.saveColumnDefaults(
                List.of(new ProductionTrackingService.ColumnDefaultSave("workshop",null))))
                .getMessage().contains("显示状态缺失"));
    }

    /**
     * 全量覆盖语义：请求里没给的列**删掉显式配置、回落代码兜底**。
     * 这样「全部显示」＝16 行 visible=1，与「未配置」可区分，不会出现
     * 「管理员选了全显示、没勾过的员工下次打开又变回隐藏」。
     */
    @Test
    void savingColumnDefaultsRemovesExplicitConfigForOmittedColumns() {
        var actors=mock(ActorResolver.class);
        when(actors.current()).thenReturn(new Actor(1L,"测试管理员"));
        var jdbc=mock(JdbcTemplate.class);
        doAnswer(inv->{
            org.springframework.jdbc.core.RowCallbackHandler handler=inv.getArgument(1);
            for(var key:List.of("workshop","assignee")){
                java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(key);
                when(rs.getBoolean(2)).thenReturn(false);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.RowCallbackHandler.class));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(7L));
        var service=new ProductionTrackingService(jdbc,actors,null, new ObjectMapper(),allScope());

        var result=service.saveColumnDefaults(
                List.of(new ProductionTrackingService.ColumnDefaultSave("assignee",true)));

        var sqlCaptor=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc,times(2)).update(sqlCaptor.capture(),any(Object[].class));
        var sqls=sqlCaptor.getAllValues();
        assertEquals(1,sqls.stream().filter(sql->sql.startsWith("DELETE FROM production_tracking_column_defaults")).count(),
                "未提交且原有显式配置的列要被删除："+sqls);
        assertEquals(1,sqls.stream().filter(sql->sql.startsWith("INSERT INTO production_tracking_column_defaults")).count(),
                "提交的列要 upsert："+sqls);
        assertEquals(2,((Number)result.get("changed")).intValue(),"删 1 行 + 改 1 行");

    }

    /**
     * 白名单是前后端的契约：18 列、不重复、覆盖兜底隐藏名单（前端契约测试同时比对两侧清单）。
     * <p>⚠️ 数量写死是刻意的：列清单增删时这里必须跟着改，等于给「悄悄加了一列」上一道闸。
     * 跨端是否同集合由前端 {@code trackingListColumns.test.ts} 直读这两个常量比对。
     */
    @Test
    void listColumnKeysAreDistinctAndCoverFallbackHidden() {
        var keys=ProductionTrackingService.LIST_COLUMN_KEYS;
        assertEquals(18,keys.size(),"改动列清单后同步本数字："+keys);
        assertEquals(keys.size(),new java.util.HashSet<>(keys).size(),"不得有重复列");
        assertEquals(6,ProductionTrackingService.LIST_COLUMN_FALLBACK_HIDDEN.size());
        assertTrue(keys.containsAll(ProductionTrackingService.LIST_COLUMN_FALLBACK_HIDDEN),
                "兜底隐藏的列必须是合法列："+ProductionTrackingService.LIST_COLUMN_FALLBACK_HIDDEN);
    }

    /**
     * 看板聚合列必须容忍 NULL。`SUM(条件A AND 条件B)` 在某车间整组行都求值为 NULL 时，SUM 整体返回 NULL
     * （真库「称重机车间」只有一单、且交付日期与交付基准都为空时即触发：`NULL<CURDATE()` 得 NULL）。
     * 直接 `.longValue()` 会抛 NPE 把整个看板打成 500——这里钉死「NULL 记 0」。
     */
    @Test
    void dashboardTreatsNullAggregatesAsZero() {
        var jdbc=mock(JdbcTemplate.class);
        var row=new java.util.LinkedHashMap<String,Object>();
        row.put("workshop","称重机车间");
        row.put("total",1L);
        row.put("active",1L);
        row.put("overdueOpen",null);
        row.put("dueSoon",null);
        row.put("noBaseline",1L);
        row.put("onTime",0L);
        row.put("deliveredOverdue",0L);
        row.put("deliveredNoBaseline",0L);
        row.put("inboundBeforeReport",null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        doReturn(List.of(row)).when(jdbc).queryForList(sql.capture(),any(Object[].class));
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var result=service.dashboard();

        var totals=(Map<?,?>)result.get("totals");
        assertEquals(0L,totals.get("overdueOpen"),"NULL 聚合应记 0，而不是抛 NPE 打挂看板");
        assertEquals(0L,totals.get("dueSoon"));
        assertEquals(0L,totals.get("inboundBeforeReport"));
        assertEquals(1L,totals.get("active"),"非空聚合照常累加");
        assertEquals(1L,totals.get("noBaseline"));
        assertEquals(List.of(row),(List<?>)result.get("workshops"));
        // SQL 侧也要兜底：车间明细行直接展示 SUM 原值，若为 NULL 会与「总计卡 0」不一致。
        assertTrue(sql.getValue().contains("COALESCE(SUM(")&&sql.getValue().contains(") overdueOpen")
                        &&sql.getValue().contains(") dueSoon"),
                "看板 SQL 的 overdueOpen/dueSoon 必须 COALESCE 兜底："+sql.getValue());
    }

    private static CurrentUserService allScope() {
        var users=mock(CurrentUserService.class);
        when(users.scopeOf("MANUFACTURING")).thenReturn("ALL");
        return users;
    }

    /** 跟单员：MANUFACTURING 数据范围 = SELF，只看按车间规则分给本人的任务。 */
    private static CurrentUserService tracker(long employeeId) {
        var users=mock(CurrentUserService.class);
        when(users.scopeOf("MANUFACTURING")).thenReturn("SELF");
        when(users.current()).thenReturn(new CurrentUserService.CurrentUser(employeeId,"跟单员","办公室",
                java.util.Set.of("PRODUCTION_TRACER"),java.util.Set.of("manufacturing.read.self"),"SELF",
                false,"COMPAT",null,null,java.util.Map.of()));
        return users;
    }

    @Test
    void forecastFinishDateIsOptionalAndOnlyLateDatesFlagDelayed() {
        assertFalse(ProductionTrackingService.REMINDER_CASE.contains("forecast_finish_date<="),
                "延期至日期可早于或等于截止日，不应被当作非法延期拦截");
        assertTrue(ProductionTrackingService.REMINDER_CASE.contains("t.forecast_finish_date>"+ProductionTrackingService.DEADLINE),
                "只有延期至晚于跟单截止日时才提示预计延期");
    }

    @Test
    void forecastOnlyFollowupIsDescribedAutomatically() {
        var before=new java.util.LinkedHashMap<String,Object>();
        before.put("forecast_finish_date","2026-10-15");
        var changed=new ProductionTrackingService.State(null,java.time.LocalDate.parse("2026-10-20"),
                null,null,null,null,null,null,null);

        String text=ProductionTrackingService.describeStateChange(before,changed);

        assertTrue(text.contains("延期至→2026-10-20"),"只改延期至日期也要能形成跟进记录："+text);
        assertEquals("",ProductionTrackingService.describeStateChange(before,new ProductionTrackingService.State(
                null,java.time.LocalDate.parse("2026-10-15"),null,null,null,null,null,null,null)),
                "日期没变时不应凭空生成内容");
        assertTrue(ProductionTrackingService.describeStateChange(before,new ProductionTrackingService.State(
                null,null,null,null,null,null,null,null,null)).contains("延期至→清空"),
                "清空延期至日期也要能形成跟进记录");
    }

    @Test
    void delayReasonChangeIsDescribedAutomatically() {
        var before=new java.util.LinkedHashMap<String,Object>();
        before.put("forecast_finish_date","2026-10-20");
        before.put("delay_reason","模具返修");
        var sameForecast=java.time.LocalDate.parse("2026-10-20");

        // 只改延期原因：生成可追溯描述（原因走 COALESCE 只能覆盖不能清空）。
        String changed=ProductionTrackingService.describeStateChange(before,new ProductionTrackingService.State(
                null,sameForecast,null,"模具返修完成，等待上机",null,null,null,null,null));
        assertTrue(changed.contains("延期原因→模具返修完成，等待上机")&&!changed.contains("延期至→"),
                "只改原因时不要把没变的延期至日期写进描述："+changed);

        // 什么都没变：不生成描述。
        assertEquals("",ProductionTrackingService.describeStateChange(before,new ProductionTrackingService.State(
                null,sameForecast,null,"模具返修",null,null,null,null,null)),"没变的字段不应生成描述");
    }

    /**
     * 生产任务单自身没有入库时间：它只能从采购入库明细的「选择生产任务单」关联过来。
     * 该关联只允许出现在列表/详情的 SELECT 列表里；统计卡与筛选 COUNT(*) 只有在谓词需要引用交付日
     * （如 time_overdue）时才允许挂同一份入库派生表，且那份派生表必须按工单号聚合（1:1）、不改变行数，
     * 否则「跟单口径」会被采购数据带着漂移（卡片数字与列表条数也会对不上）。
     */
    @Test
    void inboundDateIsDerivedFromPurchaseReceiptsAndNeverInflatesCounts() {
        var jdbc=mock(JdbcTemplate.class);
        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var rowSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(rowSql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"active",false,null,1,30);
        try { service.detail(7L); } catch (IllegalArgumentException expected) { /* 详情无数据抛错，SQL 仍需核对 */ }

        assertEquals(1,countSql.getAllValues().size(),"统计/筛选只该有一次 COUNT 查询");
        if(countSql.getValue().contains("purchase_receipt")) assertTrue(
                countSql.getValue().contains("GROUP BY i.production_order_no"),
                "COUNT 若挂入库派生表，必须是按工单号聚合的 1:1 派生表，否则会重复计数："+countSql.getValue());
        assertTrue(rowSql.getAllValues().stream().allMatch(sql->sql.contains("purchase_receipt_items i")
                        &&sql.contains("purchase_receipts r")&&sql.contains("ib.first_in inbound_date")),
                "列表与详情都要带出入库时间："+rowSql.getAllValues());
        assertFalse(ProductionTrackingService.TRACKING_FROM.contains("purchase_receipt"),
                "入库关联不能并入共用 FROM，否则统计卡与筛选会跟着变："+ProductionTrackingService.TRACKING_FROM);
        assertTrue(rowSql.getAllValues().stream()
                        .noneMatch(sql->sql.contains(", FROM")||sql.contains(",, ")||sql.contains(",,,")),
                "SELECT 列表拼接不得留多余逗号（曾因 INBOUND_COLUMNS 带尾逗号拼出 `report_to_inbound_days, FROM` "
                        +"导致 bad SQL grammar，而旧断言只查片段存在性故漏检）："+rowSql.getAllValues());
    }

    /** 业务上的「入库时间」= 首次入库日期；多次入库要另给末次与单数，且时差以报工完工为左端。 */
    @Test
    void inboundTimeTakesFirstReceiptAndMeasuresGapFromReportedFinish() {
        String join=ProductionTrackingService.INBOUND_JOIN;
        String columns=ProductionTrackingService.INBOUND_COLUMNS;

        assertTrue(join.contains("MIN(r.purchase_date) first_in"),"入库时间取首次入库日期："+join);
        assertTrue(join.contains("MAX(r.purchase_date) last_in")&&join.contains("COUNT(DISTINCT r.id) receipt_cnt"),
                "一个工单可能分多批入库，末次日期与入库单数必须一起取："+join);
        assertTrue(join.contains("GROUP BY i.production_order_no"),"关联键是工单号，必须按它聚合："+join);
        assertTrue(columns.contains("ib.first_in inbound_date")&&columns.contains("COALESCE(ib.receipt_cnt,0) inbound_count"),
                "无采购入库的工单要给出可判空的计数而不是 NULL 单数："+columns);
        assertTrue(columns.contains("DATEDIFF(ib.first_in,p.actual_completion_date)"),
                "报工完工→入库的间隔必须以 actual_completion_date 为左端（氚云里它就是报工完工时间）："+columns);
    }

    /**
     * 节点参考完成日期：锚点＝计划开工，上游已实际完成用实际完成日接力、未完成用参考值接力；
     * 按需工序不适用则不占链（与基准中位数的口径一致）；无基准不强算。负责人 2026-10-10 定。
     */
    @Test
    void nodeReferenceDatesChainFromPlannedStartAndActualCompletions() {
        var benchmarks=Map.of("MATERIAL",new java.math.BigDecimal("0"),"MOLD",new java.math.BigDecimal("3"),
                "ASSEMBLY",new java.math.BigDecimal("2"),"WAREHOUSE",new java.math.BigDecimal("0"));
        var codes=List.of("MATERIAL","MOLD","OXIDATION","ASSEMBLY","WAREHOUSE");
        var applicable=Map.of("OXIDATION",false);
        var statuses=Map.of("MATERIAL","DONE","MOLD","IN_PROGRESS");
        var dates=Map.of("MATERIAL",java.time.LocalDate.parse("2026-10-02"));

        var refs=ProductionTrackingService.computeNodeReferences(codes,java.time.LocalDate.parse("2026-10-01"),
                benchmarks,applicable,statuses,dates,java.time.LocalDate.parse("2026-10-08"));

        assertEquals(java.time.LocalDate.parse("2026-10-01"),refs.get("MATERIAL").referenceDate());
        assertNull(refs.get("MATERIAL").referenceOverdueDays(),"已完成的节点不再判超参考");
        assertEquals(java.time.LocalDate.parse("2026-10-05"),refs.get("MOLD").referenceDate(),
                "模具参考＝备料实际完成日 10-02 + 3 天");
        assertEquals(3,refs.get("MOLD").referenceOverdueDays().intValue());
        assertNull(refs.get("OXIDATION").referenceDate(),"按需工序本单不适用：不占链、无参考日期");
        assertEquals(java.time.LocalDate.parse("2026-10-07"),refs.get("ASSEMBLY").referenceDate(),
                "组装接力上游参考值（模具未完成）：10-05 + 2 天");
        assertEquals(java.time.LocalDate.parse("2026-10-07"),refs.get("WAREHOUSE").referenceDate());
    }

    @Test
    void nodesWithoutBenchmarkOrAnchorGetNoReference() {
        var refs=ProductionTrackingService.computeNodeReferences(
                List.of("DRAWING","MATERIAL"),null,Map.of(),Map.of(),
                Map.of("DRAWING","PENDING"),Map.of(),java.time.LocalDate.parse("2026-10-08"));
        assertNull(refs.get("DRAWING").referenceDate(),"无基准（机器车间暂未播种）不强算");
        assertNull(refs.get("MATERIAL").referenceDate(),"无锚点（无计划开工也无申请日期）不强算");
        assertNull(refs.get("DRAWING").referenceOverdueDays());
    }

    /**
     * 生产交付结果：实际交付日期优先跟单员「入库」节点，缺失时回退采购首次入库日期，
     * 基准是「车间给出的预计完成日期」，但<b>以跟单员确认后的为准</b>
     * （COALESCE(forecast_finish_date, expected_completion_date)）——车间申请延期后新日期只落在
     * forecast_finish_date、氚云原值不会回写；不是跟单截止日 DEADLINE。
     */
    @Test
    void deliveryOutcomeComparesAuthoritativeInboundAgainstConfirmedWorkshopDate() {
        String delivered=ProductionTrackingService.DELIVERED_IN_DATE;
        String outcome=ProductionTrackingService.DELIVERY_OUTCOME;
        String days=ProductionTrackingService.DELIVERY_OVERDUE_DAYS;

        assertTrue(ProductionTrackingService.TRACKING_FROM.contains(
                        "MAX(CASE WHEN stage_code='WAREHOUSE' AND stage_status='DONE' THEN completed_date END) warehouse_in_date"),
                "「跟单员记录的入库时间」＝入库节点的完成日期，必须从里程碑派生表取："+ProductionTrackingService.TRACKING_FROM);
        assertEquals("COALESCE(ms.warehouse_in_date,ib.first_in)",delivered,
                "跟单员节点日期优先；未登记时才回退采购首次入库日期："+delivered);
        assertTrue(outcome.contains("'PENDING'")&&outcome.contains("'ON_TIME'")&&outcome.contains("'OVERDUE'"),
                "三种结果缺一不可："+outcome);
        assertFalse(outcome.contains("'DELAYED'"),
                "「逾期」的枚举值必须是 OVERDUE，不能沿用 DELAYED（避免与「延期」协商动作混淆）："+outcome);
        assertTrue(outcome.contains(delivered+"<="+ProductionTrackingService.DELIVERY_BASELINE),
                "按期判据必须以交付基准（跟单员确认后的车间预计完成日）为准："+outcome);
        assertTrue(ProductionTrackingService.DELIVERY_BASELINE.contains(
                        "COALESCE(t.forecast_finish_date,p.expected_completion_date,")
                        &&ProductionTrackingService.DELIVERY_BASELINE.contains(ProductionTrackingService.VALID_REQUIRED_DATE),
                "延期优先、车间原日期其次，未另填时采用有效的业务员要求日："+ProductionTrackingService.DELIVERY_BASELINE);
        assertTrue(days.contains("DATEDIFF("+delivered+","+ProductionTrackingService.DELIVERY_BASELINE+")"),
                "逾期天数＝实际交付 − 交付基准："+days);
        assertTrue(ProductionTrackingService.TIME_OVERDUE.contains("n.kind='OVERDUE'")
                        &&ProductionTrackingService.TIME_OVERDUE.contains(
                                "n.source_expected_date<=>"+ProductionTrackingService.DELIVERY_BASELINE)
                        &&ProductionTrackingService.TIME_OVERDUE.contains(
                                "n.source_inbound_date<=>"+delivered),
                "逾期核实必须按「基准 / 交付日」快照判定是否已记录："+ProductionTrackingService.TIME_OVERDUE);
    }

    /** 交付结果三列必须出现在列表与详情的 SELECT 上，且拼接不留多余逗号。 */
    @Test
    void deliveryColumnsRideAlongBothListAndDetailSelects() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var rowSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(rowSql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"all","all","",false,"",1,30);
        try { service.detail(7L); } catch (IllegalArgumentException expected) { /* 详情无数据抛错，SQL 仍需核对 */ }

        assertTrue(rowSql.getAllValues().stream().allMatch(sql->sql.contains("delivered_date")
                        &&sql.contains("delivery_baseline")&&sql.contains("delivery_outcome")
                        &&sql.contains("delivery_overdue_days")),
                "列表与详情都要带出交付结果与基准："+rowSql.getAllValues());
        assertTrue(rowSql.getAllValues().stream()
                        .noneMatch(sql->sql.contains(", FROM")||sql.contains(",, ")||sql.contains(",,,")),
                "交付列拼接后不得出现多余逗号："+rowSql.getAllValues());
        assertFalse(ProductionTrackingService.TRACKING_FROM.contains("purchase_receipt"),
                "交付结果依赖采购入库别名，但关联本身仍不得并入共用 FROM："+ProductionTrackingService.TRACKING_FROM);
    }

    /**
     * 跟单待办提醒：一单只出一条，按「跟单落在业务事实后面多少」排严重度 ——
     * 已完工待跟单 > 已入库待跟单 > 已排产待跟单 > 已入库待登记 > 长期未更新。
     * 顺序错了会把最严重的单显示成最轻的提醒，所以顺序本身要钉死。
     */
    @Test
    void trackingActionRanksByHowFarTrackingLagsBehind() {
        String action=ProductionTrackingService.TRACKING_ACTION;

        int finished=action.indexOf("'FINISHED_PENDING'");
        int inboundPending=action.indexOf("'INBOUND_PENDING'");
        int scheduled=action.indexOf("'SCHEDULED_PENDING'");
        int unlogged=action.indexOf("'INBOUND_UNLOGGED'");
        int stale=action.indexOf("'STALE_FOLLOWUP'");
        assertTrue(finished>=0&&inboundPending>finished&&scheduled>inboundPending
                        &&unlogged>scheduled&&stale>unlogged,
                "提醒分支必须按严重度降序排列（先命中的先返回）："+action);
        // 内层 TRACKING_COMPLETE 自带 CASE…ELSE…END，故只能数兜底分支本身。
        assertEquals(1,action.split("ELSE NULL END",-1).length-1,
                "提醒只允许一个兜底分支，否则会同时命中多条："+action);
        assertTrue(action.endsWith(" THEN 'STALE_FOLLOWUP' ELSE NULL END"),
                "没有命中任何规则时应为空（NULL）而不是默认值："+action);

        // 未跟单＝一个跟单节点记录都没有（见 NEVER_TRACKED）；不能用 version=0 代替——
        // 进度表导入时若该行没填任何节点完成日期，只落状态行、version 却已经是 1。
        // 两条前置排除必须排在判定之前：停止跟单、已结案的单都不再给提醒（顺序变了就等于放开提醒）。
        assertTrue(action.startsWith("CASE WHEN t.stopped_at IS NOT NULL THEN NULL "
                        +"WHEN t.closed_at IS NOT NULL THEN NULL WHEN "+ProductionTrackingService.NEVER_TRACKED
                        +" AND p.actual_completion_date IS NOT NULL THEN 'FINISHED_PENDING'"),
                "最严重的一条是「氚云已报工完工但一个节点都没登记」（停止跟单与已结案先排除）："+action);
        assertTrue(action.contains(ProductionTrackingService.NEVER_TRACKED+" AND ib.first_in IS NOT NULL THEN 'INBOUND_PENDING'"),
                "第二条是「采购已入库但跟单还没开始」："+action);
        assertTrue(action.contains(ProductionTrackingService.NEVER_TRACKED+" AND p.schedule_time IS NOT NULL THEN 'SCHEDULED_PENDING'"),
                "第三条是「已排产但跟单还没开始」："+action);
        // 「跟单已推进到入库阶段」的判据是入库节点登记了完成日期，不是采购入库本身。
        assertTrue(action.contains("WHEN ib.first_in IS NOT NULL AND ms.warehouse_in_date IS NULL THEN 'INBOUND_UNLOGGED'"),
                "已在跟单的单要提醒「已入库但入库节点没登记」："+action);
        assertTrue(action.contains("WHEN NOT "+ProductionTrackingService.NEVER_TRACKED
                        +" AND NOT "+ProductionTrackingService.TRACKING_COMPLETE
                        +" AND t.last_followup_at<DATE_SUB(CURDATE(),INTERVAL "
                        +ProductionTrackingService.STALE_FOLLOWUP_DAYS+" DAY) THEN 'STALE_FOLLOWUP'"),
                "「长期没动过」必须以「已登记过节点」为前提，否则零节点的单会被笼统报成未更新："+action);
        assertTrue(ProductionTrackingService.STALE_FOLLOWUP_DAYS>0);
    }

    /** 传统待办按独立事实筛选，列表主提醒可以显示更高优先级的另一项。 */
    @Test
    void trackingActionFilterUsesSamePredicateForCountAndPage() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(new java.util.ArrayList<>());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"all","all","",false,"","","INBOUND_PENDING",1,30);

        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        var countParams=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),countParams.capture());
        var pageSql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(pageSql.capture(),any(RowMapper.class),any(Object[].class));
        String independentCondition=ProductionTrackingService.trackingActionPredicate("INBOUND_PENDING");
        assertTrue(countSql.getValue().contains(independentCondition),
                "计数查询必须带同一段的跟单待办条件："+countSql.getValue());
        assertTrue(countSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN),
                "入库类待办的计数查询必须关联入库事实："+countSql.getValue());
        assertTrue(pageSql.getValue().contains(independentCondition),
                "明细查询必须带同一段的跟单待办条件："+pageSql.getValue());
        assertTrue(independentCondition.contains("ib.first_in IS NOT NULL"));
        assertFalse(independentCondition.contains("TRACKING_ACTION"));
        assertEquals(0,countParams.getValue().length);

        // any 包含实时计算的排产/交期提醒，需先取数再筛；不复用传统提醒的 SQL COUNT。
        service.list(null,null,"all","all","",false,"","","any",1,30);
        verify(jdbc,times(1)).queryForObject(anyString(),eq(Long.class),any(Object[].class));

        assertThrows(IllegalArgumentException.class,
                ()->service.list(null,null,"all","all","",false,"","","NOT_A_ACTION",1,30),
                "白名单外的跟单待办必须报错，不能静默返回全量");
    }

    @Test void traditionalActionPredicatesMatchFactsIndependentlyOfPrimaryAction() {
        assertTrue(ProductionTrackingService.trackingActionPredicate("STALE_FOLLOWUP")
                .contains("last_followup_at<DATE_SUB"));
        assertTrue(ProductionTrackingService.trackingActionPredicate("SCHEDULED_PENDING")
                .contains("p.schedule_time IS NOT NULL"));
        assertTrue(ProductionTrackingService.trackingActionPredicate("INBOUND_UNLOGGED")
                .contains("ms.warehouse_in_date IS NULL"));
    }

    @Test void inboundUnloggedCountIncludesInboundJoin() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null,new ObjectMapper(),allScope());
        service.list(null,null,"all","all","",false,"","","INBOUND_UNLOGGED",1,30);
        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),any(Object[].class));
        assertTrue(countSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN));
        assertTrue(countSql.getValue().contains(ProductionTrackingService.trackingActionPredicate("INBOUND_UNLOGGED")));
    }

    /**
     * 「业务员 / 生产交付结果」两个列头筛选项：计数与明细必须共用同一段谓词，
     * 且业务员名（来自数据）必须以参数传入、不得拼进 SQL 字符串。
     */
    @Test
    void salespersonAndDeliveryFiltersSharePredicateAcrossCountAndPage() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var pageArgs=org.mockito.ArgumentCaptor.forClass(Object[].class);
        var pageSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(pageSql.capture(),any(RowMapper.class),pageArgs.capture())).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"all","all","",false,"","","all","","张三,李四","OVERDUE,ON_TIME",1,30);

        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        var countArgs=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),countArgs.capture());
        String expectedDelivery="("+ProductionTrackingService.DELIVERY_OUTCOME+") IN ('OVERDUE','ON_TIME')";
        assertTrue(countSql.getValue().contains("p.salesperson_name IN (?,?)"),
                "计数查询必须带业务员筛选："+countSql.getValue());
        assertTrue(countSql.getValue().contains(expectedDelivery),
                "计数查询必须带交付结果筛选："+countSql.getValue());
        assertTrue(pageSql.getValue().contains("p.salesperson_name IN (?,?)"),
                "明细查询必须带业务员筛选："+pageSql.getValue());
        assertTrue(pageSql.getValue().contains(expectedDelivery),
                "明细查询必须带交付结果筛选："+pageSql.getValue());
        assertEquals(List.of("张三","李四"),List.of(pageArgs.getValue()[0],pageArgs.getValue()[1]),
                "业务员名必须以参数传入，不能拼进 SQL："+List.of(pageArgs.getValue()));

        assertThrows(IllegalArgumentException.class,
                ()->service.list(null,null,"all","all","",false,"","","all","","","NOT_A_OUTCOME",1,30),
                "白名单外的生产交付结果必须报错，不能静默返回全量");
    }

    @Test
    void sourceCreationDateRangeIsSharedByListAndExport() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        when(jdbc.query(sql.capture(),any(RowMapper.class),args.capture())).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());
        var created=new ProductionTrackingService.DateRangeFilter("2026-09-01","2026-09-30",false);
        var empty=new ProductionTrackingService.DateRangeFilter("","",false);

        service.list("","","all","all","",false,"","","all","","","",empty,empty,empty,
                false,"",created,1,30);
        service.exportRows("","","all","all","",false,"","","all","","","",empty,empty,empty,
                false,"",created);

        assertEquals(2,sql.getAllValues().size());
        assertTrue(sql.getAllValues().get(0).contains("DATE(p.source_created_at)>=? AND DATE(p.source_created_at)<=?"));
        assertEquals(sql.getAllValues().get(0).replace(" LIMIT ? OFFSET ?"," LIMIT ?"),sql.getAllValues().get(1));
        assertEquals(java.time.LocalDate.parse("2026-09-01"),args.getAllValues().get(0)[0]);
        assertEquals(java.time.LocalDate.parse("2026-09-30"),args.getAllValues().get(0)[1]);
        assertEquals(args.getAllValues().get(0)[0],args.getAllValues().get(1)[0]);
        assertEquals(args.getAllValues().get(0)[1],args.getAllValues().get(1)[1]);
    }

    /**
     * 「预计完成」筛选必须认延期至（2026-10-21 LQ 报：周复盘 65 单、列表 71 单）。
     *
     * <p>列表该列显示的是 {@code currentCompletionDate}（延期至优先），筛选若用 {@link ProductionTrackingService#DEADLINE}
     * （不含延期至），就会出现两种错位：车间改期到 10-22 的单，筛 10-12~10-18 仍然出现；车间把 9-29 改到 10-13 的单，
     * 筛 10-12~10-18 反而筛不到。两者必须同源，都用 {@link ProductionTrackingService#DELIVERY_BASELINE}。
     */
    @Test
    void expectedCompletionFilterUsesTheSameCaliberAsTheListColumn() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        when(jdbc.query(sql.capture(),any(RowMapper.class),args.capture())).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());
        var empty=new ProductionTrackingService.DateRangeFilter("","",false);
        var expected=new ProductionTrackingService.DateRangeFilter("2026-10-12","2026-10-18",false);

        service.list("","","all","all","",false,"","","all","","","",empty,empty,expected,
                false,"",empty,1,30);

        String sameCaliber=ProductionTrackingService.DELIVERY_BASELINE+">=? AND "
                +ProductionTrackingService.DELIVERY_BASELINE+"<=?";
        assertTrue(sql.getValue().contains(sameCaliber),
                "预计完成筛选必须与列表列同源（含延期至）："+sql.getValue());
        assertFalse(sql.getValue().contains(ProductionTrackingService.DEADLINE+">=?"),
                "预计完成筛选不得退回 DEADLINE（不算延期至）："+sql.getValue());
        assertEquals(java.time.LocalDate.parse("2026-10-12"),args.getValue()[0]);
        assertEquals(java.time.LocalDate.parse("2026-10-18"),args.getValue()[1]);
    }

    /**
     * 「模具生产」跟进筛选（2026-10-21 跟单员需求：跟进模具生产有没有完成）。
     *
     * <p>要回答的只有一个问题：<b>模具做完没有</b>。所以判据刻意不看模具之前（画图/备料/钣金跟没跟都无关），
     * 只看两件事：① 模具之后<b>没有任何</b>节点跟进（后面一旦动了，说明这张单已越过模具这一关，不该再出现在待办里）；
     * ② 模具节点自身落在所选档位（未跟进 / 进行中 / 已完成）。
     *
     * <p>后置节点集合必须按车间区分：封口机是氧化/组装/入库/木箱，机器是组装/调试/入库/木箱。
     * 模具自身与模具之前的钣金都<b>不得</b>出现在后置集合里——否则「刚做完备料、模具还没开工」的单会被误判成已推进。
     */
    @Test
    void moldStageFilterOnlySelectsOrdersStuckBeforeLaterStages() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());
        var empty=new ProductionTrackingService.DateRangeFilter("","",false);

        service.list("","","all","all","",false,"","","all","","","",empty,empty,empty,
                false,"",empty,"","MOLD_NONE,MOLD_WORKING",1,30);

        String where=sql.getValue();
        assertTrue(where.contains("NOT "+ProductionTrackingService.MOLD_LATER_TRACKED),
                "模具之后已有节点跟进的单必须排除："+where);
        assertTrue(where.contains(ProductionTrackingService.MOLD_NODE_STATE+" IN ('MOLD_NONE','MOLD_WORKING')"),
                "三档必须按模具节点自身状态过滤："+where);

        // 后置节点集合按车间区分，且不得含模具自己或模具之前的钣金。
        String from=ProductionTrackingService.TRACKING_FROM;
        for(String field:List.of("later_sealer_count","later_machine_count")){
            int at=from.indexOf(field);
            assertTrue(at>0,"TRACKING_FROM 必须聚合 "+field+"："+from);
            // 只取本条 SUM(...) 表达式：用 lastIndexOf("SUM(") 回退，避免把上一条聚合的 stage_code 也算进来。
            String agg=from.substring(from.lastIndexOf("SUM(",at),at);
            assertFalse(agg.contains("'MOLD'")||agg.contains("'SHEET_METAL'"),
                    "模具自身与模具之前的钣金都不能算「模具之后」（"+field+"）："+agg);
        }
        assertTrue(from.contains("stage_code IN ('OXIDATION','ASSEMBLY','WAREHOUSE','WOODEN_CRATE')"),
                "封口机的后置节点＝氧化/组装/入库/木箱："+from);
        assertTrue(from.contains("stage_code IN ('ASSEMBLY','TESTING','WAREHOUSE','WOODEN_CRATE')"),
                "机器车间的后置节点＝组装/调试/入库/木箱："+from);

        // 导出必须与列表同一段谓词（否则导出的行和页面上看到的行对不上）。
        service.exportRows("","","all","all","",false,"","","all","","","",empty,empty,empty,
                false,"",empty,"","MOLD_DONE","");
        assertTrue(sql.getAllValues().get(1).contains(ProductionTrackingService.MOLD_NODE_STATE+" IN ('MOLD_DONE')"),
                "导出必须带同一段模具谓词："+sql.getAllValues().get(1));
        assertTrue(sql.getAllValues().get(1).contains("NOT "+ProductionTrackingService.MOLD_LATER_TRACKED),
                "导出的排除条件也必须一致："+sql.getAllValues().get(1));

        assertThrows(IllegalArgumentException.class,
                ()->service.list("","","all","all","",false,"","","all","","","",empty,empty,empty,
                        false,"",empty,"","MOLD_NOT_A_STAGE",1,30),
                "非白名单档位必须拒绝，不能静默变成不筛");
    }

    /**
     * 搜索框唯一入口（10-19 LQ：「搜索规格」并入任务号 / 产品 / 客户同一个框）。
     * 关键词必须命中四个字段，且规格走与列表列 specification_snapshot 完全相同的拼接口径 ——
     * 两处拼接方式一旦分叉，就会出现「搜出来的行，规格列里却看不到搜的那个词」。
     */
    @Test
    void keywordSearchAlsoCoversSpecification() {
        var jdbc=mock(JdbcTemplate.class);
        var countArgs=org.mockito.ArgumentCaptor.forClass(Object[].class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),countArgs.capture())).thenReturn(0L);
        var rowSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(rowSql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list("120×80","","all","all","",false,"","","all","",1,30);

        String sql=rowSql.getValue();
        for(String column:List.of("p.work_order_no","p.product_name","p.customer_name"))
            assertTrue(sql.contains(column+" LIKE ?"),"关键词必须命中 "+column+"："+sql);
        assertTrue(sql.contains("CONCAT_WS(' / ',p.box_size,p.box_shape,p.box_material) LIKE ?"),
                "规格必须按列表列同一拼接口径匹配："+sql);
        var args=countArgs.getValue();
        assertEquals(4,args.length,"4 个占位符必须配 4 个参数（少一个＝绑定错位直接 500）："+sql);
        for(Object arg:args)assertEquals("%120×80%",arg,"每个字段都要用同一个关键词模式："+sql);
    }

    /**
     * 业务员下拉清单必须与列表同一可见性口径，否则下拉里出现的人可能一个都筛不到。
     *
     * <p>⚠️ 2026-10-20：filter-options 改单遍扫描后，业务员不再是独立的一条
     * {@code SELECT DISTINCT p.salesperson_name}，而是并进主扫描的
     * {@code GROUP_CONCAT(DISTINCT CASE WHEN p.salesperson_name IS NOT NULL AND p.salesperson_name&lt;&gt;'' ...)}。
     * 断言随之改为「主扫描 SQL 里含这组去重取值 + 同一段可见范围」——口径没变，
     * 变的只是取数方式（8 条串行 → 1 次扫描）。
     */
    @Test
    void filterOptionsExposesSalespersonsWithSameVisibility() {
        var jdbc=mock(JdbcTemplate.class);
        var scanSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForList(scanSql.capture(),any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var options=service.filterOptions();

        assertTrue(options.containsKey("salespersons"),"下拉必须返回业务员清单："+options.keySet());
        String names=scanSql.getAllValues().stream()
                .filter(sql->sql.contains("GROUP_CONCAT(DISTINCT CASE WHEN p.salesperson_name")).findFirst()
                .orElseThrow(()->new AssertionError("filter-options 必须查业务员去重清单："+scanSql.getAllValues()));
        assertTrue(names.contains("p.salesperson_name IS NOT NULL")&&names.contains("<>''"),
                "业务员清单要排除空值，否则会多出一个空选项："+names);
        assertTrue(names.contains("p.external_source='H3YUN'"),
                "业务员清单必须与列表同一可见范围："+names);
    }

    /** 提醒列要同时出现在列表与详情的 SELECT 上，且拼接不留多余逗号（曾因尾逗号出过 bad SQL grammar）。 */
    @Test
    void trackingActionRidesAlongBothListAndDetailSelects() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var rowSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(rowSql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list(null,null,"all","all","",false,"",1,30);
        try { service.detail(7L); } catch (IllegalArgumentException expected) { /* 详情无数据抛错，SQL 仍需核对 */ }

        assertTrue(rowSql.getAllValues().stream().allMatch(sql->sql.contains("tracking_action")),
                "列表与详情都要带出跟单待办："+rowSql.getAllValues());
        assertTrue(rowSql.getAllValues().stream()
                        .noneMatch(sql->sql.contains(", FROM")||sql.contains(",, ")||sql.contains(",,,")),
                "跟单待办列拼接后不得出现多余逗号："+rowSql.getAllValues());
    }

    /** 下拉里的各类条数必须与列表同一个 FROM（含采购入库关联）与同一段可见性条件。 */
    @Test
    void trackingActionCountsShareTheListFrom() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of());
        var querySql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(querySql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.filterOptions();

        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc,atLeastOnce()).queryForList(countSql.capture(),any(Object[].class));
        String counts=countSql.getAllValues().stream()
                .filter(sql->sql.contains("COALESCE(SUM(CASE WHEN")&&sql.contains("FINISHED_PENDING"))
                .findFirst().orElseThrow(()->new AssertionError("filter-options 必须返回各类跟单待办的条数："+countSql.getAllValues()));
        assertTrue(counts.contains(ProductionTrackingService.INBOUND_JOIN),
                "条数要算上采购入库关联，否则「已入库」类会恒为 0："+counts);
        for(String code:ProductionTrackingService.TRACKING_ACTION_CODES)
            assertTrue(counts.contains(ProductionTrackingService.trackingActionPredicate(code)),counts);
        assertTrue(counts.contains("p.external_source='H3YUN'")&&counts.contains("production_tracking_workbook_rows"),
                "条数必须与列表同一段可见范围："+counts);
    }

    /**
     * 导出必须与列表**同一套**筛选、可见性、取数列与排序 —— 只差一个分页尾巴。
     * 逐字符比对两条 SQL：哪边单独改了条件或列，这里立刻红；导出另写一份 SELECT 是这类功能最常见的静默故障。
     */
    @Test
    void exportSharesTheSameSqlAsTheListApartFromPaging() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(2L);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        when(jdbc.query(sql.capture(),any(RowMapper.class),args.capture())).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.list("客户","封口机","open","time_overdue","",false,"7","2026-10","STALE_FOLLOWUP",2,30);
        service.exportRows("客户","封口机","open","time_overdue","",false,"7","2026-10","STALE_FOLLOWUP");

        // 取数为空时 attach* 会直接返回，故恰好两条取数 SQL。
        var queries=sql.getAllValues();
        assertEquals(2,queries.size(),"列表与导出各自只应发一次取数查询："+queries);
        assertTrue(queries.get(0).endsWith(" LIMIT ? OFFSET ?"),"列表必须分页："+queries.get(0));
        assertEquals(queries.get(0).replace(" LIMIT ? OFFSET ?"," LIMIT ?"),queries.get(1),
                "导出 SQL 必须与列表完全一致，只差分页尾巴");

        var recorded=args.getAllValues();
        var listArgs=recorded.get(0);
        var exportArgs=recorded.get(1);
        // 筛选参数个数相同，之后列表多 size/offset 两个、导出只有一个 LIMIT。
        int filterCount=exportArgs.length-1;
        assertEquals(filterCount+2,listArgs.length,"列表＝筛选参数 + size + offset");
        assertArrayEquals(java.util.Arrays.copyOf(listArgs,filterCount),java.util.Arrays.copyOf(exportArgs,filterCount),
                "同样的筛选必须拼出同样的参数");
        assertEquals(30,((Number)listArgs[filterCount]).intValue(),"倒数第二个参数是每页条数");
        assertEquals(30L,((Number)listArgs[filterCount+1]).longValue(),"最后一个参数是偏移量 = (第2页-1)×30");
        assertEquals(ProductionTrackingService.EXPORT_MAX_ROWS+1,((Number)exportArgs[filterCount]).intValue(),
                "多取一行用来判断是否被截断");

        assertThrows(IllegalArgumentException.class,
                ()->service.exportRows("","","all","all","",false,"","","nope"),
                "导出不得绕过跟单待办白名单校验");
    }

    @Test
    void derivedActionListAndExportScanTheSameFilteredRows() {
        var jdbc=mock(JdbcTemplate.class);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        when(jdbc.query(sql.capture(),any(RowMapper.class),args.capture())).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null,new ObjectMapper(),allScope());

        var listed=service.list("客户","封口机","all","all","",false,"","2026-10","any",1,30);
        var exported=service.exportRows("客户","封口机","all","all","",false,"","2026-10","any");

        assertEquals(sql.getAllValues().get(0),sql.getAllValues().get(1));
        assertArrayEquals(args.getAllValues().get(0),args.getAllValues().get(1));
        assertEquals(((Number)listed.get("total")).longValue(),((Number)exported.get("total")).longValue());
        verify(jdbc,never()).queryForObject(anyString(),eq(Long.class),any(Object[].class));
    }

    @Test void anyActionExcludesDeliveredSchedulePressure() {
        var delivered=new java.util.HashMap<String,Object>();
        delivered.put("scheduleRisk","PRESSURE");
        delivered.put("deliveredDate",java.time.LocalDate.of(2026,10,20));
        assertFalse(ProductionTrackingService.matchesScheduleAction(delivered,"any"));
        delivered.put("trackingAction","INBOUND_PENDING");
        assertTrue(ProductionTrackingService.matchesScheduleAction(delivered,"any"));
        delivered.remove("trackingAction");
        delivered.remove("deliveredDate");
        assertTrue(ProductionTrackingService.matchesScheduleAction(delivered,"any"));
    }

    /**
     * COUNT 只在筛选真的引用 {@code ib} 时才挂 {@code INBOUND_JOIN}。
     *
     * <p>背景（2026-10-20 实测）：该派生表把 6.5 万行采购明细按工单号全量聚合成 5834 组，
     * 挂上去「不改变行数」（1:1），但默认打开时让 COUNT 从 49ms 涨到 72ms。
     * 反过来更危险：一旦某个谓词引用了 {@code ib.*} 却没挂 JOIN，SQL 直接报 Unknown column（列不存在），
     * 整个列表 500。所以两头都要钉死：
     * <ul>
     *   <li>默认（无交付结果 / 无倒计时）→ COUNT SQL <b>不得</b>含 INBOUND_JOIN；</li>
     *   <li>「生产交付结果」筛选 → COUNT SQL <b>必须</b>含 INBOUND_JOIN（DELIVERY_OUTCOME 用它算交付日）；</li>
     *   <li>「倒计时」筛选 → COUNT SQL <b>必须</b>含（DELIVERED_IN_DATE 用它判已交付/未交付）；</li>
     *   <li>明细查询永远含（LIST_COLUMNS 里有 INBOUND_COLUMNS，与标记无关）。</li>
     * </ul>
     */
    @Test
    void countOnlyJoinsInboundWhenAFilterActuallyNeedsIt() {
        var created=new ProductionTrackingService.DateRangeFilter("","",false);

        // ① 默认打开：COUNT 不挂，明细照挂
        {
            var jdbc=mock(JdbcTemplate.class);
            var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
            var rowSql=org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbc.query(rowSql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
            var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

            service.list("","","all","all","",false,"","",/*action*/"all",/*countdown*/"",
                    /*salesperson*/"",/*deliveryResult*/"",created,created,created,
                    false,"",created,"",1,30);

            assertFalse(countSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN),
                    "默认打开时 COUNT 不该白跑采购入库聚合："+countSql.getValue());
            assertTrue(rowSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN),
                    "明细要出入库日期/单数/交付结果，永远得挂："+rowSql.getValue());
        }

        // ② 交付结果筛选：COUNT 必须挂
        {
            var jdbc=mock(JdbcTemplate.class);
            var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
            when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
            var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

            service.list("","","all","all","",false,"","",/*action*/"all",/*countdown*/"",
                    /*salesperson*/"",/*deliveryResult*/"OVERDUE",created,created,created,
                    false,"",created,"",1,30);

            assertTrue(countSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN),
                    "交付结果筛选引用了 ib.first_in，COUNT 必须挂："+countSql.getValue());
            assertTrue(countSql.getValue().contains("delivery")||countSql.getValue().contains("ib.first_in"),
                    "交付结果谓词要真的进 where："+countSql.getValue());
        }

        // ③ 倒计时筛选：COUNT 必须挂
        {
            var jdbc=mock(JdbcTemplate.class);
            var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
            when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
            var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

            service.list("","","all","all","",false,"","",/*action*/"all",/*countdown*/"delivered",
                    /*salesperson*/"",/*deliveryResult*/"",created,created,created,
                    false,"",created,"",1,30);

            assertTrue(countSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN),
                    "倒计时引用了交付日，COUNT 必须挂："+countSql.getValue());
        }

        // ④ 导出 COUNT 同一口径：默认也不挂
        {
            var jdbc=mock(JdbcTemplate.class);
            var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
            when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
            when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
            var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

            service.exportRows("","","all","all","",false,"","",/*action*/"all");

            assertFalse(countSql.getValue().contains(ProductionTrackingService.INBOUND_JOIN),
                    "导出的 total 与列表同口径，默认也不必挂："+countSql.getValue());
        }
    }

    /**
     * 「按需工序」筛选（氧化/喷特氟龙、木箱）：谓词必须**同时**出现在 COUNT 与明细上
     * （依赖 TRACKING_FROM 的 ms 派生表，两处都挂了同一份 FROM），且口径＝「配置要求 或 已登记该节点」——
     * 只看节点会漏掉还没开始跟单的新单，只看配置会漏掉跟单员实际登记过节点的单。
     */
    @Test
    void optionalStageFilterCoversConfiguredAndRegisteredOrders() {
        var jdbc=mock(JdbcTemplate.class);
        var countSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForObject(countSql.capture(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        var rowSql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.query(rowSql.capture(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());
        var created=new ProductionTrackingService.DateRangeFilter("","",false);

        service.list("","","all","all","",false,"","","all","","","",created,created,created,
                false,"",created,"OXIDATION",1,30);
        service.exportRows("","","all","all","",false,"","","all","","","",created,created,created,
                false,"",created,"OXIDATION","");

        String predicate=ProductionTrackingService.needsOptionalStage("OXIDATION");
        assertTrue(predicate.contains("ms.oxidation_count"),"要看该单有没有这道工序的节点记录："+predicate);
        assertTrue(predicate.contains("特氟龙")&&predicate.contains("氧化"),
                "也要认源单可选配置里写明的「氧化/特氟龙」："+predicate);
        assertTrue(predicate.contains("p.production_line LIKE '%封口%'"),
                "氧化只存在于封口机的节点序列里，机器车间的单不该被筛出来："+predicate);
        for(String sql:List.of(countSql.getValue(),rowSql.getValue()))
            assertTrue(sql.contains(predicate),"列表与导出的 COUNT/明细都必须带同一段工序谓词："+sql);

        assertThrows(IllegalArgumentException.class,
                ()->service.list("","","all","all","",false,"","","all","","","",created,created,created,
                        false,"",created,"NOPE",1,30),
                "工序码必须走白名单，非法值不得静默不筛");
    }

    /**
     * 工序条数下拉必须与列表同一份 FROM 与同一段可见范围，且空集时要 COALESCE 兜 0（SUM 会给 NULL）。
     *
     * <p>⚠️ 2026-10-20 改：filter-options 由「8 条串行 SQL」改为「1 条单遍扫描 + 3 条并行」，
     * 本方法随之调整两处：
     * <ul>
     *   <li>取数改走 {@code queryForList(sql, params)}（不带 Class 的重载），原来的
     *       {@code queryForList(anyString(), eq(String.class), ...)} 桩不再命中，会静默回落空结果；</li>
     *   <li>断言从「SQL 字面量里出现 {@code COALESCE(SUM(}」改为**直接喂一行 NULL 计数**，
     *       验的是「空集时下拉数字是 0 而不是 null」这个行为本身。字面量断言在表达式被重排
     *       （如把谓词套进 SUM 里）时就会假红，而真正的风险是 null 泄漏到前端。</li>
     * </ul>
     *
     * <p>⚠️ 2026-10-20：filter-options 改单遍扫描后，工序条数放在**并行任务**里（虚拟线程），
     * 那些任务里的取数用的是 {@code queryForList(sql, params)}；主扫描也是这个重载。
     * 故桩必须打在这个重载上；{@code jdbc.execute(SET SESSION ...)} 用 mock 默认实现（返回 null 即可）。
     */
    @Test
    void optionalStageCountsShareTheListFromAndNeverReturnNull() {
        var jdbc=mock(JdbcTemplate.class);
        var listSql=org.mockito.ArgumentCaptor.forClass(String.class);
        // 模拟真库「空集」：SUM 在无匹配行时返回 NULL（两个聚合列都是 null）。
        var nullRow=new java.util.HashMap<String,Object>();
        nullRow.put("oxidation",null);
        nullRow.put("wooden_crate",null);
        when(jdbc.queryForList(listSql.capture(),any(Object[].class)))
                .thenReturn(List.<Map<String,Object>>of(nullRow));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var options=service.filterOptions();

        assertTrue(options.containsKey("optionalStageCounts"),"下拉要给出各道按需工序的条数："+options.keySet());
        var counts=(List<?>)options.get("optionalStageCounts");
        assertEquals(2,counts.size(),"氧化与木箱各一条："+counts);
        for(Object item:counts){
            var row=(Map<?,?>)item;
            assertNotNull(row.get("count"),"SUM 在空集返回 NULL，下拉不得把 null 透给前端："+row);
            assertEquals(0L,((Number)row.get("count")).longValue(),"空集时条数必须是 0："+row);
        }
        // 按需工序计数那条：唯一锚点是 `COALESCE(SUM(`（主扫描用的是 COALESCE(ms.xxx)；ms 派生表自带 oxidation/wooden_crate 字样，故不能用它们定位）。
        String sql=listSql.getAllValues().stream().filter(item->item.contains("COALESCE(SUM("))
                .findFirst().orElseThrow(()->new AssertionError("filter-options 必须查按需工序条数："+listSql.getAllValues()));
        assertTrue(sql.contains("p.external_source='H3YUN'")&&sql.contains("production_tracking_workbook_rows"),
                "条数必须与列表同一段可见范围："+sql);
    }

    /** 超过单次上限必须截断，并如实报出「共多少、导了多少」，不能静默少给。 */
    @Test
    void exportTruncatesAtTheCapAndReportsIt() {
        var jdbc=mock(JdbcTemplate.class);
        long matching=(long)ProductionTrackingService.EXPORT_MAX_ROWS+9;
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(matching);
        stubListRows(jdbc,exportFixtureRows(ProductionTrackingService.EXPORT_MAX_ROWS+1));
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var result=service.exportRows("","","all","all","",false,"","","all");

        assertEquals(Boolean.TRUE,result.get("truncated"));
        assertEquals(ProductionTrackingService.EXPORT_MAX_ROWS,result.get("exported"));
        assertEquals(matching,result.get("total"));
        assertEquals(ProductionTrackingService.EXPORT_MAX_ROWS,((List<?>)result.get("items")).size());
    }

    /** 行数正好等于上限时不算截断（多取一行的探针不能把边界算错）。 */
    @Test
    void exportIsNotTruncatedWhenRowCountEqualsTheCap() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class)))
                .thenReturn((long)ProductionTrackingService.EXPORT_MAX_ROWS);
        stubListRows(jdbc,exportFixtureRows(ProductionTrackingService.EXPORT_MAX_ROWS));
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var result=service.exportRows("","","all","all","",false,"","","all");

        assertEquals(Boolean.FALSE,result.get("truncated"));
        assertEquals(ProductionTrackingService.EXPORT_MAX_ROWS,result.get("exported"));
        assertEquals((long)ProductionTrackingService.EXPORT_MAX_ROWS,result.get("total"));
    }

    /**
     * 取数桩：只让**主取数语句**返回给定行，里程碑 / 时间核实的挂载查询返回空。
     * ⚠️ 不能用 `contains("production_tracking_milestones")` 之类判断 —— 主语句的 FROM 里就含这张派生表，
     * 那样会把主查询也当成挂载查询、返回空列表，测试就会「假绿/假红」（踩过一次）。
     */
    private static void stubListRows(JdbcTemplate jdbc,List<Map<String,Object>> rows) {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->
                ((String)call.getArgument(0)).startsWith("SELECT p.id,p.work_order_no order_no")?rows:List.of());
    }

    /** 取数行的最小骨架：attach* 需要 id 与 workshopType，其余列不影响本用例断言。 */
    private static List<Map<String,Object>> exportFixtureRows(int count) {
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++){
            var item=new java.util.LinkedHashMap<String,Object>();
            item.put("id",(long)i);item.put("workshopType","全自动气调机");
            rows.add(item);
        }
        return rows;
    }

    private static Map<String,Object> row(String code,String status,String date,String note) {
        var out=new java.util.LinkedHashMap<String,Object>();
        out.put("stage_code",code);out.put("stage_status",status);out.put("completed_date",date);out.put("note",note);
        return out;
    }

    /** 待处理列表必须标出「该车间已有更新版本、本行未被覆盖」，避免主管继续处理过期行。 */
    @Test
    void workbookPendingQueueFlagsRowsTheNewerVersionDidNotCover() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(RowMapper.class))).thenReturn(List.of());
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        service.workbookRows(false);

        verify(jdbc).query(argThat((String sql)->sql.contains("match_status IN ('PENDING','REVIEW')")
                        &&sql.contains("stale")
                        &&sql.contains("n.created_at>r.created_at")
                        &&sql.contains("LIMIT 1000")),
                any(RowMapper.class));
    }

    /** 按钮上的数字走轻量计数：能算出「待办 / 已归档」等分类，不必把待处理明细整包拉给前端。 */
    @Test
    void workbookSummaryCountsWithoutPullingPendingDetails() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("s","PENDING","c",3),Map.of("s","REVIEW","c",1),
                Map.of("s","ARCHIVED","c",5),Map.of("s","IMPORTED","c",400)));
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var summary=service.workbookSummary();

        assertEquals(3,summary.get("pending"));
        assertEquals(1,summary.get("review"));
        assertEquals(4,summary.get("actionable"),"待办数字＝待匹配＋差异待审核");
        assertEquals(5,summary.get("archived"));
        assertEquals(409,summary.get("total"));
    }

    /** 归档规则与导入匹配共用同一个归一化键，否则会出现「手工归档了却挡不住下次导入」。 */
    @Test
    void archiveRuleKeyNormalizationIsSharedAndStable() {
        assertEquals(ProductionTrackingPolicy.normalizeKey("模具"),ProductionTrackingPolicy.normalizeKey(" 模具 "));
        assertEquals(ProductionTrackingPolicy.normalizeKey("全自动气调机"),
                ProductionTrackingPolicy.normalizeKey("全自动气调机车间"));
        assertEquals("lq750全自动片膜多模具",ProductionTrackingPolicy.normalizeKey("LQ750 全自动片膜-多模具"));
    }

    /** 关联窗口的候选必须按命中字段评分排序，并明确标出哪些字段对不上。 */
    @Test
    void workbookCandidatesAreScoredAndExplainFieldMatches() {
        var jdbc=mock(JdbcTemplate.class);
        var source=new java.util.LinkedHashMap<String,Object>();
        source.put("sheet_name","全自动气调机车间");source.put("source_order_no","5858");
        source.put("product_name","LQ750全自动多模具");
        source.put("required_completion_date",java.time.LocalDate.parse("2026-10-07"));
        source.put("source_quantity","2");
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(source));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of(
                candidate(1L,"WO-5858","LQ750全自动多模具","全自动气调机车间","2026-10-07",2),
                candidate(2L,"WO-9999","别的产品","封口机车间","2026-11-20",7)));
        var service=new ProductionTrackingService(jdbc,null,null, new ObjectMapper(),allScope());

        var scored=service.workbookCandidates(9L);

        assertEquals(1L,((Number)scored.get(0).get("id")).longValue(),"字段全中的工单必须排在最前");
        assertEquals(110,scored.get(0).get("score"));
        assertTrue(((List<?>)scored.get(0).get("matchedFields")).contains("交期"));
        assertTrue(((List<?>)scored.get(1).get("conflictFields")).contains("车间"));
        assertTrue(((List<?>)scored.get(1).get("conflictFields")).contains("交期"));
    }

    @Test
    void batchWorkbookRejectsUnknownActionsAndOversizedBatches() {
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),null,null,new ObjectMapper(),allScope());

        assertThrows(IllegalArgumentException.class,()->service.batchResolveWorkbookRows(List.of(),"skip","k"));
        assertThrows(IllegalArgumentException.class,()->service.batchResolveWorkbookRows(List.of(1L),"unknown","k"));
        assertThrows(IllegalArgumentException.class,()->service.batchResolveWorkbookRows(
                java.util.stream.LongStream.range(0,201).boxed().toList(),"skip","k"));
    }

    @Test
    void archiveRequiresAReasonBeforeTouchingTheRow() {
        var service=new ProductionTrackingService(mock(JdbcTemplate.class),null,null,new ObjectMapper(),allScope());

        assertThrows(IllegalArgumentException.class,()->service.archiveWorkbookRow(1L,"   ","k"));
    }

    /**
     * 配置页「历史任务单 / 跟单记录」两个数字必须点得开，而且明细口径要和计数口径一致：
     * ① 不自行排除草稿 / 已取消——否则明细比数字少，用户会以为丢单（页面上单独标注即可）；
     * ② 「完成节点 x/y」的 y 只数必经节点，与参考间隔分母同源，按需工序（氧化/木箱）不进分母。
     */
    @Test
    void productOrderHistoryKeepsEveryStatusAndCountsOnlyRequiredStages() {
        for (var expected : List.of(Map.entry("封口机车间", 4), Map.entry("全自动气调机车间", 7))) {
            String workshop = expected.getKey();
            int required = expected.getValue();
            var jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), any(Object[].class)))
                    .thenReturn(List.of(Map.of("config_name", "测试产品", "workshop_name", workshop)));
            when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(new java.util.LinkedHashMap<String, Object>());
            var orderSqls = new ArrayList<String>();
            doAnswer(inv -> { orderSqls.add(inv.getArgument(0)); return null; })
                    .when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class));

            var service = new ProductionTrackingService(jdbc, mock(ActorResolver.class), null, new ObjectMapper(), allScope());
            var result = service.productOrderHistory(1L, "", 50);

            assertEquals(required, result.get("requiredStageCount"),
                    workshop + " 的必经节点数必须与跟单分母同源（封口 4 / 机器 7）");
            String sql = orderSqls.getFirst();
            assertFalse(sql.contains("CANCELLED") || sql.contains("已取消"),
                    "明细不能排除任何状态：数字含草稿/已取消，明细也必须含，否则两处对不上：" + sql);
            String in = sql.substring(sql.indexOf("stage_code IN (") + "stage_code IN (".length());
            in = in.substring(0, in.indexOf(')'));
            assertEquals(required, in.split(",").length,
                    "完成节点只数必经节点，按需工序不进分母：" + in);

            // 画像统计（summary）与状态筛选解耦：左边筛状态时，右边的「共 N 张」不能跟着跳。
            var summaryArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).queryForMap(anyString(), summaryArgs.capture());
            assertEquals(2, summaryArgs.getValue().length,
                    "summary 只按车间 + 产品名两个条件统计，不接受 status 参数");
        }
    }

    private static Map<String,Object> candidate(long id,String orderNo,String product,String workshop,String due,int quantity) {
        var out=new java.util.LinkedHashMap<String,Object>();
        out.put("id",id);out.put("orderNo",orderNo);out.put("productName",product);
        out.put("workshopType",workshop);out.put("dueDate",java.time.LocalDate.parse(due));
        out.put("plannedQuantity",quantity);
        return out;
    }

}
