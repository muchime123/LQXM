package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionTrackingProcessServiceTest {
    private static final List<ProductionTrackingProcessService.Node> NODES=List.of(
            new ProductionTrackingProcessService.Node("DRAWING",1,false),
            new ProductionTrackingProcessService.Node("SHEET_METAL",2,false),
            new ProductionTrackingProcessService.Node("MOLD",3,false),
            new ProductionTrackingProcessService.Node("ASSEMBLY",4,false));
    private static final Set<String> DICTIONARY=Set.of("DRAWING","SHEET_METAL","MOLD","ASSEMBLY");

    @Test void parallelBranchesCanConverge() {
        var edges=List.of(
                new ProductionTrackingProcessService.Edge("DRAWING","SHEET_METAL"),
                new ProductionTrackingProcessService.Edge("DRAWING","MOLD"),
                new ProductionTrackingProcessService.Edge("SHEET_METAL","ASSEMBLY"),
                new ProductionTrackingProcessService.Edge("MOLD","ASSEMBLY"));
        assertDoesNotThrow(()->ProductionTrackingProcessService.validate(
                new ProductionTrackingProcessService.Draft(NODES,edges,"并行加工"),DICTIONARY));
    }

    @Test void cyclicOrUnknownDependenciesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->ProductionTrackingProcessService.validate(
                new ProductionTrackingProcessService.Draft(NODES,List.of(
                        new ProductionTrackingProcessService.Edge("DRAWING","MOLD"),
                        new ProductionTrackingProcessService.Edge("MOLD","DRAWING")),null),DICTIONARY));
        assertThrows(IllegalArgumentException.class,()->ProductionTrackingProcessService.validate(
                new ProductionTrackingProcessService.Draft(NODES,List.of(
                        new ProductionTrackingProcessService.Edge("DRAWING","OXIDATION")),null),DICTIONARY));
    }

    @Test void mysqlTinyintFlagsMayArriveAsBooleanOrNumber() {
        assertTrue(ProductionTrackingProcessService.flag(Boolean.TRUE));
        assertTrue(ProductionTrackingProcessService.flag(1));
        assertFalse(ProductionTrackingProcessService.flag(Boolean.FALSE));
        assertFalse(ProductionTrackingProcessService.flag(0));
    }

    @Test void repeatedImportCannotReplaceExistingSnapshot() {
        JdbcTemplate jdbc=new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args) {
                return type.cast(1);
            }
            @Override public int update(String sql,Object... args) {
                throw new AssertionError("重复导入不得写快照");
            }
        };
        new ProductionTrackingProcessService(jdbc).captureNewOrder(42L);
    }

    @Test void missingIntermediateDateExcludesOnlyAffectedIntervalNotTrustedWholeCycle() {
        JdbcTemplate jdbc=new JdbcTemplate() {
            @Override public Map<String,Object> queryForMap(String sql,Object... args) {
                return Map.of("id",7L,"productConfigId",2L,"workshopKind","MACHINE",
                        "versionNo",1,"state","PUBLISHED","note","");
            }
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                if(sql.contains("process_template_nodes"))return List.of(
                        Map.of("code","DRAWING","label","画图","sortNo",1,"conditional",0),
                        Map.of("code","ASSEMBLY","label","组装","sortNo",2,"conditional",0));
                if(sql.contains("process_template_edges"))return List.of(
                        Map.of("predecessor","DRAWING","successor","ASSEMBLY"));
                return List.of(
                        sample(1,"DRAWING","2026-10-01","2026-10-02"),
                        sample(1,"ASSEMBLY","2026-10-01","2026-10-05"),
                        sample(2,"DRAWING","2026-10-01",null),
                        sample(2,"ASSEMBLY","2026-10-01","2026-10-06"));
            }
        };
        var report=new ProductionTrackingProcessService(jdbc).versionDurationSamples(7);
        assertEquals(true,report.get("linear"));
        @SuppressWarnings("unchecked") var stages=(List<Map<String,Object>>)report.get("stages");
        assertEquals(1,stages.get(1).get("sampleCount"));
        @SuppressWarnings("unchecked") var excluded=(Map<String,Integer>)stages.get(1).get("excluded");
        assertEquals(1,excluded.get("PREVIOUS_DATE_MISSING"));
        @SuppressWarnings("unchecked") var whole=(Map<String,Object>)report.get("whole");
        assertEquals(2,whole.get("sampleCount"));
    }

    private static Map<String,Object> sample(int id,String stage,String anchor,String completed) {
        var row=new java.util.HashMap<String,Object>();
        row.put("order_id",id);row.put("stage_code",stage);row.put("anchor_date",LocalDate.parse(anchor));
        row.put("applicability","APPLICABLE");row.put("stage_status","DONE");
        row.put("completed_date",completed==null?null:LocalDate.parse(completed));
        return row;
    }
}
