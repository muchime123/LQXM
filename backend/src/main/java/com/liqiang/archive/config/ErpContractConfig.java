package com.liqiang.archive.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import com.liqiang.archive.erp.ErpIdempotencyService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * M0 契约（幂等模板 + 行级审计）与业务写路径的**绑定**。
 *
 * <p><b>为什么需要本类</b>：M0 落地时 {@code ErpIdempotencyService} 与 {@code ErpChangeLogWriter}
 * 各自只绑定了 {@code erpJdbcTemplate}，而库里已有 137 张业务表在**主库 product_archive**（含制造执行域
 * {@code manufacturing_*}）。若用 erp 连接去包裹主库业务写，两条连接各自成事务，B15 的两条失败语义
 * 同时失效：
 * <ul>
 *   <li>「业务失败 → 幂等键也不存在」→ 实际会留下一条指向已回滚业务的键，用户重试被判为冲突；
 *   <li>「幂等键冲突 → 业务写入回滚」→ 实际业务已提交，键没写进去，重试会**重复落账**。</li>
 * </ul>
 * 并发下这两种表现都不会报错，只会静默产生重复单据或丢失幂等保护 —— 属最贵的一类坑。
 *
 * <p><b>处置</b>：同一份实现、两个绑定，事务边界与业务写入所在库一致。
 * 主库侧以 {@code erp.idem_request} / {@code erp.row_change_log} **限定名**访问——
 * 两个 schema 在同一个 MySQL 实例上，同一条连接的一个事务即可覆盖，仍然是原子的，
 * 同时保持「请求键与审计只有一处」这一单一事实来源（不新建第二张幂等表）。
 *
 * <p><b>⚠️ 新增写路径时先问一句</b>：我的业务表在哪个库？
 * 主库 → {@code archiveIdempotencyService} + {@code archiveAuditWriter}；
 * erp → {@code erpIdempotencyService} + {@code erpAuditWriter}。选错不报错，只是保护失效。
 *
 * <p><b>已知前提（U9 未收口）</b>：当前 ERP 与主库复用同一 DB 账号，故主库连接有权写 erp 表。
 * 将来按 U9 拆账号时，主库账号需被授予 {@code erp.idem_request} 与 {@code erp.row_change_log}
 * 的 INSERT 权限，否则所有主库写路径会在写幂等键时失败。
 */
@Configuration
public class ErpContractConfig {

    // ---------- 幂等模板 ----------

    /** erp schema 内业务写入的幂等绑定（幂等表 {@code erp.idem_request}）。 */
    @Bean(name = "erpIdempotencyService")
    @Primary
    public ErpIdempotencyService erpIdempotencyService(
            @Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            @Qualifier("erpTransactionManager") PlatformTransactionManager erpTransactionManager) {
        return new ErpIdempotencyService(erpJdbcTemplate, erpTransactionManager, "idem_request");
    }

    /**
     * 主库业务写入的幂等绑定：JdbcTemplate 与事务管理器都取主库，
     * 幂等表用限定名 {@code erp.idem_request} 走同一条连接、同一个事务。
     */
    @Bean(name = "archiveIdempotencyService")
    public ErpIdempotencyService archiveIdempotencyService(
            @Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        return new ErpIdempotencyService(jdbcTemplate, transactionManager, "erp.idem_request");
    }

    // ---------- 行级审计 ----------

    /** erp schema 内业务写入的审计绑定（审计表 {@code erp.row_change_log}）。 */
    @Bean(name = "erpAuditWriter")
    @Primary
    public ErpChangeLogWriter erpAuditWriter(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
                                             ObjectMapper objectMapper) {
        return new ErpChangeLogWriter(erpJdbcTemplate, objectMapper, "row_change_log");
    }

    /** 主库业务写入的审计绑定：同连接同事务写 {@code erp.row_change_log}。 */
    @Bean(name = "archiveAuditWriter")
    public ErpChangeLogWriter archiveAuditWriter(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
                                                 ObjectMapper objectMapper) {
        return new ErpChangeLogWriter(jdbcTemplate, objectMapper, "erp.row_change_log");
    }
}
