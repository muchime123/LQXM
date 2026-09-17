package com.liqiang.archive.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 区域口径统一（A1+A2）后，省/市/区县规范名归一 API 的口径契约：
 * 分布聚合与档案写入口都必须产出与地图 feature 同源的官方全名。
 */
class ChinaRegionDictionaryStandardizeTest {

    @Test
    void provinceShortFormIsCompletedToOfficialName() {
        assertEquals("浙江省", ChinaRegionDictionary.canonicalProvinceName("浙江"));
        assertEquals("浙江省", ChinaRegionDictionary.canonicalProvinceName(" 浙江 "));
        assertEquals("江苏省", ChinaRegionDictionary.canonicalProvinceName("江苏"));
        assertEquals("广西壮族自治区", ChinaRegionDictionary.canonicalProvinceName("广西"));
        assertEquals("内蒙古自治区", ChinaRegionDictionary.canonicalProvinceName("内蒙古"));
        assertEquals("新疆维吾尔自治区", ChinaRegionDictionary.canonicalProvinceName("新疆"));
    }

    @Test
    void hkMacauTaiwanKeptAsOfficialNames() {
        assertEquals("台湾省", ChinaRegionDictionary.canonicalProvinceName("台湾"));
        assertEquals("香港特别行政区", ChinaRegionDictionary.canonicalProvinceName("香港"));
        assertEquals("澳门特别行政区", ChinaRegionDictionary.canonicalProvinceName("澳门"));
        assertTrue(ChinaRegionDictionary.isOfficialProvinceName("台湾省"));
        assertTrue(ChinaRegionDictionary.isOfficialProvinceName("香港特别行政区"));
    }

    @Test
    void officialProvinceNamePassesThrough() {
        assertEquals("广东省", ChinaRegionDictionary.canonicalProvinceName("广东省"));
        assertTrue(ChinaRegionDictionary.isOfficialProvinceName("广东省"));
        assertTrue(ChinaRegionDictionary.isOfficialProvinceName("北京市"));
        assertFalse(ChinaRegionDictionary.isOfficialProvinceName("广东"));
        assertFalse(ChinaRegionDictionary.isOfficialProvinceName("海外"));
    }

    @Test
    void unknownProvinceKeepsReadableValue() {
        assertEquals("海外", ChinaRegionDictionary.canonicalProvinceName("海外"));
        assertNull(ChinaRegionDictionary.canonicalProvinceName(null));
        assertNull(ChinaRegionDictionary.canonicalProvinceName("  "));
    }

    @Test
    void cityCompletesSuffixAndKeepsOfficialNames() {
        assertEquals("嘉兴市", ChinaRegionDictionary.canonicalCityName("嘉兴", null));
        assertEquals("东莞市", ChinaRegionDictionary.canonicalCityName("东莞", null));
        assertEquals("杭州市", ChinaRegionDictionary.canonicalCityName("杭州市", "浙江省"));
        assertEquals("北京市", ChinaRegionDictionary.canonicalCityName("北京市", "北京市"));
        assertEquals("湖州市", ChinaRegionDictionary.canonicalCityName("湖州市", "浙江省"));
        assertEquals("海外城", ChinaRegionDictionary.canonicalCityName("海外城", null));
        assertNull(ChinaRegionDictionary.canonicalCityName(" ", null));
    }

    @Test
    void districtOfficialNamePassesThroughByProvinceScope() {
        assertEquals("白云区", ChinaRegionDictionary.canonicalDistrictName("白云区", "广东省"));
        assertEquals("嘉定区", ChinaRegionDictionary.canonicalDistrictName("嘉定区", "上海市"));
        assertEquals("滨江区", ChinaRegionDictionary.canonicalDistrictName("滨江区", "浙江省"));
        assertEquals("虎门镇", ChinaRegionDictionary.canonicalDistrictName("虎门镇", "广东省"));
        assertEquals("街道办", ChinaRegionDictionary.canonicalDistrictName("街道办", "广东省"));
        assertNull(ChinaRegionDictionary.canonicalDistrictName(null, "广东省"));
    }

    @Test
    void duplicateDistrictWithinProvinceCanBeValidatedByCity() {
        assertTrue(ChinaRegionDictionary.districtBelongsTo("向阳区", "黑龙江省", "鹤岗市"));
        assertTrue(ChinaRegionDictionary.districtBelongsTo("向阳区", "黑龙江省", "佳木斯市"));
        assertFalse(ChinaRegionDictionary.districtBelongsTo("向阳区", "黑龙江省", "哈尔滨市"));
    }

    @Test
    void countyLevelCityCanBeUsedAsCityField() {
        // 有真实地级市父级：归一到父地级市
        assertEquals("无锡市", ChinaRegionDictionary.asCityOfCountyLevelCity("江阴市", "江苏省"));
        assertEquals("枣庄市", ChinaRegionDictionary.asCityOfCountyLevelCity("滕州市", "山东省"));
        assertEquals("唐山市", ChinaRegionDictionary.asCityOfCountyLevelCity("迁安市", "河北省"));
        // 省直辖县级行政区划下的县级市：无父地级市，自身即市级
        assertEquals("仙桃市", ChinaRegionDictionary.asCityOfCountyLevelCity("仙桃市", "湖北省"));
        assertEquals("天门市", ChinaRegionDictionary.asCityOfCountyLevelCity("天门市", "湖北省"));
        assertEquals("济源市", ChinaRegionDictionary.asCityOfCountyLevelCity("济源市", "河南省"));
        assertEquals("万宁市", ChinaRegionDictionary.asCityOfCountyLevelCity("万宁市", "海南省"));
        // 非县级市写法（普通区县）不参与
        assertNull(ChinaRegionDictionary.asCityOfCountyLevelCity("白云区", "广东省"));
        assertNull(ChinaRegionDictionary.asCityOfCountyLevelCity("杭州市", "浙江省"));
        // 跨省错配不猜测
        assertNull(ChinaRegionDictionary.asCityOfCountyLevelCity("江阴市", "浙江省"));
    }

    @Test
    void usableAsCityCoversPrefectureAndCountyLevelCity() {
        assertTrue(ChinaRegionDictionary.isUsableAsCity("无锡市", "江苏省"));
        assertTrue(ChinaRegionDictionary.isUsableAsCity("仙桃市", "湖北省"));
        assertTrue(ChinaRegionDictionary.isUsableAsCity("万宁市", "海南省"));
        assertTrue(ChinaRegionDictionary.isUsableAsCity("北京市", "北京市"));
        // 省/市错配
        assertFalse(ChinaRegionDictionary.isUsableAsCity("无锡市", "浙江省"));
        assertFalse(ChinaRegionDictionary.isUsableAsCity("北京市", "天津市"));
        // 普通区县不能当市级；省份未知时保守只认地级市
        assertFalse(ChinaRegionDictionary.isUsableAsCity("白云区", "广东省"));
        assertTrue(ChinaRegionDictionary.isUsableAsCity("无锡市", null));
        assertFalse(ChinaRegionDictionary.isUsableAsCity("仙桃市", null));
    }

    @Test
    void countyCitySelfDistrictIsAccepted() {
        assertTrue(ChinaRegionDictionary.isCountyCitySelfDistrict("仙桃市", "湖北省", "仙桃市"));
        assertTrue(ChinaRegionDictionary.isCountyCitySelfDistrict("滕州市", "山东省", "滕州市"));
        // 区县与市不同名则不适用
        assertFalse(ChinaRegionDictionary.isCountyCitySelfDistrict("白云区", "广东省", "广州市"));
        // 市本身不是县级市时不适用
        assertFalse(ChinaRegionDictionary.isCountyCitySelfDistrict("杭州市", "浙江省", "杭州市"));
    }

    @Test
    void userRegionAliasFallsBackOnDictionaryMissAndHonorsProvinceScope() {
        ChinaRegionDictionary.replaceUserAliases(List.of(
                new ChinaRegionDictionary.UserRegionAlias("DISTRICT", "南山", "广东省", "南山区"),
                new ChinaRegionDictionary.UserRegionAlias("CITY", "广东东莞", "广东省", "东莞市")));
        try {
            // 写法别名仅在字典官方未命中时兜底
            assertEquals("南山区", ChinaRegionDictionary.canonicalDistrictName("南山", "广东省"));
            assertEquals("南山", ChinaRegionDictionary.canonicalDistrictName("南山", "浙江省"));
            // 官方区县名/官方市名直通不受别名影响
            assertEquals("白云区", ChinaRegionDictionary.canonicalDistrictName("白云区", "广东省"));
            assertEquals("杭州市", ChinaRegionDictionary.canonicalCityName("杭州", "浙江省"));
            // 城市别名同样受省份作用域约束
            assertEquals("东莞市", ChinaRegionDictionary.canonicalCityName("广东东莞", "广东省"));
            assertEquals("广东东莞", ChinaRegionDictionary.canonicalCityName("广东东莞", "浙江省"));
        } finally {
            ChinaRegionDictionary.replaceUserAliases(List.of());
        }
    }

    @Test
    void userRegionAliasClearsWhenRegistryReplacedWithEmpty() {
        ChinaRegionDictionary.replaceUserAliases(List.of(
                new ChinaRegionDictionary.UserRegionAlias("DISTRICT", "南山", "广东省", "南山区")));
        ChinaRegionDictionary.replaceUserAliases(List.of());
        assertEquals("南山", ChinaRegionDictionary.canonicalDistrictName("南山", "广东省"));
    }

    @Test
    void userProvinceAliasFallsBackOnDictionaryMiss() {
        ChinaRegionDictionary.replaceUserAliases(List.of(
                new ChinaRegionDictionary.UserRegionAlias("PROVINCE", "海外中心", null, "广东省")));
        try {
            assertEquals("广东省", ChinaRegionDictionary.canonicalProvinceName("海外中心"));
            assertEquals("海外", ChinaRegionDictionary.canonicalProvinceName("海外"));
        } finally {
            ChinaRegionDictionary.replaceUserAliases(List.of());
        }
    }
}
