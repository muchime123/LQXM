package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.security.DataScopeSupport;
import com.liqiang.archive.security.FeatureCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesOutboundCustomerFilterTest {
    @Test
    void customerFilterAndEmployeeScopeApplyToOrderProfitList() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataScopeSupport scope = mock(DataScopeSupport.class);
        doAnswer(invocation -> {
            StringBuilder where = invocation.getArgument(0);
            List<Object> params = invocation.getArgument(1);
            where.append(" AND o.employee_id=?");
            params.add(9L);
            return null;
        }).when(scope).appendByEmployeeId(any(), anyList(), eq("o.employee_id"), eq("e.department"), eq(FeatureCode.ORDER_PROFIT));
        SalesOutboundProfitService service = new SalesOutboundProfitService(jdbc, scope,
                mock(CurrentUserService.class), mock(AuditLogService.class),
                mock(SalesOutboundCommissionService.class));

        service.pageOutboundProfits(null, null, 0, 20, 42L);

        var queries = mockingDetails(jdbc).getInvocations();
        assertEquals(2, queries.size()); // list count + list rows
        for (var query : queries) {
            Object[] args = query.getRawArguments();
            String sql = (String) args[0];
            assertTrue(sql.contains("o.customer_id=? AND o.employee_id=?"), sql);
            Object[] parameters = (Object[]) args[2];
            assertEquals(42L, parameters[0]);
            assertEquals(9L, parameters[1]);
        }
        verify(scope, times(1)).appendByEmployeeId(any(), anyList(), eq("o.employee_id"), eq("e.department"), eq(FeatureCode.ORDER_PROFIT));
    }
}
