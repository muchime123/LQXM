package com.liqiang.archive.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 氚云表头「(必填)」后缀归一契约（2026-10-21）。
 *
 * <p><b>背景</b>：同一张生产任务单表单，「选择全部字段」导出的文件内容相同，但表头会随导出人权限变化：
 * 管理员导出 97 列、表头作「业务员(必填)」「订单要求生产完成日期(必填)」；
 * 跟单员导出 77 列、表头作「业务员」「订单要求生产完成日期」（少了「零部件制程」子表）。
 *
 * <p><b>故障形态</b>（修复前实测）：跟单员文件能解析、能预览、<b>不报任何错</b>，但
 * {@code saveProduction} 里 8 个按带后缀名字读取的字段全部取到 {@code null}——
 * 业务员 / 订单要求生产完成日期 / 电压 / 选择制程 / 产线选择 / 所属部门 / 创建人 / 创建时间，
 * 确认导入后生产跟单页的任务单业务员、交期、制程全是空的。这是典型的「静默写错」，
 * 只钉结果字段的测试发现不了（必须同时钉表头归一的语义）。
 *
 * <p><b>同文件内后缀并不统一</b>：跟单员件的「拥有者(必填)」「所属部门(必填)」带后缀，
 * 而「创建人」「修改时间」不带 ⇒ 不能靠逐字段加兜底，只能在唯一的表头归一入口统一剥掉。
 */
class H3FactImportHeaderNormalizationTest {

    /** 反射调用 private putHeader：它是流式与兜底两条解析路径共用的唯一归一入口。 */
    private static void putHeader(Map<String, Integer> built, H3FactImportService.FactType type,
                                  String raw, int index) throws Exception {
        Method m = H3FactImportService.class.getDeclaredMethod(
                "putHeader", Map.class, H3FactImportService.FactType.class, String.class, int.class);
        m.setAccessible(true);
        // putHeader 不触碰任何实例状态（只操作入参 Map），但 @RequiredArgsConstructor 参数多且依赖 DB，
        // 故不构造真实实例，直接用 Unsafe 分配一个未初始化对象承载这次调用。
        m.invoke(SERVICE.get(), built, type, raw, index);
    }

    private static final ThreadLocal<Object> SERVICE = ThreadLocal.withInitial(() -> {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
            return unsafe.allocateInstance(H3FactImportService.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法分配 H3FactImportService 实例用于表头归一测试", e);
        }
    });

    private static Map<String, Integer> columns(String... headers) throws Exception {
        Map<String, Integer> built = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) putHeader(built, H3FactImportService.FactType.PRODUCTION, headers[i], i);
        return built;
    }

    @Test
    @DisplayName("带「(必填)」后缀的表头被剥成裸名——运营/管理员导出件")
    void fillableSuffixIsStripped() throws Exception {
        Map<String, Integer> c = columns("业务员(必填)", "订单要求生产完成日期(必填)", "电压(必填)",
                "选择制程(必填)", "产线选择(必填)", "所属部门(必填)", "创建人(必填)", "创建时间(必填)");
        assertTrue(c.containsKey("业务员"), "带后缀的表头必须归一成「业务员」，否则 saveProduction 取不到");
        assertTrue(c.containsKey("订单要求生产完成日期"));
        assertTrue(c.containsKey("电压"));
        assertTrue(c.containsKey("选择制程"));
        assertTrue(c.containsKey("产线选择"));
        assertTrue(c.containsKey("所属部门"));
        assertTrue(c.containsKey("创建人"));
        assertTrue(c.containsKey("创建时间"));
        assertFalse(c.containsKey("业务员(必填)"), "不应再保留带后缀的键：写入侧一律按裸名取，留着只会让人以为两种写法都支持");
    }

    @Test
    @DisplayName("裸表头原样保留——跟单员导出件（本测试钉的就是这条）")
    void bareHeaderIsKeptAsIs() throws Exception {
        Map<String, Integer> c = columns("业务员", "订单要求生产完成日期", "电压", "选择制程", "产线选择", "创建人", "修改时间");
        assertTrue(c.containsKey("业务员"));
        assertTrue(c.containsKey("订单要求生产完成日期"));
        assertTrue(c.containsKey("电压"));
        assertTrue(c.containsKey("选择制程"));
        assertTrue(c.containsKey("产线选择"));
        assertTrue(c.containsKey("创建人"));
        assertTrue(c.containsKey("修改时间"));
    }

    @Test
    @DisplayName("两种导出件归一后得到同一组键——这是「跟单员也能导入」的判据")
    void adminAndFollowupExportsNormalizeToSameKeys() throws Exception {
        Map<String, Integer> admin = columns("业务员(必填)", "订单要求生产完成日期(必填)", "电压(必填)",
                "选择制程(必填)", "产线选择(必填)", "所属部门(必填)", "创建人(必填)",
                "创建时间(必填)", "修改时间(必填)", "拥有者(必填)");
        Map<String, Integer> followup = columns("业务员", "订单要求生产完成日期", "电压", "选择制程",
                "产线选择", "所属部门", "创建人", "创建时间", "修改时间", "拥有者(必填)");
        assertEquals(admin.keySet(), followup.keySet(),
                "同一表单的两种导出件，归一后必须产出完全相同的键集合，否则写入侧必然有一边取到 null");
    }

    @Test
    @DisplayName("子表前缀列同样剥后缀：「产品明细.入库数量(必填)」→「产品明细.入库数量」")
    void childTablePrefixedHeaderIsStripped() throws Exception {
        Map<String, Integer> c = new LinkedHashMap<>();
        putHeader(c, H3FactImportService.FactType.PURCHASE_IN, "产品明细.入库数量(必填)", 0);
        putHeader(c, H3FactImportService.FactType.OTHER_OUT, "其他出库明细.出库数量(必填)", 1);
        assertTrue(c.containsKey("产品明细.入库数量"));
        assertTrue(c.containsKey("其他出库明细.出库数量"));
    }

    @Test
    @DisplayName("全角括号与夹带空白也能剥掉")
    void fullWidthParenthesesAndSpacesAreHandled() throws Exception {
        Map<String, Integer> c = columns("业务员（必填）", " 电压(必填) ", "选择制程（必填） ");
        assertTrue(c.containsKey("业务员"));
        assertTrue(c.containsKey("电压"));
        assertTrue(c.containsKey("选择制程"));
    }

    @Test
    @DisplayName("语义性括号不受影响——只剥「(必填)」这一种")
    void otherParentheticalNamesAreNotTouched() throws Exception {
        Map<String, Integer> c = columns("生产进度_停用", "排产状态：是否排产", "入库数量_数值");
        assertTrue(c.containsKey("生产进度_停用"));
        assertTrue(c.containsKey("排产状态：是否排产"));
        assertTrue(c.containsKey("入库数量_数值"));
    }

    @Test
    @DisplayName("同名重复列仍按首命中优先（防止归一后误吞既有改名规则）")
    void duplicateHeaderKeepsFirstOccurrence() throws Exception {
        Map<String, Integer> c = new LinkedHashMap<>();
        putHeader(c, H3FactImportService.FactType.PRODUCTION, "业务员(必填)", 3);
        putHeader(c, H3FactImportService.FactType.PRODUCTION, "业务员", 9);
        assertEquals(3, c.get("业务员"), "归一后撞名应保留先出现的那列，与既有 putIfAbsent 语义一致");
    }
}
