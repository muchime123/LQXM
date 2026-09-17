package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户地址批量解析的「写回到哪一级」判定契约：
 * 省份必须是官方省名；市/区县能补就补、补不上留空（能补多少补多少，不再要求三级齐全）；
 * 县级市当市级时允许区县回退为该县级市自身。
 */
class CustomerRegionAutoParseTest {

    @Test
    void fullThreeLevelAddressIsReady() {
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("浙江省嘉兴市南湖区中环南路123号");
        assertTrue(r.ready(), r.reason());
        assertEquals("浙江省", r.province());
        assertEquals("嘉兴市", r.city());
        assertEquals("南湖区", r.district());
    }

    @Test
    void countyLevelCityFallsBackToSelfAsDistrict() {
        // 省直辖县级市：市=天门市，区县回退为天门市自身
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("湖北省天门市石家河镇育红路1号");
        assertTrue(r.ready(), r.reason());
        assertEquals("湖北省", r.province());
        assertEquals("天门市", r.city());
        assertEquals("天门市", r.district());
    }

    @Test
    void countyLevelCityUnderPrefectureIsLifted() {
        // 有真实地级市父级的县级市：市上提为地级市，区县为县级市
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("江苏省无锡市江阴市华士镇某某路8号");
        assertTrue(r.ready(), r.reason());
        assertEquals("江苏省", r.province());
        assertEquals("无锡市", r.city());
        assertEquals("江阴市", r.district());
    }

    @Test
    void unrecognizableDistrictStillWritesBackProvinceAndCity() {
        // 高新区不是正式区县名：不再整条作废，只补省+市，区县留空待人工
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("广东省广州市高新区科学大道1号");
        assertTrue(r.ready(), r.reason());
        assertEquals("广东省", r.province());
        assertEquals("广州市", r.city());
        assertNull(r.district());
    }

    @Test
    void blankAndProvinceOnlyAddressesReportSpecificReasons() {
        assertEquals("详细地址为空", CustomerRegionService.diagnose("   ").reason());
        assertEquals("无法解析规范省份", CustomerRegionService.diagnose("某某市场A区12号").reason());
        // 只有省名：现在允许只补省
        CustomerRegionService.ParseResult only = CustomerRegionService.diagnose("广东省（粤）");
        assertTrue(only.ready());
        assertEquals("广东省", only.province());
        assertNull(only.city());
        assertNull(only.district());
    }

    @Test
    void looseParseRecoversCityWithoutAdministrativeSuffix() {
        // 城市名不带「市」字：由区划字典对原文做文本扫描兜底
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("河北石家庄太行国宾馆外卖城");
        assertTrue(r.ready(), r.reason());
        assertEquals("河北省", r.province());
        assertEquals("石家庄市", r.city());
    }

    @Test
    void looseParseRecoversCityPrefixedAddressWithoutProvince() {
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("温州乐清柳市镇，院前路13号后门");
        assertTrue(r.ready(), r.reason());
        assertEquals("浙江省", r.province());
        assertEquals("温州市", r.city());
        assertEquals("乐清市", r.district());
    }

    @Test
    void looseParseHandlesProvinceCitySameName() {
        // 「吉林市」与省名同字：省名抢先消耗后仍要补出吉林市
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("吉林市船营区北京路基督教旁家加冒菜");
        assertTrue(r.ready(), r.reason());
        assertEquals("吉林省", r.province());
        assertEquals("吉林市", r.city());
        assertEquals("船营区", r.district());
    }

    @Test
    void looseParseRecoversDistrictFromKnownProvinceAndCity() {
        CustomerRegionService.ParseResult r =
                CustomerRegionService.diagnose("广州白云区人和镇东华工业区同富路60号");
        assertTrue(r.ready(), r.reason());
        assertEquals("广东省", r.province());
        assertEquals("广州市", r.city());
        assertEquals("白云区", r.district());
    }
}
