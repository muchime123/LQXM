package com.liqiang.archive.service;

import com.liqiang.archive.dto.SettlementLedgerDtos.OutboundLedger;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

class OutboundSignedAmountTest {
    private OutboundLedger ledger(String sales, String received) throws Exception {
        var service = mock(SettlementLedgerService.class, CALLS_REAL_METHODS);
        Method method = SettlementLedgerService.class.getDeclaredMethod("buildOutboundLedger",
                Long.class, BigDecimal.class, BigDecimal.class, BigDecimal.class, BigDecimal.class, String.class);
        method.setAccessible(true);
        BigDecimal amount = new BigDecimal(received);
        return (OutboundLedger) method.invoke(service, 1L, new BigDecimal(sales), amount, amount, amount, "已生效");
    }
    @Test void preservesNegativeReceived() throws Exception {
        assertEquals(0, ledger("-298", "-298").displayReceived().compareTo(new BigDecimal("-298")));
    }
    @Test void doesNotInventRefundForUnpaidReturn() throws Exception {
        assertEquals(0, ledger("-7960", "0").displayReceived().compareTo(BigDecimal.ZERO));
    }
    @Test void preservesOverpayment() throws Exception {
        assertEquals(0, ledger("100", "120").displayReceived().compareTo(new BigDecimal("120")));
    }
}
