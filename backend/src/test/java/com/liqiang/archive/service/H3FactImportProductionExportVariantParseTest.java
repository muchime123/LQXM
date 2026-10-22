package com.liqiang.archive.service;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生产任务单「两种氚云导出件都能导入」的端到端解析契约（2026-10-22）。
 *
 * <p><b>背景</b>：氚云「生产任务单管理」列表页按导出人权限给出两种文件：
 * <ul>
 *   <li><b>管理员件</b>：97 列，含 19 个「零部件制程.*」子表列，表头带「(必填)」后缀；</li>
 *   <li><b>跟单员件</b>：77 列，<b>没有</b>零部件制程子表，表头是裸名，且<b>列序完全不同</b>
 *       （跟单员件把「数据ID/创建人/工单申请日期」放在最前面）。</li>
 * </ul>
 * 表头后缀归一已由 {@link H3FactImportHeaderNormalizationTest} 钉住，但那只测了「列名归一」这一层；
 * 本测试补的是**整条 parse 路径**：真实 .xlsx → 临时文件 → OPCPackage(READ) 流式解析 → 分组 → 字段。
 *
 * <p>另外钉一个副作用：跟单员件没有零部件制程列时，{@code saveProduction} 不得清空该单已有的工序明细
 * （判据＝本组行里是否出现「零部件制程.」前缀键，见 {@code saveProduction} 注释）。
 */
class H3FactImportProductionExportVariantParseTest {

    private static final String XLSX = "x.xlsx";

    /** 跟单员件：列序按真实导出件（数据ID 在最前、业务员在中间、无子表列、表头裸名）。 */
    private static final String[][] FOLLOWER_HEADERS = {
            {"数据ID(不可修改)", "D-F-1"},
            {"数据标题", "SCGD-F-1"},
            {"创建人", "徐恩永"},
            {"拥有者(必填)", "徐恩永"},
            {"所属部门(必填)", "浙江利强包装科技有限公司"},
            {"创建时间", "2026-10-20 19:50:13"},
            {"修改时间", "2026-10-21 08:15:27"},
            {"工单申请日期", "2026-10-20"},
            {"生产工单流水号", "SCGD-F-1"},
            {"排产时间", "2026-10-21 08:15"},
            {"订单要求生产完成日期", "2026-10-24"},
            {"预计生产完成日期", "2026-10-24"},
            {"计划类型", "非合同生产"},
            {"客户信息", "L"},
            {"选择制程", "封口机车间"},
            {"产线选择", "封口机车间"},
            {"业务员", "徐恩永"},
            {"产品名称", "手推自动片膜"},
            {"需求数量", "1"},
            {"电压", "单相220V"},
            {"插头", "其他"},
            {"品牌", "中性"},
            {"盒子尺寸", "75x75"},
            {"盒子形状", "方形"},
            {"盒子材质", "PP"},
            {"模具排列", "一出四"},
            {"可选配置", "出口木箱"},
            {"备注", "加急"},
            {"接单员", "陆伟"},
            {"入库员", "曹义山"},
            {"数据状态", "已生效"},
    };

    @BeforeAll
    static void ensureTempDir() {
        // 解析走 Files.createTempFile，只依赖 java.io.tmpdir 可写，无需额外准备。
        assertTrue(System.getProperty("java.io.tmpdir") != null);
    }

    @Test
    @DisplayName("跟单员导出件（77 列裸表头、无零部件制程子表）能解析且关键字段不丢")
    void followerExportParses() throws Exception {
        byte[] bytes = followerWorkbook();
        List<?> groups = parseProduction(bytes);
        assertEquals(2, groups.size(), "两行数据 → 两张工单");

        Map<String, String> main = mainOf(groups.get(0));
        assertEquals("SCGD-F-1", keyOf(groups.get(0)));
        assertEquals("D-F-1", externalIdOf(groups.get(0)));
        // 这 8 个字段正是「带后缀 vs 裸名」最容易静默写空的字段
        assertEquals("徐恩永", main.get("业务员"));
        assertEquals("2026-10-24", main.get("订单要求生产完成日期"));
        assertEquals("单相220V", main.get("电压"));
        assertEquals("封口机车间", main.get("选择制程"));
        assertEquals("封口机车间", main.get("产线选择"));
        assertEquals("浙江利强包装科技有限公司", main.get("所属部门"));
        assertEquals("2026-10-20 19:50:13", main.get("创建时间"));
        assertEquals("出口木箱", main.get("可选配置"), "可选配置决定按需工序，丢了跟单分母就错");
        // 没有子表列 ⇒ 不能出现任何「零部件制程.」键（saveProduction 用它判断「本次不能清旧明细」）
        assertTrue(main.keySet().stream().noneMatch(k -> k.startsWith("零部件制程.")),
                "跟单员件不该凭空长出零部件制程字段");
        assertTrue(rowsOf(groups.get(0)).stream().noneMatch(r -> r.keySet().stream().anyMatch(k -> k.startsWith("零部件制程."))),
                "行里也不该出现子表键——否则会把空明细当真实明细写库并清掉原有工序");
    }

    @Test
    @DisplayName("管理员导出件（(必填) 后缀 + 零部件制程子表）仍照旧，且子表多行归到同一工单")
    void adminExportParsesWithComponents() throws Exception {
        byte[] bytes = adminWorkbook();
        List<?> groups = parseProduction(bytes);
        assertEquals(1, groups.size(), "两行子表属于同一张工单，必须合成 1 组");

        Map<String, String> main = mainOf(groups.get(0));
        assertEquals("SCGD-A-1", keyOf(groups.get(0)));
        assertEquals("徐恩永", main.get("业务员"), "带 (必填) 后缀的表头必须归一后才取得到值");
        assertEquals("2026-10-24", main.get("订单要求生产完成日期"));
        assertEquals("单相220V", main.get("电压"));
        assertTrue(main.keySet().stream().anyMatch(k -> k.startsWith("零部件制程.")),
                "管理员件必须带出子表字段，否则工序明细写入链路断掉");
        assertEquals(2, rowsOf(groups.get(0)).size(), "两条零部件制程行都要留在 rows 里");
    }

    @Test
    @DisplayName("两种导出件的同一张单：归一后业务主表字段取值一致")
    void sameOrderSameValuesAcrossExports() throws Exception {
        Map<String, String> f = mainOf(parseProduction(followerWorkbook()).get(0));
        Map<String, String> a = mainOf(parseProduction(adminWorkbookForSameOrder()).get(0));
        for (String k : List.of("业务员", "订单要求生产完成日期", "电压", "选择制程", "产线选择",
                "产品名称", "需求数量", "盒子尺寸", "盒子形状", "盒子材质", "模具排列", "可选配置")) {
            assertEquals(a.get(k), f.get(k), "字段「" + k + "」两种导出件取值必须一致");
        }
    }

    // ---------- 解析调用 ----------

    private static Object service;

    private static Object service() throws Exception {
        if (service == null) {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
            Object svc = unsafe.allocateInstance(H3FactImportService.class);
            set(svc, "formatter", new DataFormatter());
            set(svc, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
            service = svc;
        }
        return service;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = H3FactImportService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static List<?> parseProduction(byte[] bytes) throws Exception {
        Method m = H3FactImportService.class.getDeclaredMethod("parse",
                H3FactImportService.FactType.class,
                org.springframework.web.multipart.MultipartFile.class);
        m.setAccessible(true);
        Object result = m.invoke(service(), H3FactImportService.FactType.PRODUCTION,
                new MockMultipartFile("file", XLSX, "application/octet-stream", bytes));
        return (List<?>) result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mainOf(Object group) throws Exception {
        Method m = group.getClass().getDeclaredMethod("main");
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(group);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> rowsOf(Object group) throws Exception {
        Method m = group.getClass().getDeclaredMethod("rows");
        m.setAccessible(true);
        return (List<Map<String, String>>) m.invoke(group);
    }

    private static String keyOf(Object group) throws Exception {
        Method m = group.getClass().getDeclaredMethod("key");
        m.setAccessible(true);
        return (String) m.invoke(group);
    }

    private static String externalIdOf(Object group) throws Exception {
        Method m = group.getClass().getDeclaredMethod("externalId");
        m.setAccessible(true);
        return (String) m.invoke(group);
    }

    // ---------- 造件 ----------

    private static byte[] followerWorkbook() throws Exception {
        Map<String, String> row1 = new LinkedHashMap<>();
        Map<String, String> row2 = new LinkedHashMap<>();
        for (String[] h : FOLLOWER_HEADERS) {
            row1.put(h[0], h[1]);
            row2.put(h[0], h[1]);
        }
        row2.put("数据ID(不可修改)", "D-F-2");
        row2.put("数据标题", "SCGD-F-2");
        row2.put("生产工单流水号", "SCGD-F-2");
        row2.put("产品名称", "手推自动卷膜");
        return build(List.of(FOLLOWER_HEADERS), List.of(row1, row2));
    }

    /** 同一张单的管理员件形状：表头带后缀 + 两条零部件制程行。 */
    private static byte[] adminWorkbookForSameOrder() throws Exception {
        return adminWorkbook();
    }

    private static final String[][] ADMIN_EXTRA = {
            {"零部件制程.数据ID(不可修改)", "C-1"},
            {"零部件制程.序号", "1"},
            {"零部件制程.零部件名", "模具加工"},
            {"零部件制程.需求数量", "1"},
            {"零部件制程.是否最终工序", "是"},
    };

    private static byte[] adminWorkbook() throws Exception {
        // 管理员件：后缀 + 列序不同（业务员在很前面），并追加子表列
        String[][] base = {
                {"工单申请日期", "2026-10-20"},
                {"生产工单流水号", "SCGD-A-1"},
                {"业务员(必填)", "徐恩永"},
                {"客户信息", "L"},
                {"订单要求生产完成日期(必填)", "2026-10-24"},
                {"数据ID(不可修改)", "D-A-1"},
                {"数据标题", "SCGD-A-1"},
                {"计划类型", "非合同生产"},
                {"选择制程(必填)", "封口机车间"},
                {"产线选择(必填)", "封口机车间"},
                {"产品名称", "手推自动片膜"},
                {"需求数量", "1"},
                {"电压(必填)", "单相220V"},
                {"盒子尺寸", "75x75"},
                {"盒子形状", "方形"},
                {"盒子材质", "PP"},
                {"模具排列", "一出四"},
                {"可选配置", "出口木箱"},
                {"所属部门(必填)", "浙江利强包装科技有限公司"},
                {"创建人(必填)", "徐恩永"},
                {"创建时间(必填)", "2026-10-20 19:50:13"},
                {"修改时间(必填)", "2026-10-21 08:15:27"},
                {"数据状态", "进行中"},
        };
        List<String[]> headers = new java.util.ArrayList<>();
        for (String[] h : base) headers.add(h);
        for (String[] h : ADMIN_EXTRA) headers.add(h);

        Map<String, String> row1 = new LinkedHashMap<>();
        Map<String, String> row2 = new LinkedHashMap<>();
        for (String[] h : base) {
            row1.put(h[0], h[1]);
            row2.put(h[0], h[1]);
        }
        for (String[] h : ADMIN_EXTRA) row1.put(h[0], h[1]);
        row2.put("零部件制程.数据ID(不可修改)", "C-2");
        row2.put("零部件制程.序号", "2");
        row2.put("零部件制程.零部件名", "钣金加工");
        row2.put("零部件制程.需求数量", "1");
        row2.put("零部件制程.是否最终工序", "否");
        return build(headers, List.of(row1, row2));
    }

    /** headers 是「列名 → 该列样例值」，只用列名建表头，值单独塞进各行。 */
    private static byte[] build(List<String[]> headers, List<Map<String, String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Sheet1");
            var header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c)[0]);
            }
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(r + 1);
                Map<String, String> values = rows.get(r);
                for (int c = 0; c < headers.size(); c++) {
                    String v = values.get(headers.get(c)[0]);
                    if (v != null && !v.isEmpty()) row.createCell(c).setCellValue(v);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
