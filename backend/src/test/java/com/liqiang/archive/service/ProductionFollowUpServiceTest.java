package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.*;
import com.liqiang.archive.security.DataScopeSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionFollowUpServiceTest {
    private final LocalDate today=LocalDate.of(2026,9,22);
    private ProductionFollowUpService.State state(LocalDate completed,LocalDate stored,Map<String,LocalDate> stages) {
        return new ProductionFollowUpService.State(null,today.plusDays(3),"",completed,stored,null,"",stages);
    }
    @Test void stagesFollowWorkshopRatherThanFormalOperations() {
        assertEquals(6,ProductionFollowUpService.stageNames("全自动气调机车间").size());
        assertEquals(List.of("备料","模具生产","组装"),ProductionFollowUpService.stageNames("封口机车间"));
        assertTrue(ProductionFollowUpService.stageNames("新车间").isEmpty());
    }
    @Test void rejectsUnknownStagesAndFutureActualDatesButAllowsForecast() {
        assertThrows(IllegalArgumentException.class,()->ProductionFollowUpService.validate(state(null,null,Map.of("检验",today)),List.of("备料"),today));
        assertThrows(IllegalArgumentException.class,()->ProductionFollowUpService.validate(state(today.plusDays(1),null,Map.of()),List.of(),today));
        assertThrows(IllegalArgumentException.class,()->ProductionFollowUpService.validate(state(null,null,Map.of("备料",today.plusDays(1))),List.of("备料"),today));
        assertDoesNotThrow(()->ProductionFollowUpService.validate(state(null,null,Map.of()),List.of(),today));
    }
    @Test void acceptsExplicitClearAndKeepsProductionSeparateFromWarehouse() {
        var stages=new HashMap<String,LocalDate>();stages.put("备料",null);
        var result=ProductionFollowUpService.validate(state(today,null,stages),List.of("备料"),today);
        assertNull(result.warehousedDate());assertNull(result.stages().get("备料"));
        assertThrows(IllegalArgumentException.class,()->ProductionFollowUpService.validate(state(today,today.minusDays(1),Map.of()),List.of(),today));
    }
    @Test void forbiddenOrderCannotReachIdempotentReplay() {
        var jdbc=mock(JdbcTemplate.class);var idem=mock(ErpIdempotencyService.class);
        var service=new ProductionFollowUpService(jdbc,mock(DataScopeSupport.class),mock(ErpActorResolver.class),idem,mock(ErpChangeLogWriter.class),new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->service.save(999,new ProductionFollowUpService.SaveRequest(0,state(null,null,Map.of()),"跟进"),"already-used-key"));
        verifyNoInteractions(idem);
    }
}
