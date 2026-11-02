package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 回归护栏：{@code enrichCustomerFromOutbound} 的省/市/区县解析不得空指针。
 *
 * <p>背景：该方法用 {@code clean()} 取值，而 {@code clean()} 对 null / 纯空白一律返回 <b>null</b>
 * （见 {@code H3FactImportService#clean}）。2026-09-16 确认氚云批次 152 时报 500：
 * {@code Cannot invoke "String.isEmpty()" because "address" is null} ——
 * 出库单「客户详细地址」列为空 → {@code address} 为 null，而代码直接调了 {@code address.isEmpty()}。
 * 同一方法内 {@code regionText} 也来自 {@code clean()}，属同一类隐患，一并纳入断言。
 *
 * <p>同类安全写法参考 {@code CustomerRegionService}（{@code address == null || address.isBlank()}）
 * 与 {@code SourceIdentityService}（{@code addr == null ? null : ChinaAddressParser.parseLoose(addr)}）。
 *
 * <p>本测试直接调用真实方法（反射进 private），只 mock {@link JdbcTemplate}，
 * 因此不需要数据库、也不需要 Spring 上下文。
 */
class H3FactImportServiceCustomerEnrichNullSafetyTest {

    private static final long CUSTOMER_ID = 42L;

    /** UPDATE customers 的占位符共 9 个，其中第 4、第 9 个是「地址原文」槽位。 */
    private static final int ADDRESS_ARG_INDEX = 3;
    private static final int CUSTOMER_ID_ARG_INDEX = 4;
    private static final int WHERE_ADDRESS_ARG_INDEX = 8;

    private H3FactImportService newService(JdbcTemplate jdbc) {
        return new H3FactImportService(jdbc,
                mock(H3EmployeeIdentityService.class),
                mock(SourceIdentityService.class),
                new ObjectMapper(),
                mock(SalesOutboundSettlementService.class),
                mock(SalesOutboundProfitService.class),
                mock(SalesOutboundNatureService.class),
                mock(H3PostImportAsyncService.class),
                mock(BatchImportPipelineService.class));
    }

    private static void enrich(H3FactImportService service, Map<String, String> row) throws Exception {
        Method method = H3FactImportService.class.getDeclaredMethod(
                "enrichCustomerFromOutbound", Long.class, Map.class);
        method.setAccessible(true);
        method.invoke(service, CUSTOMER_ID, row);
    }

    /** 取唯一一条 UPDATE 的占位符数组（Mockito 会把变长参数折叠成 Object[]）。 */
    private static Object[] soleUpdateParams(JdbcTemplate jdbc) {
        List<Invocation> invocations = List.copyOf(mockingDetails(jdbc).getInvocations());
        assertEquals(1, invocations.size(), "应恰好发出 1 条 SQL，实际 " + invocations.size());
        Object[] raw = invocations.getFirst().getRawArguments();
        assertEquals(2, raw.length, "update(sql, args...) 的原始参数应为 [sql, Object[]]");
        return (Object[]) raw[1];
    }

    @Test
    void nullAddressFallsBackToRegionWithoutNullPointer() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        H3FactImportService service = newService(jdbc);

        Map<String, String> row = new HashMap<>();
        row.put("地区", "江苏苏州");          // 「客户详细地址」完全缺失 → 修复前在此抛 NPE

        assertDoesNotThrow(() -> enrich(service, row));

        Object[] params = soleUpdateParams(jdbc);
        assertEquals(9, params.length, "UPDATE 占位符数量应与 SQL 一致");
        assertNull(params[ADDRESS_ARG_INDEX], "地址缺失时不应把「地区」文本当成详细地址写入");
        assertNull(params[WHERE_ADDRESS_ARG_INDEX], "WHERE 子句里的地址槽位同样应为 null");
        assertEquals(CUSTOMER_ID, params[CUSTOMER_ID_ARG_INDEX], "客户主键应原样传入");
    }

    @Test
    void blankAddressIsTreatedAsAbsent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        H3FactImportService service = newService(jdbc);

        Map<String, String> row = new HashMap<>();
        row.put("客户详细地址", "   \t ");
        row.put("地区", "浙江温州");

        assertDoesNotThrow(() -> enrich(service, row));
        assertNull(soleUpdateParams(jdbc)[ADDRESS_ARG_INDEX], "纯空白地址应等同于缺失");
    }

    @Test
    void explicitAddressWinsOverRegionAndIsPersisted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        H3FactImportService service = newService(jdbc);

        String address = "江苏省苏州市张家港市塘桥镇刘村村委西300米";
        Map<String, String> row = new HashMap<>();
        row.put("客户详细地址", address);
        row.put("地区", "上海");

        assertDoesNotThrow(() -> enrich(service, row));

        Object[] params = soleUpdateParams(jdbc);
        assertEquals(address, params[ADDRESS_ARG_INDEX], "有详细地址时应以详细地址为地址原文");
        assertEquals(address, params[WHERE_ADDRESS_ARG_INDEX]);
    }

    @Test
    void nothingToEnrichTouchesNoSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        H3FactImportService service = newService(jdbc);

        // 地址 / 地区 / 联系方式 / 业务员 全空：应直接返回，一条 SQL 都不发
        assertDoesNotThrow(() -> enrich(service, new HashMap<>()));
        verifyNoInteractions(jdbc);
    }

    @Test
    void nullValuedFieldsDoNotThrow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        H3FactImportService service = newService(jdbc);

        Map<String, String> row = new HashMap<>();
        row.put("客户详细地址", null);
        row.put("地区", null);
        row.put("联系方式", null);
        row.put("业务员(必填)", null);

        assertDoesNotThrow(() -> enrich(service, row));
        verifyNoInteractions(jdbc);
    }
}
