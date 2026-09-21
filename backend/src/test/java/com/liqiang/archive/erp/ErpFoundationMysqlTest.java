package com.liqiang.archive.erp;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL test: session-local temporary tables, no production schema or persistent rows. */
@EnabledIfSystemProperty(named="erp.foundation.mysql", matches="true")
class ErpFoundationMysqlTest {
    static final ErpActor ACTOR = new ErpActor(23L, "李强");
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    ErpIdempotencyService service;
    @BeforeEach void setup() throws Exception {
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(Path.of(".env"))) {
            int i=line.indexOf('=');if(i>0) env.put(line.substring(0,i),line.substring(i+1).trim());
        }
        var uri=java.net.URI.create(env.getOrDefault("SPRING_DATASOURCE_URL","jdbc:mysql://localhost:3306/product_archive").substring(5));
        String url="jdbc:mysql://"+uri.getHost()+":"+(uri.getPort()<0?3306:uri.getPort())+"/erp_s0_hardening";
        source=new SingleConnectionDataSource(DriverManager.getConnection(url,env.getOrDefault("DB_USERNAME","root"),env.getOrDefault("DB_PASSWORD","")),true);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TEMPORARY TABLE idem_request(request_key VARCHAR(100) PRIMARY KEY, action VARCHAR(64), payload_hash CHAR(64), result_ref VARCHAR(200), created_by BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TEMPORARY TABLE foundation_probe(id INT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TEMPORARY TABLE foundation_master("+ErpCommonColumns.MASTER_DATA_DDL+", PRIMARY KEY(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        service=new ErpIdempotencyService(jdbc,new DataSourceTransactionManager(source));
    }
    @AfterEach void close() {if(source!=null)source.destroy();}
    @Test void rollbackThenRetryAndReplay() {
        assertThrows(IllegalStateException.class,()->service.execute(ACTOR,"k","post",Map.of("id",1),()->{
            jdbc.update("INSERT INTO foundation_probe VALUES(1)");throw new IllegalStateException("injected");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM foundation_probe",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idem_request",Integer.class));
        assertEquals("1",service.execute(ACTOR,"k","post",Map.of("id",1),()->{jdbc.update("INSERT INTO foundation_probe VALUES(1)");return "1";}));
        assertEquals("1",service.execute(ACTOR,"k","post",Map.of("id",1),()->fail("must not replay work")));
        assertThrows(ErpBusinessException.class,()->service.execute(ACTOR,"k","refund",Map.of("id",1),()->fail()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM foundation_probe",Integer.class));
        Long createdBy=jdbc.queryForObject("SELECT created_by FROM idem_request WHERE request_key='k'",Long.class);
        assertEquals(23L, createdBy==null?-1L:createdBy.longValue());
    }
    @Test void idempotencyInsertFailureRollsBackBusinessWrite() {
        jdbc.update("INSERT INTO idem_request VALUES('taken','post',?,'old',23)",service.hash(Map.of("id",1)));
        // Simulate a concurrent winner becoming visible only at insertion, without a second connection.
        assertThrows(ErpBusinessException.class,()->service.execute(ACTOR,"new","post",Map.of("id",1),()->{
            jdbc.update("INSERT INTO foundation_probe VALUES(2)");
            jdbc.update("INSERT INTO idem_request VALUES('new','post',?,'winner',23)",service.hash(Map.of("id",1)));
            return "2";
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM foundation_probe",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idem_request WHERE request_key='new'",Integer.class));
    }
    @Test void disabledCodeRemainsReservedAndDraftsMayHaveNoCode() {
        jdbc.update("INSERT INTO foundation_master(code,name,lifecycle_status) VALUES('AUTO-330','device','ACTIVE')");
        jdbc.update("UPDATE foundation_master SET lifecycle_status='INACTIVE' WHERE code='AUTO-330'");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO foundation_master(code,name) VALUES('AUTO-330','other')"));
        jdbc.update("INSERT INTO foundation_master(name) VALUES('draft-a'),('draft-b')");
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO foundation_master(name,lifecycle_status) VALUES('invalid','ACTIVE')"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO foundation_master(code,name) VALUES(' ','blank')"));
    }
}
