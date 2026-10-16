package com.liqiang.archive.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.liqiang.archive.service.ProductConfigurationNaming.*;
class ProductConfigurationServiceTest {
    @Test void staleDraftCannotOverwriteConcurrentEdit() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT * FROM manufacturing_task_config_items WHERE id=? AND config_type='PRODUCT_NAME' FOR UPDATE",7L))
                .thenReturn(List.of(Map.of("id",7L,"config_name","配件","config_code","BP-7","workshop_id",1L)));
        when(jdbc.queryForList("SELECT * FROM manufacturing_task_config_items WHERE id=? AND config_type='PRODUCT_NAME'",7L))
                .thenReturn(List.of(Map.of("id",7L,"config_name","配件","config_code","BP-7","workshop_id",1L)));
        var service=new ProductConfigurationService(jdbc,new ObjectMapper(),null,null);
        var definition=new Definition("PART",List.of("specificName","partSpecification"),List.of(),false);
        assertThrows(IllegalStateException.class,()->service.save(7,10,new ProductConfigurationService.Save(1,definition)));
    }
    @Test void baseProductCannotBeSubmittedAgainstAnotherWorkshop(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT * FROM manufacturing_task_config_items WHERE id=? AND config_type='PRODUCT_NAME'",7L))
                .thenReturn(List.of(Map.of("workshop_id",1L,"config_name","配件")));
        var service=new ProductConfigurationService(jdbc,new ObjectMapper(),null,null);
        assertThrows(IllegalArgumentException.class,()->service.snapshot(2L,"配件",Map.of("baseProductId",7L)));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
