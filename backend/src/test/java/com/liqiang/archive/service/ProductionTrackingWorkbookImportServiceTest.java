package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionTrackingWorkbookImportServiceTest {
    @Test
    void locatesNewSealerStageByHeaderWithoutShiftingOtherFields() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("封口机车间");
            var header=sheet.createRow(1);
            String[] columns={"订单号","产品名称","规格","尺寸","数量","计划开工", "订单要求生产完成日期",
                    "预计生产完成日期（延期时填写）","跟单阶段","","","","", "交付结果","延期原因","发货状态","业务员","备注"};
            for(int i=0;i<columns.length;i++)header.createCell(i).setCellValue(columns[i]);
            var stages=sheet.createRow(2);
            String[] names={"备料","模具生产","氧化/喷特氟龙","组装","入库"};
            for(int i=0;i<names.length;i++)stages.createCell(8+i).setCellValue(names[i]);
            var row=sheet.createRow(3);
            row.createCell(0).setCellValue("1234");
            row.createCell(1).setCellValue("封口机");
            row.createCell(4).setCellValue(2);
            row.createCell(5).setCellValue("2026-10-01");
            row.createCell(6).setCellValue("2026-10-30");
            row.createCell(7).setCellValue("2026-11-02");
            row.createCell(8).setCellValue("2026-10-03");
            row.createCell(9).setCellValue("2026-10-09");
            row.createCell(10).setCellValue("2026-10-11");
            row.createCell(11).setCellValue("2026-10-15");
            row.createCell(13).setCellValue("已交付");
            row.createCell(14).setCellValue("氧化排期");
            row.createCell(17).setCellValue("需要喷特氟龙");
            var formulaRow=sheet.createRow(4);
            formulaRow.createCell(0).setCellValue("1235");
            formulaRow.createCell(1).setCellValue("封口机");
            formulaRow.createCell(4).setCellValue(1);
            formulaRow.createCell(5).setCellValue("2026-10-02");
            formulaRow.createCell(6).setCellValue(java.sql.Date.valueOf("2026-10-31"));
            formulaRow.createCell(7).setCellFormula("G5");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formulaRow.getCell(7));
            book.write(out);
            bytes=out.toByteArray();
        }
        var service=new ProductionTrackingWorkbookImportService(null,new ObjectMapper(),null,null,null,null);
        Method parse=ProductionTrackingWorkbookImportService.class.getDeclaredMethod("parse",byte[].class,String.class,String.class);
        parse.setAccessible(true);
        var parsed=(List<?>)parse.invoke(service,bytes,"sample.xlsx","hash");
        Method payload=parsed.get(0).getClass().getDeclaredMethod("payload");
        payload.setAccessible(true);
        var data=new ObjectMapper().readTree((String)payload.invoke(parsed.get(0)));

        assertEquals("2026-11-02",data.path("forecastFinishDate").asText());
        assertEquals("氧化排期",data.path("delayReason").asText());
        assertEquals("OXIDATION",data.path("milestones").get(2).path("code").asText());
        assertEquals("2026-10-11",data.path("milestones").get(2).path("completedDate").asText());
        assertEquals("ASSEMBLY",data.path("milestones").get(3).path("code").asText());
        // 【同步范围 2026-10-10】这四类字段退出表格同步：payload 里不应再出现。
        for(String banned:List.of("plannedStartDate","deliveryResult","shipmentStatus","salesperson","remark",
                "productionCycleDays"))
            assertTrue(data.path(banned).isMissingNode(),"进度表 payload 不应再携带 "+banned+":"+data);
        var formulaData=new ObjectMapper().readTree((String)payload.invoke(parsed.get(1)));
        assertEquals("2026-10-31",formulaData.path("forecastFinishDate").asText(),
                "车间表中等于订单要求日期的公式值仍要保留为延期至日期，不能当作空值丢弃");
    }

    /**
     * 归档规则命中后，导入预览必须把该行判为 ARCHIVED（移出待处理队列），
     * 否则每次上传都会重新产生同一条待匹配行，主管只能反复手工跳过。
     */
    @Test
    void rowsMatchingAnArchiveRuleSkipThePendingQueue() throws Exception {
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("全自动气调机车间");
            var header=sheet.createRow(1);
            String[] columns={"订单号","产品名称","规格","尺寸","数量","计划开工","订单要求生产完成日期","延期原因"};
            for(int i=0;i<columns.length;i++)header.createCell(i).setCellValue(columns[i]);
            var stages=sheet.createRow(2);
            String[] names={"画图","备料","钣金","模具","组装","调试","入库"};
            for(int i=0;i<names.length;i++)stages.createCell(8+i).setCellValue(names[i]);
            // 无订单号、产品名为「模具」的行：这类行本来就对不上氚云工单。
            var row=sheet.createRow(3);
            row.createCell(1).setCellValue("模具");
            row.createCell(4).setCellValue(1);
            row.createCell(6).setCellValue("2026-10-10");
            book.write(out);
            bytes=out.toByteArray();
        }
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(),eq(Long.class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("product_key","模具","reason","非成品行")));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),anyString())).thenReturn(0);

        var service=new ProductionTrackingWorkbookImportService(jdbc,new ObjectMapper(),null,null,null,null);
        var preview=service.preview(new MockMultipartFile("file","sample.xlsx","application/octet-stream",bytes));

        assertEquals("ARCHIVED",preview.rows().get(0).status(),"命中归档规则的行应判为已归档："+preview.rows());
        assertEquals(1,preview.archived());
        assertEquals(0,preview.pending(),"归档规则命中后不应再产生待匹配行");
    }
}
