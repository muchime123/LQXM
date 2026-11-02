package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H3CashflowSourceRowNumberTest {
    @Test
    void movingAnUnchangedEntryChangesBatchLocationButNotBusinessHash() throws Exception {
        List<?> before = parse(singleEntryWorkbook(3), "journal.xlsx");
        List<?> after = parse(singleEntryWorkbook(7), "journal.xlsx");
        assertEquals("3", sourceRows(before).get(1));
        assertEquals("7", sourceRows(after).get(1));
        assertEquals(hashOf(before.get(1)), hashOf(after.get(1)));
    }

    @Test
    void changedHistoricalKeyIsBlockedBeforeAutomaticImport() throws Exception {
        List<?> groups = parse(singleEntryWorkbook(3), "journal.xlsx");
        String incomingKey = externalIdOf(groups.get(1));
        Map<String, java.util.Set<String>> incoming = Map.of("测试账户", java.util.Set.of(incomingKey));
        assertEquals(List.of(), H3FactImportService.missingCashflowAccounts(incoming,
                List.of(Map.of("account", "测试账户", "externalId", incomingKey))));
        assertEquals(List.of("测试账户"), H3FactImportService.missingCashflowAccounts(incoming,
                List.of(Map.of("account", "测试账户", "externalId", "已被修改的旧键"))));
    }

    @Test
    void streamingAndWorkbookPathsKeepOriginalExcelRowNumbers() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("测试账户");
            String[] headers = {"月份", "日期", "供应商", "摘要", "客   户    供应商", "报销人", "是否开票",
                    "收入", "支出", "结存", "账户", "返回首页"};
            var header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            var opening = sheet.createRow(1);
            opening.createCell(3).setCellValue("上期结存");
            opening.createCell(9).setCellValue(100);
            var first = sheet.createRow(2);
            first.createCell(1).setCellValue("2026-01-01");
            first.createCell(3).setCellValue("第一笔");
            first.createCell(7).setCellValue(20);
            first.createCell(9).setCellValue(120);
            sheet.createRow(3); // 空行不能让下一笔误记为第 4 行
            var second = sheet.createRow(4);
            second.createCell(1).setCellValue("2026-01-02");
            second.createCell(3).setCellValue("第二笔");
            second.createCell(8).setCellValue(5);
            second.createCell(9).setCellValue(115);
            workbook.write(out);
            bytes = out.toByteArray();
        }

        assertEquals(List.of("2", "3", "5"), sourceRows(parse(bytes, "journal.xlsx")));
        assertEquals(List.of("2", "3", "5"), sourceRows(parse(bytes, "journal.xls")));
    }

    @Test
    void balanceShiftKeepsCoreIdentityAndNormalizesExcelNumbers() throws Exception {
        var service = new H3FactImportService(null, null, null, new ObjectMapper(), null, null, null, null, null);
        Method identity = H3FactImportService.class.getDeclaredMethod("cashflowIdentity", Map.class, boolean.class);
        identity.setAccessible(true);
        Map<String, String> oldRow = Map.of("账户", "安徽盒多多工商银行", "日期", "2026-10-17",
                "收入", "200000.0000", "结存", "228424.23", "摘要", "旧注释");
        Map<String, String> newRow = Map.of("账户", "安徽盒多多工商银行", "日期", "2026\"年\"10\"月\"17\"日\"",
                "收入", "200000", "结存", "242184.2300000001", "摘要", "新注释");
        assertEquals(identity.invoke(service, oldRow, false), identity.invoke(service, newRow, false));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                identity.invoke(service, oldRow, true), identity.invoke(service, newRow, true));
    }

    private List<?> parse(byte[] bytes, String fileName) throws Exception {
        var service = new H3FactImportService(null, null, null, new ObjectMapper(), null, null, null, null, null);
        Method parse = H3FactImportService.class.getDeclaredMethod("parse", H3FactImportService.FactType.class,
                org.springframework.web.multipart.MultipartFile.class);
        parse.setAccessible(true);
        return (List<?>) parse.invoke(service, H3FactImportService.FactType.CASHFLOW_JOURNAL,
                new MockMultipartFile("file", fileName, "application/octet-stream", bytes));
    }

    private byte[] singleEntryWorkbook(int excelRow) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("测试账户");
            String[] headers = {"日期", "摘要", "收入", "支出", "结存"};
            var header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);
            var opening = sheet.createRow(1);
            opening.createCell(1).setCellValue("上期结存");
            opening.createCell(4).setCellValue(100);
            var entry = sheet.createRow(excelRow - 1);
            entry.createCell(0).setCellValue("2026-01-01");
            entry.createCell(1).setCellValue("同一笔");
            entry.createCell(2).setCellValue(20);
            entry.createCell(4).setCellValue(120);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String hashOf(Object group) throws Exception {
        Method hash = group.getClass().getDeclaredMethod("hash");
        hash.setAccessible(true);
        return (String) hash.invoke(group);
    }

    private String externalIdOf(Object group) throws Exception {
        Method externalId = group.getClass().getDeclaredMethod("externalId");
        externalId.setAccessible(true);
        return (String) externalId.invoke(group);
    }

    @SuppressWarnings("unchecked")
    private List<String> sourceRows(List<?> groups) throws Exception {
        return groups.stream().map(group -> {
            try {
                Method main = group.getClass().getDeclaredMethod("main");
                main.setAccessible(true);
                return ((Map<String, String>) main.invoke(group)).get("__sourceRowNumber");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }).toList();
    }
}
