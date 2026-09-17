package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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
        assertEquals(20L,resolver.resolve(1L,"产品").templateId());
    }
    @Test void unassignedProductUsesWorkshop() {
        configure(null,10L);
        assertEquals(10L,resolver.resolve(1L,"产品").templateId());
    }
    @Test void missingBothRequiresConfiguration() {
        configure(null,null);
        assertThrows(IllegalArgumentException.class,()->resolver.resolve(1L,"产品"));
    }
    @Test void disabledProductTemplateDoesNotSilentlyFallback() {
        configure(20L,10L);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(20L))).thenReturn(0);
        assertThrows(IllegalArgumentException.class,()->resolver.resolve(1L,"产品"));
    }
}
