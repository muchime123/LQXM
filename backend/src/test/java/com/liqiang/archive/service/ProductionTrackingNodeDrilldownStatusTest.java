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
    @Test
    void waitDrilldownIgnoresNodeStatus(){
        String sql = call(null, "OPEN", 3).sql();
        assertTrue(sql.contains(">=?"), "等待下钻按下限匹配：" + sql);
        assertFalse(sql.contains("m.stage_status"), "等待下钻不应引入节点状态条件：" + sql);
    }

}
