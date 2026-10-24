package com.liqiang.archive.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyMainSchemaMysqlTest {
    @Test
    @EnabledIfSystemProperty(named = "cashflow.idempotency.mysql", matches = "true")
    void replayUsesMainSchemaAndDoesNotWriteLegacySchema() {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl("jdbc:mysql://" + System.getenv().getOrDefault("DB_HOST", "localhost")
                + ":3306/" + System.getenv().getOrDefault("DB_NAME", "product_archive"));
        source.setUsername(System.getenv().getOrDefault("DB_USER", "root"));
        source.setPassword(System.getenv("DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var service = new IdempotencyService(jdbc, new DataSourceTransactionManager(source));
        var key = "acceptance-v407-" + UUID.randomUUID();
        var executions = new AtomicInteger();
        Integer legacyBefore = jdbc.queryForObject("SELECT COUNT(*) FROM erp.idem_request", Integer.class);
        try {
            var actor = new Actor(23, "V407验收");
            String first = service.execute(actor, key, "acceptance.v407", Map.of("sample", 1),
                    () -> { executions.incrementAndGet(); return "result-1"; });
            String replay = service.execute(actor, key, "acceptance.v407", Map.of("sample", 1),
                    () -> { executions.incrementAndGet(); return "wrong"; });
            assertEquals("result-1", first);
            assertEquals(first, replay);
            assertEquals(1, executions.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency WHERE request_key=?",
                    Integer.class, key));
            assertThrows(ApiException.class, () -> service.execute(actor, key, "acceptance.v407",
                    Map.of("sample", 2), () -> "wrong"));
            assertEquals(legacyBefore, jdbc.queryForObject("SELECT COUNT(*) FROM erp.idem_request", Integer.class));
        } finally {
            jdbc.update("DELETE FROM request_idempotency WHERE request_key=?", key);
        }
    }
}
