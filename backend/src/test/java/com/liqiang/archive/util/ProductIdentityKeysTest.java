package com.liqiang.archive.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「任务单配置 → SKU 身份」口径测试（2026-09-23 定稿，
 * 见 {@code docs/SKU重设计-任务单配置锚定身份-20260923.md}）。
 *
 * <p><b>期望值全部取自真库实测</b>，不是拍脑袋：
 * <ul>
 *   <li>尺寸写法三态（{@code *} 1244 / 纯数字 1747 / {@code x} 1004 / 含 mm 1616 / {@code ×} 34）；</li>
 *   <li>库存名里真实写着 {@code 2014气动一出四卷膜} / {@code 1812按钮全自动卷膜} ——
 *       压掉乘号是业务写法，规范化<b>禁还原语义</b>（本测试钉死这条，防将来有人"聪明"一把）；</li>
 *   <li>一出N 台账中文数字（一出四 1385）与 ERP JSON 阿拉伯（1出4）双语源，必须归一；</li>
 *   <li>模具/配件无尺寸（374 张模具单共用全空配置键）→ 非标分支 skuKey=null。</li>
 * </ul>
 *
 * <p>全角字符一律写成 Unicode 转义，避免源文件编码差异把测试悄悄改味。
 */
class ProductIdentityKeysTest {

    // ===== 尺寸规范化：只统一字形，不还原语义 =====

    @Test
    void 尺寸_乘号三态统一为小写x() {
        assertEquals("200x138", ProductIdentityKeys.normalizeSize("200*138"));
        assertEquals("188x81", ProductIdentityKeys.normalizeSize("188\u00d781"));   // ×
        assertEquals("370x270", ProductIdentityKeys.normalizeSize("370X270"));      // 大写 X
        assertEquals("370x270", ProductIdentityKeys.normalizeSize(" 370x270 "));
    }

    @Test
    void 尺寸_去mm单位() {
        assertEquals("70", ProductIdentityKeys.normalizeSize("70mm"));
        assertEquals("70", ProductIdentityKeys.normalizeSize("70 mm"));
        assertEquals("86", ProductIdentityKeys.normalizeSize("86mm"));
        assertEquals("80x60", ProductIdentityKeys.normalizeSize("80mmx60mm"));
    }

    @Test
    void 尺寸_压掉乘号的业务写法原样保留_禁还原() {
        // 真库证据：库存名「2014气动一出四卷膜」「1812按钮全自动卷膜」——2014/1812 就是业务写法
        assertEquals("2014", ProductIdentityKeys.normalizeSize("2014"));
        assertEquals("1812", ProductIdentityKeys.normalizeSize("1812"));
        assertEquals("2216", ProductIdentityKeys.normalizeSize("2216"));
    }

    @Test
    void 尺寸_小数不取整() {
        // 真库证据：203.9x146 这类单 80 张，字面命中库存名仅 47.5%——取整会让命中率更低
        assertEquals("203.9x146", ProductIdentityKeys.normalizeSize("203.9x146"));
        assertEquals("203.9x146", ProductIdentityKeys.normalizeSize("203.9X146"));
    }

    @Test
    void 尺寸_全角括号与Phi统一() {
        assertEquals("(70x70)", ProductIdentityKeys.normalizeSize("\uff0870x70\uff09")); // （）
        assertEquals("\u03a650", ProductIdentityKeys.normalizeSize("\u03c650"));         // φ → Φ
        assertEquals("\u03a650", ProductIdentityKeys.normalizeSize("\u03a650"));         // Φ 原样
    }

    @Test
    void 尺寸_数字后的逗号与多段mm剥离() {
        // 真库形态：46,mm(1) / φ90，φ80(3) / 86mm75mm(多段各带单位) / 112x112mm84mm
        assertEquals("46", ProductIdentityKeys.normalizeSize("46,mm"));
        assertEquals("\u03a690\u03a680", ProductIdentityKeys.normalizeSize("\u03c690\uff0c\u03c680"));
        assertEquals("86x75", ProductIdentityKeys.normalizeSize("86mmx75mm"));
        // 每段都带单位且乘号被压掉的写法 → 与「2014」同一套人脑约定
        assertEquals("8675", ProductIdentityKeys.normalizeSize("86mm75mm"));
        assertEquals("112x11284", ProductIdentityKeys.normalizeSize("112x112mm84mm"));
    }

    @Test
    void 尺寸_空值归空() {
        assertEquals("", ProductIdentityKeys.normalizeSize(null));
        assertEquals("", ProductIdentityKeys.normalizeSize("   "));
        assertEquals("", ProductIdentityKeys.normalizeSize(""));
    }

    // ===== 一出N：双语源归一（台账中文 / ERP JSON 阿拉伯）=====

    @Test
    void 一出N_台账中文原样() {
        assertEquals("一出四", ProductIdentityKeys.normalizeMoldLayout("一出四"));
        assertEquals("一出十二", ProductIdentityKeys.normalizeMoldLayout("一出十二"));
        assertEquals("一出二十", ProductIdentityKeys.normalizeMoldLayout("一出二十"));
        assertEquals("一出一", ProductIdentityKeys.normalizeMoldLayout("一出一"));
    }

    @Test
    void 一出N_ERP阿拉伯归一为台账写法() {
        assertEquals("一出四", ProductIdentityKeys.normalizeMoldLayout("1出4"));
        assertEquals("一出一", ProductIdentityKeys.normalizeMoldLayout("1出1"));
        assertEquals("一出二", ProductIdentityKeys.normalizeMoldLayout("一出2"));
        assertEquals("一出十二", ProductIdentityKeys.normalizeMoldLayout("1出12"));
        assertEquals("十二出十二", ProductIdentityKeys.normalizeMoldLayout("12出12"));
        // ERP 建单 JSON 真实取值（MO00008768 moldArrangement="2出1"）——头部不是恒为 1
        assertEquals("二出一", ProductIdentityKeys.normalizeMoldLayout("2出1"));
    }

    @Test
    void 一出N_大数与异常值() {
        // 真库异常值「一出104」（1 条）→ 一百零四
        assertEquals("一出一百零四", ProductIdentityKeys.normalizeMoldLayout("一出104"));
        assertEquals("一出一百零四", ProductIdentityKeys.normalizeMoldLayout("1出104"));
        assertEquals("一出三十九", ProductIdentityKeys.normalizeMoldLayout("一出三十九"));
    }

    @Test
    void 一出N_解析不出时降级为去空白原文_不抛异常() {
        assertEquals("abc", ProductIdentityKeys.normalizeMoldLayout("ABC"));
        assertEquals("一出", ProductIdentityKeys.normalizeMoldLayout("一出"));
        assertEquals("", ProductIdentityKeys.normalizeMoldLayout(null));
        assertEquals("", ProductIdentityKeys.normalizeMoldLayout(""));
    }

    // ===== derive：身份键 / 显示名 / 非标分支 =====

    @Test
    void 规格化卷膜_键与显示名() {
        // 对应真库样本：任务单「气动卷膜 200x138 一出四 + 特氟龙」（订做）
        ProductIdentityKeys.Identity id = ProductIdentityKeys.derive(
                "气动卷膜", "200*138", "1出4", List.of("特氟龙"), true);
        assertEquals("气动卷膜|200x138|一出四", id.skuKey());
        assertEquals("订做气动卷膜-200x138一出四+特氟龙", id.displayName());
        assertFalse(id.nonStandard());
    }

    @Test
    void 规格化卷膜_双语源归一到同一个键() {
        // 同一件货：台账侧录「一出四」、ERP 建单 JSON 录「1出4」——键必须相同（否则同款裂两份）
        ProductIdentityKeys.Identity a = ProductIdentityKeys.derive(
                "气动卷膜", "200*138", "一出四", List.of(), false);
        ProductIdentityKeys.Identity b = ProductIdentityKeys.derive(
                "气动卷膜", "200x138", "1出4", List.of(), false);
        assertEquals(a.skuKey(), b.skuKey());
        // 显示名里乘号字形跟随原文归一后一致
        assertEquals(a.displayName(), b.displayName());
    }

    @Test
    void 同款不同配置_键相同_显示名不同() {
        // S 拍板：可选配置不进身份键（只把组数 2717→3709、救不了退化组），进显示名
        ProductIdentityKeys.Identity plain = ProductIdentityKeys.derive(
                "手动片膜", "370x270", "一出一", List.of(), false);
        ProductIdentityKeys.Identity withCfg = ProductIdentityKeys.derive(
                "手动片膜", "370x270", "一出一", List.of("特氟龙"), false);
        assertEquals(plain.skuKey(), withCfg.skuKey());
        assertNotEquals(plain.displayName(), withCfg.displayName());
    }

    @Test
    void LQ整机_仿库存语法拆机型号前缀() {
        // 对应真库样本：库存名「LQ310T-230x165一出一台式全自动+易撕口」
        ProductIdentityKeys.Identity id = ProductIdentityKeys.derive(
                "LQ310T台式全自动", "230x165", "一出一", List.of("易撕口"), false);
        assertEquals("lq310t台式全自动|230x165|一出一", id.skuKey());
        assertEquals("LQ310T-230x165一出一台式全自动+易撕口", id.displayName());
    }

    @Test
    void 可选配置_去空白去重保序() {
        ProductIdentityKeys.Identity id = ProductIdentityKeys.derive(
                "气动卷膜", "200x138", "一出四",
                List.of("特氟龙", " 特氟龙 ", "", "气流式"), false);
        assertTrue(id.displayName().endsWith("+特氟龙+气流式"));
    }

    @Test
    void 非标_模具配件无尺寸_skyKey为空() {
        // 真库：374 张模具单配置全空；542 张无尺寸单 = 模具 430 + 配件 108
        for (String cat : List.of("模具", "配件")) {
            ProductIdentityKeys.Identity id = ProductIdentityKeys.derive(cat, null, null, List.of(), false);
            assertNull(id.skuKey(), cat + " 应走 MFG-{工单号}");
            assertTrue(id.nonStandard());
            assertEquals(cat, id.displayName());
        }
    }

    @Test
    void 非标_有品类但尺寸或一出N缺失也走MFG() {
        ProductIdentityKeys.Identity noSize = ProductIdentityKeys.derive("手动片膜", null, "一出一", List.of(), false);
        assertNull(noSize.skuKey());
        ProductIdentityKeys.Identity noLayout = ProductIdentityKeys.derive("手动片膜", "250", null, List.of(), false);
        assertNull(noLayout.skuKey());
        ProductIdentityKeys.Identity noCat = ProductIdentityKeys.derive(null, "250", "一出一", List.of(), false);
        assertNull(noCat.skuKey());
    }

    @Test
    void 身份键_不受首尾与内部空白大小写影响() {
        ProductIdentityKeys.Identity a = ProductIdentityKeys.derive("气动 卷膜", " 200*138 ", " 一出四 ", List.of(), false);
        ProductIdentityKeys.Identity b = ProductIdentityKeys.derive("气动卷膜", "200x138", "1出4", List.of(), false);
        assertEquals(b.skuKey(), a.skuKey());
    }
}
