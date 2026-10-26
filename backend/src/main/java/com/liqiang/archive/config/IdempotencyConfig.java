package com.liqiang.archive.config;

import com.liqiang.archive.infra.IdempotencyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 系统通用幂等能力装配。
 *
 * <p>2026-10-23：随 ERP 独立 schema 下线，由 {@code ErpContractConfig} 收敛而来——
 * 不再存在主库 / ERP 双数据源、双审计 writer 的区分，只剩一个通用
 * {@link IdempotencyService}，绑定当前主数据源的 {@code request_idempotency} 表。
 *
 * <p><b>绑定纪律（勿改回）</b>：幂等模板必须与「业务写入」绑定同一个 DataSource 与同一个事务管理器，
 * 否则「业务回滚则键也不存在」与「键冲突则业务回滚」两条都失效。这里显式注入主库
 * {@code JdbcTemplate} 与主库事务管理器，与生产跟单等业务写入同源。
 */
@Configuration
public class IdempotencyConfig {

    /** 主库幂等模板（生产跟单等写动作统一经它防重复提交）。 */
    @Bean
    public IdempotencyService idempotencyService(JdbcTemplate jdbcTemplate,
                                                 PlatformTransactionManager transactionManager) {
        // 不指定 schema，确保幂等记录与业务记录使用同一 DataSource（含隔离测试库）。
        return new IdempotencyService(jdbcTemplate, transactionManager, "request_idempotency");
    }
}
