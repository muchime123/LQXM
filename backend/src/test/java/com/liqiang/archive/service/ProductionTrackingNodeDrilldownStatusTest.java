package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节点下钻「该节点状态」筛选的<b>判据契约</b>。
 *
 * <p>【这条契约为什么必须钉死】里程碑的实际状态只有 {@code DONE / PENDING / IN_PROGRESS}
 * （V369 建表 {@code stage_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'}），库内<b>从不存在 'OPEN'</b>。
 * 而旧实现把「未完成」翻译成 {@code m.stage_status = 'OPEN'}，于是筛选永远返回 0 条 ——
 * 界面上不报错、只显示"没有符合条件的工单"，是最难发现的一类静默失败。
 *
 * <p>因此这里钉死三件事：
 * <ol>
 *   <li>「未完成」按 {@code <> 'DONE'} 判定 —— {@code PENDING} 与 {@code IN_PROGRESS} 都要能进来，
 *       将来新增状态值也自动纳入，不再依赖前端与后端同步枚举；</li>
 *   <li>「已完成」仍按 {@code = 'DONE'} 判定，不受本次修改影响；</li>
 *   <li>参数里不再出现 {@code 'OPEN'} 这个库里不存在的值。</li>
 * </ol>
 */
class ProductionTrackingNodeDrilldownStatusTest {
    private JdbcTemplate jdbc;
    private ProductionTrackingService tracking;
    private ProductionTrackingAnalyticsService service;

    @BeforeEach
    void setUp(){
        jdbc = mock(JdbcTemplate.class);
        tracking = mock(ProductionTrackingService.class);
        service = new ProductionTrackingAnalyticsService(jdbc, tracking, new ObjectMapper());
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "tracking", tracking);
        when(tracking.visible(any())).thenReturn(new StringBuilder());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    /** 一次调用同时捕获 SQL 与参数（verify 默认只认一次调用，故不能在同个用例里调两遍）。 */
    private record Captured(String sql, Object[] args){}

    private Captured call(String status){
        return call("MOLD", status, null);
    }

    private Captured call(String stage, String status, Integer waitDaysMin){
        service.nodeAnalyticsOrders("封口机车间", stage, status, null, waitDaysMin, 1, 30);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), args.capture());
        return new Captured(sql.getValue(), args.getValue());
    }

    @Test
    void unfinishedFilterMatchesEveryNonDoneStatus(){
        Captured open = call("OPEN");
        String sql = open.sql();
        assertTrue(sql.contains("COALESCE(m.stage_status,'')<>?"),
                "「未完成」必须按 <> 'DONE' 判定，否则 PENDING / IN_PROGRESS 的单筛不出来：" + sql);
        assertFalse(sql.contains("m.stage_status='OPEN'"), "不得再按不存在的 'OPEN' 等值匹配：" + sql);
        assertTrue(Arrays.asList(open.args()).contains("DONE"),
                "「未完成」的比较值只能是 DONE（取反），不能是 'OPEN'");
    }

    @Test
    void unfinishedFilterNeverBindsOpenValue(){
        for(Object value:call("OPEN").args()){
            assertFalse("OPEN".equals(value), "参数里不应出现库内不存在的 'OPEN' 状态值");
        }
    }

    @Test
    void doneFilterStillMatchesDoneExactly(){
        Captured done = call("DONE");
        assertTrue(done.sql().contains("m.stage_status=?"), "「已完成」仍按 = 'DONE' 精确匹配：" + done.sql());
        assertFalse(done.sql().contains("COALESCE(m.stage_status,'')<>?"), "「已完成」不应按取反判定：" + done.sql());
        assertTrue(Arrays.asList(done.args()).contains("DONE"), "已完成筛选的比较值必须是 DONE");
    }

    @Test
    void allStatusAppliesNoStatusCondition(){
        String sql = call("ALL").sql();
        assertFalse(sql.contains("m.stage_status=?"), "「全部」不应带状态等值条件：" + sql);
        assertFalse(sql.contains("COALESCE(m.stage_status,'')<>?"), "「全部」不应带状态取反条件：" + sql);
    }

    /** 等待天数下钻走另一条分支，节点状态筛选对它无效 —— 钉住两者不会互相串条件。 */
    /**
     * 「当前超期」必须是<b>整单级</b>状态：只用「当前有效预计日期」（车间 forecast 优先），
     * 且只在<b>整单仍在产</b>时才算 —— 完成信号取自跟单结案 {@code closed_at} 与
     * 「最后适用节点已完成」{@code TRACKING_COMPLETE}。
     *
     * <p>这里刻意钉死「不再用 {@code p.actual_completion_date}」：那列对这批工单常年不回填，
     * SCGD20260005428 七个节点全 DONE、入库也 DONE，它依然是 NULL，
     * 于是已完工的历史单被当成未完工，超期天数还随日期一天天累加。
     */
    @Test
    void openOverdueUsesTrackingCompletionAndCurrentEffectiveBaseline(){
        String sql = call("OPEN").sql();
        assertTrue(sql.contains("COALESCE(t.forecast_finish_date,p.expected_completion_date) expectedDate"),
                "当前预计应与生产分析同源（车间 forecast 优先），不用源单 expected_completion_date：" + sql);
        assertTrue(sql.contains("t.closed_at"), "整单完成必须认跟单结案：" + sql);
        assertTrue(sql.contains("machine_done_count"),
                "整单完成必须复用「最后适用节点已完成」判据 TRACKING_COMPLETE（含按需节点计数）：" + sql);
        assertFalse(sql.contains("p.actual_completion_date IS NULL"),
                "不得再用源单整单完工日判当前超期：" + sql);
    }

    /** 节点<b>状态</b>筛选（DONE / PENDING / IN_PROGRESS）与整单完成是两个维度，不许混用。 */
    @Test
    void nodeStatusFilterStillJudgedByMilestoneStatus(){
        Captured open = call("OPEN");
        assertTrue(open.sql().contains("COALESCE(m.stage_status,'')<>?"),
                "节点状态筛选仍按里程碑实际状态取值判定：" + open.sql());
    }

    /** 停产 / 取消必须挡在<b>所有</b>节点查询之外（节点完成率 / 等待分布 / 异常单），不是只在下钻里过滤。 */
    @Test
    void stoppedAndCancelledAreExcludedFromEveryNodeQuery(){
        service.nodeAnalyticsView();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).queryForList(sql.capture(), any(Object[].class));
        var all = sql.getAllValues();
        assertFalse(all.isEmpty(), "视图至少应查一次明细");
        for (String one : all) {
            assertTrue(one.contains("t.stopped_at IS NULL"), "查询缺少停产过滤：" + one);
            assertTrue(one.contains("已取消"), "查询缺少取消过滤：" + one);
        }
    }

    /**
     * 等待天数下钻走另一条分支，不该挂上「某个节点」的维度条件。
     *
     * <p>取 WHERE 段的锚点只能用等待下钻特有的 JOIN：整体 SQL 里 {@code m.stage_status} 也会出现
     * （{@code TRACKING_COMPLETE} 依赖的 ms 派生表，用于判断整单是否仍在产，不是节点筛选），
     * 而 mock 环境下 {@code visible()} 返回空，主查询没有自己的 " WHERE " 可定位。
     */
    @Test
    void waitDrilldownAppliesNoNodeDimensionCondition(){
        Captured wait = call(null, "OPEN", 3);
        String anchor = "JOIN (SELECT DISTINCT production_order_id FROM production_tracking_milestones) m ON m.production_order_id=p.id";
        assertTrue(wait.sql().contains(anchor), "应走等待下钻的 JOIN：" + wait.sql());
        String tail = wait.sql().substring(wait.sql().indexOf(anchor));
        assertTrue(tail.contains(">=?"), "等待下钻按下限匹配：" + tail);
        assertTrue(tail.contains("t.stopped_at IS NULL"), "等待下钻同样要排除停产：" + tail);
        assertFalse(tail.contains("m.stage_code=?"), "等待下钻不应带单个节点的过滤：" + tail);
        assertFalse(tail.contains("m.stage_status"), "等待下钻不应引入节点状态条件：" + tail);
    }
}
