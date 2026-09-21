package com.liqiang.archive.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M0 收口的**真实事务边界**证据：两个真实连接 + 真实表，验证幂等模板与行级审计的失败语义。
 *
 * <p>为什么要单独建这个类：{@link ErpFoundationMysqlTest} 用单连接 + {@code TEMPORARY TABLE}
 * 模拟并发与回滚。临时表是会话级的，第二个连接根本看不到，因此那一版只能证明「代码分支被走到」，
 * 不能证明「事务真的回滚了、并发真的只写一次」。本轮复核明确指出不能用哈希单测或单连接模拟替代。
 *
 * <p>隔离边界：只操作专用测试 schema {@code erp_m0_test}（建/删由本测试自管），
 * 不触碰 {@code erp} 正式 schema 与 {@code product_archive}；表结构与 V1 迁移保持一致，
 * 保证测的是真 DDL 约束（含 {@code UNIQUE(request_key)}）而不是简化版。
 *
 * <p>启用方式：{@code mvn -o test -Dtest=ErpM0TwoConnectionMysqlTest -Derp.foundation.mysql=true}
 */
@EnabledIfSystemProperty(named = "erp.foundation.mysql", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErpM0TwoConnectionMysqlTest {

    private static final String SCHEMA = "erp_m0_test";

    /** 与 erp V1 迁移同结构（省略无关注释），确保测的是真实唯一约束。 */
    private static final String IDEM_DDL = """
            CREATE TABLE idem_request (
                id           BIGINT       NOT NULL AUTO_INCREMENT,
                request_key  VARCHAR(100) NOT NULL,
                action       VARCHAR(64)  NOT NULL,
                payload_hash CHAR(64)     NOT NULL,
                result_ref   VARCHAR(200) NULL,
                created_by   BIGINT       NULL,
                created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE KEY uk_idem_request (request_key)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
            """;

    private static final String ROW_CHANGE_LOG_DDL = """
            CREATE TABLE row_change_log (
                id             BIGINT        NOT NULL AUTO_INCREMENT,
                target_table   VARCHAR(64)   NOT NULL,
                target_id      BIGINT        NOT NULL,
                action         VARCHAR(20)   NOT NULL,
                changed_fields JSON          NULL,
                reason_code    VARCHAR(50)   NULL,
                change_reason  VARCHAR(1000) NULL,
                operator_id    BIGINT        NULL,
                operator_name  VARCHAR(100)  NULL,
                request_key    VARCHAR(100)  NULL,
                trace_id       VARCHAR(64)   NULL,
                changed_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
            """;

    private static final String PROBE_DDL = """
            CREATE TABLE m0_probe (
                id   BIGINT      NOT NULL AUTO_INCREMENT,
                note VARCHAR(50) NULL,
                PRIMARY KEY (id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
            """;

    private static final ErpActor ACTOR = new ErpActor(23L, "李强");
    private static final Map<String, Object> PAYLOAD = Map.of("id", 7);

    private static String url;
    private static String user;
    private static String pass;
    private static Connection admin;

    @BeforeAll
    static void createSchema() throws Exception {
        var env = new HashMap<String, String>();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                int i = line.indexOf('=');
                if (i > 0) env.put(line.substring(0, i), line.substring(i + 1).trim());
            }
        }
        var uri = java.net.URI.create(
                env.getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://localhost:3306/product_archive").substring(5));
        user = env.getOrDefault("DB_USERNAME", "root");
        pass = env.getOrDefault("DB_PASSWORD", "");
        url = "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort()) + "/" + SCHEMA;

        guardTestSchema();
        admin = DriverManager.getConnection(
                "jdbc:mysql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 3306 : uri.getPort()),
                user, pass);
        admin.createStatement().execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
        admin.createStatement().execute("USE " + SCHEMA);
        recreateTables(admin);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (admin == null) return;
        guardTestSchema();
        admin.createStatement().execute("DROP DATABASE IF EXISTS " + SCHEMA);
        admin.close();
    }

    /** 防呆：任何情况下都不允许对测试以外的库执行 DROP/CREATE。 */
    private static void guardTestSchema() {
        if (!SCHEMA.startsWith("erp_m0_") || SCHEMA.equals("erp") || SCHEMA.contains("product_archive")) {
            throw new IllegalStateException("拒绝在非测试 schema 上执行 DDL：" + SCHEMA);
        }
    }

    private static void recreateTables(Connection c) throws Exception {
        for (String t : new String[]{"idem_request", "row_change_log", "m0_probe"}) {
            c.createStatement().execute("DROP TABLE IF EXISTS " + t);
        }
        c.createStatement().execute(IDEM_DDL);
        c.createStatement().execute(ROW_CHANGE_LOG_DDL);
        c.createStatement().execute(PROBE_DDL);
    }

    @BeforeEach
    void clean() throws Exception {
        admin.createStatement().execute("TRUNCATE TABLE idem_request");
        admin.createStatement().execute("TRUNCATE TABLE row_change_log");
        admin.createStatement().execute("TRUNCATE TABLE m0_probe");
    }

    private static JdbcTemplate adminJdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource(admin, true));
    }

    private static int count(String table) {
        return adminJdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** {@code {"字段":{"from":…,"to":…}}} 的变更前后值；Map.of 不接受 null，故用 LinkedHashMap。 */
    private static Map<String, Object> change(Object from, Object to) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    private static String errCodeOf(org.junit.jupiter.api.function.Executable callable) {
        try {
            callable.execute();
            return "NO_ERROR";
        } catch (ErpBusinessException e) {
            return "ERR:" + e.errorCode().name();
        } catch (Throwable t) {
            throw new AssertionError("非预期异常类型：" + t, t);
        }
    }

    /** 在业务体内提交另一条连接时用：SQLException 不能从 Supplier 里抛出。 */
    private static void commitQuietly(Connection c) {
        try {
            c.commit();
        } catch (Exception e) {
            throw new IllegalStateException("提交失败", e);
        }
    }

    /** 一个独立连接上的完整幂等调用栈（连接 → 事务管理器 → 模板 → 服务）。 */
    private record Node(Connection conn, JdbcTemplate jdbc, TransactionTemplate tx, ErpIdempotencyService service)
            implements AutoCloseable {
        static Node open() throws Exception {
            Connection c = DriverManager.getConnection(url, user, pass);
            var ds = new SingleConnectionDataSource(c, true);
            var jdbc = new JdbcTemplate(ds);
            var tm = new DataSourceTransactionManager(ds);
            return new Node(c, jdbc, new TransactionTemplate(tm), new ErpIdempotencyService(jdbc, tm));
        }

        void probe(String note) {
            jdbc.update("INSERT INTO m0_probe(note) VALUES(?)", note);
        }

        @Override public void close() throws Exception {
            conn.close();
        }
    }

    /** 两个真实连接同时发起同一请求键，返回各自结果（成功为 ref，失败为错误码名）。 */
    private List<String> raceSameKey(String key, String actionA, Object payloadA,
                                     String actionB, Object payloadB) throws Exception {
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> a = () -> {
                try (var n = Node.open()) {
                    start.await();
                    return n.service().execute(ACTOR, key, actionA, payloadA, () -> {
                        n.probe("A");
                        return "doc-" + key;
                    });
                } catch (ErpBusinessException e) {
                    return "ERR:" + e.errorCode().name();
                } finally {
                    done.countDown();
                }
            };
            Callable<String> b = () -> {
                try (var n = Node.open()) {
                    start.await();
                    return n.service().execute(ACTOR, key, actionB, payloadB, () -> {
                        n.probe("B");
                        return "doc-" + key;
                    });
                } catch (ErpBusinessException e) {
                    return "ERR:" + e.errorCode().name();
                } finally {
                    done.countDown();
                }
            };
            var fa = pool.submit(a);
            var fb = pool.submit(b);
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发两个连接在 30s 内未结束");
            return List.of(fa.get(), fb.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 同键同载荷并发：无论竞态落在「抢先写键」还是「读到期后重放」，业务写入都只能发生一次。
     * 重复 12 轮以提高覆盖到两条分支的概率（不是靠单次运气）。
     */
    @Test
    void concurrentSameKeySamePayloadWritesBusinessExactlyOnce() throws Exception {
        int collisionRounds = 0;
        for (int round = 0; round < 12; round++) {
            String key = "race-same-" + round;
            List<String> outcomes = raceSameKey(key, "post", PAYLOAD, "post", PAYLOAD);
            assertEquals(1, count("m0_probe"), "第 " + round + " 轮业务写入不是恰好一次：" + outcomes);
            assertEquals(1, count("idem_request"), "第 " + round + " 轮幂等键不是恰好一行：" + outcomes);
            assertEquals("doc-" + key, adminJdbc().queryForObject(
                    "SELECT result_ref FROM idem_request WHERE request_key=?", String.class, key));
            for (String outcome : outcomes) {
                if (outcome.startsWith("ERR:")) {
                    assertEquals("ERR:IDEMPOTENCY_PAYLOAD_CONFLICT", outcome,
                            "并发失败方的错误码只允许 IDEMPOTENCY_PAYLOAD_CONFLICT，实际：" + outcomes);
                    collisionRounds++;
                }
            }
            // 收尾：确认计入 created_by（M0 收口新增的写入路径在真库生效）
            Long createdBy = adminJdbc().queryForObject(
                    "SELECT created_by FROM idem_request WHERE request_key=?", Long.class, key);
            assertEquals(23L, createdBy == null ? -1L : createdBy.longValue());
            clean();
        }
        // 不设阈值断言（命中哪条分支取决于机器负载），但把分布打出来，让「真的跑到了哪条分支」可复核
        System.out.printf("[M0 并发证据] 12 轮同键同载荷竞态：命中「插入冲突回滚」分支 %d 次，"
                + "其余为「率先写入 / 读到期后重放」分支；每轮业务写入与幂等键均恰好 1 条%n", collisionRounds);
    }

    /** 同键异载荷并发：必须恰好一方成功，另一方冲突，且不产生第二条业务记录。 */
    @Test
    void concurrentSameKeyDifferentPayloadConflictsDeterministically() throws Exception {
        String key = "race-diff";
        List<String> outcomes = raceSameKey(key, "post", Map.of("id", 1), "post", Map.of("id", 2));
        long ok = outcomes.stream().filter(o -> !o.startsWith("ERR:")).count();
        assertEquals(1, ok, "异载荷并发应恰好一方成功：" + outcomes);
        assertEquals(1, outcomes.stream()
                .filter(o -> o.equals("ERR:IDEMPOTENCY_PAYLOAD_CONFLICT")).count());
        assertEquals(1, count("m0_probe"));
        assertEquals(1, count("idem_request"));
    }

    /**
     * 确定性覆盖「业务后插幂等键、冲突回滚」这条分支（不靠竞态碰运气、不用 sleep 赌时序）：
     * 连接 W 先插入请求键但**不提交**；连接 L 走完整 execute——
     * 预检查看不到未提交行，于是先写了业务，在业务体内让 W 提交（此刻 W 才可见），
     * L 随后插幂等键被唯一索引挡住，必须整笔回滚，不能留下「业务进了、键没进」或半条记录。
     */
    @Test
    void losingConnectionRollsBackBusinessWhenWinnerBecomesVisibleAtInsertTime() throws Exception {
        String key = "lock-key";
        String hash = ErpPayloadHasher.sha256(PAYLOAD);
        try (Connection winner = DriverManager.getConnection(url, user, pass)) {
            winner.setAutoCommit(false);
            try (var st = winner.prepareStatement(
                    "INSERT INTO idem_request(request_key,action,payload_hash,result_ref,created_by) VALUES(?,?,?,?,?)")) {
                st.setString(1, key);
                st.setString(2, "post");
                st.setString(3, hash);
                st.setString(4, "doc-winner");
                st.setLong(5, 23L);
                st.executeUpdate();
            }
            try (var loser = Node.open()) {
                assertEquals("ERR:IDEMPOTENCY_PAYLOAD_CONFLICT", errCodeOf(() ->
                        loser.service().execute(ACTOR, key, "post", PAYLOAD, () -> {
                            loser.probe("loser");
                            commitQuietly(winner); // 赢家恰好在此刻可见：预检查已过、幂等键尚未插入
                            return "doc-loser";
                        })));
            }
        }
        assertEquals(1, count("idem_request"), "只应留下赢家的那一行请求键");
        assertEquals(0, count("m0_probe"), "冲突方的业务写入必须随事务一并回滚");
    }

    /** 同请求键换动作：按冲突拒绝，不允许借旧键拿到旧动作的结果。 */    @Test
    void sameKeyDifferentActionIsConflictOnRealDatabase() throws Exception {
        try (var n = Node.open()) {
            assertEquals("doc-act", n.service().execute(ACTOR, "act-key", "post", PAYLOAD, () -> {
                n.probe("post");
                return "doc-act";
            }));
            assertEquals("ERR:IDEMPOTENCY_PAYLOAD_CONFLICT",
                    errCodeOf(() -> n.service().execute(ACTOR, "act-key", "refund", PAYLOAD, () -> {
                        n.probe("refund-should-not-run");
                        return "doc-act";
                    })));
        }
        assertEquals(1, count("m0_probe"));
        assertEquals(1, count("idem_request"));
    }

    /** B15 语义：业务失败 → 键与业务写入一并回滚；同键重试可成功。 */
    @Test
    void failedRequestLeavesNoKeyAndRetrySucceeds() throws Exception {
        try (var n = Node.open()) {
            assertThrows(IllegalStateException.class, () -> n.service().execute(ACTOR, "retry-key", "post", PAYLOAD, () -> {
                n.probe("doomed");
                throw new IllegalStateException("injected");
            }));
        }
        assertEquals(0, count("m0_probe"));
        assertEquals(0, count("idem_request"), "事务回滚后请求键必须不存在，否则无法安全重试");

        try (var n = Node.open()) {
            assertEquals("doc-retry", n.service().execute(ACTOR, "retry-key", "post", PAYLOAD, () -> {
                n.probe("ok");
                return "doc-retry";
            }));
        }
        assertEquals(1, count("m0_probe"));
        assertEquals(1, count("idem_request"));
    }

    /** 重放：换人不换键，返回首次结果且业务体不执行。 */
    @Test
    void replayReturnsFirstResultWithoutSecondWrite() throws Exception {
        try (var n = Node.open()) {
            assertEquals("doc-first", n.service().execute(ACTOR, "replay-key", "post", PAYLOAD, () -> {
                n.probe("first");
                return "doc-first";
            }));
            assertEquals("doc-first", n.service().execute(new ErpActor(99L, "王五"),
                    "replay-key", "post", PAYLOAD, () -> fail("重放不得再执行一次业务体")));
        }
        assertEquals(1, count("m0_probe"));
    }

    // ---------- 审计：与业务同事务的两条失败语义 ----------

    @Test
    void auditRowRollsBackTogetherWithBusinessFailure() throws Exception {
        try (var n = Node.open()) {
            var writer = new ErpChangeLogWriter(n.jdbc(), new ObjectMapper());
            assertThrows(IllegalStateException.class, () -> n.tx().execute(st -> {
                n.probe("business");
                writer.write(ErpChangeLogWriter.of(ACTOR, "m0_probe", 1L, "INSERT",
                        Map.of("note", change(null, "business")), null, "测试", "audit-key", "trace-1"));
                throw new IllegalStateException("业务在写审计之后失败");
            }));
        }
        assertEquals(0, count("m0_probe"));
        assertEquals(0, count("row_change_log"), "业务回滚时审计行必须同步回滚，不能留下假记录");
    }

    @Test
    void auditWriteFailureRollsBackBusinessWrite() throws Exception {
        try (var n = Node.open()) {
            var writer = new ErpChangeLogWriter(n.jdbc(), new ObjectMapper());
            // operator_name 列为 VARCHAR(100)：150 字符触发真库 "Data too long"，代表审计写入失败
            var tooLong = new ErpChangeLogWriter.Entry("m0_probe", 1L, "INSERT", Map.of("note", "x"),
                    null, null, 23L, "李".repeat(150), "audit-key", "trace-2");
            assertThrows(DataAccessException.class, () -> n.tx().execute(st -> {
                n.probe("business");
                writer.write(tooLong);
                return null;
            }));
        }
        assertEquals(0, count("m0_probe"), "审计写入失败时业务写入必须一并回滚");
        assertEquals(0, count("row_change_log"));
    }

    @Test
    void auditRejectsMissingOperatorBeforeTouchingDatabase() throws Exception {
        try (var n = Node.open()) {
            var writer = new ErpChangeLogWriter(n.jdbc(), new ObjectMapper());
            var noOperator = new ErpChangeLogWriter.Entry("m0_probe", 1L, "UPDATE",
                    Map.of("note", "x"), null, "改口径", null, null, "audit-key", "trace-3");
            assertEquals(ErpErrorCode.INVALID_ARGUMENT,
                    assertThrows(ErpBusinessException.class, () -> writer.write(noOperator)).errorCode());
        }
        assertEquals(0, count("row_change_log"));
    }

    @Test
    void auditRowCommitsWithBusinessAndStoresOperator() throws Exception {
        try (var n = Node.open()) {
            var writer = new ErpChangeLogWriter(n.jdbc(), new ObjectMapper());
            n.tx().execute(st -> {
                n.probe("business");
                writer.write(ErpChangeLogWriter.of(ACTOR, "m0_probe", 1L, "INSERT",
                        Map.of("note", change(null, "business")), null, "测试", "audit-key", "trace-4"));
                return null;
            });
        }
        var row = adminJdbc().queryForMap("SELECT operator_id, operator_name, target_table FROM row_change_log");
        assertEquals(23L, ((Number) row.get("operator_id")).longValue());
        assertEquals("李强", row.get("operator_name"));
        assertEquals("m0_probe", row.get("target_table"));
        assertEquals(1, count("m0_probe"));
    }
}
