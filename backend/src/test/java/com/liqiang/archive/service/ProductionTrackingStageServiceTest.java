package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 制程管理的两条硬约束（都是为了防止历史跟单数据失联）：
 * ① 节点编码不可修改；② 已有跟单记录的节点只能停用、不能删除。
 * 再加按需工序必须给关键词——否则它永远不会被判为「本单要走」。
 */
class ProductionTrackingStageServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ProductionTrackingStageService service=new ProductionTrackingStageService(jdbc,mock(AuditLogService.class));

    private static ProductionTrackingStageService.StageSave save(String kind,String code,String label,boolean optional,
                                                                 String match) {
        return new ProductionTrackingStageService.StageSave(kind,code,label,optional,match,null,null,true);
    }

    @Test void workshopKindMustBeKnown() {
        assertThrows(IllegalArgumentException.class,() -> service.create(save("WEIGHER","PACK","备料齐套",false,null)));
    }

    @Test void stageCodeMustBeAnUpperSnakeIdentifier() {
        assertThrows(IllegalArgumentException.class,() -> service.create(save("SEALER","物料配齐","物料配齐",false,null)),
                "中文编码会让 SQL 与表头映射全线失效");
        assertThrows(IllegalArgumentException.class,() -> service.create(save("SEALER","1PACK","备料齐套",false,null)));
    }

    @Test void labelIsRequired() {
        assertThrows(IllegalArgumentException.class,() -> service.create(save("SEALER","PACK","  ",false,null)));
    }

    @Test void optionalStageWithoutKeywordsIsRejected() {
        stubNoConflict();
        assertThrows(IllegalArgumentException.class,() -> service.create(save("SEALER","PACK","备料齐套",true,null)),
                "没有命中关键词的按需工序永远不会被判为「本单要走」");
    }

    @Test void stageCodeCannotBeRenamed() {
        when(jdbc.queryForMap(anyString(),any(Object.class)))
                .thenReturn(Map.of("workshop_kind","SEALER","stage_code","MATERIAL_READY","stage_label","物料配齐"));
        stubNoConflict();
        var thrown=assertThrows(IllegalArgumentException.class,
                () -> service.update(7L,save("SEALER","PACKING","物料配齐",false,null)));
        assertTrue(thrown.getMessage().contains("编码不可修改"),thrown.getMessage());
    }

    @Test void stageWithTrackingRecordsCannotBeDeleted() {
        when(jdbc.queryForMap(anyString(),any(Object.class)))
                .thenReturn(Map.of("workshop_kind","SEALER","stage_code","ASSEMBLY","stage_label","组装"));
        // 已有 12 条跟单记录
        doReturn(12).when(jdbc).queryForObject(anyString(),eq(Integer.class),any(Object[].class));
        var thrown=assertThrows(IllegalArgumentException.class,() -> service.remove(3L));
        assertTrue(thrown.getMessage().contains("停用"),"应引导改为停用而不是直接删："+thrown.getMessage());
    }

    /** create/update 里的重名与重编码检查都返回 0（无冲突），以便走到后面的业务校验。 */
    private void stubNoConflict() {
        doReturn(0).when(jdbc).queryForObject(anyString(),eq(Integer.class),any(Object[].class));
    }
}
