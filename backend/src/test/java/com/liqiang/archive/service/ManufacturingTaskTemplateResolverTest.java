package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 制程解析契约：**制程跟随产线**（车间默认制程为主线，产品名称只是覆盖项）。
 *
 * <p>2026-09-23 起「产品级与车间级都没有制程」**不再是错误** —— 氚云《产品零部件制程》里
 * 称重机车间与「无需报工」两条路线都标「是否有制程=否」，且《生产任务单管理》5,691 张里
 * 有 180 张是显式选「无需报工」的。改为返回「无制程」的解析结果，由建单方落一张
 * **不展开工序、直接待收货**的工单（免报工）。
 *
 * <p>仍须抛错的只剩**真配置事故**：车间不存在/停用、该产线下产品名称配置不唯一、选定制程已停用。
 */
class ManufacturingTaskTemplateResolverTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ManufacturingTaskTemplateResolver resolver = new ManufacturingTaskTemplateResolver(jdbc);

    private void configure(Long productTemplate, Long defaultTemplate) {
        Map<String,Object> workshop = new HashMap<>();
        workshop.put("workshop_name","测试车间");
        workshop.put("default_process_template_id",defaultTemplate);
        Map<String,Object> product = new HashMap<>();
        product.put("process_template_id",productTemplate);
        when(jdbc.queryForList(contains("FROM manufacturing_workshops"),eq(1L))).thenReturn(List.of(workshop));
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items"),eq(1L),eq("产品"))).thenReturn(List.of(product));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),anyLong())).thenReturn(1);
    }

    @Test void productOverridesWorkshop() {
        configure(20L,10L);
        assertEquals(20L,resolver.resolve(1L,"产品").templateId().longValue());
    }
    @Test void unassignedProductUsesWorkshop() {
        configure(null,10L);
        assertEquals(10L,resolver.resolve(1L,"产品").templateId().longValue());
    }

    /** 双空 = 无制程工单（免报工），**不是**配置事故 —— 09-23 放开的契约。 */
    @Test void missingBothMeansNoProcessOrder() {
        configure(null,null);
        var selection = resolver.resolve(1L,"产品");
        assertNull(selection.templateId(),"双空必须返回「无制程」，不得借用别的车间的制程");
        assertEquals("测试车间",selection.workshopName(),"产线名仍要带出来（建单要落到工单上）");
    }

    @Test void disabledProductTemplateDoesNotSilentlyFallback() {
        configure(20L,10L);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(20L))).thenReturn(0);
        assertThrows(IllegalArgumentException.class,()->resolver.resolve(1L,"产品"));
    }
}
