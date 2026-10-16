package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionTrackingWorkbookReviewServiceTest {
    @Test
    void workbookOnlyUpdateStopsIfHumanFollowupAppearedAfterPreview() {
        var jdbc=mock(JdbcTemplate.class);
        long orderId=19L;
        when(jdbc.queryForList(contains("SELECT * FROM production_tracking_state"),eq(orderId)))
                .thenReturn(List.of(Map.of("version",2)));
        when(jdbc.queryForObject(contains("created_by_employee_id>0"),eq(Integer.class),eq(orderId)))
                .thenReturn(1);
        var review=new ProductionTrackingWorkbookReviewService(jdbc,new ObjectMapper(),null,null,null,null);

        assertThrows(ResponseStatusException.class,()->review.applyWorkbookOnly(orderId,
                "{\"forecastFinishDate\":\"2026-10-20\"}",5L));
        verify(jdbc,never()).update(contains("forecast_finish_date"),
                org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void stageWithSameDateStillNeedsReviewWhenLocalStatusIsNotDone() {
        var jdbc=mock(JdbcTemplate.class);
        long orderId=18L;
        when(jdbc.queryForList(contains("SELECT * FROM production_tracking_state"),org.mockito.ArgumentMatchers.eq(orderId)))
                .thenReturn(List.of(Map.of("version",2)));
        when(jdbc.queryForList(contains("SELECT stage_code,stage_status,completed_date"),org.mockito.ArgumentMatchers.eq(orderId)))
                .thenReturn(List.of(Map.of("stage_code","MOLD","stage_status","IN_PROGRESS",
                        "completed_date",Date.valueOf("2026-09-15"))));
        when(jdbc.queryForObject(contains("SELECT production_line"),org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(orderId))).thenReturn("封口机车间");
        var review=new ProductionTrackingWorkbookReviewService(jdbc,new ObjectMapper(),null,null,null,null);

        var changes=review.differences(orderId,"""
                {"milestones":[{"code":"MOLD","completedDate":"2026-09-15"}]}
                """);

        assertEquals(List.of("stage:MOLD"),changes.stream().map(
                ProductionTrackingWorkbookReviewService.Difference::key).toList());
    }

    @Test
    void proposesOnlyFilledChangedFieldsIncludingNewOptionalStage() {
        var jdbc=mock(JdbcTemplate.class);
        long orderId=17L;
        when(jdbc.queryForList(contains("SELECT * FROM production_tracking_state"),org.mockito.ArgumentMatchers.eq(orderId)))
                .thenReturn(List.of(Map.of("version",3,"remark","人工备注","delivery_result","已交付")));
        when(jdbc.queryForList(contains("SELECT stage_code,completed_date"),org.mockito.ArgumentMatchers.eq(orderId)))
                .thenReturn(List.of(Map.of("stage_code","MOLD","stage_status","DONE","completed_date",Date.valueOf("2026-09-15"))));
        when(jdbc.queryForObject(contains("SELECT production_line"),org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(orderId))).thenReturn("封口机车间");
        var review=new ProductionTrackingWorkbookReviewService(jdbc,new ObjectMapper(),null,null,null,null);

        var changes=review.differences(orderId,"""
                {"remark":"","deliveryResult":"已交付","plannedStartDate":"2026-10-01","shipmentStatus":"已发货","milestones":[
                  {"code":"MOLD","completedDate":"2026-09-16"},
                  {"code":"OXIDATION","completedDate":"2026-09-18"},
                  {"code":"ASSEMBLY","completedDate":null}]}
                """);

        assertEquals(List.of("stage:MOLD","stage:OXIDATION"),changes.stream().map(
                ProductionTrackingWorkbookReviewService.Difference::key).toList());
        assertTrue(changes.stream().noneMatch(item->"remark".equals(item.key())),
                "表格空单元格不能清除人工备注");
        // 【同步范围 2026-10-10】旧 payload 里残留的 计划开工/交付结果/发货状态 不得再进差异确认。
        for(String banned:List.of("plannedStartDate","deliveryResult","shipmentStatus","remark"))
            assertTrue(changes.stream().noneMatch(item->banned.equals(item.key())),
                    banned+" 已退出表格同步，不应出现在差异里");
    }
}
