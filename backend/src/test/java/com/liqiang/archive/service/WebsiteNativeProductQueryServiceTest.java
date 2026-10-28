package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsiteNativeProductQueryServiceTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void adminDraftPreviewUsesWebsiteTablesAndIncludesDraftMedia() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(RowMapper.class), eq(7L))).thenReturn(List.of());

        var result = new WebsiteNativeProductQueryService(jdbc, new ObjectMapper()).previewDetail(7L);

        assertTrue(result.isEmpty());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(7L));
        assertTrue(sql.getValue().contains("FROM website_products p"));
        assertTrue(sql.getValue().contains("hm.status IN ('DRAFT', 'PUBLISHED')"));
        assertTrue(sql.getValue().contains("p.status IN ('DRAFT', 'PUBLISHED')"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void filterOptionsComeOnlyFromPublishedWebsiteRows() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("SELECT c.name")) return List.of("封口机");
            ResultSet row = mock(ResultSet.class);
            when(row.getString("application_tags")).thenReturn("[\"鲜食\",\"肉类\"]");
            when(row.getString("container_tags")).thenReturn("[\"餐盒\"]");
            when(row.getString("process_tags")).thenReturn("[\"气调\"]");
            when(row.getString("capacity_level")).thenReturn("半自动");
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        var options = new WebsiteNativeProductQueryService(jdbc, new ObjectMapper()).filterOptions();
        assertEquals(List.of("封口机"), options.equipmentCategories());
        assertEquals(List.of("鲜食", "肉类"), options.applications());
        assertEquals(List.of("餐盒"), options.containers());
        assertEquals(List.of("气调"), options.processes());
        assertEquals(List.of("半自动"), options.capacityLevels());
    }
}
