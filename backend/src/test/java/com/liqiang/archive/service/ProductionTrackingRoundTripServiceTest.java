package com.liqiang.archive.service;

import com.liqiang.archive.service.ProductionTrackingRoundTripService.OrderState;
import com.liqiang.archive.service.ProductionTrackingRoundTripService.ParsedRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导回：只测**脱库的纯函数**（解析自家导出件、行差异判定）。
 * 真正的写入（授权 / 版本 / 节点 / 历史 / 审计）复用 add()，由真实 HTTP 验收覆盖。
 */
class ProductionTrackingRoundTripServiceTest {

    /** 本系统导出件的表头（与会话里 EXPORT_COLUMNS 的 label 一致；列顺序故意打乱，验证按表头文字定位）。 */
    private static final String[] EXPORT_HEADERS={"任务号","跟单备注","产品","规格","车间","数量",
            "车间预计完成","延期原因","生产交付结果","逾期天数","备料","模具生产","氧化/喷特氟龙","组装","入库",
            "采购入库日期"};

    private static byte[] workbook(List<String[]> rows,boolean withGuideSheet)throws Exception{
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("封口机车间");
            var header=sheet.createRow(0);
            for(int i=0;i<EXPORT_HEADERS.length;i++)header.createCell(i).setCellValue(EXPORT_HEADERS[i]);
            for(int r=0;r<rows.size();r++){
                var row=sheet.createRow(r+1);
                String[] values=rows.get(r);
                for(int c=0;c<values.length;c++)
                    if(values[c]!=null)row.createCell(c).setCellValue(values[c]);
            }
            if(withGuideSheet){
                var guide=book.createSheet("导出说明");
                guide.createRow(0).createCell(0).setCellValue("项");
                guide.createRow(0).createCell(1).setCellValue("内容");
                guide.createRow(1).createCell(0).setCellValue("可回写列");
                guide.createRow(1).createCell(1).setCellValue("跟单备注 / 车间预计完成 / 延期原因 / 各节点完成日期");
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    /** 按表头顺序拼一行数据（只填关心的列，其余留空＝不修改）。 */
    private static String[] row(Map<String,String> values){
        String[] cells=new String[EXPORT_HEADERS.length];
        for(int i=0;i<EXPORT_HEADERS.length;i++)cells[i]=values.get(EXPORT_HEADERS[i]);
        return cells;
    }

    @Test
    void parsesExportWorkbookAndIgnoresGuideSheet()throws Exception{
        var bytes=workbook(List.<String[]>of(row(Map.of(
                "任务号","SCGD20260005889","产品","手动片膜","车间","封口机车间",
                "车间预计完成","2026-10-20","延期原因","氧化排期","跟单备注","客户催货",
                "备料","2026-10-08","组装","2026/10/9","入库","无","采购入库日期","2026-10-10"))),true);
        var parsed=ProductionTrackingRoundTripService.parse(bytes);
        assertEquals(1,parsed.size());
        var line=parsed.get(0);
        assertEquals("封口机车间",line.sheet());
        assertEquals(2,line.excelRow());
        assertEquals("SCGD20260005889",line.orderNo());
        assertEquals(LocalDate.of(2026,10,20),line.fileForecast());
        assertEquals("氧化排期",line.delayReason());
        assertEquals("客户催货",line.remark());
        // 阶段列按表头文字定位：「组装」写成 2026/10/9 也要认；「无」＝本单不走该工序 ⇒ 不修改
        assertEquals(LocalDate.of(2026,10,8),line.stageDates().get("MATERIAL"));
        assertEquals(LocalDate.of(2026,10,9),line.stageDates().get("ASSEMBLY"));
        // 「采购入库日期」这类列名含节点名，但那是派生列、不是节点列 —— 不能误读成「入库」节点完成
        assertNull(line.stageDates().get("WAREHOUSE"));
        assertTrue(line.problems().isEmpty());
    }

    @Test
    void acceptsExcelDateSerialAndRejectsUnreadableDate()throws Exception{
        byte[] bytes;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("封口机车间");
            var header=sheet.createRow(0);
            for(int i=0;i<EXPORT_HEADERS.length;i++)header.createCell(i).setCellValue(EXPORT_HEADERS[i]);
            var row=sheet.createRow(1);
            row.createCell(0).setCellValue("SCGD1");
            var cell=row.createCell(10); // 备料
            cell.setCellValue(java.sql.Date.valueOf("2026-10-09"));
            var style=book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            cell.setCellStyle(style);
            var bad=sheet.createRow(2);
            bad.createCell(0).setCellValue("SCGD2");
            bad.createCell(10).setCellValue("上旬");
            book.write(out);
            bytes=out.toByteArray();
        }
        var parsed=ProductionTrackingRoundTripService.parse(bytes);
        assertEquals(LocalDate.of(2026,10,9),parsed.get(0).stageDates().get("MATERIAL"));
        assertEquals("「备料」的日期无法识别：上旬",parsed.get(1).problems().get(0));
    }

    @Test
    void rejectsFileWithoutTrackingColumns()throws Exception{
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("随便");
            sheet.createRow(0).createCell(0).setCellValue("订单号");
            book.write(out);
            var error=assertThrows(IllegalArgumentException.class,
                    ()->ProductionTrackingRoundTripService.parse(out.toByteArray()));
            assertTrue(error.getMessage().contains("任务号"));
        }
    }

    @Test
    void flagsRowWithoutOrderNo()throws Exception{
        var parsed=ProductionTrackingRoundTripService.parse(workbook(List.<String[]>of(
                row(Map.of("产品","手动片膜","跟单备注","没有任务号")),
                row(Map.of("任务号","SCGD3","跟单备注","正常行"))),false));
        assertEquals("缺少任务号",parsed.get(0).problems().get(0));
        assertTrue(parsed.get(1).problems().isEmpty());
    }

    // ------------------------------------------------------------- 差异判定（空白＝不修改）

    private static OrderState state(Map<String,Object> overrides){
        Map<String,Object> base=new LinkedHashMap<>();
        base.put("id",7L);
        base.put("orderNo","SCGD20260005889");
        base.put("productName","手动片膜");
        base.put("workshop","封口机车间");
        base.put("sourceExpected",LocalDate.of(2026,10,15));
        base.put("dueDate",LocalDate.of(2026,10,14));
        base.put("version",3L);
        base.put("forecast",null);
        base.put("delayReason",null);
        base.put("remark","原备注");
        base.put("stages",new LinkedHashMap<String,LocalDate>());
        base.put("done",new LinkedHashSet<String>());
        base.putAll(overrides);
        @SuppressWarnings("unchecked")
        var stages=(Map<String,LocalDate>)base.get("stages");
        @SuppressWarnings("unchecked")
        var done=(Set<String>)base.get("done");
        return new OrderState((Long)base.get("id"),(String)base.get("orderNo"),(String)base.get("productName"),
                (String)base.get("workshop"),(LocalDate)base.get("sourceExpected"),(LocalDate)base.get("dueDate"),
                (Long)base.get("version"),(LocalDate)base.get("forecast"),(String)base.get("delayReason"),
                (String)base.get("remark"),null,null,false,stages,done);
    }

    private static ParsedRow parsed(Map<String,Object> overrides){
        Map<String,Object> base=new LinkedHashMap<>();
        base.put("orderNo","SCGD20260005889");
        base.put("fileForecast",null);
        base.put("delayReason",null);
        base.put("remark",null);
        base.put("stages",new LinkedHashMap<String,LocalDate>());
        base.putAll(overrides);
        @SuppressWarnings("unchecked")
        var stages=(Map<String,LocalDate>)base.get("stages");
        return new ParsedRow("封口机车间",2,(String)base.get("orderNo"),"手动片膜","封口机车间",
                (LocalDate)base.get("fileForecast"),(String)base.get("delayReason"),(String)base.get("remark"),
                stages,new ArrayList<>());
    }

    @Test
    void blankCellsMeanNoChange()throws Exception{
        var diff=ProductionTrackingRoundTripService.diff(parsed(Map.of()),state(Map.of()));
        assertTrue(diff.changes().isEmpty(),"空白＝不修改：没填的行不应产生任何变更");
        assertEquals("原备注",diff.remark());
        assertNull(diff.forecast());
    }

    @Test
    void filledForecastDifferentFromSourceExpectedMeansDelay()throws Exception{
        var diff=ProductionTrackingRoundTripService.diff(
                parsed(Map.of("fileForecast",LocalDate.of(2026,10,20))),state(Map.of()));
        assertEquals(LocalDate.of(2026,10,20),diff.forecast());
        assertTrue(diff.forecastChanged());
        assertEquals("延期至",diff.changes().get(0).field());
        assertEquals("2026-10-20",diff.changes().get(0).after());
    }

    @Test
    void forecastEqualToSourceExpectedClearsExistingDelay()throws Exception{
        // 用户把「车间预计完成」改回车间给的源日期 ⇒ 语义是「取消延期」，而不是「登记一个与源相同的延期」
        //（手工跟进的 add() 会直接拒绝后者）。
        var diff=ProductionTrackingRoundTripService.diff(parsed(Map.of("fileForecast",LocalDate.of(2026,10,15))),
                state(Map.of("forecast",LocalDate.of(2026,10,20))));
        assertTrue(diff.forecastChanged());
        assertNull(diff.forecast());
        assertEquals("（清空）",diff.changes().get(0).after());
    }

    @Test
    void sameForecastMeansNoChange()throws Exception{
        var diff=ProductionTrackingRoundTripService.diff(parsed(Map.of("fileForecast",LocalDate.of(2026,10,20))),
                state(Map.of("forecast",LocalDate.of(2026,10,20))));
        assertTrue(diff.changes().isEmpty());
    }

    @Test
    void remarkAndReasonOnlyWrittenWhenFilled()throws Exception{
        var filled=ProductionTrackingRoundTripService.diff(parsed(Map.of("remark","客户催货","delayReason","氧化排期")),
                state(Map.of()));
        assertEquals(2,filled.changes().size());
        assertTrue(filled.remarkChanged());
        assertTrue(filled.delayReasonChanged());
        // 与库内相同的值不写（避免每次导回都产生一条「无变化」的跟单历史）
        var same=ProductionTrackingRoundTripService.diff(parsed(Map.of("remark","原备注")),state(Map.of()));
        assertTrue(same.changes().isEmpty());
    }

    @Test
    void stageDateCompletesNodeButIdenticalDoneDateIsNoChange()throws Exception{
        var stages=new LinkedHashMap<String,LocalDate>();
        stages.put("MATERIAL",LocalDate.of(2026,10,8));
        stages.put("ASSEMBLY",LocalDate.of(2026,10,5));
        var done=new LinkedHashSet<String>();
        done.add("ASSEMBLY");
        var diff=ProductionTrackingRoundTripService.diff(parsed(Map.of("stages",stages)),
                state(Map.of("stages",Map.of("ASSEMBLY",LocalDate.of(2026,10,5)),"done",done)));
        assertEquals(List.of("MATERIAL"),diff.stageCodes(),"已完成的节点日期没变就不该再写一次");
        assertEquals("备料",diff.changes().get(0).field());
        assertEquals("已完成 2026-10-08",diff.changes().get(0).after());
    }

    @Test
    void dateFormatsAndNotApplicableMarkers()throws Exception{
        var problems=new ArrayList<String>();
        assertEquals(LocalDate.of(2026,10,8),ProductionTrackingRoundTripService.parseDate("2026-10-8","备料",problems));
        assertEquals(LocalDate.of(2026,10,8),ProductionTrackingRoundTripService.parseDate("2026/10/08","备料",problems));
        assertEquals(LocalDate.of(2026,10,8),ProductionTrackingRoundTripService.parseDate("2026.10.8","备料",problems));
        assertEquals(LocalDate.of(2026,10,8),ProductionTrackingRoundTripService.parseDate("2026-10-08 00:00:00","备料",problems));
        // 这些写法表示「本单不走这道按需工序」⇒ 不修改，且不算错误
        assertNull(ProductionTrackingRoundTripService.parseDate("无","氧化/喷特氟龙",problems));
        assertNull(ProductionTrackingRoundTripService.parseDate("/","氧化/喷特氟龙",problems));
        assertNull(ProductionTrackingRoundTripService.parseDate("","备料",problems));
        assertTrue(problems.isEmpty());
        assertNull(ProductionTrackingRoundTripService.parseDate("待定","组装",problems));
        assertEquals(1,problems.size());
        assertTrue(problems.get(0).contains("「组装」的日期无法识别：待定"));
    }

    @Test
    void headerKeyDropsWhitespaceAndParentheses()throws Exception{
        assertEquals("车间预计完成",ProductionTrackingRoundTripService.headerKey(" 车间预计完成 "));
        assertEquals("车间预计完成",ProductionTrackingRoundTripService.headerKey("车间预计\u3000完成"));
        assertEquals("预计生产完成日期",ProductionTrackingRoundTripService.headerKey("预计生产完成日期（延期时填写）"));
        assertNull(ProductionTrackingPolicy.stageCodeOfLabel("采购入库日期"));
        assertEquals("WAREHOUSE",ProductionTrackingPolicy.stageCodeOfLabel("入库"));
        assertEquals("MOLD",ProductionTrackingPolicy.stageCodeOfLabel("模具生产"));
        assertEquals("MOLD",ProductionTrackingPolicy.stageCodeOfLabel("模具"));
        assertEquals("OXIDATION",ProductionTrackingPolicy.stageCodeOfLabel("氧化/喷特氟龙"));
    }
}
