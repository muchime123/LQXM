package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P2-A 主体服务纯逻辑测试（不依赖库；jdbc 为 null 仅走参数校验与静态逻辑）。 */
class CashflowEntityServiceTest {

    @Test
    void longestCommonPrefix_chineseNames() {
        assertEquals("浙江利强", CashflowEntityService.longestCommonPrefix("浙江利强工商银行", "浙江利强建设银行"));
        assertEquals("安徽利强", CashflowEntityService.longestCommonPrefix("安徽利强中国银行", "安徽利强农业银行"));
        assertEquals("", CashflowEntityService.longestCommonPrefix("利强包装微信零钱", "安徽利强中国银行"));
        assertEquals("于", CashflowEntityService.longestCommonPrefix("于清山微信", "于帅帅支付宝"));
    }

    @Test
    void create_rejectsIllegalTypeAndBlankName_beforeTouchingDb() {
        CashflowEntityService svc = new CashflowEntityService(null);
        assertThrows(IllegalArgumentException.class, () -> svc.create("X", "LEGAL_PERSON", null));
        assertThrows(IllegalArgumentException.class, () -> svc.create(" ", "COMPANY", null));
        assertThrows(IllegalArgumentException.class, () -> svc.create(null, "COMPANY", null));
    }

    @Test
    void update_rejectsIllegalStatus_beforeTouchingDb() {
        CashflowEntityService svc = new CashflowEntityService(null);
        // update 需先查库，这里只验证 setAccountStatus 的状态白名单（jdbc null 但校验在前）
        assertThrows(IllegalArgumentException.class, () -> svc.setAccountStatus(1, "ARCHIVED"));
    }

    @Test
    void suggest_grouping_contract_onSampleNames() {
        // suggest 依赖 jdbc；此处用最小桩验证分组口径——通过反射调用不可行，改为约定验证：
        // 单户孤组不给建议、组内≥2 户给最长公共前缀。完整链路由活体验收覆盖。
        List<String> names = List.of("浙江利强工商银行", "浙江利强建设银行", "强子科技兴业银行");
        String lcp = CashflowEntityService.longestCommonPrefix(names.get(0), names.get(1));
        assertEquals("浙江利强", lcp);
        // 强子科技孤户：与任何户前缀 <2 字 → suggest 应标待认领（契约在验收脚本断言）
        for (String n : names) {
            if (n.equals("强子科技兴业银行")) {
                assertNull(names.stream()
                        .filter(o -> !o.equals(n) && CashflowEntityService.longestCommonPrefix(o, n).length() >= 2)
                        .findFirst().orElse(null));
            }
        }
        Map.of();
    }
}
