package com.liqiang.archive.controller;

import com.liqiang.archive.dto.H3FactImportDtos.BatchProgress;
import com.liqiang.archive.dto.H3FactImportDtos.StageAccepted;
import com.liqiang.archive.dto.H3FactImportDtos.StagingPreview;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.H3BusinessQueryService;
import com.liqiang.archive.service.H3FactImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 跟单员「生产任务单」导入入口的边界测试（LQ 10-19）：
 * - 只允许导入生产任务单批次（拿别人的出库/收款批次 id 来确认必须被拒）；
 * - 解析与写库完全复用氚云导入中心的服务，不另写一套；
 * - 权限码是 manufacturing.followup —— 跟单员不需要 integration.h3（导入中心）权限。
 */
class ProductionTaskImportControllerTest {
    private final H3FactImportService imports=mock(H3FactImportService.class);
    private final H3FactImportService importProxy=mock(H3FactImportService.class);
    private final H3BusinessQueryService batches=mock(H3BusinessQueryService.class);
    private final ProductionTaskImportController controller=new ProductionTaskImportController(imports,batches);

    private ProductionTaskImportController controller() {
        ReflectionTestUtils.setField(controller,"importProxy",importProxy);
        return controller;
    }

    @Test
    void stageCreatesProductionBatchAndTriggersAsyncParse() throws Exception {
        var file=new MockMultipartFile("file","生产任务单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",new byte[]{1,2,3});
        when(imports.createBatchRecord(eq("production"),eq("生产任务单.xlsx"),eq(3L),anyString()))
                .thenReturn(new StageAccepted(11L,"PRODUCTION","生产任务单.xlsx",false,"UPLOADED","ok"));

        var accepted=controller().stage(file);

        assertEquals(11L,accepted.batchId());
        // 解析仍走导入中心同一方法，且类型被钉死为 PRODUCTION —— 调用方传不进别的类型。
        // Mockito 5 已移除 AdditionalMatchers.aryEq；eq() 对数组同样是逐元素比较，语义等价。
        var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(importProxy).asyncParseAndStage(eq(11L),eq(H3FactImportService.FactType.PRODUCTION),
                bytes.capture(),eq("生产任务单.xlsx"));
        assertArrayEquals(new byte[]{1,2,3},bytes.getValue(),"落盘的必须是原始文件字节，不能被改写");
    }

    @Test
    void stageSkipsParseWhenSameFileAlreadyStaged() throws Exception {
        var file=new MockMultipartFile("file","生产任务单.xlsx","application/octet-stream",new byte[]{9});
        when(imports.createBatchRecord(anyString(),anyString(),anyLong(),anyString()))
                .thenReturn(new StageAccepted(12L,"PRODUCTION","生产任务单.xlsx",true,"PREVIEW_READY","命中"));

        var accepted=controller().stage(file);

        assertTrue(accepted.hashHit());
        verifyNoInteractions(importProxy);
    }

    @Test
    void stageRejectsMissingFileWithTrackingWording() {
        var error=assertThrows(IllegalArgumentException.class,()->controller().stage(null));
        assertEquals("请选择氚云导出的生产任务单 Excel 文件",error.getMessage());
        verifyNoInteractions(imports);
    }

    @Test
    void progressAndPreviewAndConfirmAllRejectNonProductionBatch() {
        when(batches.importBatchDataType(99L)).thenReturn("OUTBOUND");

        var error=assertThrows(IllegalArgumentException.class,()->controller().preview(99L));
        assertEquals("只能导入生产任务单批次",error.getMessage());
        assertThrows(IllegalArgumentException.class,()->controller().progress(99L));
        assertThrows(IllegalArgumentException.class,()->controller().confirm(99L));
        verifyNoInteractions(imports);
    }

    @Test
    void productionBatchReadsGoThroughTheSharedImportService() {
        when(batches.importBatchDataType(21L)).thenReturn("PRODUCTION");
        var progress=new BatchProgress(21L,"PREVIEW_READY",100,"PREVIEW",null,3,1,1,1,null);
        var preview=new StagingPreview(21L,null);
        when(imports.getBatchProgress(21L)).thenReturn(progress);
        when(imports.getPreviewFromStaging(21L)).thenReturn(preview);

        assertSame(progress,controller().progress(21L));
        assertSame(preview,controller().preview(21L));
    }

    @Test
    void entryIsGuardedByFollowupPermissionNotImportCenterPermission() {
        var guard=ProductionTaskImportController.class.getAnnotation(RequirePermission.class);
        assertNotNull(guard,"必须声明权限，否则跟单员入口会变成人人可调");
        assertArrayEquals(new String[]{"manufacturing.followup"},guard.value());
    }
}
